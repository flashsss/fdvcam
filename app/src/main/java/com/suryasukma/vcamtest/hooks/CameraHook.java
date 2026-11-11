package com.suryasukma.vcamtest.hooks;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.suryasukma.vcamtest.xposed.XCamModule;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the original Camera API (android.hardware.Camera).
 * Consolidated all Legacy Camera logic including Renderer hooks.
 */
public class CameraHook {
    private static final long PICTURE_DELIVERY_DELAY_MS = 150;
    private final XCamModule module;
    private final Handler mainHandler;
    private final Set<String> hookedPreviewCallbackClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile Surface viewfinderSurface;
    // One dummy texture per Camera instance: front/back switching opens the second camera before the first is released.
    private final java.util.Map<Camera, SurfaceTexture> dummyTextures =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private SurfaceTexture dummyFor(Camera camera) {
        synchronized (dummyTextures) {
            SurfaceTexture st = dummyTextures.get(camera);
            if (st == null) {
                st = com.suryasukma.vcamtest.core.surface.TextureDrain.INSTANCE.create();
                if (st == null) st = new SurfaceTexture(10); // EGL unavailable: undrained fallback
                dummyTextures.put(camera, st);
            }
            return st;
        }
    }

    private void releaseDummyFor(Camera camera) {
        SurfaceTexture st = dummyTextures.remove(camera);
        com.suryasukma.vcamtest.core.surface.TextureDrain.INSTANCE.release(st);
    }

