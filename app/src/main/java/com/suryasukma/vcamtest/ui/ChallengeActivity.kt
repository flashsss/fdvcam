package com.suryasukma.vcamtest.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suryasukma.vcamtest.R
import com.suryasukma.vcamtest.data.Constants
import com.suryasukma.vcamtest.media.ChallengeLibrary
import com.suryasukma.vcamtest.media.ChallengeRole
import com.suryasukma.vcamtest.service.OverlayService
import java.util.concurrent.Executors

/**
 * Manages the Challenge Test clip set (one short clip per [ChallengeRole]) and starts/stops the manual active-
 * liveness test: a floating panel ([OverlayService]) lets the tester switch the camera feed in real time to match
 * whatever prompt their own system shows.
 */
class ChallengeActivity : AppCompatActivity() {

    private class RoleRow(val thumb: ImageView, val status: TextView, val btnDelete: View)

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var rows: Map<ChallengeRole, RoleRow>
    private lateinit var btnStartStop: MaterialButton
    private var pendingImportRole: ChallengeRole? = null
    private var active = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canUseOverlay()) startChallengeTest() else Toast.makeText(this, R.string.toast_overlay_permission_needed, Toast.LENGTH_LONG).show()
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val role = pendingImportRole
        pendingImportRole = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && role != null && uri != null) {
            Toast.makeText(this, R.string.label_processing, Toast.LENGTH_SHORT).show()
            ChallengeLibrary.importClip(this, role, uri) { ok, error ->
                if (ok) refreshRow(role) else Toast.makeText(this, error ?: getString(R.string.toast_edit_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_challenge)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.challenge_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val d = resources.displayMetrics.density
            val base = (20 * d).toInt()
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }

        findViewById<View>(R.id.btn_challenge_back).setOnClickListener { finish() }
        btnStartStop = findViewById(R.id.btn_start_stop_challenge)

        rows = mapOf(
            ChallengeRole.NEUTRAL to RoleRow(findViewById(R.id.iv_thumb_neutral), findViewById(R.id.tv_status_neutral), findViewById(R.id.btn_delete_neutral)),
            ChallengeRole.LOOK_LEFT to RoleRow(findViewById(R.id.iv_thumb_look_left), findViewById(R.id.tv_status_look_left), findViewById(R.id.btn_delete_look_left)),
            ChallengeRole.LOOK_RIGHT to RoleRow(findViewById(R.id.iv_thumb_look_right), findViewById(R.id.tv_status_look_right), findViewById(R.id.btn_delete_look_right)),
            ChallengeRole.BLINK to RoleRow(findViewById(R.id.iv_thumb_blink), findViewById(R.id.tv_status_blink), findViewById(R.id.btn_delete_blink)),
        )
        findViewById<View>(R.id.btn_import_neutral).setOnClickListener { pickClip(ChallengeRole.NEUTRAL) }
        findViewById<View>(R.id.btn_import_look_left).setOnClickListener { pickClip(ChallengeRole.LOOK_LEFT) }
        findViewById<View>(R.id.btn_import_look_right).setOnClickListener { pickClip(ChallengeRole.LOOK_RIGHT) }
        findViewById<View>(R.id.btn_import_blink).setOnClickListener { pickClip(ChallengeRole.BLINK) }
        rows.forEach { (role, row) -> row.btnDelete.setOnClickListener { deleteClip(role) } }

        btnStartStop.setOnClickListener { if (active) stopChallengeTest() else requestOverlayThenStart() }

        ChallengeRole.entries.forEach { refreshRow(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun pickClip(role: ChallengeRole) {
        pendingImportRole = role
        importLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
        )
    }

    private fun deleteClip(role: ChallengeRole) {
        MaterialAlertDialogBuilder(this)
            .setTitle(role.displayName)
            .setMessage(R.string.msg_confirm_remove)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                ChallengeLibrary.deleteClip(this, role)
                refreshRow(role)
            }
            .show()
    }

    private fun refreshRow(role: ChallengeRole) {
        val row = rows[role] ?: return
        try {
            io.execute {
                val bmp = ChallengeLibrary.thumbnail(this, role)
                ui.post {
                    if (isDestroyed) return@post
                    row.thumb.setImageBitmap(bmp)
                    row.status.setText(if (bmp != null) R.string.label_clip_ready else R.string.label_clip_missing)
                    row.btnDelete.visibility = if (bmp != null) View.VISIBLE else View.GONE
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Activity is being destroyed.
        }
    }

    private fun canUseOverlay() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayThenStart() {
        if (!ChallengeLibrary.hasAnyClip(this)) {
            Toast.makeText(this, R.string.toast_import_at_least_one, Toast.LENGTH_LONG).show()
            return
        }
        if (canUseOverlay()) {
            startChallengeTest()
        } else {
            Toast.makeText(this, R.string.toast_overlay_permission_needed, Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun startChallengeTest() {
        startService(Intent(this, OverlayService::class.java))
        applicationContext.sendBroadcast(Intent(Constants.ACTION_CHALLENGE_START))
        active = true
        btnStartStop.setText(R.string.btn_stop_challenge_test)
        Toast.makeText(this, R.string.toast_challenge_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopChallengeTest() {
        applicationContext.sendBroadcast(Intent(Constants.ACTION_CHALLENGE_STOP))
        stopService(Intent(this, OverlayService::class.java))
        active = false
        btnStartStop.setText(R.string.btn_start_challenge_test)
    }
}
