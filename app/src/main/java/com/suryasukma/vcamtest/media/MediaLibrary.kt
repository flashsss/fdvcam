package com.suryasukma.vcamtest.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import com.suryasukma.vcamtest.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Manages the media files in app storage:
 *  - source.*  : the untouched original (kept so edits can be redone / reset)
 *  - virtual.* : the file the hooks actually play (edited result)
 */
object MediaLibrary {
    const val VIRTUAL_PREFIX = "virtual."
    const val SOURCE_PREFIX = "source."
    const val DEFAULT_VIDEO_NAME = "virtual.mp4"
    const val TEMP_OUTPUT_NAME = "virtual_tmp.mp4"

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

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Copies [uri] into app storage and prepares the playable virtual file. Call on main thread; [onDone] runs on main. */
    fun importUri(ctx: Context, uri: Uri, onDone: (path: String?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            onDone(null, "Invalid file type. Only images and videos are supported.")
            return
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        io.execute {
            val tmp = File(app.filesDir, "import_tmp.$ext")
            try {
                (app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open file")).use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }
                main.post {
                    // Only replace the previous media once the new copy succeeded.
                    clearAll(app)
                    val src = File(app.filesDir, SOURCE_PREFIX + ext)
                    tmp.renameTo(src)
                    materialize(app, src, onDone)
                }
            } catch (e: Exception) {
                tmp.delete()
                main.post { onDone(null, e.message) }
            }
        }
    }

    /** Rebuilds the virtual file from the original. Call on main thread. */
    fun resetToOriginal(ctx: Context, onDone: (path: String?, error: String?) -> Unit) {
        val src = sourceFile(ctx)
        if (src == null) {
            onDone(null, "No original media")
            return
        }
        clearVirtual(ctx)
        materialize(ctx, src, onDone)
    }

    private fun materialize(ctx: Context, src: File, onDone: (String?, String?) -> Unit) {
        if (isVideo(src)) {
            val dst = File(ctx.filesDir, VIRTUAL_PREFIX + src.extension)
            io.execute {
                try {
                    src.copyTo(dst, overwrite = true)
                    main.post { onDone(dst.absolutePath, null) }
                } catch (e: Exception) {
                    main.post { onDone(null, e.message) }
                }
            }
        } else {
            val out = File(ctx.filesDir, DEFAULT_VIDEO_NAME)
            MediaConverter.convertImageToMp4(ctx, Uri.fromFile(src), out) { ok ->
                if (ok) onDone(out.absolutePath, null) else onDone(null, "Conversion failed")
            }
        }
    }
}
