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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Foreground service owning: capture pipeline (MediaProjection -> VirtualDisplay
 * -> MediaCodec HW encoder -> RollingBuffer in RAM) + the single floating
 * gesture bubble that drives the whole session.
 *
 * Bubble gestures (finalized design):
 *  - Tap                 : IDLE    -> start the session (init pipeline now, at
 *                                      whatever screen orientation is live —
 *                                      this is what fixes the landscape/portrait bug)
 *                           RECORDING -> mark a moment (bookmark; no file written yet)
 *                           PAUSED  -> no-op (buffer frozen, nothing to mark)
 *                           DOCKED  -> just undock, first tap doesn't trigger the action above
 *  - Swipe left (fling)  : pause / resume the rolling buffer
 *  - Swipe right (fling) : stop the session -> batch-export all marked moments to mp4
 *  - Swipe up (fling)    : toggle the duration picker (15/30/60/90s)
 *  - Swipe down (fling)  : dock the bubble to the nearest screen edge
 *  - Slow drag           : freely reposition the bubble (e.g. to avoid covering skills)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_CHANNEL_ID = "replay_capture"
        private const val NOTIF_ID = 1001

        private const val VIDEO_BITRATE = 10_000_000
        private const val VIDEO_FRAME_RATE = 60
        private const val VIDEO_I_FRAME_INTERVAL_SEC = 2

        private val DURATION_OPTIONS_SEC = listOf(15L, 30L, 60L, 90L)
        private const val DEFAULT_DURATION_INDEX = 1 // 30s

        private const val TAP_SLOP_PX = 16f
        private const val LONG_PRESS_MS = 500L
        private const val SWIPE_MIN_DISTANCE_DP = 24

        @Volatile
        var isRunning = false
    }

    private enum class BubbleState { IDLE, RECORDING, PAUSED, DOCKED }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private var outputFormat: MediaFormat? = null
    private var rollingBuffer: RollingBuffer? = null
    private lateinit var momentStore: MomentStore
    private val isBusy = AtomicBoolean(false) // guards marking/exporting from overlapping

    private var videoWidth = 0
    private var videoHeight = 0
    private var durationIndex = DEFAULT_DURATION_INDEX

    private var bubbleState = BubbleState.IDLE
    private var stateBeforeDock = BubbleState.IDLE

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pickerView: LinearLayout? = null
    private var pickerParams: WindowManager.LayoutParams? = null
    private var petalView: PetalMenuView? = null
    private var petalParams: WindowManager.LayoutParams? = null

    // touch tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLayoutX = 0
    private var downLayoutY = 0
    private var isDragMode = false
    private var isSelectingGesture = false
    private val longPressHandler = Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        momentStore = MomentStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        // No MediaProjection is requested here anymore. The service only
        // needs the overlay permission to show the bubble; screen-capture
        // permission is requested lazily on the bubble's first tap (see
        // requestProjectionThenStart()) — this is what lets "grant overlay"
        // and "grant screen recording" be two fully separate steps.
        if (bubbleView == null) {
            showBubble()
            isRunning = true
        }

        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Screen size (read at the moment recording actually starts, not at
    // service-start time — this is what avoids the portrait/landscape bug)
    // ---------------------------------------------------------------------

    private fun captureCurrentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val rotation = display.rotation
        val isLandscapeRotation = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val currentlyLandscape = w > h
        if (isLandscapeRotation != currentlyLandscape) {
            val t = w; w = h; h = t
        }

        val evenW = w - (w % 2)
        val evenH = h - (h % 2)
        return evenW to evenH
    }

    // ---------------------------------------------------------------------
    // Pipeline
    // ---------------------------------------------------------------------

    private fun startPipeline() {
        val (w, h) = captureCurrentScreenSize()
        videoWidth = w
        videoHeight = h
        rollingBuffer = RollingBuffer(DURATION_OPTIONS_SEC[durationIndex] * 1_000_000L)

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight
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
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (bubbleState != BubbleState.PAUSED) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes, 0, info.size)
                            rollingBuffer?.add(
                                EncodedFrame(
                                    data = bytes,
                                    presentationTimeUs = info.presentationTimeUs,
                                    flags = info.flags,
                                    isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                )
                            )
                        }
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
            videoWidth, videoHeight, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, encoderHandler
        )

        Log.i(TAG, "Pipeline started: ${videoWidth}x${videoHeight}@${VIDEO_FRAME_RATE}fps, window=${DURATION_OPTIONS_SEC[durationIndex]}s")
    }

    private fun teardownPipeline() {
        try {
            virtualDisplay?.release()
            encoder?.stop()
            encoder?.release()
            encoderThread?.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down pipeline", e)
        }
        virtualDisplay = null
        encoder = null
        encoderThread = null
        encoderHandler = null
        outputFormat = null
        rollingBuffer = null
    }

    // ---------------------------------------------------------------------
    // Gesture actions
    // ---------------------------------------------------------------------

    private fun onTap() {
        when (bubbleState) {
            BubbleState.IDLE -> {
                if (mediaProjection == null) {
                    requestProjectionThenStart()
                } else {
                    startPipelineAndRecord()
                }
            }
            BubbleState.RECORDING -> markMoment()
            BubbleState.PAUSED -> toast("Đang tạm dừng — vuốt trái để tiếp tục")
            BubbleState.DOCKED -> undock()
        }
    }

    private fun startPipelineAndRecord() {
        try {
            startPipeline()
            setState(BubbleState.RECORDING)
            vibrate(40)
            toast("Đang quay ${videoWidth}x${videoHeight} — chạm để đánh dấu khoảnh khắc")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pipeline", e)
            toast("Lỗi khi bắt đầu quay: ${e.message}")
        }
    }

    /**
     * Fires only on the bubble's very first tap. Launches the transparent
     * ProjectionRequestActivity to show the system screen-recording consent
     * dialog, then resumes here with the result via ProjectionPermissionBridge.
     */
    private fun requestProjectionThenStart() {
        ProjectionPermissionBridge.await { resultCode, data ->
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection stopped by system/user")
                            stopSelf()
                        }
                    }, Handler(mainLooper))
                    startPipelineAndRecord()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare capture", e)
                    toast("Lỗi khởi động: ${e.message}")
                }
            } else {
                toast("Cần cho phép quay màn hình thì mới bắt đầu quay được")
            }
        }
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun markMoment() {
        if (!isBusy.compareAndSet(false, true)) return
        val buffer = rollingBuffer
        if (buffer == null) {
            isBusy.set(false)
            return
        }
        Thread {
            try {
                val snapshot = buffer.snapshot()
                val ok = momentStore.addMark(snapshot)
                Handler(mainLooper).post {
                    if (ok) {
                        vibrate(30); vibrate(30) // two short pulses = bookmark
                        toast("Đã đánh dấu khoảnh khắc #${momentStore.count}")
                    } else {
                        toast("Chưa có gì để đánh dấu")
                    }
                }
            } finally {
                isBusy.set(false)
            }
        }.start()
    }

    private fun togglePauseResume() {
        when (bubbleState) {
            BubbleState.RECORDING -> {
                setState(BubbleState.PAUSED)
                vibrate(40)
                toast("Đã tạm dừng buffer")
            }
            BubbleState.PAUSED -> {
                setState(BubbleState.RECORDING)
                vibrate(40)
                toast("Đã tiếp tục quay")
            }
            else -> { /* no-op when idle/docked */ }
        }
    }

    private fun stopSession() {
        if (bubbleState == BubbleState.IDLE) {
            // Nothing recorded yet — just close the bubble entirely.
            stopSelf()
            return
        }
        if (!isBusy.compareAndSet(false, true)) return

        val format = outputFormat
        vibrate(120)
        toast("Đang xuất ${momentStore.count} khoảnh khắc...")

        Thread {
            try {
                val exported = if (format != null) momentStore.exportAll(format) else 0
                Handler(mainLooper).post {
                    toast(
                        if (exported > 0) "Đã lưu $exported video vào Movies/GameReplay"
                        else "Không có khoảnh khắc nào được lưu"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                Handler(mainLooper).post { toast("Lỗi khi xuất video") }
            } finally {
                isBusy.set(false)
                teardownPipeline()
                Handler(mainLooper).post { stopSelf() }
            }
        }.start()
    }

    private fun dock() {
        if (bubbleState == BubbleState.DOCKED) return
        stateBeforeDock = bubbleState
        val params = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val goRight = params.x + (bubbleView?.width ?: 0) / 2 > metrics.widthPixels / 2
        params.x = if (goRight) metrics.widthPixels - dp(20) else -dp(36)
        wm.updateViewLayout(bubbleView, params)
        bubbleState = BubbleState.DOCKED
        bubbleView?.state = BubbleView.VisualState.DOCKED
        hidePicker()
    }

    private fun undock() {
        val params = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val goRight = params.x > metrics.widthPixels / 2
        params.x = if (goRight) metrics.widthPixels - dp(80) else dp(20)
        wm.updateViewLayout(bubbleView, params)
        setState(stateBeforeDock)
    }

    private fun setState(newState: BubbleState) {
        bubbleState = newState
        bubbleView?.state = when (newState) {
            BubbleState.IDLE -> BubbleView.VisualState.IDLE
            BubbleState.RECORDING -> BubbleView.VisualState.RECORDING
            BubbleState.PAUSED -> BubbleView.VisualState.PAUSED
            BubbleState.DOCKED -> BubbleView.VisualState.DOCKED
        }
    }

    // ---------------------------------------------------------------------
    // Duration picker (shown on swipe-up). Simplified for this first pass:
    // a row of 4 tappable labels rather than a full drag-to-select slider —
    // functionally equivalent (pick one of 4 fixed values), just less fancy.
    // ---------------------------------------------------------------------

    private fun togglePicker() {
        if (pickerView != null) {
            hidePicker()
        } else {
            showPicker()
        }
    }

    private fun showPicker() {
        val wm = windowManager ?: return
        val bp = bubbleParams ?: return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#DD202020"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        DURATION_OPTIONS_SEC.forEachIndexed { i, seconds ->
            val label = TextView(this).apply {
                text = "${seconds}s"
                setTextColor(if (i == durationIndex) Color.parseColor("#FF64B5F6") else Color.WHITE)
                textSize = 16f
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setOnClickListener {
                    durationIndex = i
                    rollingBuffer?.setWindowUs(seconds * 1_000_000L)
                    toast("Đã chọn lưu $seconds giây gần nhất")
                    hidePicker()
                }
            }
            row.addView(label)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bp.x
            y = (bp.y - dp(56)).coerceAtLeast(0)
        }

        wm.addView(row, params)
        pickerView = row
        pickerParams = params
    }

    private fun hidePicker() {
        pickerView?.let { windowManager?.removeView(it) }
        pickerView = null
        pickerParams = null
    }

    // ---------------------------------------------------------------------
    // Petal menu: shown the instant a swipe is detected, gives visual
    // feedback for the currently-selected direction, lets the user change
    // direction before releasing.
    // ---------------------------------------------------------------------

    private fun showPetalMenu() {
        val wm = windowManager ?: return
        val bp = bubbleParams ?: return
        if (petalView != null) return

        val view = PetalMenuView(this)
        val menuSize = dp(PetalMenuView.SIZE_DP)
        val bubbleSize = dp(BubbleView.SIZE_DP)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Center the (larger) petal menu on the bubble's current center.
            x = bp.x - (menuSize - bubbleSize) / 2
            y = bp.y - (menuSize - bubbleSize) / 2
        }

        wm.addView(view, params)
        petalView = view
        petalParams = params
    }

    private fun updatePetalDirection(dx: Float, dy: Float) {
        val swipeThresholdPx = dp(SWIPE_MIN_DISTANCE_DP)
        val dir = if (maxOf(abs(dx), abs(dy)) < swipeThresholdPx) {
            PetalMenuView.Direction.NONE
        } else if (abs(dx) > abs(dy)) {
            if (dx < 0) PetalMenuView.Direction.LEFT else PetalMenuView.Direction.RIGHT
        } else {
            if (dy < 0) PetalMenuView.Direction.UP else PetalMenuView.Direction.DOWN
        }
        petalView?.highlighted = dir
    }

    private fun currentPetalDirection(): PetalMenuView.Direction =
        petalView?.highlighted ?: PetalMenuView.Direction.NONE

    private fun hidePetalMenu() {
        petalView?.let { windowManager?.removeView(it) }
        petalView = null
        petalParams = null
    }

    // ---------------------------------------------------------------------
    // Bubble view + gesture detection
    // ---------------------------------------------------------------------

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dp(80)
            y = dp(200)
        }

        val view = BubbleView(this)
        view.setOnTouchListener { _, event -> handleTouch(event) }

        windowManager?.addView(view, params)
        bubbleView = view
        bubbleParams = params

        toast("Vào game rồi chạm bong bóng để xin quyền quay & bắt đầu")
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        val wm = windowManager ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downLayoutX = params.x
                downLayoutY = params.y
                isDragMode = false
                isSelectingGesture = false

                // Only a sustained press (no swipe yet) arms drag mode.
                longPressRunnable = Runnable {
                    isDragMode = true
                    vibrate(15) // subtle tick: "picked up", now draggable
                }
                longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (isDragMode) {
                    if (bubbleState != BubbleState.DOCKED) {
                        params.x = downLayoutX + dx.toInt()
                        params.y = downLayoutY + dy.toInt()
                        wm.updateViewLayout(bubbleView, params)
                    }
                    return true
                }

                if (!isSelectingGesture) {
                    // Any real movement before the long-press timer fires means
                    // this is a swipe attempt, not a drag — cancel arming drag
                    // mode and enter gesture-selection mode instead.
                    if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) {
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        isSelectingGesture = true
                        showPetalMenu()
                        vibrate(10)
                    } else {
                        return true
                    }
                }

                // In gesture-selection mode: just update which petal is
                // highlighted based on the current finger position — the
                // bubble itself does NOT move, so this can never be mistaken
                // for dragging. The user can freely change direction here.
                updatePetalDirection(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                if (isDragMode) {
                    isDragMode = false
                    return true
                }

                if (isSelectingGesture) {
                    val dir = currentPetalDirection()
                    hidePetalMenu()
                    isSelectingGesture = false
                    when (dir) {
                        PetalMenuView.Direction.LEFT -> togglePauseResume()
                        PetalMenuView.Direction.RIGHT -> stopSession()
                        PetalMenuView.Direction.UP -> togglePicker()
                        PetalMenuView.Direction.DOWN -> dock()
                        PetalMenuView.Direction.NONE -> onTap() // released back in the dead zone
                    }
                    return true
                }

                // No drag armed, no gesture-selection entered: a plain tap.
                onTap()
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun vibrate(ms: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {}
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
            .setContentTitle("Game Replay Recorder")
            .setContentText("Chạm / vuốt bong bóng nổi để điều khiển")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        hidePicker()
        hidePetalMenu()
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        teardownPipeline()
        momentStore.clear()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
