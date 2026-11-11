package com.suryasukma.vcamtest.hooks;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.suryasukma.vcamtest.data.Constants;
import com.suryasukma.vcamtest.xposed.XCamModule;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Consolidated Capture Hook.
 * Handles all image data injection points (BitmapFactory, Bitmap.compress, MediaStore, FileOutput).
 * Prevents redundant extractions and recursion.
 */
public class CaptureHook {
    private final XCamModule module;
    private final ImageReaderHook imageReaderHook;

    private static class RecorderTarget {
        String path;
        FileDescriptor fd;
        long startMediaPosMs = 0;
        long startWallClockMs = 0;

        RecorderTarget(String path, FileDescriptor fd) {
            this.path = path;
            this.fd = fd;
        }
    }

    private final Map<Object, RecorderTarget> recorderTargets = new ConcurrentHashMap<>();
    // Recorders that declared a video source: audio-only recorders (voice notes) must never get video written over them.
    private final java.util.Set<Object> videoRecorders =
            java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()));
    private volatile RecorderTarget lastRecorderTarget;
    private volatile long lastStartMediaPosMs = 0;
    private volatile long lastStartWallClockMs = 0;

    public CaptureHook(XCamModule module, ImageReaderHook imageReaderHook) {
        this.module = module;
        this.imageReaderHook = imageReaderHook;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Capture Diversion Hooks");
            
            hookBitmapFactory(param);
            
            hookBitmapCompress(param);
            
            hookMediaStore(param);
            
            hookMediaRecorder(param);

            hookMediaCodec(param);
            
            module.logHook("[+] Capture Diversion Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Capture Hooks installation failed: " + t.getMessage());
        }
    }

    private long lastSkipLogMs = 0L;

    /** Throttled diagnostics: explains why a decode/compress during the capture window was left untouched. */
    private void logSkip(String what) {
        long now = System.currentTimeMillis();
        if (module.isCapturingState() && now - lastSkipLogMs > 300) {
            lastSkipLogMs = now;
            module.logHook("[~] capture window: " + what + " left untouched");
        }
    }

    private static boolean looksLikeCaptureDecode(java.util.List<Object> a) {
        try {
            byte[] src = (byte[]) a.get(0);
            if (src == null || src.length < 30000) return false;
            if (a.size() >= 4 && a.get(3) instanceof BitmapFactory.Options
                    && ((BitmapFactory.Options) a.get(3)).inJustDecodeBounds) return false;
            return (src[0] & 0xFF) == 0xFF && (src[1] & 0xFF) == 0xD8;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isBoundsOnly(java.util.List<Object> a, int optIndex) {
        try {
            return a.size() > optIndex && a.get(optIndex) instanceof BitmapFactory.Options
                    && ((BitmapFactory.Options) a.get(optIndex)).inJustDecodeBounds;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookBitmapFactory(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                if (method.getName().equals("decodeByteArray")) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        Object[] newArgs = null;
                        if (module.isCapturingState() && module.getMediaPath() != null
                                && !looksLikeCaptureDecode(chain.getArgs())) {
                            logSkip("decodeByteArray (not a capture-sized JPEG)");
                        }
                        if (module.isCapturingState() && module.getMediaPath() != null
                                && looksLikeCaptureDecode(chain.getArgs())) {
                            // Never let a hook bug crash the host app: on any failure fall back to the original call.
                            try {
                                int activeW = imageReaderHook.getActiveCaptureWidth();
                                int activeH = imageReaderHook.getActiveCaptureHeight();
                                int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                                int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                                byte[] injected = module.handleCapture(targetW, targetH);
                                if (injected != null) {
                                    int actualW = targetW;
                                    int actualH = targetH;
                                    // Guard: decoding bounds calls decodeByteArray again, which would re-enter this hook forever.
                                    boolean wasIgnoring = module.isIgnoringHooks();
                                    try {
                                        module.setIgnoringHooks(true);
                                        BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                        boundOpts.inJustDecodeBounds = true;
                                        BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                        if (boundOpts.outWidth > 0) actualW = boundOpts.outWidth;
                                        if (boundOpts.outHeight > 0) actualH = boundOpts.outHeight;
                                    } finally {
                                        module.setIgnoringHooks(wasIgnoring);
                                    }
                                    String nodeDesc = "BitmapFactory#decodeByteArray(" + actualW + "x" + actualH + ")";
                                    module.recordPipelineNode(nodeDesc);
                                    module.reportPipelineCapture(actualW, actualH, injected);
                                    module.logHook("[*] Activity: Captured -> Injected into BitmapFactory#decodeByteArray (" + actualW + "x" + actualH + ")");
                                    module.consumeInjectionToken();
                                    Object[] args = chain.getArgs().toArray();
                                    args[0] = injected;
                                    if (args.length >= 3) { args[1] = 0; args[2] = injected.length; }
                                    newArgs = args;
                                }
                            } catch (Throwable t) {
                                module.logHook("[!] decodeByteArray injection FAILED: " + t);
                                newArgs = null;
                            }
                        }
                        if (newArgs == null) return chain.proceed();
                        // 3-arg overload delegates to the 4-arg one: don't inject twice.
                        boolean prev = module.isIgnoringHooks();
                        try {
                            module.setIgnoringHooks(true);
                            return chain.proceed(newArgs);
                        } finally {
                            module.setIgnoringHooks(prev);
                        }
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeByteArray");
                }
                
                if (method.getName().equals("decodeStream") && method.getParameterTypes().length >= 1) {
                    module.hook(method).intercept(chain -> {
                        if (module.isIgnoringHooks()) return chain.proceed();
                        if (module.isCapturingState() && module.getMediaPath() != null
                                && !module.hasInjectionToken()) {
                            logSkip("decodeStream (injection already done)");
                        }
                        if (module.isCapturingState() && module.getMediaPath() != null
                                && module.hasInjectionToken() && !isBoundsOnly(chain.getArgs(), 2)) {
                            int activeW = imageReaderHook.getActiveCaptureWidth();
                            int activeH = imageReaderHook.getActiveCaptureHeight();
                            int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                            int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                            byte[] injected = null;
                            try {
                                injected = module.handleCapture(targetW, targetH);
                            } catch (Throwable t) {
                                module.logHook("[!] decodeStream capture failed: " + t);
                            }
                            if (injected != null) {
                                try {
                                    module.setIgnoringHooks(true);
                                    BitmapFactory.Options opts = null;
                                    if (chain.getArgs().size() >= 3 && chain.getArgs().get(2) instanceof BitmapFactory.Options) {
                                        opts = (BitmapFactory.Options) chain.getArgs().get(2);
                                    }
                                    
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(injected, 0, injected.length, opts);
                                    if (bitmap != null) {
                                        int actualW = bitmap.getWidth();
                                        int actualH = bitmap.getHeight();
                                        String nodeDesc = "BitmapFactory#decodeStream(" + actualW + "x" + actualH + ")";
                                        module.recordPipelineNode(nodeDesc);
                                        module.reportPipelineCapture(actualW, actualH, injected);
                                        module.logHook("[*] Activity: Captured -> Injected virtual Bitmap into BitmapFactory#decodeStream (" + actualW + "x" + actualH + ")");
                                        module.consumeInjectionToken();
                                        return bitmap;
                                    }
                                } catch (Throwable t) {
                                    module.logHook("[!] decodeStream injection failed: " + t);
                                } finally {
                                    module.setIgnoringHooks(false);
                                }
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: BitmapFactory#decodeStream");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookBitmapCompress(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method compress = Bitmap.class.getDeclaredMethod("compress", Bitmap.CompressFormat.class, int.class, OutputStream.class);
            module.hook(compress).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                boolean bigJpeg = false;
                try {
                    Bitmap self = (Bitmap) chain.getThisObject();
                    bigJpeg = chain.getArgs().get(0) == Bitmap.CompressFormat.JPEG
                            && self != null && (long) self.getWidth() * self.getHeight() >= 500_000L;
                } catch (Throwable ignored) {}
                if (bigJpeg && module.isCapturingState() && module.getMediaPath() != null && module.hasInjectionToken()) {
                    int activeW = imageReaderHook.getActiveCaptureWidth();
                    int activeH = imageReaderHook.getActiveCaptureHeight();
                    int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                    int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                    byte[] injected = null;
                    try {
                        injected = module.handleCapture(targetW, targetH);
                    } catch (Throwable t) {
                        module.logHook("[!] compress capture failed: " + t);
                    }
                    if (injected != null) {
                        OutputStream os = (OutputStream) chain.getArgs().get(2);
                        if (os != null) {
                            try {
                                module.setIgnoringHooks(true);
                                os.write(injected);
                                os.flush();
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "Bitmap#compress(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into Bitmap#compress (" + actualW + "x" + actualH + ")");
                                module.consumeInjectionToken();
                                return true;
                            } catch (Throwable t) {
                                module.logHook("[!] Bitmap#compress injection FAILED: " + t.getMessage());
                            } finally {
                                module.setIgnoringHooks(false);
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Bitmap#compress");
        } catch (Throwable ignored) {}
    }

    private void hookMediaStore(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method openFD = ContentResolver.class.getDeclaredMethod("openFileDescriptor", Uri.class, String.class);
            module.hook(openFD).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                
                Uri uri = (Uri) chain.getArgs().get(0);
                String mode = (String) chain.getArgs().get(1);
                
                if (uri != null && mode != null && mode.contains("w") && module.isCapturingState()) {
                    ParcelFileDescriptor pfd = (ParcelFileDescriptor) chain.proceed();
                    if (pfd != null) {
                        int activeW = imageReaderHook.getActiveCaptureWidth();
                        int activeH = imageReaderHook.getActiveCaptureHeight();
                        int targetW = activeW > 0 ? activeW : Constants.DEFAULT_CAPTURE_WIDTH;
                        int targetH = activeH > 0 ? activeH : Constants.DEFAULT_CAPTURE_HEIGHT;
                        byte[] injected = module.handleCapture(targetW, targetH);
                        if (injected != null) {
                            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                                module.setIgnoringHooks(true);
                                fos.write(injected);
                                fos.flush();
                                BitmapFactory.Options boundOpts = new BitmapFactory.Options();
                                boundOpts.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(injected, 0, injected.length, boundOpts);
                                int actualW = boundOpts.outWidth > 0 ? boundOpts.outWidth : targetW;
                                int actualH = boundOpts.outHeight > 0 ? boundOpts.outHeight : targetH;
                                String nodeDesc = "MediaStore#openFD(" + actualW + "x" + actualH + ")";
                                module.recordPipelineNode(nodeDesc);
                                module.reportPipelineCapture(actualW, actualH, injected);
                                module.logHook("[*] Activity: Captured -> Injected into MediaStore FD (" + actualW + "x" + actualH + "): " + uri);
                            } catch (Throwable t) {
                                module.logHook("[!] MediaStore injection error: " + t.getMessage());
                            } finally {
                                module.setIgnoringHooks(false);
                            }
                        }
                    }
                    return pfd;
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: ContentResolver#openFileDescriptor");
        } catch (Throwable ignored) {}
    }

    private String resolveFdPath(FileDescriptor fd) {
        if (fd == null) return null;
        try {
            Field field = FileDescriptor.class.getDeclaredField("descriptor");
            field.setAccessible(true);
            int fdInt = (int) field.get(fd);
            if (fdInt >= 0) {
                File procLink = new File("/proc/self/fd/" + fdInt);
                if (procLink.exists()) {
                    return procLink.getCanonicalPath();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void recordOutputTarget(Object recorder, String path, FileDescriptor fd) {
        String resolvedPath = path;
        if (resolvedPath == null && fd != null) {
            resolvedPath = resolveFdPath(fd);
        }
        RecorderTarget target = new RecorderTarget(resolvedPath, fd);
        if (recorder != null) {
            recorderTargets.put(recorder, target);
        }
        lastRecorderTarget = target;
        module.logHook("[*] MediaRecorder output configured -> Path: " + resolvedPath + " | FD: " + fd);
    }

    private InputStream openSourceStream(String path) {
        if (path == null) return null;
        try {
            if (path.startsWith("content://")) {
                android.content.Context ctx = module.getContext();
                if (ctx != null) {
                    return ctx.getContentResolver().openInputStream(Uri.parse(path));
                }
            }
            if (path.startsWith("file://")) {
                return new FileInputStream(Uri.parse(path).getPath());
            }
            return new FileInputStream(path);
        } catch (Throwable t) {
            module.logHook("[!] Failed to open media source stream: " + t.getMessage());
            return null;
        }
    }

    private void recordStartTime(Object recorder) {
        long currentPos = module.getCurrentPosition();
        long now = System.currentTimeMillis();
        lastStartMediaPosMs = currentPos;
        lastStartWallClockMs = now;

        RecorderTarget target = null;
        if (recorder != null) {
            target = recorderTargets.get(recorder);
        }
        if (target == null && recorder == null) {
            target = lastRecorderTarget;
        }
        if (target != null) {
            target.startMediaPosMs = currentPos;
            target.startWallClockMs = now;
        }
        module.logHook("[*] MediaRecorder#start timing captured: StartMediaPos=" + currentPos + " ms, WallClock=" + now);
    }

    private void replaceMediaRecorderOutput(Object recorder) {
        if (recorder != null && !videoRecorders.contains(recorder)) {
            recorderTargets.remove(recorder);
            module.logHook("[*] MediaRecorder#stop: no video source declared, skipping replacement");
            return;
        }
        RecorderTarget target = null;
        if (recorder != null) {
            target = recorderTargets.remove(recorder);
        }
        // Only fall back to the last known output when we cannot tell which recorder this is.
        if (target == null && recorder == null) {
            target = lastRecorderTarget;
            lastRecorderTarget = null;
        }
        if (target == null) {
            module.logHook("[!] MediaRecorder#stop: No output target found to replace");
            return;
        }

        String mediaPath = module.getMediaPath();
        if (mediaPath == null) {
            module.logHook("[!] MediaRecorder#stop: Media path is null, cannot replace recorded video");
            return;
        }

        long stopWallClockMs = System.currentTimeMillis();
        long startWallClock = (target.startWallClockMs > 0) ? target.startWallClockMs : lastStartWallClockMs;
        long startMediaPosMs = (target.startMediaPosMs > 0) ? target.startMediaPosMs : lastStartMediaPosMs;
        long recordDurationMs = (startWallClock > 0) ? (stopWallClockMs - startWallClock) : 0;
        module.logHook("[*] MediaRecorder#stop timing: Duration=" + recordDurationMs + " ms, StartMediaPos=" + startMediaPosMs + " ms");

        // Audio-only recordings (voice notes) must never be overwritten with video.
        if (target.path != null) {
            String lower = target.path.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus")
                    || lower.endsWith(".amr") || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".oga")) {
                module.logHook("[*] MediaRecorder#stop: audio-only output, skipping replacement");
                return;
            }
        }

        boolean replaced = false;
        long copiedBytes = 0;

        if (target.path != null) {
            File destFile = new File(target.path);
            android.content.Context ctx = module.getContext();
            if (ctx != null && recordDurationMs > 300) {
                try {
                    module.setIgnoringHooks(true);
                    replaced = com.suryasukma.vcamtest.utils.MediaRemuxer.INSTANCE.trimVideo(
                        ctx,
                        mediaPath,
                        destFile,
                        startMediaPosMs,
                        recordDurationMs,
                        msg -> {
                            module.logHook(msg);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                    if (replaced) {
                        module.recordPipelineNode("MediaRecorder(VideoTrimmed)");
                        module.logHook("[*] Activity: MediaRecorder#stop -> Trimmed video successfully: " + target.path + " (Duration: " + recordDurationMs + " ms)");
                    }
                } catch (Throwable t) {
                    module.logHook("[!] Trimming attempt failed: " + t.getMessage());
                } finally {
                    module.setIgnoringHooks(false);
                }
            }

            if (!replaced) {
                try (InputStream in = openSourceStream(mediaPath)) {
                    if (in != null) {
                        // Only open (and truncate) the recorded file once the replacement source is readable.
                        try (FileOutputStream out = new FileOutputStream(destFile, false)) {
                            byte[] buffer = new byte[65536];
                            int len;
                            while ((len = in.read(buffer)) != -1) {
                                out.write(buffer, 0, len);
                                copiedBytes += len;
                            }
                            out.flush();
                            try {
                                out.getFD().sync();
                            } catch (Throwable ignored) {}
                            replaced = true;
                            module.recordPipelineNode("MediaRecorder(VideoReplaced)");
                            module.logHook("[*] Activity: MediaRecorder#stop -> Successfully replaced file: " + target.path + " (" + copiedBytes + " bytes)");
                        }
                    }
                } catch (Throwable t) {
                    module.logHook("[!] Failed to replace file at " + target.path + ": " + t.getMessage());
                }
            }
        }

        if (!replaced && target.fd != null && target.fd.valid()) {
            try (ParcelFileDescriptor dupPfd = ParcelFileDescriptor.dup(target.fd);
                 FileOutputStream fos = new FileOutputStream(dupPfd.getFileDescriptor())) {
                java.nio.channels.FileChannel channel = fos.getChannel();
                channel.position(0);
                channel.truncate(0);
                try (InputStream in = openSourceStream(mediaPath)) {
                    if (in != null) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                            copiedBytes += len;
                        }
                        fos.flush();
                        try {
                            channel.force(true);
                        } catch (Throwable ignored) {}
                        replaced = true;
                        module.recordPipelineNode("MediaRecorder(VideoReplaced)");
                        module.logHook("[*] Activity: MediaRecorder#stop -> Successfully replaced via FileDescriptor (" + copiedBytes + " bytes)");
                    }
                }
            } catch (Throwable t) {
                module.logHook("[!] Failed to replace via FileDescriptor: " + t.getMessage());
            }
        }

        if (!replaced) {
            module.logHook("[!] MediaRecorder#stop: Output replacement was not successful");
        }
    }

    private void hookMediaRecorder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> mrClass = param.getClassLoader().loadClass("android.media.MediaRecorder");

            for (Method m : mrClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("setOutputFile") || name.equals("setNextOutputFile")) {
                    module.hook(m).intercept(chain -> {
                        Object recorder = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        if (args != null && !args.isEmpty()) {
                            Object target = args.get(0);
                            if (target instanceof String) {
                                recordOutputTarget(recorder, (String) target, null);
                            } else if (target instanceof File) {
                                recordOutputTarget(recorder, ((File) target).getAbsolutePath(), null);
                            } else if (target instanceof FileDescriptor) {
                                recordOutputTarget(recorder, null, (FileDescriptor) target);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#" + name);
                }
            }

            for (Method m : mrClass.getDeclaredMethods()) {
                if (m.getName().equals("setVideoSource")) {
                    module.hook(m).intercept(chain -> {
                        Object recorder = chain.getThisObject();
                        if (recorder != null) videoRecorders.add(recorder);
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: MediaRecorder#setVideoSource");
                }
            }

            Method startMethod = mrClass.getDeclaredMethod("start");
            module.hook(startMethod).intercept(chain -> {
                Object recorder = chain.getThisObject();
                recordStartTime(recorder);
                module.recordPipelineNode("MediaRecorder#start");
                module.logHook("[*] Activity: MediaRecorder#start");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: MediaRecorder#start");

            Method stopMethod = mrClass.getDeclaredMethod("stop");
            module.hook(stopMethod).intercept(chain -> {
                Object recorder = chain.getThisObject();
                Object result = chain.proceed();
                try {
                    replaceMediaRecorderOutput(recorder);
                } catch (Throwable t) {
                    module.logHook("[!] MediaRecorder#stop replacement invocation failed: " + t.getMessage());
                }
                return result;
            });
            module.logHook("[+] Hooked: MediaRecorder#stop");

            for (Method m : mrClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("reset") || name.equals("release")) {
                    module.hook(m).intercept(chain -> {
                        Object recorder = chain.getThisObject();
                        if (recorder != null) {
                            recorderTargets.remove(recorder);
                            videoRecorders.remove(recorder);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookMediaCodec(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> mcClass = param.getClassLoader().loadClass("android.media.MediaCodec");
            Method createInputSurface = mcClass.getDeclaredMethod("createInputSurface");
            module.hook(createInputSurface).intercept(chain -> {
                if (module.isIgnoringHooks()) return chain.proceed();
                module.recordPipelineNode("MediaCodec(InputSurface)");
                module.logHook("[*] Activity: MediaCodec#createInputSurface");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: MediaCodec#createInputSurface");
        } catch (Throwable ignored) {}
    }
}
