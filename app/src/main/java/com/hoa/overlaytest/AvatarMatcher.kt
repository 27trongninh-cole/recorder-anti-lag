package com.hoa.overlaytest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.min

/**
 * So khớp avatar tướng bằng dHash (difference hash) — nhẹ, đủ chính xác cho
 * icon nhỏ, chịu được sai lệch nhỏ về kích thước/nén ảnh khác nhau.
 *
 * Quy trình chuẩn hoá trước khi so sánh (xem DESIGN.md):
 * 1. Cắt tròn nội tiếp (loại bỏ góc vuông không liên quan)
 * 2. Resize về kích thước chuẩn cố định
 * 3. Tính dHash (64-bit) trên ảnh grayscale
 * 4. So khoảng cách Hamming giữa 2 hash — dưới ngưỡng thì coi là khớp
 */
object AvatarMatcher {

    private const val HASH_SIZE = 8 // dHash 9x8 -> 64 bit
    const val MATCH_THRESHOLD = 10 // khoảng cách Hamming tối đa để coi là khớp (trên tổng 64 bit)

    data class ReferenceAvatar(val name: String, val hash: Long)

    fun buildReference(name: String, bitmap: Bitmap): ReferenceAvatar {
        val circular = cropToCircle(bitmap)
        return ReferenceAvatar(name, computeDHash(circular))
    }

    /** So sánh 1 vùng crop tròn từ HUD với danh sách tham chiếu, trả về tên khớp nhất hoặc null */
    fun findBestMatch(
        candidateCircular: Bitmap,
        references: List<ReferenceAvatar>
    ): Pair<String, Int>? {
        val candidateHash = computeDHash(candidateCircular)
        var best: Pair<String, Int>? = null
        for (ref in references) {
            val dist = hammingDistance(candidateHash, ref.hash)
            if (best == null || dist < best.second) {
                best = Pair(ref.name, dist)
            }
        }
        return best?.takeIf { it.second <= MATCH_THRESHOLD }
    }

    /** Cắt ảnh vuông/chữ nhật thành hình tròn nội tiếp, nền trong suốt ngoài vòng tròn */
    fun cropToCircle(source: Bitmap): Bitmap {
        val size = min(source.width, source.height)
        val squared = Bitmap.createBitmap(source, 0, 0, size, size)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)

        canvas.drawARGB(0, 0, 0, 0)
        paint.color = Color.BLACK
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squared, rect, rect, paint)

        return output
    }

    private fun computeDHash(bitmap: Bitmap): Long {
        // Resize về (HASH_SIZE+1) x HASH_SIZE rồi so sánh độ sáng giữa các pixel liền kề theo hàng
        val resized = Bitmap.createScaledBitmap(bitmap, HASH_SIZE + 1, HASH_SIZE, true)
        var hash = 0L
        var bitIndex = 0
        for (y in 0 until HASH_SIZE) {
            for (x in 0 until HASH_SIZE) {
                val leftPixel = resized.getPixel(x, y)
                val rightPixel = resized.getPixel(x + 1, y)
                if (luminance(leftPixel) > luminance(rightPixel)) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }
}
