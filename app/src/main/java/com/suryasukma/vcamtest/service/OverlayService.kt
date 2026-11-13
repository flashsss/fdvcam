package com.suryasukma.vcamtest.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.suryasukma.vcamtest.R
import com.suryasukma.vcamtest.data.Constants

/**
 * Floating control panel for Challenge Test mode: a few buttons drawn on top of whatever app is in the foreground,
 * so a tester can switch the camera feed to match an active-liveness prompt (look left/right, blink) without
 * leaving the app under test — leaving it would normally close its camera session and abort the test.
 *
 * Requires "draw over other apps" (checked by the caller before starting this service). Every tap just sends a
 * broadcast; the actual camera-feed switch happens inside the hooked app's own process (see XCamModule).
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    private fun send(action: String, role: String? = null) {
        sendBroadcast(Intent(action).apply { role?.let { putExtra(Constants.EXTRA_CHALLENGE_ROLE, it) } })
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: run { stopSelf(); return }
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_challenge, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        view.findViewById<View>(R.id.btn_overlay_neutral).setOnClickListener { send(Constants.ACTION_CHALLENGE_SWITCH, "neutral") }
        view.findViewById<View>(R.id.btn_overlay_look_left).setOnClickListener { send(Constants.ACTION_CHALLENGE_SWITCH, "look_left") }
        view.findViewById<View>(R.id.btn_overlay_look_right).setOnClickListener { send(Constants.ACTION_CHALLENGE_SWITCH, "look_right") }
        view.findViewById<View>(R.id.btn_overlay_blink).setOnClickListener { send(Constants.ACTION_CHALLENGE_SWITCH, "blink") }
        view.findViewById<View>(R.id.btn_overlay_close).setOnClickListener {
            send(Constants.ACTION_CHALLENGE_STOP)
            stopSelf()
        }

        // Drag the handle to reposition the panel.
        val handle = view.findViewById<View>(R.id.overlay_handle)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
        } catch (_: Throwable) {
            // Most likely the overlay permission was revoked after the caller checked it.
            stopSelf()
        }
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        overlayView = null
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
    }
}
