package com.rgc.replay

import android.media.MediaCodec
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Holds a copy of one encoded frame produced by MediaCodec.
 * We copy the bytes out of the codec's buffer because that buffer
 * gets reused/released right after this callback returns.
 */
data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
    val isKeyFrame: Boolean
)

/**
 * Thread-safe rolling window of encoded video frames.
 *
 * - add() is called continuously from the encoder callback thread.
 * - Frames older than [windowUs] (measured from the newest frame) are dropped.
 * - snapshot() is called from the UI/button thread when the user wants to save
 *   a highlight; it returns an ordered, immutable copy of what's currently buffered,
 *   trimmed so it starts on a keyframe (required for a valid, standalone mp4).
 *
 * This is intentionally an in-memory buffer (no disk I/O while "recording"),
 * which is what avoids the continuous-write overhead that causes long-session lag.
 */
class RollingBuffer(@Volatile private var windowUs: Long) {

    private val frames = ConcurrentLinkedDeque<EncodedFrame>()

    /** Lets the user change the "last N seconds" window at runtime (15/30/60/90s picker). */
    fun setWindowUs(newWindowUs: Long) {
        windowUs = newWindowUs
        trim()
    }

    fun add(frame: EncodedFrame) {
        frames.addLast(frame)
        trim()
    }

    private fun trim() {
        val newestTime = frames.peekLast()?.presentationTimeUs ?: return
        while (true) {
            val oldest = frames.peekFirst() ?: break
            if (newestTime - oldest.presentationTimeUs > windowUs) {
                frames.pollFirst()
            } else {
                break
            }
        }
    }

    fun clear() = frames.clear()

    /** Returns a copy of the buffer, trimmed to start at the first keyframe. */
    fun snapshot(): List<EncodedFrame> {
        val copy = frames.toList()
        val firstKeyIndex = copy.indexOfFirst { it.isKeyFrame }
        return if (firstKeyIndex <= 0) copy else copy.subList(firstKeyIndex, copy.size)
    }

    fun isKeyFrame(bufferInfo: MediaCodec.BufferInfo): Boolean =
        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
}
