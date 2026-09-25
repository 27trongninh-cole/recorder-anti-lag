package com.hoa.overlaytest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service ghi hình dạng "circular buffer": mã hoá H.264 liên tục bằng
 * MediaCodec ở chế độ Surface-input (GPU đổ thẳng vào encoder phần cứng,
 * không copy raw frame qua CPU) — đây là điểm quan trọng để không làm
 * giật/lag game khi ghi hình chạy nền.
 *
 * Các gói tin đã mã hoá (chỉ vài trăm KB/giây, không phải raw video) được
 * giữ trong 1 hàng đợi (deque) khoảng MAX_BUFFER_SECONDS giây gần nhất.
 * Khi có "khoảnh khắc xuất thần" (ở bản test này là giả lập bằng nút bấm),
 * chỉ cần cắt & mux lại đúng N giây gần nhất từ buffer đó thành file MP4 —
 * không cần re-encode gì cả nên xử lý gần như tức thời.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.hoa.overlaytest.action.START"
        const val ACTION_EXPORT = "com.hoa.overlaytest.action.EXPORT"
        const val ACTION_STOP = "com.hoa.overlaytest.action.STOP"
        const val ACTION_EXPORT_RESULT = "com.hoa.overlaytest.action.EXPORT_RESULT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_EXPORT_SECONDS = "extra_export_seconds"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_ERROR = "extra_error"

        // Giữ buffer dài hơn N tối đa cho phép 1 chút để luôn có đủ dữ liệu cắt
        const val MAX_BUFFER_SECONDS = 40
        const val TARGET_LONG_SIDE_PX = 1280 // giảm độ phân giải để nhẹ tải encoder/GPU
        const val BIT_RATE = 8_000_000
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL_SEC = 1 // keyframe mỗi giây -> cắt clip chính xác hơn

        var isRunning = false
            private set
        var lastExportPath: String? = null
            private set
        var lastError: String? = null
            private set
    }

    private data class EncodedSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private val bufferLock = Any()
    private val frameBuffer = ArrayDeque<EncodedSample>()
    private var outputFormat: MediaFormat? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    @Volatile private var draining = false

    // Thread riêng để mux/export, tránh chặn luồng drain đang bận nhận frame mới
    private var exportThread: HandlerThread? = null
    private var exportHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundWithNotification()
        } catch (e: Exception) {
            reportError("startForeground crash", e)
            stopSelf()
        }
        exportThread = HandlerThread("RecordingExportThread").apply { start() }
        exportHandler = Handler(exportThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_EXPORT -> {
                val seconds = intent.getIntExtra(EXTRA_EXPORT_SECONDS, 15)
                exportHandler?.post { exportLastNSeconds(seconds) }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- Bắt đầu ghi ----------

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            reportError("Thiếu resultData khi start", null)
            stopSelf()
            return
        }

        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopRecordingInternal()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            startEncoderAndVirtualDisplay()
            isRunning = true
            lastError = null
        } catch (e: Exception) {
            reportError("Lỗi khi bắt đầu ghi hình", e)
            isRunning = false
            stopSelf()
        }
    }

    private fun computeTargetSize(): Pair<Int, Int> {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        // Luôn ép khung ghi hình theo tỷ lệ NGANG (game luôn chơi ở chế độ ngang),
        // bất kể lúc bấm nút app đang ở chế độ dọc hay ngang. Nếu không ép thế này,
        // VirtualDisplay sẽ giữ nguyên tỷ lệ lúc tạo (có thể là dọc), khiến nội dung
        // game ngang bị bóp méo/dùng không hết độ phân giải khi hiển thị vào khung đó.
        val longSidePx = maxOf(metrics.widthPixels, metrics.heightPixels)
        val shortSidePx = minOf(metrics.widthPixels, metrics.heightPixels)

        val scale = if (longSidePx > TARGET_LONG_SIDE_PX) TARGET_LONG_SIDE_PX.toDouble() / longSidePx else 1.0

        // MediaCodec yêu cầu kích thước chẵn
        var w = (longSidePx * scale).toInt()
        var h = (shortSidePx * scale).toInt()
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        return Pair(w, h)
    }

    private fun startEncoderAndVirtualDisplay() {
        val (width, height) = computeTargetSize()
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OverlayTestRecording",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null, null
        )

        synchronized(bufferLock) {
            frameBuffer.clear()
            outputFormat = null
        }

        draining = true
        drainThread = HandlerThread("RecordingDrainThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        drainHandler = Handler(drainThread!!.looper)
        drainHandler?.post { drainLoop() }
    }

    // ---------- Vòng lặp lấy dữ liệu đã mã hoá ----------

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (draining) {
            val codec = encoder ?: break
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                reportError("Lỗi dequeueOutputBuffer", e)
                break
            }

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(bufferLock) { outputFormat = codec.outputFormat }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // chưa có frame mới, chờ tiếp — không tốn CPU đáng kể vì timeout 10ms
                }
                outIndex >= 0 -> {
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outBuffer.get(data)
                            addSampleToBuffer(EncodedSample(data, bufferInfo.presentationTimeUs, bufferInfo.flags))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun addSampleToBuffer(sample: EncodedSample) {
        synchronized(bufferLock) {
            frameBuffer.addLast(sample)
            val newestPts = frameBuffer.last().presentationTimeUs
            val cutoff = newestPts - MAX_BUFFER_SECONDS * 1_000_000L
            while (frameBuffer.isNotEmpty() && frameBuffer.first().presentationTimeUs < cutoff) {
                frameBuffer.removeFirst()
            }
        }
    }

    // ---------- Xuất N giây gần nhất ----------

    private fun exportLastNSeconds(requestedSeconds: Int) {
        val seconds = requestedSeconds.coerceIn(1, MAX_BUFFER_SECONDS - 2)
        val format: MediaFormat?
        val samplesToExport: List<EncodedSample>

        synchronized(bufferLock) {
            format = outputFormat
            if (format == null || frameBuffer.isEmpty()) {
                reportError("Chưa có dữ liệu để xuất (buffer rỗng hoặc chưa có format)", null)
                sendExportResultBroadcast(false, null, lastError)
                return
            }
            val newestPts = frameBuffer.last().presentationTimeUs
            val cutoff = newestPts - seconds * 1_000_000L

            // Tìm keyframe gần nhất trước hoặc bằng thời điểm cutoff, để clip
            // xuất ra luôn giải mã được đúng ngay từ frame đầu tiên.
            var startIdx = 0
            for (i in frameBuffer.indices) {
                val s = frameBuffer.elementAt(i)
                val isKeyFrame = (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isKeyFrame && s.presentationTimeUs <= cutoff) {
                    startIdx = i
                }
            }
            samplesToExport = frameBuffer.toList().subList(startIdx, frameBuffer.size)
        }

        try {
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: filesDir
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val fileName = "clip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val outFile = File(moviesDir, fileName)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(format!!)
            muxer.start()

            val ptsOffset = samplesToExport.first().presentationTimeUs
            val bufferInfo = MediaCodec.BufferInfo()
            for (sample in samplesToExport) {
                val byteBuffer = ByteBuffer.wrap(sample.data)
                bufferInfo.set(
                    0,
                    sample.data.size,
                    sample.presentationTimeUs - ptsOffset,
                    sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                )
                muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
            }
            muxer.stop()
            muxer.release()

            lastExportPath = outFile.absolutePath
            lastError = null
            android.util.Log.i("RecordingService", "Xuất clip thành công: ${outFile.absolutePath}")
            sendExportResultBroadcast(true, outFile.absolutePath, null)
        } catch (e: Exception) {
            reportError("Lỗi khi mux/xuất file", e)
            sendExportResultBroadcast(false, null, lastError)
        }
    }

    private fun sendExportResultBroadcast(success: Boolean, path: String?, error: String?) {
        val intent = Intent(ACTION_EXPORT_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(intent)
    }

    // ---------- Dừng & dọn dẹp ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
        exportThread?.quitSafely()
        exportThread = null
        exportHandler = null
    }

    private fun stopRecordingInternal() {
        draining = false
        drainThread?.quitSafely()
        drainThread = null
        drainHandler = null

        virtualDisplay?.release()
        virtualDisplay = null

        try {
            encoder?.stop()
        } catch (_: Exception) { }
        encoder?.release()
        encoder = null

        mediaProjection?.stop()
        mediaProjection = null

        synchronized(bufferLock) {
            frameBuffer.clear()
            outputFormat = null
        }

        isRunning = false
    }

    private fun reportError(context: String, e: Exception?) {
        val msg = if (e != null) "$context: ${e.javaClass.simpleName}: ${e.message}" else context
        android.util.Log.e("RecordingService", msg, e)
        lastError = msg
    }

    private fun startForegroundWithNotification() {
        val channelId = "recording_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Đang ghi hình (buffer)")
            .setContentText("Giữ N giây gần nhất trong bộ nhớ")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(3, notification)
    }
}
