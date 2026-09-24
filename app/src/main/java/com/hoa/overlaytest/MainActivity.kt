package com.hoa.overlaytest

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var projectionStatus: TextView
    private lateinit var inputSeconds: EditText

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            projectionStatus.text = "Ghi hình: người dùng từ chối quyền chia sẻ màn hình"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        projectionStatus = findViewById(R.id.projectionStatus)
        inputSeconds = findViewById(R.id.inputSeconds)

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
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateProjectionStatus()
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
