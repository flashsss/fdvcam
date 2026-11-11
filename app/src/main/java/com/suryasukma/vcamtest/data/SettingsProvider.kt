package com.suryasukma.vcamtest.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.suryasukma.vcamtest.data.Constants.AUTHORITY
import com.suryasukma.vcamtest.data.Constants.KEY_IS_ENABLED
import com.suryasukma.vcamtest.data.Constants.KEY_IS_MIRRORED
import com.suryasukma.vcamtest.data.Constants.KEY_ROTATION_ANGLE
import com.suryasukma.vcamtest.data.Constants.KEY_MEDIA_PATH
import com.suryasukma.vcamtest.data.Constants.PREFS_NAME
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsProvider : ContentProvider() {
    @Volatile private var lastNotifyAt = 0L

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        // The provider must be reachable from every hooked app, so it cannot be permission-protected.
        // Instead make sure a caller can only report about itself.
        val callerPackages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
        if (arg == null || callerPackages == null || !callerPackages.contains(arg)) return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (method) {
            com.suryasukma.vcamtest.data.Constants.METHOD_RECORD_NODE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: ""
                prefs.edit()
                    .putString(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()
            }
            com.suryasukma.vcamtest.data.Constants.METHOD_NOTIFY_CAPTURE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: "Standard"
                val width = extras?.getInt("width") ?: 0
                val height = extras?.getInt("height") ?: 0
                val thumbnail = extras?.getByteArray("thumbnail")?.takeIf { it.size <= 256 * 1024 }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifyAt < 1000L) return Bundle()
                lastNotifyAt = now

                prefs.edit()
                    .putString(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()

                val isNotifEnabled = prefs.getBoolean(com.suryasukma.vcamtest.data.Constants.KEY_ENABLE_CAPTURE_NOTIF, true)
                if (isNotifEnabled) {
                    com.suryasukma.vcamtest.ui.CaptureNotificationHelper.showCaptureNotification(
                        ctx, packageName, route, width, height, thumbnail
                    )
                }
            }
        }
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs?.getString(KEY_MEDIA_PATH, "") ?: ""
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        
        val cursor = MatrixCursor(arrayOf(KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE))
        val hasMedia = path.isNotEmpty() && File(path).exists()
        val videoUri = if (hasMedia) "content://$AUTHORITY/$fileName" else ""
        
        cursor.addRow(arrayOf(
            videoUri,
            if (hasMedia && (prefs?.getBoolean(KEY_IS_ENABLED, true) != false)) "1" else "0",
            if (prefs?.getBoolean(KEY_IS_MIRRORED, false) == true) "1" else "0",
            (prefs?.getInt(KEY_ROTATION_ANGLE, 0) ?: 0).toString()
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        // Only the playable media is exposed; the original (source.*) and temp files stay private.
        if (!fileName.startsWith("virtual.")) return null
        val file = File(context.filesDir, fileName)
        
        if (file.exists()) {
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: Exception) {
                null
            }
        }

        val fallbackFile = context.filesDir.listFiles { _, name -> 
            name.startsWith("virtual.") 
        }?.firstOrNull()
        
        return try {
            fallbackFile?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType?.startsWith("video/") == true -> mimeType
            else -> "video/mp4"
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
