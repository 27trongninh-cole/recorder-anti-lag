package com.hoa.overlaytest

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var projectionStatus: TextView
    private lateinit var inputSeconds: EditText
    private lateinit var heroButtonsContainer: LinearLayout
    private lateinit var selectedHeroText: TextView
    private lateinit var detectionLog: TextView

    private var selectedHeroFile: String? = null
    private val logLines = ArrayDeque<String>()
    private val MAX_LOG_LINES = 60

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
                putExtra(RecordingService.EXTRA_HERO_FILE, selectedHeroFile)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            projectionStatus.text = "Ghi hình: người dùng từ chối quyền chia sẻ màn hình"
        }
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                RecordingService.ACTION_EXPORT_RESULT -> {
                    val success = intent.getBooleanExtra(RecordingService.EXTRA_SUCCESS, false)
                    val path = intent.getStringExtra(RecordingService.EXTRA_PATH)
                    val error = intent.getStringExtra(RecordingService.EXTRA_ERROR)
                    projectionStatus.text = if (success) {
                        "Cắt clip THÀNH CÔNG ✅\nFile: $path"
                    } else {
                        "Cắt clip THẤT BẠI ❌\nLỗi: $error"
                    }
                }
                RecordingService.ACTION_DETECTION_LOG -> {
                    val msg = intent.getStringExtra(RecordingService.EXTRA_LOG_MESSAGE) ?: return
                    appendLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        projectionStatus = findViewById(R.id.projectionStatus)
        inputSeconds = findViewById(R.id.inputSeconds)
        heroButtonsContainer = findViewById(R.id.heroButtonsContainer)
        selectedHeroText = findViewById(R.id.selectedHeroText)
        detectionLog = findViewById(R.id.detectionLog)

        setupHeroButtons()

        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.btnBatteryOptimization).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            } else {
                requestOverlayPermission()
            }
        }

        findViewById<Button>(R.id.btnStopOverlay).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        findViewById<Button>(R.id.btnStartProjection).setOnClickListener {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnSimulateCapture).setOnClickListener {
            val seconds = inputSeconds.text.toString().toIntOrNull() ?: 15
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_EXPORT
                putExtra(RecordingService.EXTRA_EXPORT_SECONDS, seconds)
            }
            startService(intent)
            projectionStatus.text = "Đang cắt $seconds giây gần nhất... (kiểm tra lại sau vài giây)"
        }

        findViewById<Button>(R.id.btnStopProjection).setOnClickListener {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("detection_log", detectionLog.text.toString()))
        }
    }

    private fun setupHeroButtons() {
        val files = try {
            assets.list("avatars")?.filter {
                it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (files.isEmpty()) {
            selectedHeroText.text = "Chưa có ảnh avatar nào trong assets/avatars — xem README trong thư mục đó"
            return
        }

        for (fileName in files) {
            val btn = Button(this).apply {
                text = fileName.substringBeforeLast(".")
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    selectedHeroFile = fileName
                    selectedHeroText.text = "Đã chọn: $fileName"
                }
            }
            heroButtonsContainer.addView(btn)
        }
    }

    private fun appendLog(msg: String) {
        logLines.addLast(msg)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        detectionLog.text = logLines.joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateProjectionStatus()
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_EXPORT_RESULT)
            addAction(RecordingService.ACTION_DETECTION_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(eventReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(eventReceiver)
        } catch (_: Exception) { }
    }

    private fun updateStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        statusText.text = buildString {
            append("Quyền overlay: ")
            append(if (canOverlay) "ĐÃ CẤP ✅" else "CHƯA CẤP ❌")
            append("\nBỏ giới hạn pin: ")
            append(if (ignoringBattery) "ĐÃ BẬT ✅" else "CHƯA BẬT ⚠️")
        }
    }

    private fun updateProjectionStatus() {
        projectionStatus.text = buildString {
            append("Ghi hình: ")
            append(if (RecordingService.isRunning) "ĐANG CHẠY ✅" else "chưa chạy / đã dừng")
            RecordingService.lastExportPath?.let {
                append("\nClip gần nhất đã xuất:\n$it")
            }
            RecordingService.lastError?.let {
                append("\n\nLỗi gần nhất: $it")
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
