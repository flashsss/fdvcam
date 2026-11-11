package com.suryasukma.vcamtest.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import com.suryasukma.vcamtest.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Manages the media files in app storage:
 *  - source.*  : the untouched original (kept so edits can be redone / reset)
 *  - virtual.* : the file the hooks actually play (edited result)
 *
 * New media is always prepared under temporary names (pending.*, virtual_tmp.*) and swapped in only once it is
 * fully ready, so the media the hooks are using is never deleted before its replacement exists.
 */
object MediaLibrary {
    const val VIRTUAL_PREFIX = "virtual."
    const val SOURCE_PREFIX = "source."
    const val DEFAULT_VIDEO_NAME = "virtual.mp4"
    const val TEMP_OUTPUT_NAME = "virtual_tmp.mp4"
    private const val PENDING_PREFIX = "pending."
    private const val TEMP_VIRTUAL_PREFIX = "virtual_tmp."

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isVideo(file: File): Boolean {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        return mime?.startsWith("video/") == true
    }

    fun sourceFile(ctx: Context): File? =
        ctx.filesDir.listFiles { _, n -> n.startsWith(SOURCE_PREFIX) }?.firstOrNull()

    /** Returns the source file, promoting the current virtual file if no source was saved. */
    fun ensureSource(ctx: Context, currentPath: String?): File? {
        sourceFile(ctx)?.let { return it }
        val cur = currentPath?.let(::File)?.takeIf { it.exists() } ?: return null
        val dst = File(ctx.filesDir, SOURCE_PREFIX + cur.extension)
        cur.copyTo(dst, overwrite = true)
        return dst
    }

    fun clearVirtual(ctx: Context) {
        ctx.filesDir.listFiles { _, n -> n.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
    }

    fun clearAll(ctx: Context) {
        clearVirtual(ctx)
        ctx.filesDir.listFiles { _, n -> n.startsWith(SOURCE_PREFIX) }?.forEach { it.delete() }
    }

    /** Moves [from] to [to], replacing [to]. Returns false (and leaves [to] untouched) on failure. */
    fun replaceFile(from: File, to: File): Boolean {
        return try {
            java.nio.file.Files.move(
                from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---- import ------------------------------------------------------------

    /** Copies [uri] into app storage and prepares the playable virtual file. Call on main thread; [onDone] runs on main. */
    fun importUri(ctx: Context, uri: Uri, onDone: (path: String?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        val mime = app.contentResolver.getType(uri)
        var ext = extensionFor(mime, uri)
        val pendingName = PENDING_PREFIX + System.nanoTime()
        io.execute {
            val raw = File(app.filesDir, "$pendingName.tmp")
            try {
                (app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")).use { input ->
                    FileOutputStream(raw).use { input.copyTo(it) }
                }
                // Providers sometimes report no / generic MIME types: fall back to looking at the bytes.
                if (ext == null) ext = sniffExtension(raw)
                val finalExt = ext
                if (finalExt == null) {
                    raw.delete()
                    main.post { onDone(null, "Invalid file type. Only images and videos are supported.") }
                    return@execute
                }
                val pending = File(app.filesDir, "$pendingName.$finalExt")
                if (!raw.renameTo(pending)) throw IOException("Cannot store file")
                main.post { prepare(app, pending, onDone) { adoptAsSource(app, pending) } }
            } catch (e: Exception) {
                raw.delete()
                main.post { onDone(null, e.message) }
            }
        }
    }

    /** Rebuilds the virtual file from the original. Call on main thread. */
    fun resetToOriginal(ctx: Context, onDone: (path: String?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        val src = sourceFile(app)
        if (src == null) {
            onDone(null, "No original media")
            return
        }
        prepare(app, src, onDone) { /* keep the existing source */ }
    }

    private fun adoptAsSource(app: Context, pending: File) {
        app.filesDir.listFiles { _, n -> n.startsWith(SOURCE_PREFIX) }?.forEach { it.delete() }
        replaceFile(pending, File(app.filesDir, SOURCE_PREFIX + pending.extension))
    }

    /**
     * Builds the playable file from [src] into a temp name, then swaps it in (after [beforeSwap]).
     * On failure nothing that was previously installed is touched.
     */
    private fun prepare(
        app: Context,
        src: File,
        onDone: (String?, String?) -> Unit,
        beforeSwap: (File) -> Unit,
    ) {
        val finish = { built: File?, error: String? ->
            if (built == null || !built.exists() || built.length() == 0L) {
                built?.delete()
                if (src.name.startsWith(PENDING_PREFIX)) src.delete()
                onDone(null, error ?: "Conversion failed")
            } else {
                val finalFile = File(app.filesDir, VIRTUAL_PREFIX + built.extension)
                if (replaceFile(built, finalFile)) {
                    // Only now that the new file is in place, drop the old ones.
                    app.filesDir.listFiles { _, n -> n.startsWith(VIRTUAL_PREFIX) && n != finalFile.name }
                        ?.forEach { it.delete() }
                    beforeSwap(src)
                    onDone(finalFile.absolutePath, null)
                } else {
                    built.delete()
                    onDone(null, "Cannot install media")
                }
            }
        }

        if (isVideo(src) && !src.extension.equals("mp4", ignoreCase = true)) {
            // Every hook path expects an .mp4 file, so convert other containers (mov, mkv, 3gp, webm...) once.
            val out = File(app.filesDir, TEMP_VIRTUAL_PREFIX + "mp4")
            MediaEditor.editVideo(app, src, EditSpec(), out) { ok ->
                finish(if (ok) out else null, "Video conversion failed")
            }
        } else if (isVideo(src)) {
            val out = File(app.filesDir, TEMP_VIRTUAL_PREFIX + src.extension)
            io.execute {
                try {
                    src.copyTo(out, overwrite = true)
                    main.post { finish(out, null) }
                } catch (e: Exception) {
                    main.post { finish(null, e.message) }
                }
            }
        } else {
            val out = File(app.filesDir, TEMP_VIRTUAL_PREFIX + "mp4")
            MediaConverter.convertImageToMp4(app, Uri.fromFile(src), out) { ok ->
                finish(if (ok) out else null, "Conversion failed")
            }
        }
    }

    // ---- type detection ----------------------------------------------------

    private fun extensionFor(mime: String?, uri: Uri): String? {
        val map = MimeTypeMap.getSingleton()
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            return map.getExtensionFromMimeType(mime) ?: if (mime.startsWith("video/")) "mp4" else "jpg"
        }
        val fromName = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val guessed = map.getMimeTypeFromExtension(fromName)
        return if (guessed != null && (guessed.startsWith("image/") || guessed.startsWith("video/"))) fromName else null
    }

    private fun sniffExtension(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return "jpg"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") "mp4" else null
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }
}
