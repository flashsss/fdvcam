package com.suryasukma.vcamtest.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.suryasukma.vcamtest.R
import com.suryasukma.vcamtest.data.Constants.KEY_MEDIA_PATH
import com.suryasukma.vcamtest.data.Constants.PREFS_NAME
import com.suryasukma.vcamtest.media.BitmapOps
import com.suryasukma.vcamtest.media.EditSpec
import com.suryasukma.vcamtest.media.MediaEditor
import com.suryasukma.vcamtest.media.MediaLibrary
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var stage: View
    private lateinit var ivEditor: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var progress: View
    private lateinit var btnApply: MaterialButton
    private lateinit var btnClose: MaterialButton
    private lateinit var panels: Map<Int, View>
    private lateinit var sliderTrim: RangeSlider

    private var source: File? = null
    private var isVideo = false
    private var base: Bitmap? = null
    private var displayed: Bitmap? = null

    private var rotation = 0
    private var flipH = false
    private var flipV = false
    private var brightness = 0f
    private var contrast = 0f
    private var saturation = 0f
    private var durationMs = 0L
    private var trimStart = 0L
    private var trimEnd = 0L
    private var busy = false

    private val frameWorker = Executors.newSingleThreadExecutor()
    private val pendingFrameMs = AtomicLong(-1)
    private var retriever: MediaMetadataRetriever? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_editor)

        val root = findViewById<View>(R.id.editor_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val src = MediaLibrary.ensureSource(this, prefs.getString(KEY_MEDIA_PATH, ""))
        if (src == null) {
            Toast.makeText(this, R.string.toast_no_source, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        source = src
        isVideo = MediaLibrary.isVideo(src)

        bindViews()
        setupTabs()
        setupCrop()
        setupRotate()
        setupAdjust()
        setupTrim()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) finish()
            }
        })
        loadBase(src)
    }

    override fun onDestroy() {
        super.onDestroy()
        frameWorker.execute {
            try { retriever?.release() } catch (_: Throwable) {}
            retriever = null
        }
        frameWorker.shutdown()
    }

    // ---- setup -----------------------------------------------------------

    private fun bindViews() {
        stage = findViewById(R.id.editor_stage)
        ivEditor = findViewById(R.id.iv_editor)
        overlay = findViewById(R.id.crop_overlay)
        progress = findViewById(R.id.progress_container)
        btnApply = findViewById(R.id.btn_editor_apply)
        btnClose = findViewById(R.id.btn_editor_close)
        sliderTrim = findViewById(R.id.slider_trim)
        panels = mapOf(
            R.id.tab_crop to findViewById(R.id.panel_crop),
            R.id.tab_rotate to findViewById(R.id.panel_rotate),
            R.id.tab_adjust to findViewById(R.id.panel_adjust),
            R.id.tab_trim to findViewById(R.id.panel_trim),
        )
        btnClose.setOnClickListener { if (!busy) finish() }
        btnApply.setOnClickListener { applyEdit() }
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) {
                displayed?.let { updateOverlayRect(it.width, it.height, keepCrop = true) }
            }
        }
    }

    private fun setupTabs() {
        val tabs = findViewById<MaterialButtonToggleGroup>(R.id.tabs)
        if (!isVideo) findViewById<View>(R.id.tab_trim).visibility = View.GONE
        tabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            panels.forEach { (tab, panel) -> panel.visibility = if (tab == id) View.VISIBLE else View.GONE }
            overlay.interactive = id == R.id.tab_crop
        }
        tabs.check(R.id.tab_crop)
    }

    private fun setupCrop() {
        val chips = findViewById<ChipGroup>(R.id.chips_aspect)
        chips.setOnCheckedStateChangeListener { _, ids ->
            val aspect = when (ids.firstOrNull()) {
                R.id.chip_1_1 -> 1f
                R.id.chip_4_3 -> 4f / 3f
                R.id.chip_3_4 -> 3f / 4f
                R.id.chip_16_9 -> 16f / 9f
                R.id.chip_9_16 -> 9f / 16f
                else -> null
            }
            overlay.setAspect(aspect)
        }
        findViewById<View>(R.id.btn_reset_crop).setOnClickListener {
            chips.check(R.id.chip_free)
            overlay.setAspect(null)
            overlay.resetCrop()
        }
    }

    private fun setupRotate() {
        findViewById<View>(R.id.btn_ed_rotate_left).setOnClickListener {
            rotation = (rotation + 270) % 360; refreshDisplay(resetCrop = true)
        }
        findViewById<View>(R.id.btn_ed_rotate_right).setOnClickListener {
            rotation = (rotation + 90) % 360; refreshDisplay(resetCrop = true)
        }
        findViewById<View>(R.id.btn_ed_flip_h).setOnClickListener {
            flipH = !flipH; refreshDisplay(resetCrop = true)
        }
        findViewById<View>(R.id.btn_ed_flip_v).setOnClickListener {
            flipV = !flipV; refreshDisplay(resetCrop = true)
        }
    }

    private fun setupAdjust() {
        val sb = findViewById<Slider>(R.id.slider_brightness)
        val sc = findViewById<Slider>(R.id.slider_contrast)
        val ss = findViewById<Slider>(R.id.slider_saturation)
        val tb = findViewById<TextView>(R.id.tv_brightness)
        val tc = findViewById<TextView>(R.id.tv_contrast)
        val ts = findViewById<TextView>(R.id.tv_saturation)
        fun label(tv: TextView, res: Int, v: Float) {
            tv.text = String.format(Locale.US, "%s  %+d", getString(res), v.toInt())
        }
        sb.addOnChangeListener { _, v, _ -> brightness = v / 100f; label(tb, R.string.label_brightness, v); applyColorPreview() }
        sc.addOnChangeListener { _, v, _ -> contrast = v / 100f; label(tc, R.string.label_contrast, v); applyColorPreview() }
        ss.addOnChangeListener { _, v, _ -> saturation = v; label(ts, R.string.label_saturation, v); applyColorPreview() }
        findViewById<View>(R.id.btn_reset_adjust).setOnClickListener {
            sb.value = 0f; sc.value = 0f; ss.value = 0f
        }
    }

    private fun setupTrim() {
        var lastStart = 0f
        sliderTrim.addOnChangeListener { slider, _, fromUser ->
            val v = slider.values
            trimStart = v[0].toLong()
            trimEnd = v[1].toLong()
            updateTrimLabel()
            if (fromUser) {
                requestFrame(if (v[0] != lastStart) trimStart else trimEnd)
            }
            lastStart = v[0]
        }
    }

    // ---- media loading ---------------------------------------------------

    private fun loadBase(src: File) {
        frameWorker.execute {
            try {
                val bmp: Bitmap
                if (isVideo) {
                    val r = MediaMetadataRetriever().apply { setDataSource(src.absolutePath) }
                    retriever = r
                    durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { scaleDown(it) } ?: throw IllegalStateException("no frame")
                } else {
                    bmp = MediaEditor.decodeSampled(src.absolutePath, 1600)
                }
                runOnUiThread {
                    if (isVideo) initTrim()
                    base = bmp
                    refreshDisplay(resetCrop = true)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, R.string.toast_no_source, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int = 1600): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun initTrim() {
        if (durationMs <= 0L) {
            findViewById<View>(R.id.tab_trim).visibility = View.GONE
            return
        }
        trimStart = 0L
        trimEnd = durationMs
        sliderTrim.valueFrom = 0f
        sliderTrim.valueTo = durationMs.toFloat()
        sliderTrim.values = listOf(0f, durationMs.toFloat())
        updateTrimLabel()
    }

    private fun updateTrimLabel() {
        findViewById<TextView>(R.id.tv_trim_range).text =
            getString(R.string.label_trim_range, formatTime(trimStart), formatTime(trimEnd))
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, (ms % 1000) / 100)
    }

    private fun requestFrame(ms: Long) {
        pendingFrameMs.set(ms)
        frameWorker.execute {
            val t = pendingFrameMs.getAndSet(-1)
            if (t < 0) return@execute
            val bmp = try {
                retriever?.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it) }
            } catch (_: Throwable) { null }
            if (bmp != null) runOnUiThread {
                if (!isDestroyed) {
                    base = bmp
                    refreshDisplay(resetCrop = false)
                }
            }
        }
    }

    // ---- preview ---------------------------------------------------------

    private fun currentSpec(crop: RectF = overlay.normalizedCrop()): EditSpec {
        val end = if (isVideo && trimEnd < durationMs - 50) trimEnd else -1L
        return EditSpec(
            rotation, flipH, flipV,
            crop.left, crop.top, crop.right, crop.bottom,
            brightness, contrast, saturation,
            if (isVideo) trimStart else 0L, end,
        )
    }

    private fun refreshDisplay(resetCrop: Boolean) {
        val b = base ?: return
        val d = BitmapOps.transform(b, flipH, flipV, rotation)
        displayed = d
        ivEditor.setImageBitmap(d)
        applyColorPreview()
        updateOverlayRect(d.width, d.height, keepCrop = !resetCrop)
    }

    private fun applyColorPreview() {
        val f = EditSpec(brightness = brightness, contrast = contrast, saturation = saturation).colorFilter()
        if (f != null) ivEditor.colorFilter = f else ivEditor.clearColorFilter()
    }

    private fun updateOverlayRect(bw: Int, bh: Int, keepCrop: Boolean) {
        val vw = stage.width.toFloat()
        val vh = stage.height.toFloat()
        if (vw <= 0f || vh <= 0f) {
            stage.post { updateOverlayRect(bw, bh, keepCrop) }
            return
        }
        val s = min(vw / bw, vh / bh)
        val w = bw * s
        val h = bh * s
        val left = (vw - w) / 2f
        val top = (vh - h) / 2f
        overlay.setImageRect(RectF(left, top, left + w, top + h), keepCrop)
    }

    // ---- export ----------------------------------------------------------

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnApply.isEnabled = !value
        btnClose.isEnabled = !value
    }

    private fun applyEdit() {
        val src = source ?: return
        if (busy) return
        val spec = currentSpec()
        if (spec.isIdentity(includeTrim = isVideo)) {
            Toast.makeText(this, R.string.toast_no_changes, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        val tmp = File(filesDir, MediaLibrary.TEMP_OUTPUT_NAME)
        val out = File(filesDir, MediaLibrary.DEFAULT_VIDEO_NAME)
        val done = { ok: Boolean ->
            runOnUiThread {
                setBusy(false)
                if (ok && tmp.exists()) {
                    MediaLibrary.clearVirtual(this)
                    tmp.renameTo(out)
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(KEY_MEDIA_PATH, out.absolutePath) }
                    Toast.makeText(this, R.string.toast_edit_done, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    tmp.delete()
                    Toast.makeText(this, R.string.toast_edit_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (isVideo) MediaEditor.editVideo(this, src, spec, tmp, done)
        else MediaEditor.editImage(this, src, spec, tmp, done)
    }
}
