package com.rgc.replay

import android.content.Intent
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

/**
 * The only screen the user ever sees. One button, two possible taps:
 *  - overlay permission not granted yet -> tap sends them to the system
 *    permission screen.
 *  - overlay permission already granted -> tap starts the bubble service
 *    and closes this app immediately. Screen-recording permission is asked
 *    for later, from the bubble itself (see ScreenCaptureService.onTap()).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var startBtn: Button

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshButtonLabel()
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

        startBtn = Button(this).apply {
            setOnClickListener { onStartClicked() }
        }

        val hint = TextView(this).apply {
            text = "Sau khi bong bóng hiện ra, mọi thao tác đều qua nó:\n" +
                "• Chạm lần 1 (khi vào game rồi): xin quyền quay màn hình\n" +
                "• Chạm lần 2: bắt đầu quay / các lần sau: đánh dấu khoảnh khắc\n" +
                "• Vuốt trái: tạm dừng / tiếp tục\n" +
                "• Vuốt phải: dừng quay, xuất tất cả khoảnh khắc đã đánh dấu\n" +
                "• Vuốt lên: chọn thời lượng lưu (15/30/60/90s)\n" +
                "• Vuốt xuống: dính bong bóng vào cạnh màn hình\n" +
                "• Nhấn giữ ~0.5s rồi kéo: di chuyển bong bóng\n\n" +
                "Video chỉ được lưu khi bạn vuốt phải để dừng quay — vào Movies/GameReplay."
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(startBtn)
        root.addView(hint)
        return root
    }

    private fun refreshButtonLabel() {
        startBtn.text = if (Settings.canDrawOverlays(this))
            "Mở bong bóng"
        else
            "1) Cấp quyền hiển thị nổi (overlay)"
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        // Permission already granted: show the bubble and get out of the way.
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_SHOW_BUBBLE
            }
        )
        Toast.makeText(this, "Bong bóng đã hiện", Toast.LENGTH_SHORT).show()
        finish()
    }
}
