package com.nolag.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quay màn hình bằng cách ghi thẳng vào Surface đầu vào của MediaCodec (hardware encoder).
 * Không copy bitmap qua CPU -> gần như không tốn thêm tải GPU/CPU của game.
 * Toàn bộ việc "rút" dữ liệu đã encode chạy trên 1 HandlerThread riêng, tách khỏi main thread.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.nolag.screenrecorder.START"
        const val ACTION_STOP = "com.nolag.screenrecorder.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 1001

        // Cấu hình quay - giữ đúng theo yêu cầu: 1080p @ 60fps
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val FRAME_RATE = 60
        private const val BITRATE = 12_000_000 // 12 Mbps, VBR mặc định của encoder
        private const val I_FRAME_INTERVAL = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private lateinit var encoderThread: HandlerThread
    private val stopRequested = AtomicBoolean(false)
    private val alreadyStopped = AtomicBoolean(false)
    private var stopLatch: CountDownLatch? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecordingInternal()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startForeground(NOTIF_ID, buildNotification())
                    startRecording(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopRecordingInternal()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ghi màn hình", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang quay màn hình")
            .setContentText("1080p @ 60fps - hardware encoder")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        muxerStarted = false
        trackIndex = -1
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        setupEncoder()
        setupMuxer()

        val dpi = resources.displayMetrics.densityDpi
        val inputSurface = encoder!!.createInputSurface()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NoLagScreenRecord",
            VIDEO_WIDTH, VIDEO_HEIGHT, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null, null
        )

        encoder?.start()
        startDrainThread()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        // Lưu ý: createInputSurface() phải gọi SAU configure(), TRƯỚC start().
        // Được gọi lại ở startRecording() qua encoder!!.createInputSurface()
    }

    private fun setupMuxer() {
        val fileName = "rec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: lưu qua MediaStore -> video hiện thẳng trong Gallery/Ảnh.
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/ScreenRecNoLag"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            outputUri = uri
            val pfd = contentResolver.openFileDescriptor(uri!!, "rw")
            outputPfd = pfd
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            // Android cũ hơn (<10): ghi trực tiếp vào thư mục Movies công khai.
            @Suppress("DEPRECATION")
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ScreenRecNoLag"
            ).apply { mkdirs() }
            val outFile = File(moviesDir, fileName)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun startDrainThread() {
        encoderThread = HandlerThread("EncoderDrainThread").apply { start() }
        stopRequested.set(false)
        alreadyStopped.set(false)
        val handler = android.os.Handler(encoderThread.looper)
        handler.post(object : Runnable {
            private val bufferInfo = MediaCodec.BufferInfo()
            override fun run() {
                val codec = encoder
                if (codec == null) {
                    finishTeardown()
                    return
                }
                try {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encodedData = codec.getOutputBuffer(outIndex)
                            if (encodedData != null && muxerStarted &&
                                bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }

                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isEos) {
                        // Đã nhận đủ dữ liệu cuối cùng -> dọn dẹp ngay tại đây, cùng 1 luồng.
                        finishTeardown()
                        return
                    }
                } catch (e: Exception) {
                    // Nếu codec bị lỗi/đã đóng, dừng vòng lặp an toàn thay vì crash app.
                    finishTeardown()
                    return
                }

                if (!stopRequested.get()) {
                    handler.post(this)
                } else {
                    // Đã yêu cầu dừng nhưng chưa thấy EOS (vd EOS bị timeout) -> thử vài lần rồi buộc dọn dẹp.
                    handler.post(this)
                }
            }
        })
    }

    /**
     * Dọn dẹp toàn bộ tài nguyên (encoder, muxer, virtual display, projection).
     * QUAN TRỌNG: hàm này chỉ được gọi từ encoderThread để tránh 2 luồng cùng
     * đụng vào MediaCodec/MediaMuxer một lúc (nguyên nhân gây crash khi bấm dừng).
     */
    private fun finishTeardown() {
        if (!alreadyStopped.compareAndSet(false, true)) return
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            if (muxerStarted) {
                muxer?.stop()
            }
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        try {
            outputPfd?.close()
        } catch (_: Exception) {
        }
        try {
            val uri = outputUri
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        encoder = null
        muxer = null
        virtualDisplay = null
        mediaProjection = null
        outputUri = null
        outputPfd = null
        stopLatch?.countDown()
        if (::encoderThread.isInitialized) {
            encoderThread.quitSafely()
        }
    }

    private fun stopRecordingInternal() {
        if (stopRequested.getAndSet(true)) return // đã yêu cầu dừng rồi, tránh gọi trùng
        val latch = CountDownLatch(1)
        stopLatch = latch
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
            // Nếu báo EOS thất bại (vd codec đã lỗi), buộc dọn dẹp ngay trên encoderThread.
            if (::encoderThread.isInitialized) {
                android.os.Handler(encoderThread.looper).post { finishTeardown() }
            } else {
                finishTeardown()
            }
        }
        // Đợi tối đa 3 giây để encoderThread tự dọn dẹp xong, tránh main thread đụng
        // vào MediaCodec/MediaMuxer cùng lúc với encoderThread (nguyên nhân gây crash).
        latch.await(3, TimeUnit.SECONDS)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRecordingInternal()
        super.onDestroy()
    }
}
