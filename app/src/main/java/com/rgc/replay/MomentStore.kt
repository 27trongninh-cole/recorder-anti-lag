package com.rgc.replay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records "moments" (bookmarks) during a session without touching the
 * Movies/ directory yet. Each mark just dumps a snapshot of the rolling
 * buffer to a small temp file in cacheDir — quick, infrequent, and cheap
 * compared to muxing a final mp4 on every tap.
 *
 * The actual mp4 files are only produced once, in a batch, when the user
 * ends the session (swipe right) — matching "chỉ lưu khi kết thúc phiên".
 */
class MomentStore(private val context: Context) {

    private val TAG = "MomentStore"
    private val marks = mutableListOf<File>()

    val count: Int get() = marks.size

    /** Serializes a frame snapshot to a temp file. Call off the main/encoder thread. */
    fun addMark(frames: List<EncodedFrame>): Boolean {
        if (frames.isEmpty()) return false
        return try {
            val file = File.createTempFile("moment_", ".bin", context.cacheDir)
            DataOutputStream(FileOutputStream(file)).use { out ->
                out.writeInt(frames.size)
                for (f in frames) {
                    out.writeLong(f.presentationTimeUs)
                    out.writeInt(f.flags)
                    out.writeInt(f.data.size)
                    out.write(f.data)
                }
            }
            marks.add(file)
            Log.i(TAG, "Marked moment #${marks.size} (${frames.size} frames, ${file.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store mark", e)
            false
        }
    }

    private fun readBack(file: File): List<EncodedFrame> {
        val result = mutableListOf<EncodedFrame>()
        DataInputStream(FileInputStream(file)).use { din ->
            val n = din.readInt()
            repeat(n) {
                val pts = din.readLong()
                val flags = din.readInt()
                val size = din.readInt()
                val data = ByteArray(size)
                din.readFully(data)
                val isKey = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                result.add(EncodedFrame(data, pts, flags, isKey))
            }
        }
        return result
    }

    /**
     * Exports every mark to its own standalone mp4 in Movies/GameReplay.
     * Returns the number of files successfully written. Call off the main thread.
     */
    fun exportAll(outputFormat: MediaFormat): Int {
        if (marks.isEmpty()) return 0

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "GameReplay"
        )
        if (!dir.exists()) dir.mkdirs()

        var success = 0
        marks.forEachIndexed { index, tempFile ->
            try {
                val frames = readBack(tempFile)
                val name = "highlight_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${index + 1}.mp4"
                val outFile = File(dir, name)

                val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val trackIndex = muxer.addTrack(outputFormat)
                muxer.start()

                val baseTimeUs = frames.first().presentationTimeUs
                val bufferInfo = MediaCodec.BufferInfo()
                for (frame in frames) {
                    val byteBuffer = ByteBuffer.wrap(frame.data)
                    bufferInfo.set(0, frame.data.size, frame.presentationTimeUs - baseTimeUs, frame.flags)
                    muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                }
                muxer.stop()
                muxer.release()
                success++
                Log.i(TAG, "Exported ${outFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export mark #$index", e)
            } finally {
                tempFile.delete()
            }
        }
        marks.clear()
        return success
    }

    /** Discard everything without exporting (e.g. app force-quit mid session). */
    fun clear() {
        marks.forEach { it.delete() }
        marks.clear()
    }
}
