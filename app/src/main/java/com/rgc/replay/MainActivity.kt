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

class MainActivity : AppCompatActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val overlayBtn = Button(this).apply {
            text = "1) Cấp quyền hiển thị nổi (overlay)"
            setOnClickListener { requestOverlayPermissionIfNeeded() }
        }

        val bubbleBtn = Button(this).apply {
            text = "2) Hiện bong bóng nổi"
            setOnClickListener { showBubbleOnly() }
        }

        val stopBtn = Button(this).apply {
            text = "Đóng app quay (chỉ khi không đang quay)"
            setOnClickListener { stopCapture() }
        }

        val hint = TextView(this).apply {
            text = "Bước 2 chỉ hiện bong bóng, CHƯA xin quyền quay màn hình.\n" +
                "Quyền quay màn hình chỉ được hỏi khi bạn CHẠM bong bóng lần đầu " +
                "(nên vào game trước rồi mới chạm, để pipeline đọc đúng kích thước " +
                "màn hình lúc đó).\n\n" +
                "Sau khi bong bóng hiện lên:\n" +
                "• Chạm lần đầu: xin quyền quay màn hình → bắt đầu quay\n" +
                "• Chạm khi đang quay: đánh dấu khoảnh khắc\n" +
                "• Vuốt trái: tạm dừng / tiếp tục\n" +
                "• Vuốt phải: dừng quay, xuất tất cả khoảnh khắc đã đánh dấu\n" +
                "• Vuốt lên: chọn thời lượng lưu (15/30/60/90s)\n" +
                "• Vuốt xuống: dính bong bóng vào cạnh màn hình\n" +
                "• Kéo chậm: di chuyển bong bóng tránh đè nút/tướng\n\n" +
                "(Lưu ý: phải NHẤN GIỮ ~0.5s rồi mới kéo thì mới di chuyển được bong bóng; " +
                "chạm rồi vuốt ngay sẽ toả ra menu 4 hướng, di chuyển tay để chọn rồi nhấc tay để chốt.)\n\n" +
                "Video chỉ được lưu khi bạn vuốt phải để dừng quay — vào Movies/GameReplay."
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(overlayBtn)
        root.addView(bubbleBtn)
        root.addView(stopBtn)
        root.addView(hint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        return root
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            // Targeting this app's own package URI is the most direct API
            // Android offers — it opens this app's toggle screen, not a
            // browsable list. Some OEM ROMs (MIUI/HyperOS in particular)
            // override this system screen with their own permission list;
            // that substitution happens outside the app and can't be
            // bypassed from here.
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Đã có quyền overlay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBubbleOnly() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cấp quyền overlay trước (bước 1)", Toast.LENGTH_SHORT).show()
            return
        }
        if (ScreenCaptureService.isRunning) {
            Toast.makeText(this, "Bong bóng đã hiện rồi", Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(
            this,
            "Bong bóng đã hiện — vào game rồi chạm để xin quyền quay & bắt đầu",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "Đã dừng quay", Toast.LENGTH_SHORT).show()
    }
}
