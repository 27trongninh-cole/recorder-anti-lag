package com.rgc.replay

import android.app.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the whole capture pipeline:
 *
 *   MediaProjection -> VirtualDisplay -> MediaCodec input Surface (HW encoder)
 *      -> encoder output drained on a dedicated thread -> RollingBuffer (RAM)
 *
 * No frame ever touches a Bitmap and nothing is written to disk while playing.
 * A small floating button lets the user dump the last N seconds to an mp4
 * whenever they want (see HighlightMuxer).
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "replay_capture"
        private const val NOTIF_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // Tunables — adjust for device/game.
        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 2400
        private const val VIDEO_BITRATE = 10_000_000
        private const val VIDEO_FRAME_RATE = 60
        private const val VIDEO_I_FRAME_INTERVAL_SEC = 2
        private const val BUFFER_WINDOW_SECONDS = 90L

        @Volatile
        var isRunning = false
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private var outputFormat: MediaFormat? = null
    private val rollingBuffer = RollingBuffer(BUFFER_WINDOW_SECONDS * 1_000_000L)
    private val isSaving = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var overlayView: Button? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_CANCELED || resultData == null) {
            Log.e(TAG, "Missing screen capture permission result, stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        startPipeline()
        showOverlayButton()
        isRunning = true
        return START_STICKY
    }

    private fun startPipeline() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL_SEC)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        encoderThread = HandlerThread("EncoderCallbackThread").also { it.start() }
        encoderHandler = Handler(encoderThread!!.looper)

        encoder!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used: input comes from the Surface, not from us.
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        rollingBuffer.add(
                            EncodedFrame(
                                data = bytes,
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags,
                                isKeyFrame = rollingBuffer.isKeyFrame(info)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error draining encoder output", e)
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                outputFormat = format
            }
        }, encoderHandler)

        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "GameReplayCapture",
            VIDEO_WIDTH, VIDEO_HEIGHT, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, encoderHandler
        )

        Log.i(TAG, "Capture pipeline started: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FRAME_RATE}fps")
    }

    /** Small floating button so the user can save a highlight without leaving the game. */
    private fun showOverlayButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
        }

        overlayView = Button(this).apply {
            text = "SAVE"
            setBackgroundColor(Color.parseColor("#CC1976D2"))
            setTextColor(Color.WHITE)
            setOnClickListener { saveHighlight() }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun saveHighlight() {
        if (!isSaving.compareAndSet(false, true)) return

        val format = outputFormat
        if (format == null) {
            Toast.makeText(this, "Chưa sẵn sàng, thử lại sau vài giây", Toast.LENGTH_SHORT).show()
            isSaving.set(false)
            return
        }

        Thread {
            try {
                val snapshot = rollingBuffer.snapshot()
                val file = HighlightMuxer.save(snapshot, format)
                Handler(mainLooper).post {
                    val msg = if (file != null) "Đã lưu: ${file.name}" else "Chưa có gì để lưu"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save highlight", e)
                Handler(mainLooper).post {
                    Toast.makeText(this, "Lỗi khi lưu highlight", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSaving.set(false)
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Screen Replay", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Đang ghi replay")
            .setContentText("Chạm nút SAVE để lưu highlight vừa xảy ra")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}

        try {
            virtualDisplay?.release()
            encoder?.stop()
            encoder?.release()
            encoderThread?.quitSafely()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        rollingBuffer.clear()
        super.onDestroy()
    }
}
