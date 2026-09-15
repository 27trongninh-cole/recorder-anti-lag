package com.rgc.replay

import android.content.Intent

/**
 * In-process handoff for the MediaProjection permission result.
 *
 * MediaProjectionManager.createScreenCaptureIntent() can only be launched
 * via startActivityForResult from an Activity, but the bubble lives in a
 * Service. ScreenCaptureService registers a callback here right before it
 * launches the transparent ProjectionRequestActivity; that activity delivers
 * the result through this bridge and finishes itself immediately.
 */
object ProjectionPermissionBridge {
    private var callback: ((resultCode: Int, data: Intent?) -> Unit)? = null

    fun await(onResult: (resultCode: Int, data: Intent?) -> Unit) {
        callback = onResult
    }

    fun deliver(resultCode: Int, data: Intent?) {
        val cb = callback
        callback = null
        cb?.invoke(resultCode, data)
    }
}
