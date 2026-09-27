package com.hoa.overlaytest

import java.io.InputStream
import kotlin.math.sqrt

/**
 * So khớp audio bằng "vân tay" đơn giản: dãy năng lượng RMS theo từng khung
 * ~20ms (envelope). Nhẹ hơn nhiều so với so khớp waveform thô, và đủ phân
 * biệt vì các câu thoại hệ thống là audio cố định (không phải giọng nói
 * ngẫu nhiên) — chỉ cần so hình dạng năng lượng theo thời gian là đủ.
 */
object AudioMatcher {

    private const val FRAME_MS = 20
    const val MATCH_THRESHOLD = 0.80 // hệ số tương quan tối thiểu để coi là khớp

    data class ReferenceClip(val name: String, val envelope: FloatArray, val sampleRate: Int)

    /** Đọc file WAV PCM 16-bit mono từ assets, trả về envelope RMS theo khung FRAME_MS */
    fun loadWavReference(name: String, input: InputStream): ReferenceClip? {
        val bytes = input.readBytes()
        val wav = parseWav(bytes) ?: return null
        val envelope = computeEnvelope(wav.pcm, wav.sampleRate)
        return ReferenceClip(name, envelope, wav.sampleRate)
    }

    /** Tính envelope RMS cho 1 đoạn PCM 16-bit mono (dùng cho audio live capture) */
    fun computeEnvelope(pcm: ShortArray, sampleRate: Int): FloatArray {
        val frameSize = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val frameCount = pcm.size / frameSize
        val envelope = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sumSquares = 0.0
            val start = i * frameSize
            for (j in start until start + frameSize) {
                val v = pcm[j] / 32768.0
                sumSquares += v * v
            }
            envelope[i] = sqrt(sumSquares / frameSize).toFloat()
        }
        return envelope
    }

    /**
     * Trượt cửa sổ dài bằng reference.envelope trên liveEnvelope (dài hơn),
     * trả về hệ số tương quan cao nhất tìm được.
     */
    fun bestCorrelation(liveEnvelope: FloatArray, reference: FloatArray): Double {
        if (liveEnvelope.size < reference.size || reference.isEmpty()) return 0.0
        var best = 0.0
        val refNorm = normalize(reference)
        val step = 1 // có thể tăng lên để nhẹ CPU hơn, đánh đổi độ chính xác thời điểm
        var i = 0
        while (i + reference.size <= liveEnvelope.size) {
            val window = liveEnvelope.copyOfRange(i, i + reference.size)
            val corr = correlate(normalize(window), refNorm)
            if (corr > best) best = corr
            i += step
        }
        return best
    }

    private fun normalize(arr: FloatArray): FloatArray {
        val mean = arr.average()
        val std = sqrt(arr.map { (it - mean) * (it - mean) }.average()).let { if (it < 1e-6) 1e-6 else it }
        return FloatArray(arr.size) { ((arr[it] - mean) / std).toFloat() }
    }

    private fun correlate(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum / a.size
    }

    private data class WavData(val pcm: ShortArray, val sampleRate: Int)

    /** Parser WAV tối giản: chỉ hỗ trợ PCM 16-bit mono/stereo, tự mix về mono nếu stereo */
    private fun parseWav(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null
        fun le16(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
        fun le32(off: Int) = (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

        var pos = 12 // sau "RIFF....WAVE"
        var sampleRate = 44100
        var numChannels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = le32(pos + 4)
            val chunkDataStart = pos + 8
            if (chunkId == "fmt ") {
                numChannels = le16(chunkDataStart + 2)
                sampleRate = le32(chunkDataStart + 4)
                bitsPerSample = le16(chunkDataStart + 14)
            } else if (chunkId == "data") {
                dataOffset = chunkDataStart
                dataSize = chunkSize
            }
            pos = chunkDataStart + chunkSize + (chunkSize % 2)
        }

        if (dataOffset < 0 || bitsPerSample != 16) return null

        val totalSamples = dataSize / 2
        val raw = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val off = dataOffset + i * 2
            if (off + 1 >= bytes.size) break
            raw[i] = ((bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)).toShort()
        }

        val mono = if (numChannels == 1) raw else {
            val frames = raw.size / numChannels
            ShortArray(frames) { i ->
                var sum = 0
                for (c in 0 until numChannels) sum += raw[i * numChannels + c]
                (sum / numChannels).toShort()
            }
        }

        return WavData(mono, sampleRate)
    }
}
