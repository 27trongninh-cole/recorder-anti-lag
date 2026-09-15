package com.rgc.replay

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent trampoline: shows only the system "start recording / casting"
 * dialog, forwards the result to ScreenCaptureService via
 * ProjectionPermissionBridge, then finishes. Never actually visible as a
 * screen of its own — see the NoDisplay theme in AndroidManifest.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ProjectionPermissionBridge.deliver(result.resultCode, result.data)
            finish()
            overridePendingTransition(0, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