    public CameraHook(XCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera API (Legacy)");
            hookCameraParameters(param);
            hookViewfinder(param);
            hookPreviewCallbacks(param);
            hookCameraEventHandler(param);
            hookDisplayOrientation(param);
            hookRelease(param);
            hookTakePicture(param);
            hookLegacyMethod1(param);
        } catch (Throwable t) {
            module.logHook("[!] FAILED to initialize Camera API hooks: " + t.getMessage());
        }
    }

    private void hookLegacyMethod1(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> legacyDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.legacy.LegacyCameraDevice");
            for (Method method : legacyDeviceClass.getDeclaredMethods()) {
                if (method.getName().equals("produceFrame") && method.getParameterTypes().length == 5) {
                    module.hook(method).intercept(chain -> {
                        int format = (int) chain.getArgs().get(4);
                        if (format == 0x21) {
                            int width = (int) chain.getArgs().get(2);
                            int height = (int) chain.getArgs().get(3);
                            byte[] replacement = module.handleCapture(width, height);
                            if (replacement != null) {
                                Object[] newArgs = chain.getArgs().toArray();
                                newArgs[1] = replacement;
                                return chain.proceed(newArgs);
                            }
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: LegacyCameraDevice#produceFrame");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookViewfinder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setPreviewDisplay = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            module.hook(setPreviewDisplay).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.getArgs().get(0);
                if (holder != null && holder.getSurface() != null && module.getMediaPath() != null) {
                    viewfinderSurface = holder.getSurface();
                    module.registerPreviewSurface(viewfinderSurface);
                    try {
                        Camera camera = (Camera) chain.getThisObject();
                        camera.setPreviewTexture(dummyFor(camera));
                        module.logHook("[+] Hijacked Camera#setPreviewDisplay -> diverted camera to dummy texture");
                        return null;
                    } catch (Throwable ignored) {}
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewDisplay");

            Method setPreviewTexture = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture).intercept(chain -> {
                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                if (texture != null && module.getMediaPath() != null) {
                    Camera cam = (Camera) chain.getThisObject();
                    SurfaceTexture dummy = dummyFor(cam);
                    if (texture == dummy) {
                        return chain.proceed();
                    }
                    Surface old = viewfinderSurface;
                    viewfinderSurface = new Surface(texture);
                    if (old != null) { try { old.release(); } catch (Throwable ignored) {} }
                    module.registerPreviewSurface(viewfinderSurface);
                    module.logHook("[+] Hijacked Camera#setPreviewTexture -> diverted camera to dummy texture");
                    Object[] newArgs = chain.getArgs().toArray();
                    newArgs[0] = dummy;
                    return chain.proceed(newArgs);
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewTexture");

            Method startPreview = Camera.class.getDeclaredMethod("startPreview");
            module.hook(startPreview).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() != null && viewfinderSurface != null && viewfinderSurface.isValid()) {
                    module.recordPipelineNode("Camera1(PreviewStarted)");
                    module.logHook("[*] Starting FDVCam playback for Camera1 preview surface: " + viewfinderSurface);
                    module.handleModernPreview(viewfinderSurface);
                }
                return result;
            });
            module.logHook("[+] Hooked: Camera#startPreview");

            Method stopPreview = Camera.class.getDeclaredMethod("stopPreview");
            module.hook(stopPreview).intercept(chain -> {
                Object result = chain.proceed();
                try { module.stopEngine(); } catch (Throwable t) { module.logHook("[!] stopEngine failed: " + t); }
                // The real camera is still bound to the dummy texture after stopPreview();
                // it is only released together with the camera (see hookRelease).
                return result;
            });
            module.logHook("[+] Hooked: Camera#stopPreview");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook viewfinder: " + t.getMessage());
        }
    }

    private void hookCameraParameters(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> paramsClass = Camera.Parameters.class;
            Method setPreviewSize = paramsClass.getDeclaredMethod("setPreviewSize", int.class, int.class);
            module.hook(setPreviewSize).intercept(chain -> {
                int pw = (int) chain.getArgs().get(0);
                int ph = (int) chain.getArgs().get(1);
                module.recordPipelineNode("Camera1(Preview " + pw + "x" + ph + " NV21)");
                module.logHook("[*] Legacy Activity: setPreviewSize " + pw + "x" + ph);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera.Parameters#setPreviewSize");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook Camera.Parameters: " + t.getMessage());
        }
    }

    private void hookTakePicture(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method m : Camera.class.getDeclaredMethods()) {
                if (m.getName().equals("takePicture") && m.getParameterTypes().length == 4) {
                    module.hook(m).intercept(chain -> {
                        module.logHook("[*] Camera#takePicture called (media=" + (module.getMediaPath() != null) + ", jpegCallback=" + (chain.getArgs().get(3) != null) + ")");
                        if (module.getMediaPath() == null) return chain.proceed();
                        Camera camera = (Camera) chain.getThisObject();
                        PictureCallback jpegCallback = (PictureCallback) chain.getArgs().get(3);
                        if (jpegCallback == null) return chain.proceed();

                        final int picW;
                        final int picH;
                        final byte[] imageData;
                        try {
                            module.triggerCaptureState();
                            Camera.Parameters params = camera.getParameters();
                            picW = params.getPictureSize().width;
                            picH = params.getPictureSize().height;
                            imageData = module.handleCapture(picW, picH);
                        } catch (Throwable t) {
                            module.logHook("[!] takePicture injection failed: " + t);
                            return chain.proceed();
                        }

                        if (imageData == null) {
                            module.logHook("[!] takePicture: no virtual photo available, using the real camera");
                            return chain.proceed();
                        }
                        module.logHook("[+] takePicture: delivering virtual photo " + picW + "x" + picH + " (" + imageData.length + " bytes)");

                        module.recordPipelineNode("Camera1(takePicture " + picW + "x" + picH + " JPEG)");
                        module.reportPipelineCapture(picW, picH, imageData);

                        final ShutterCallback shutterCallback = (ShutterCallback) chain.getArgs().get(0);
                        final PictureCallback rawCallback = (PictureCallback) chain.getArgs().get(1);
                        final PictureCallback postviewCallback = (PictureCallback) chain.getArgs().get(2);

                        Runnable deliver = () -> {
                            try {
                                if (shutterCallback != null) shutterCallback.onShutter();
                                if (rawCallback != null) rawCallback.onPictureTaken(null, camera);
                                if (postviewCallback != null) postviewCallback.onPictureTaken(null, camera);
                                // The real takePicture() never ran, so the preview was never stopped:
                                // do not restart it here (that re-opened the preview after the app had closed the camera).
                                jpegCallback.onPictureTaken(imageData, camera);
                            } catch (Throwable t) {
                                module.logHook("[!] takePicture callback threw: " + t);
                            }
                        };
                        // The real camera always delivers picture callbacks asynchronously, on the looper of the thread that
                        // opened it. Calling them synchronously inside takePicture() breaks apps that update their
                        // "taking photo" state after the call returns, so post to the caller's own looper after a short
                        // delay that mimics the capture time.
                        Looper callerLooper = Looper.myLooper();
                        Handler target = callerLooper != null ? new Handler(callerLooper) : mainHandler;
                        target.postDelayed(deliver, PICTURE_DELIVERY_DELAY_MS);
                        return null;
                    });
                    module.logHook("[+] Hooked: Camera#takePicture (Legacy)");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook takePicture: " + t.getMessage());
        }
    }

    private void hookPreviewCallbacks(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setCallback = Camera.class.getDeclaredMethod("setPreviewCallback", Camera.PreviewCallback.class);
            module.hook(setCallback).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewCallback");

            Method setCallbackWithBuffer = Camera.class.getDeclaredMethod("setPreviewCallbackWithBuffer", Camera.PreviewCallback.class);
            module.hook(setCallbackWithBuffer).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setPreviewCallbackWithBuffer");

            Method setOneShot = Camera.class.getDeclaredMethod("setOneShotPreviewCallback", Camera.PreviewCallback.class);
            module.hook(setOneShot).intercept(chain -> {
                Object cb = chain.getArgs().get(0);
                if (cb != null) processCallbackRegistration(cb);
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setOneShotPreviewCallback");

        } catch (Throwable t) {
            module.logHook("[!] Failed to hook preview callbacks: " + t.getMessage());
        }
    }

    // Camera#getParameters() is a binder call + string parsing: never do it per preview frame.
    private final java.util.Map<Camera, long[]> previewSizeCache =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private int[] cachedPreviewSize(Camera camera) {
        long now = android.os.SystemClock.elapsedRealtime();
        long[] e = previewSizeCache.get(camera);
        if (e != null && now - e[2] < 1500) return new int[]{(int) e[0], (int) e[1]};
        int w = 640;
        int h = 480;
        try {
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = params != null ? params.getPreviewSize() : null;
            if (size != null) { w = size.width; h = size.height; }
        } catch (Throwable ignored) {}
        previewSizeCache.put(camera, new long[]{w, h, now});
        return new int[]{w, h};
    }

    private void processCallbackRegistration(Object callback) {
        if (callback == null) return;
        Class<?> callbackClass = callback.getClass();
        if (!hookedPreviewCallbackClasses.add(callbackClass.getName())) {
            return;
        }

        try {
            Class<?> clazz = callbackClass;
            while (clazz != null && clazz != Object.class) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("onPreviewFrame") && method.getParameterTypes().length == 2
                            && method.getParameterTypes()[0] == byte[].class && method.getParameterTypes()[1] == Camera.class) {
                        method.setAccessible(true);
                        module.hook(method).intercept(chain -> {
                            byte[] buffer = (byte[]) chain.getArgs().get(0);
                            Camera camera = (Camera) chain.getArgs().get(1);
                            if (buffer != null && camera != null && module.getMediaPath() != null) {
                                int[] ps = cachedPreviewSize(camera);
                                int w = ps[0];
                                int h = ps[1];

                                int[] dims = resolveDimensions(w, h, buffer.length);
                                w = dims[0];
                                h = dims[1];

                                byte[] nv21 = module.handleNv21Frame(w, h);
                                if (nv21 != null && nv21.length <= buffer.length) {
                                    System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                                    if (nv21.length < buffer.length) {
                                        java.util.Arrays.fill(buffer, nv21.length, buffer.length, (byte) 128);
                                    }
                                    module.recordPipelineNode("Camera1(Callback " + w + "x" + h + " NV21)");
                                }
                            }
                            return chain.proceed();
                        });
                        module.logHook("[+] Hooked onPreviewFrame on: " + clazz.getName());
                        return;
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook onPreviewFrame on " + callbackClass.getName() + ": " + t.getMessage());
        }
    }

    private void hookCameraEventHandler(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi")
            Class<?> eventHandlerClass = Class.forName("android.hardware.Camera$EventHandler");
            Method handleMessage = eventHandlerClass.getDeclaredMethod("handleMessage", android.os.Message.class);
            module.hook(handleMessage).intercept(chain -> {
                try {
                    android.os.Message msg = (android.os.Message) chain.getArgs().get(0);
                    // 0x010 is CAMERA_MSG_PREVIEW_FRAME in AOSP
                    if (msg != null && msg.what == 0x010 && msg.obj instanceof byte[] && module.getMediaPath() != null) {
                        byte[] buffer = (byte[]) msg.obj;
                        int w = 640;
                        int h = 480;
                        try {
                            java.lang.reflect.Field cameraField = eventHandlerClass.getDeclaredField("mCamera");
                            cameraField.setAccessible(true);
                            Camera camera = (Camera) cameraField.get(chain.getThisObject());
                            if (camera != null) {
                                int[] ps = cachedPreviewSize(camera);
                                w = ps[0];
                                h = ps[1];
                            }
                        } catch (Throwable ignored) {}

                        int[] dims = resolveDimensions(w, h, buffer.length);
                        w = dims[0];
                        h = dims[1];

                        byte[] nv21 = module.handleNv21Frame(w, h);
                        if (nv21 != null && nv21.length <= buffer.length) {
                            System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                            if (nv21.length < buffer.length) {
                                java.util.Arrays.fill(buffer, nv21.length, buffer.length, (byte) 128);
                            }
                            module.recordPipelineNode("Camera1(EventHandler " + w + "x" + h + " NV21)");
                        }
                    }
                } catch (Throwable ignored) {}
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera$EventHandler#handleMessage");
        } catch (Throwable t) {
            module.logHook("[!] Camera$EventHandler hook skipped: " + t.getMessage());
        }
    }

    private void hookDisplayOrientation(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setDisplayOrientation = Camera.class.getDeclaredMethod("setDisplayOrientation", int.class);
            module.hook(setDisplayOrientation).intercept(chain -> {
                int degrees = (int) chain.getArgs().get(0);
                module.logHook("[*] Camera#setDisplayOrientation: " + degrees + " degrees");
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Camera#setDisplayOrientation");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook setDisplayOrientation: " + t.getMessage());
        }
    }

    private void hookRelease(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method release = Camera.class.getDeclaredMethod("release");
            module.hook(release).intercept(chain -> {
                module.logHook("[*] Camera#release invoked");
                try { module.stopEngine(); } catch (Throwable t) { module.logHook("[!] stopEngine failed: " + t); }
                Camera self = (Camera) chain.getThisObject();
                try {
                    return chain.proceed();
                } finally {
                    releaseDummyFor(self);
                }
            });
            module.logHook("[+] Hooked: Camera#release");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook release: " + t.getMessage());
        }
    }

    private static int[] resolveDimensions(int w, int h, int bufferLength) {
        if (w > 0 && h > 0 && w * h * 3 / 2 == bufferLength) {
            return new int[]{w, h};
        }
        int[][] standards = {
            {1920, 1080}, {1280, 720}, {960, 540}, {800, 600}, {800, 480},
            {640, 480}, {352, 288}, {320, 240}, {176, 144}
        };
        for (int[] res : standards) {
            if (res[0] * res[1] * 3 / 2 == bufferLength) {
                return res;
            }
        }
        if (w > 0 && (bufferLength * 2) % (w * 3) == 0) {
            int calcH = (bufferLength * 2) / (w * 3);
            return new int[]{w, calcH};
        }
        return new int[]{w > 0 ? w : 640, h > 0 ? h : 480};
    }
}

