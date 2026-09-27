package com.hoa.overlaytest

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Bắt chữ HUD kill bằng ML Kit, và tính vùng crop avatar THEO TỶ LỆ TƯƠNG ĐỐI
 * với bounding box của chữ vừa tìm được — không dùng toạ độ tuyệt đối, vì
 * HUD co giãn theo độ dài chữ (xem DESIGN.md, nguyên tắc cốt lõi #1).
 */
object OcrAnalyzer {

    // Danh sách chữ khoá đọc từ assets/keywords.txt — sửa file đó để thêm/bớt,
    // không cần sửa code. Gọi loadKeywords() 1 lần lúc bắt đầu ghi hình.
    @Volatile
    private var keywords: List<String> = listOf("kill", "hạ") // fallback nếu chưa load được file

    fun loadKeywords(assets: AssetManager) {
        try {
            val lines = assets.open("keywords.txt").bufferedReader().readLines()
            val parsed = lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase() }
            if (parsed.isNotEmpty()) keywords = parsed
        } catch (e: Exception) {
            // giữ nguyên fallback nếu đọc file lỗi
        }
    }

    // Hệ số ước lượng vị trí avatar dựa theo chiều cao dòng chữ (đo thủ công
    // từ ảnh mẫu, có thể tinh chỉnh thêm khi test thực tế nhiều skin hơn)
    private const val AVATAR_SIZE_RATIO = 1.6 // avatar cao gấp ~1.6 lần chữ
    private const val AVATAR_GAP_RATIO = 0.3 // khoảng cách từ mép trái chữ tới avatar, theo tỷ lệ chiều cao chữ

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class DetectedEvent(val keyword: String, val textBoundingBox: Rect, val avatarLeftRegion: Rect)

    /** Chạy đồng bộ (gọi từ thread nền riêng, KHÔNG gọi từ main thread) */
    fun analyze(bitmap: Bitmap): List<DetectedEvent> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = try {
            Tasks.await(recognizer.process(image))
        } catch (e: Exception) {
            return emptyList()
        }

        val events = mutableListOf<DetectedEvent>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim().lowercase()
                val matchedKeyword = keywords.firstOrNull { text.contains(it) } ?: continue
                val box = line.boundingBox ?: continue
                events.add(DetectedEvent(matchedKeyword, box, computeAvatarRegion(box, bitmap.width, bitmap.height)))
            }
        }
        return events
    }

    /**
     * Ước lượng vùng avatar bên TRÁI của chữ, dựa theo bounding box chữ.
     * Anchor theo chiều cao dòng chữ (proxy cho "độ phóng đại" HUD hiện tại),
     * không phụ thuộc độ dài nội dung chữ.
     */
    private fun computeAvatarRegion(textBox: Rect, frameWidth: Int, frameHeight: Int): Rect {
        val lineHeight = textBox.height().coerceAtLeast(1)
        val avatarSize = (lineHeight * AVATAR_SIZE_RATIO).toInt()
        val gap = (lineHeight * AVATAR_GAP_RATIO).toInt()

        val right = (textBox.left - gap).coerceAtLeast(0)
        val left = (right - avatarSize).coerceAtLeast(0)
        val centerY = textBox.centerY()
        val top = (centerY - avatarSize / 2).coerceAtLeast(0)
        val bottom = (top + avatarSize).coerceAtMost(frameHeight)

        return Rect(left, top, right.coerceAtMost(frameWidth), bottom)
    }
}
