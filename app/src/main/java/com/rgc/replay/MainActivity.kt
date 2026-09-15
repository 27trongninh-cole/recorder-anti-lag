package com.rgc.replay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Bong bóng đã hiện — vào game rồi chạm để bắt đầu quay", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Bạn cần cho phép quay màn hình", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContentView(buildUi())
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Game Replay Recorder"
            textSize = 20f
        }

        val startBtn = Button(this).apply {
            text = "1) Cấp quyền hiển thị nổi (overlay)"
            setOnClickListener { requestOverlayPermissionIfNeeded() }
        }

        val recordBtn = Button(this).apply {
            text = "2) Cấp quyền quay màn hình → hiện bong bóng"
            setOnClickListener { startCapture() }
        }

        val stopBtn = Button(this).apply {
            text = "Đóng app quay (chỉ khi không đang quay)"
            setOnClickListener { stopCapture() }
        }

        val hint = TextView(this).apply {
            text = "Sau bước 2 sẽ hiện 1 bong bóng tròn nổi:\n" +
                "• Chạm: bắt đầu quay (lần đầu) / đánh dấu khoảnh khắc (khi đang quay)\n" +
                "• Vuốt trái: tạm dừng / tiếp tục\n" +
                "• Vuốt phải: dừng quay, xuất tất cả khoảnh khắc đã đánh dấu\n" +
                "• Vuốt lên: chọn thời lượng lưu (15/30/60/90s)\n" +
                "• Vuốt xuống: dính bong bóng vào cạnh màn hình\n" +
                "• Kéo chậm: di chuyển bong bóng tránh đè nút/tướng\n\n" +
                "Video chỉ được lưu khi bạn vuốt phải để dừng quay — vào Movies/GameReplay."
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(startBtn)
        root.addView(recordBtn)
        root.addView(stopBtn)
        root.addView(hint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        return root
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Đã có quyền overlay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cấp quyền overlay trước (bước 1)", Toast.LENGTH_SHORT).show()
            return
        }
        if (ScreenCaptureService.isRunning) {
            Toast.makeText(this, "Đang quay rồi", Toast.LENGTH_SHORT).show()
            return
        }
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "Đã dừng quay", Toast.LENGTH_SHORT).show()
    }
}
