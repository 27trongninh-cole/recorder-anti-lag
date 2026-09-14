package com.rgc.replay

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes a snapshot of buffered encoded frames and writes them out as a
 * single standalone .mp4 file. This disk write only happens once, on demand,
 * when the user taps "save highlight" — not continuously during play.
 */
object HighlightMuxer {

    private const val TAG = "HighlightMuxer"

    fun save(frames: List<EncodedFrame>, outputFormat: MediaFormat): File? {
        if (frames.isEmpty()) {
            Log.w(TAG, "No frames buffered yet, nothing to save.")
            return null
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "GameReplay"
        )
        if (!dir.exists()) dir.mkdirs()

        val name = "highlight_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val outFile = File(dir, name)

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIndex = muxer.addTrack(outputFormat)
        muxer.start()

        // Re-base timestamps so the clip starts at 0.
        val baseTimeUs = frames.first().presentationTimeUs
        val bufferInfo = MediaCodec.BufferInfo()

        for (frame in frames) {
            val byteBuffer = ByteBuffer.wrap(frame.data)
            bufferInfo.set(
                0,
                frame.data.size,
                frame.presentationTimeUs - baseTimeUs,
                frame.flags
            )
            muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
        }

        muxer.stop()
        muxer.release()

        Log.i(TAG, "Saved highlight: ${outFile.absolutePath} (${frames.size} frames)")
        return outFile
    }
}
