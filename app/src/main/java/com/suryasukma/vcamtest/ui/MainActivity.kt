package com.suryasukma.vcamtest.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.suryasukma.vcamtest.R
import com.suryasukma.vcamtest.data.Constants.KEY_ENABLE_CAPTURE_NOTIF
import com.suryasukma.vcamtest.data.Constants.KEY_FIT_CONTAIN
import com.suryasukma.vcamtest.data.Constants.KEY_IS_ENABLED
import com.suryasukma.vcamtest.data.Constants.KEY_IS_MIRRORED
import com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_APP
import com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_ROUTE
import com.suryasukma.vcamtest.data.Constants.KEY_LATEST_PIPELINE_TIME
import com.suryasukma.vcamtest.data.Constants.KEY_MEDIA_PATH
import com.suryasukma.vcamtest.data.Constants.KEY_ROTATION_ANGLE
import com.suryasukma.vcamtest.data.Constants.PREFS_NAME
import com.suryasukma.vcamtest.media.MediaLibrary
import com.suryasukma.vcamtest.utils.RootShell
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

    private class PreviewResult(val bitmap: Bitmap?, val info: String)

    private lateinit var pageCamera: View
    private lateinit var pageMonitor: View
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var ivPreview: ImageView
    private lateinit var layoutNoPreview: View
    private lateinit var tvMediaInfo: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnChooseEmpty: MaterialButton
    private lateinit var btnSelectMedia: MaterialButton
    private lateinit var btnEdit: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnResetOriginal: MaterialButton
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var tvEnabledDesc: TextView

    private lateinit var tvModuleStatus: TextView
    private lateinit var cardModuleStatus: MaterialCardView
    private lateinit var layoutScopedApps: LinearLayout
    private lateinit var tvScopedHint: TextView

    private lateinit var switchCaptureNotif: MaterialSwitch
    private lateinit var tvPipelineEmpty: TextView
    private lateinit var layoutPipelineDetails: LinearLayout
    private lateinit var tvPipelineApp: TextView
    private lateinit var tvPipelineRoute: TextView
    private lateinit var tvPipelineTime: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var previewToken = 0

    private var loadingSettings = false
    private var isMirrored = false
    private var rotationAngle = 0
    private var hasMedia = false
    private var mXposedService: XposedService? = null

    private val prefs get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for Capture Alert Card", Toast.LENGTH_SHORT).show()
        }
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { loadSettings() }

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { copyMediaToInternal(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        bindViews()
        setupWindowInsets()
        setupNavigation()
        setupCameraPage()
        setupMonitorPage()
        loadSettings()
        updateModuleStatusUI()
        requestNotificationPermissionIfNeeded()
        // registerListener may call onServiceBind synchronously: every view must be initialized first.
        XposedServiceHelper.registerListener(this)
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewToken++
        io.shutdown()
    }

    override fun onServiceBind(service: XposedService) {
        mXposedService = service
        runOnUiThread { updateModuleStatusUI() }
    }

    override fun onServiceDied(service: XposedService) {
        mXposedService = null
        runOnUiThread { updateModuleStatusUI() }
    }

    // ---- setup -----------------------------------------------------------

    private fun bindViews() {
        pageCamera = findViewById(R.id.page_camera)
        pageMonitor = findViewById(R.id.page_monitor)
        bottomNav = findViewById(R.id.bottom_nav)

        ivPreview = findViewById(R.id.iv_preview)
        layoutNoPreview = findViewById(R.id.layout_no_preview)
        tvMediaInfo = findViewById(R.id.tv_media_info)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnChooseEmpty = findViewById(R.id.btn_choose_empty)
        btnSelectMedia = findViewById(R.id.btn_select_video)
        btnEdit = findViewById(R.id.btn_edit)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnResetOriginal = findViewById(R.id.btn_reset_original)
        switchEnabled = findViewById(R.id.switch_enabled)
        tvEnabledDesc = findViewById(R.id.tv_enabled_desc)

        tvModuleStatus = findViewById(R.id.tv_module_status)
        cardModuleStatus = findViewById(R.id.card_module_status)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)
        tvScopedHint = findViewById(R.id.tv_scoped_hint)

        switchCaptureNotif = findViewById(R.id.switch_capture_notif)
        tvPipelineEmpty = findViewById(R.id.tv_pipeline_empty)
        layoutPipelineDetails = findViewById(R.id.layout_pipeline_details)
        tvPipelineApp = findViewById(R.id.tv_pipeline_app)
        tvPipelineRoute = findViewById(R.id.tv_pipeline_route)
        tvPipelineTime = findViewById(R.id.tv_pipeline_time)

        setupTitleSpannable(findViewById(R.id.tv_title))
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val base = (20 * density).toInt()
        listOf(R.id.main_camera, R.id.main_monitor).forEach { id ->
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(id)) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left + base, bars.top + base, bars.right + base, base)
                insets
            }
        }
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId == R.id.nav_camera)
            true
        }
        // BottomNavigationView restores its selection after recreation without calling the listener.
        showPage(bottomNav.selectedItemId != R.id.nav_monitor)
    }

    private fun showPage(camera: Boolean) {
        pageCamera.visibility = if (camera) View.VISIBLE else View.GONE
        pageMonitor.visibility = if (camera) View.GONE else View.VISIBLE
    }

    /** Capture alerts default to on, so ask once up front instead of silently dropping the first alert. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            prefs.getBoolean(KEY_ENABLE_CAPTURE_NOTIF, true) &&
            !prefs.getBoolean("notif_permission_asked", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit { putBoolean("notif_permission_asked", true) }
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupCameraPage() {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (loadingSettings) return@setOnCheckedChangeListener
            prefs.edit { putBoolean(KEY_IS_ENABLED, isChecked) }
            updateEnabledUi()
        }
        val pick = View.OnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            mediaPickerLauncher.launch(intent)
        }
        btnSelectMedia.setOnClickListener(pick)
        btnChooseEmpty.setOnClickListener(pick)
        btnEdit.setOnClickListener { editorLauncher.launch(Intent(this, EditorActivity::class.java)) }
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_fit).addOnButtonCheckedListener { _, id, checked ->
            if (loadingSettings || !checked) return@addOnButtonCheckedListener
            prefs.edit { putBoolean(KEY_FIT_CONTAIN, id == R.id.btn_fit_contain) }
        }
        btnDeleteMedia.setOnClickListener { confirmDeleteMedia() }
        btnResetOriginal.setOnClickListener { resetToOriginal() }

        btnMirror.setOnClickListener {
            isMirrored = !isMirrored
            saveTransform()
            updatePreviewTransform()
        }
        btnRotateLeft.setOnClickListener {
            rotationAngle = (rotationAngle - 90 + 360) % 360
            saveTransform()
            updatePreviewTransform()
        }
        btnRotateRight.setOnClickListener {
            rotationAngle = (rotationAngle + 90) % 360
            saveTransform()
            updatePreviewTransform()
        }
    }

    private fun setupMonitorPage() {
        switchCaptureNotif.setOnCheckedChangeListener { _, isChecked ->
            if (loadingSettings) return@setOnCheckedChangeListener
            prefs.edit { putBoolean(KEY_ENABLE_CAPTURE_NOTIF, isChecked) }
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<View>(R.id.btn_copy_pipeline).setOnClickListener { copyPipeline() }
        findViewById<View>(R.id.btn_clear_pipeline).setOnClickListener {
            prefs.edit {
                remove(KEY_LATEST_PIPELINE_APP)
                remove(KEY_LATEST_PIPELINE_ROUTE)
                remove(KEY_LATEST_PIPELINE_TIME)
            }
            loadTelemetryUI()
            Toast.makeText(this, "Pipeline history cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_view_logs).setOnClickListener { showLogs() }
        findViewById<View>(R.id.btn_about).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_about)
                .setMessage(R.string.msg_about)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }
    }

    // ---- media -----------------------------------------------------------

    private fun copyMediaToInternal(uri: Uri) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        MediaLibrary.importUri(this, uri) { path, error ->
            runOnUiThread {
                if (path != null) {
                    // The old files are already gone: always persist, even if this activity was recreated meanwhile.
                    prefs.edit {
                        putBoolean(KEY_IS_ENABLED, true)
                        putString(KEY_MEDIA_PATH, path)
                        putBoolean(KEY_IS_MIRRORED, false)
                        putInt(KEY_ROTATION_ANGLE, 0)
                    }
                    if (!isDestroyed) {
                        isMirrored = false
                        rotationAngle = 0
                        loadSettings()
                    }
                    Toast.makeText(applicationContext, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_media_import_failed, error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resetToOriginal() {
        MediaLibrary.resetToOriginal(this) { path, error ->
            runOnUiThread {
                if (path != null) {
                    prefs.edit {
                        putString(KEY_MEDIA_PATH, path)
                        putBoolean(KEY_IS_MIRRORED, false)
                        putInt(KEY_ROTATION_ANGLE, 0)
                    }
                    if (!isDestroyed) {
                        isMirrored = false
                        rotationAngle = 0
                        loadSettings()
                    }
                    Toast.makeText(applicationContext, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, error ?: "Reset failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeleteMedia() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toast_media_removed)
            .setMessage(R.string.msg_confirm_remove)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                MediaLibrary.clearAll(this)
                saveMediaPath("", resetTransform = true)
                Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveMediaPath(path: String, resetTransform: Boolean) {
        if (resetTransform) {
            isMirrored = false
            rotationAngle = 0
        }
        prefs.edit {
            putString(KEY_MEDIA_PATH, path)
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
        loadSettings()
    }

    private fun saveTransform() {
        prefs.edit {
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
    }

    // ---- state -> UI -----------------------------------------------------

    private fun loadSettings() {
        val p = prefs
        val mediaPath = p.getString(KEY_MEDIA_PATH, "") ?: ""
        isMirrored = p.getBoolean(KEY_IS_MIRRORED, false)
        rotationAngle = p.getInt(KEY_ROTATION_ANGLE, 0)
        loadingSettings = true
        switchCaptureNotif.isChecked = p.getBoolean(KEY_ENABLE_CAPTURE_NOTIF, true)
        switchEnabled.isChecked = p.getBoolean(KEY_IS_ENABLED, true)
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_fit)
            .check(if (p.getBoolean(KEY_FIT_CONTAIN, false)) R.id.btn_fit_contain else R.id.btn_fit_fill)
        loadingSettings = false
        updatePreview(mediaPath)
        updatePreviewTransform()
        loadTelemetryUI()
    }

    private fun updateEnabledUi() {
        switchEnabled.isEnabled = hasMedia
        tvEnabledDesc.setText(
            when {
                !hasMedia -> R.string.desc_enabled_no_media
                switchEnabled.isChecked -> R.string.desc_enabled_on
                else -> R.string.desc_enabled_off
            }
        )
    }

    private fun updatePreview(path: String) {
        val token = ++previewToken
        val file = if (path.isNotEmpty()) File(path) else null
        hasMedia = file?.exists() == true

        btnEdit.isEnabled = hasMedia
        btnRotateLeft.isEnabled = hasMedia
        btnRotateRight.isEnabled = hasMedia
        btnMirror.isEnabled = hasMedia
        btnDeleteMedia.visibility = if (hasMedia) View.VISIBLE else View.GONE
        btnResetOriginal.visibility = if (hasMedia && MediaLibrary.sourceFile(this) != null) View.VISIBLE else View.GONE
        btnSelectMedia.setText(if (hasMedia) R.string.btn_replace_media else R.string.btn_select_video)
        updateEnabledUi()

        if (!hasMedia || file == null) {
            ivPreview.setImageBitmap(null)
            layoutNoPreview.visibility = View.VISIBLE
            tvMediaInfo.visibility = View.GONE
            return
        }

        layoutNoPreview.visibility = View.GONE
        // Decoding a frame can be slow: never do it on the main thread.
        try {
            io.execute {
                val result = decodePreview(file)
                ui.post {
                    if (token != previewToken || isDestroyed) return@post
                    if (result.bitmap != null) {
                        ivPreview.setImageBitmap(result.bitmap)
                        tvMediaInfo.text = result.info
                        tvMediaInfo.visibility = View.VISIBLE
                    } else {
                        ivPreview.setImageBitmap(null)
                        layoutNoPreview.visibility = View.VISIBLE
                        tvMediaInfo.visibility = View.GONE
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Activity is being destroyed.
        }
    }

    private fun decodePreview(file: File): PreviewResult {
        val info = mutableListOf<String>()
        var bitmap: Bitmap? = null
        try {
            if (MediaLibrary.isVideo(file)) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    bitmap = retriever.getFrameAtTime(1_000_000) ?: retriever.getFrameAtTime(0)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (w != null && h != null) info += if (rot % 180 != 0) "${h}×${w}" else "${w}×${h}"
                    if (dur > 0) info += String.format(Locale.US, "%d:%02d", dur / 60000, (dur / 1000) % 60)
                } finally {
                    try { retriever.release() } catch (_: Throwable) {}
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bounds.outWidth > 0) info += "${bounds.outWidth}×${bounds.outHeight}"
            }
            info += android.text.format.Formatter.formatShortFileSize(this, file.length())
        } catch (_: Throwable) {
            bitmap = null
        }
        return PreviewResult(bitmap, info.joinToString("  ·  "))
    }

    private fun updatePreviewTransform() {
        ivPreview.scaleX = if (isMirrored) -1f else 1f
        ivPreview.rotation = rotationAngle.toFloat()
        val activeColor = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val activeIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)
        val inactiveColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.LTGRAY)
        val inactiveIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.DKGRAY)
        btnMirror.setBackgroundColor(if (isMirrored) activeColor else inactiveColor)
        btnMirror.alpha = if (btnMirror.isEnabled) 1f else 0.38f
        btnMirror.iconTint = android.content.res.ColorStateList.valueOf(if (isMirrored) activeIconColor else inactiveIconColor)
    }

    // ---- module status / scope ------------------------------------------

    private fun updateModuleStatusUI() {
        val service = mXposedService
        // checkSelfActive() is hooked by the module itself (XCamModule#hookManagerApp) to report "active".
        val isActive = service != null || checkSelfActive()

        if (isActive) {
            tvModuleStatus.text = if (service != null && !service.frameworkName.isNullOrBlank()) {
                "${getString(R.string.status_module_active)} (${service.frameworkName})"
            } else {
                getString(R.string.status_module_active)
            }
            val activeColor = MaterialColors.getColor(tvModuleStatus, androidx.appcompat.R.attr.colorPrimary)
            tvModuleStatus.setTextColor(activeColor)
            cardModuleStatus.strokeColor = activeColor
            refreshLSPosedScope()
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            tvModuleStatus.setTextColor(MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOnSurfaceVariant))
            cardModuleStatus.strokeColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOutline)
            layoutScopedApps.removeAllViews()
            tvScopedHint.setText(R.string.label_no_target_apps)
        }
    }

    private fun checkSelfActive(): Boolean = false

    private fun getOfficialScope(): List<String> = try {
        mXposedService?.scope ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews()
        val scope = getOfficialScope().filter { it != packageName }
        if (scope.isEmpty()) {
            tvScopedHint.setText(R.string.label_no_target_apps)
        } else {
            tvScopedHint.setText(R.string.label_target_apps_hint)
            scope.forEach { addAppIconToLayout(it) }
        }
    }

    private fun addAppIconToLayout(pkgName: String) {
        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            val size = (44 * resources.displayMetrics.density).toInt()
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, 16, 0) }
                setImageDrawable(icon)
                contentDescription = pkgName
                setOnClickListener { showAppActions(pkgName) }
            }
            layoutScopedApps.addView(imageView)
        } catch (_: Exception) {}
    }

    private fun showAppActions(pkg: String) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        val items = arrayOf(getString(R.string.action_open_app), getString(R.string.action_force_stop))
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setItems(items) { _, which ->
                if (which == 0) {
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                } else {
                    io.execute {
                        val r = RootShell.forceStop(pkg)
                        ui.post {
                            if (isDestroyed) return@post
                            Toast.makeText(
                                this,
                                if (r.ok) getString(R.string.toast_force_stopped, label) else getString(R.string.toast_root_denied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    // ---- monitor ---------------------------------------------------------

    private fun loadTelemetryUI() {
        val p = prefs
        val app = p.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
        val route = p.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
        val time = p.getLong(KEY_LATEST_PIPELINE_TIME, 0L)

        if (app.isEmpty() || route.isEmpty()) {
            tvPipelineEmpty.visibility = View.VISIBLE
            layoutPipelineDetails.visibility = View.GONE
        } else {
            tvPipelineEmpty.visibility = View.GONE
            layoutPipelineDetails.visibility = View.VISIBLE
            tvPipelineApp.text = getString(R.string.label_last_active_app, app)
            tvPipelineRoute.text = route
            val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
            tvPipelineTime.text = getString(R.string.label_last_timestamp, formatted)
        }
    }

    private fun copyPipeline() {
        val p = prefs
        val app = p.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
        val route = p.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
        val time = p.getLong(KEY_LATEST_PIPELINE_TIME, 0L)
        if (app.isEmpty() && route.isEmpty()) {
            Toast.makeText(this, "No route to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val formattedTime = if (time > 0L) SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(time)) else ""
        val text = buildString {
            if (app.isNotEmpty()) append("Target: $app\n")
            if (route.isNotEmpty()) append("Route: $route\n")
            if (formattedTime.isNotEmpty()) append("Time: $formattedTime")
        }.trim()
        copyToClipboard("FDVCam Pipeline", text)
        Toast.makeText(this, "Pipeline route copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showLogs() {
        Toast.makeText(this, R.string.label_processing, Toast.LENGTH_SHORT).show()
        io.execute {
            val (rooted, text) = RootShell.readLogs()
            ui.post {
                if (isDestroyed) return@post
                val body = if (text.isBlank()) getString(R.string.label_logs_empty) else text
                val padding = (16 * resources.displayMetrics.density).toInt()
                val tv = TextView(this).apply {
                    this.text = body
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(padding, padding, padding, padding)
                }
                val scroll = ScrollView(this).apply { addView(tv) }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.title_logs)
                    .apply { if (!rooted) setMessage(R.string.label_logs_no_root) }
                    .setView(scroll)
                    .setPositiveButton(R.string.btn_copy) { _, _ ->
                        copyToClipboard("FDVCam logs", body)
                        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton(R.string.btn_clear_logs) { _, _ ->
                        io.execute {
                            val r = RootShell.clearLogs()
                            ui.post {
                                if (!isDestroyed) {
                                    Toast.makeText(
                                        this,
                                        if (r.ok) getString(R.string.toast_logs_cleared) else getString(R.string.toast_root_denied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.btn_close, null)
                    .show()
                // Jump to the newest lines.
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun setupTitleSpannable(tvTitle: TextView) {
        val spannable = SpannableStringBuilder(tvTitle.text.toString())
        val primaryColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, "#FF6200EE".toColorInt())
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTitle.text = spannable
    }
}
