package com.hoa.overlaytest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.AudioAttributes
import android.media.Image
import android.media.ImageReader
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
import android.graphics.Bitmap
import android.graphics.Rect

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
        const val ACTION_DETECTION_LOG = "com.hoa.overlaytest.action.DETECTION_LOG"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_EXPORT_SECONDS = "extra_export_seconds"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_ERROR = "extra_error"
        const val EXTRA_HERO_FILE = "extra_hero_file"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        const val ANALYSIS_LONG_SIDE_PX = 960 // độ phân giải nhẹ cho OCR/avatar, không cần cao như file ghi hình
        const val AUTO_EXPORT_SECONDS = 15
        const val AUTO_EXPORT_COOLDOWN_MS = 5000L
        const val OCR_THROTTLE_MS = 400L
        const val AUDIO_CHECK_INTERVAL_MS = 300L
        const val AUDIO_ROLLING_WINDOW_SEC = 3

        // Giữ buffer dài hơn N tối đa cho phép 1 chút để luôn có đủ dữ liệu cắt
        const val MAX_BUFFER_SECONDS = 40
        const val TARGET_LONG_SIDE_PX = 1920 // gần độ phân giải gốc máy hơn, vẫn nhẹ vì encode bằng phần cứng
        const val BIT_RATE = 14_000_000
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

    // --- Phân tích (OCR + avatar) ---
    private var analysisVirtualDisplay: VirtualDisplay? = null
    private var analysisImageReader: ImageReader? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    @Volatile private var lastOcrProcessTime = 0L
    private var heroReferences: List<AvatarMatcher.ReferenceAvatar> = emptyList()
    private var myHeroName: String? = null

    // --- Audio matching ---
    private var audioReferences: List<AudioMatcher.ReferenceClip> = emptyList()
    private var audioRecord: AudioRecord? = null
    private var audioThread: HandlerThread? = null
    @Volatile private var audioCapturing = false
    private val lastAudioTriggerTime = mutableMapOf<String, Long>()

    @Volatile private var lastAutoExportTime = 0L

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

            // Phần CỐT LÕI: phải thành công, nếu lỗi thì dừng hẳn service
            startEncoderAndVirtualDisplay()
            isRunning = true
            lastError = null
        } catch (e: Exception) {
            reportError("Lỗi khi bắt đầu ghi hình (phần cốt lõi)", e)
            isRunning = false
            stopSelf()
            return
        }

        // Phần PHỤ (OCR/avatar/audio): lỗi ở đây KHÔNG được làm dừng phần ghi
        // hình chính đã chạy thành công ở trên — chỉ log lỗi và bỏ qua bước đó.
        try {
            OcrAnalyzer.loadKeywords(assets)
            loadReferences(intent.getStringExtra(EXTRA_HERO_FILE))
        } catch (e: Exception) {
            reportError("Lỗi nạp dữ liệu tham chiếu (không ảnh hưởng ghi hình)", e)
        }

        try {
            startAnalysisPipeline()
        } catch (e: Exception) {
            reportError("Lỗi khởi động OCR/avatar pipeline (không ảnh hưởng ghi hình)", e)
        }

        try {
            startAudioCapture()
        } catch (e: Exception) {
            reportError("Lỗi khởi động audio capture (không ảnh hưởng ghi hình)", e)
        }
    }

    private fun computeTargetSize(): Pair<Int, Int> = computeSizeForLongSide(TARGET_LONG_SIDE_PX)

    private fun computeSizeForLongSide(targetLongSide: Int): Pair<Int, Int> {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        // Luôn ép khung theo tỷ lệ NGANG (game luôn chơi ở chế độ ngang),
        // bất kể lúc bấm nút app đang ở chế độ dọc hay ngang.
        val longSidePx = maxOf(metrics.widthPixels, metrics.heightPixels)
        val shortSidePx = minOf(metrics.widthPixels, metrics.heightPixels)

        val scale = if (longSidePx > targetLongSide) targetLongSide.toDouble() / longSidePx else 1.0

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
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
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

    // ---------- Nạp dữ liệu tham chiếu (avatar + audio mẫu) ----------

    private fun loadReferences(heroFileExtra: String?) {
        myHeroName = heroFileExtra
        val refs = mutableListOf<AvatarMatcher.ReferenceAvatar>()
        try {
            val files = assets.list("avatars")?.filter {
                it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true)
            } ?: emptyList()
            for (fileName in files) {
                assets.open("avatars/$fileName").use { input ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(input)
                    if (bmp != null) {
                        refs.add(AvatarMatcher.buildReference(fileName, bmp))
                    }
                }
            }
            sendLog("Đã nạp ${refs.size} avatar tham chiếu: ${refs.joinToString { it.name }}")
        } catch (e: Exception) {
            sendLog("Lỗi nạp avatar tham chiếu: ${e.message}")
        }
        heroReferences = refs

        val audioRefs = mutableListOf<AudioMatcher.ReferenceClip>()
        try {
            val files = assets.list("audio_samples")?.filter { it.endsWith(".wav", true) } ?: emptyList()
            for (fileName in files) {
                assets.open("audio_samples/$fileName").use { input ->
                    AudioMatcher.loadWavReference(fileName, input)?.let { audioRefs.add(it) }
                }
            }
            sendLog("Đã nạp ${audioRefs.size} audio mẫu: ${audioRefs.joinToString { it.name }}")
        } catch (e: Exception) {
            sendLog("Lỗi nạp audio mẫu: ${e.message}")
        }
        audioReferences = audioRefs
    }

    // ---------- Pipeline phân tích: OCR + avatar matching ----------

    private fun startAnalysisPipeline() {
        if (heroReferences.isEmpty()) {
            sendLog("Không có avatar tham chiếu nào — bỏ qua bước avatar matching")
        }
        val (w, h) = computeSizeForLongSide(ANALYSIS_LONG_SIDE_PX)
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics)

        analysisThread = HandlerThread("AnalysisThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        analysisHandler = Handler(analysisThread!!.looper)

        analysisImageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        analysisImageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastOcrProcessTime >= OCR_THROTTLE_MS) {
                    lastOcrProcessTime = now
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) processAnalysisFrame(bitmap)
                }
            } catch (e: Exception) {
                sendLog("Lỗi xử lý frame phân tích: ${e.message}")
            } finally {
                image.close()
            }
        }, analysisHandler)

        analysisVirtualDisplay = mediaProjection?.createVirtualDisplay(
            "OverlayTestAnalysis",
            w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            analysisImageReader?.surface,
            null, null
        )
        sendLog("Bắt đầu phân tích OCR ở độ phân giải ${w}x${h}")
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun processAnalysisFrame(bitmap: Bitmap) {
        val events = OcrAnalyzer.analyze(bitmap)
        for (event in events) {
            val region = clampRect(event.avatarLeftRegion, bitmap.width, bitmap.height)
            if (region.width() < 4 || region.height() < 4) continue
            val crop = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            val circular = AvatarMatcher.cropToCircle(crop)
            val match = AvatarMatcher.findBestMatch(circular, heroReferences)

            when {
                match != null && match.first == myHeroName -> {
                    sendLog("OCR '${event.keyword}' + avatar KHỚP tướng mình (${match.first}, dist=${match.second}) -> quay")
                    triggerAutoExport("OCR+avatar: ${event.keyword} (${match.first})")
                }
                match != null && match.first != myHeroName -> {
                    sendLog("OCR '${event.keyword}' + avatar khớp tướng KHÁC (${match.first}, dist=${match.second}) -> loại")
                }
                else -> {
                    sendLog("OCR '${event.keyword}' nhưng KHÔNG nhận diện được avatar -> mặc định quay")
                    triggerAutoExport("OCR (không rõ avatar): ${event.keyword}")
                }
            }
        }
    }

    private fun clampRect(rect: Rect, maxW: Int, maxH: Int): Rect {
        val left = rect.left.coerceIn(0, maxW - 1)
        val top = rect.top.coerceIn(0, maxH - 1)
        val right = rect.right.coerceIn(left + 1, maxW)
        val bottom = rect.bottom.coerceIn(top + 1, maxH)
        return Rect(left, top, right, bottom)
    }

    // ---------- Audio matching ----------

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendLog("Audio capture cần Android 10+ — bỏ qua trên máy này")
            return
        }
        if (audioReferences.isEmpty()) {
            sendLog("Không có audio mẫu nào — bỏ qua bước audio matching")
            return
        }
        val projection = mediaProjection ?: return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 44100
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord?.startRecording()
            audioCapturing = true

            audioThread = HandlerThread("AudioMatchThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            Handler(audioThread!!.looper).post { audioCaptureLoop(sampleRate) }

            sendLog("Bắt đầu audio capture (${audioReferences.size} mẫu)")
        } catch (e: Exception) {
            sendLog("Lỗi khởi động audio capture: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun audioCaptureLoop(sampleRate: Int) {
        val rollingWindowSamples = sampleRate * AUDIO_ROLLING_WINDOW_SEC
        val rolling = ShortArray(rollingWindowSamples)
        val readChunk = ShortArray(sampleRate / 5) // ~200ms mỗi lần đọc
        var lastCheck = 0L

        while (audioCapturing) {
            val record = audioRecord ?: break
            val n = try {
                record.read(readChunk, 0, readChunk.size)
            } catch (e: Exception) {
                sendLog("Lỗi đọc audio: ${e.message}")
                break
            }
            if (n > 0) {
                // dịch cửa sổ trượt: bỏ n mẫu cũ nhất, thêm n mẫu mới vào cuối
                System.arraycopy(rolling, n, rolling, 0, rolling.size - n)
                System.arraycopy(readChunk, 0, rolling, rolling.size - n, n)
            }

            val now = System.currentTimeMillis()
            if (now - lastCheck >= AUDIO_CHECK_INTERVAL_MS) {
                lastCheck = now
                val liveEnvelope = AudioMatcher.computeEnvelope(rolling, sampleRate)
                for (ref in audioReferences) {
                    val score = AudioMatcher.bestCorrelation(liveEnvelope, ref.envelope)
                    if (score >= AudioMatcher.MATCH_THRESHOLD) {
                        val lastTrigger = lastAudioTriggerTime[ref.name] ?: 0L
                        if (now - lastTrigger >= AUTO_EXPORT_COOLDOWN_MS) {
                            lastAudioTriggerTime[ref.name] = now
                            sendLog("Audio KHỚP '${ref.name}' (score=${"%.2f".format(score)}) -> quay")
                            triggerAutoExport("Audio: ${ref.name}")
                        }
                    }
                }
            }
        }
    }

    // ---------- Trigger tự động cắt clip ----------

    private fun triggerAutoExport(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAutoExportTime < AUTO_EXPORT_COOLDOWN_MS) return
        lastAutoExportTime = now
        sendLog("--> Tự động cắt clip: $reason")
        exportHandler?.post { exportLastNSeconds(AUTO_EXPORT_SECONDS) }
    }

    private fun sendLog(message: String) {
        android.util.Log.i("RecordingService", message)
        val intent = Intent(ACTION_DETECTION_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MESSAGE, message)
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

        analysisImageReader?.setOnImageAvailableListener(null, null)
        analysisVirtualDisplay?.release()
        analysisVirtualDisplay = null
        analysisImageReader?.close()
        analysisImageReader = null
        analysisThread?.quitSafely()
        analysisThread = null
        analysisHandler = null

        audioCapturing = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
        audioThread?.quitSafely()
        audioThread = null

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
