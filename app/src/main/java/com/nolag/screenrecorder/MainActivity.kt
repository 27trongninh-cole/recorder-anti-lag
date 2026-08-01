package com.nolag.screenrecorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                }
                ActivityCompat.startForegroundService(this, serviceIntent)
                tvStatus.text = "Đang quay 1080p @ 60fps..."
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            } else {
                tvStatus.text = "Bạn đã từ chối quyền quay màn hình"
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        btnStart.setOnClickListener {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(stopIntent)
            tvStatus.text = "Đã dừng. Video nằm trong Movies/ScreenRecNoLag (xem trong Gallery)"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }
}
