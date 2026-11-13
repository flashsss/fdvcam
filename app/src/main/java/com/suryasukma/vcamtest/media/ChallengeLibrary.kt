package com.suryasukma.vcamtest.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.suryasukma.vcamtest.data.Constants.CHALLENGE_FILE_PREFIX
import com.suryasukma.vcamtest.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * One labeled action a tester may need to react to during an active-liveness check.
 * The ordinal order (declaration order) is the default playback order in [ChallengeLibrary] and [XCamModule.startChallenge].
 */
enum class ChallengeRole(val key: String, val displayName: String) {
    NEUTRAL("neutral", "Neutral"),
    LOOK_LEFT("look_left", "Look Left"),
    LOOK_RIGHT("look_right", "Look Right"),
    BLINK("blink", "Blink");

    companion object {
        fun fromKey(key: String?): ChallengeRole? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Stores up to one short clip per [ChallengeRole] for Challenge Test mode (manual active-liveness testing): a
 * tester switches the camera feed in real time to match an app's own prompt ("look left", "blink", ...).
 *
 * Independent from the normal single-media slot in [MediaLibrary]: importing a challenge clip never touches
 * `virtual.mp4`, and using Challenge Test never touches the normal media. Every clip is normalized to
 * "challenge_<role>.mp4" in app storage, reusing the same conversion pipeline as [MediaLibrary].
 */
object ChallengeLibrary {
    private const val PENDING_PREFIX = "challenge_pending_"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fileName(role: ChallengeRole) = "$CHALLENGE_FILE_PREFIX${role.key}.mp4"

    fun clipFile(ctx: Context, role: ChallengeRole): File? =
        File(ctx.filesDir, fileName(role)).takeIf { it.exists() && it.length() > 0 }

    fun hasAnyClip(ctx: Context): Boolean = ChallengeRole.entries.any { clipFile(ctx, it) != null }

    fun deleteClip(ctx: Context, role: ChallengeRole) {
        File(ctx.filesDir, fileName(role)).delete()
    }

    /** A quick preview frame for the clip manager UI. Does file I/O: call off the main thread. */
    fun thumbnail(ctx: Context, role: ChallengeRole): Bitmap? {
        val file = clipFile(ctx, role) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /** Imports [uri] as the clip for [role]. Call on main thread; [onDone] runs on main. */
    fun importClip(ctx: Context, role: ChallengeRole, uri: Uri, onDone: (ok: Boolean, error: String?) -> Unit) {
        val app = ctx.applicationContext
        val mime = app.contentResolver.getType(uri)
        var ext = MediaLibrary.extensionFor(mime, uri)
        io.execute {
            val raw = File(app.filesDir, "$PENDING_PREFIX${role.key}_raw.tmp")
            try {
                (app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")).use { input ->
                    FileOutputStream(raw).use { input.copyTo(it) }
                }
                if (ext == null) ext = MediaLibrary.sniffExtension(raw)
                val finalExt = ext
                if (finalExt == null) {
                    raw.delete()
                    main.post { onDone(false, "Invalid file type. Only images and videos are supported.") }
                    return@execute
                }
                val src = File(app.filesDir, "$PENDING_PREFIX${role.key}.$finalExt")
                if (!raw.renameTo(src)) throw IOException("Cannot store file")
                main.post { buildAndInstall(app, role, src, onDone) }
            } catch (e: Exception) {
                raw.delete()
                main.post { onDone(false, e.message) }
            }
        }
    }

    /** Converts/copies [src] into the final "challenge_<role>.mp4" slot, replacing it only once the build succeeds. */
    private fun buildAndInstall(app: Context, role: ChallengeRole, src: File, onDone: (Boolean, String?) -> Unit) {
        val target = File(app.filesDir, fileName(role))
        val tmpOut = File(app.filesDir, "$PENDING_PREFIX${role.key}_out.mp4")
        val finish = { ok: Boolean, error: String? ->
            src.delete()
            if (ok && tmpOut.exists() && tmpOut.length() > 0 && MediaLibrary.replaceFile(tmpOut, target)) {
                onDone(true, null)
            } else {
                tmpOut.delete()
                onDone(false, error ?: "Conversion failed")
            }
        }
        if (MediaLibrary.isVideo(src)) {
            if (src.extension.equals("mp4", ignoreCase = true)) {
                io.execute {
                    try {
                        src.copyTo(tmpOut, overwrite = true)
                        main.post { finish(true, null) }
                    } catch (e: Exception) {
                        main.post { finish(false, e.message) }
                    }
                }
            } else {
                MediaEditor.editVideo(app, src, EditSpec(), tmpOut) { ok -> finish(ok, "Video conversion failed") }
            }
        } else {
            MediaConverter.convertImageToMp4(app, Uri.fromFile(src), tmpOut) { ok -> finish(ok, "Conversion failed") }
        }
    }
}
