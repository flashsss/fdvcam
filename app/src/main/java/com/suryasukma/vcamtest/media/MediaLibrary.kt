package com.suryasukma.vcamtest.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.suryasukma.vcamtest.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream

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

    /** Copies [uri] into app storage and prepares the playable virtual file. Call on main thread. */
    fun importUri(ctx: Context, uri: Uri, onDone: (path: String?, error: String?) -> Unit) {
        try {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
                onDone(null, "Invalid file type. Only images and videos are supported.")
                return
            }
            clearAll(ctx)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val src = File(ctx.filesDir, SOURCE_PREFIX + ext)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(src).use { input.copyTo(it) }
            }
            materialize(ctx, src, onDone)
        } catch (e: Exception) {
            onDone(null, e.message)
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
            src.copyTo(dst, overwrite = true)
            onDone(dst.absolutePath, null)
        } else {
            val out = File(ctx.filesDir, DEFAULT_VIDEO_NAME)
            MediaConverter.convertImageToMp4(ctx, Uri.fromFile(src), out) { ok ->
                if (ok) onDone(out.absolutePath, null) else onDone(null, "Conversion failed")
            }
        }
    }
}
