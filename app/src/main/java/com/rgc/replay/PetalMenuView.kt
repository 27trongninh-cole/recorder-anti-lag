package com.rgc.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Radial "petal" menu shown around the bubble the moment a swipe is
 * detected (before the finger is lifted). It gives visual feedback for
 * which of the 4 gestures is currently selected, and lets the user change
 * their mind by moving to a different direction before releasing —
 * without that movement ever being mistaken for dragging the bubble
 * (drag-to-reposition is a completely separate, long-press-gated mode).
 */
class PetalMenuView(context: Context) : View(context) {

    enum class Direction { NONE, UP, DOWN, LEFT, RIGHT }

    var highlighted: Direction = Direction.NONE
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    companion object {
        const val SIZE_DP = 168
        private const val PETAL_RADIUS_DP = 26
        private const val PETAL_DISTANCE_DP = 62
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#992F2F2F")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1976D2")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val dist = dp(PETAL_DISTANCE_DP)
        val r = dp(PETAL_RADIUS_DP)

        canvas.drawCircle(cx, cy, dp(10), centerDotPaint)

        drawPetal(canvas, cx, cy - dist, r, Direction.UP)     // vuốt lên: chọn thời lượng
        drawPetal(canvas, cx, cy + dist, r, Direction.DOWN)   // vuốt xuống: dính cạnh
        drawPetal(canvas, cx - dist, cy, r, Direction.LEFT)   // vuốt trái: tạm dừng/tiếp tục
        drawPetal(canvas, cx + dist, cy, r, Direction.RIGHT)  // vuốt phải: dừng quay
    }

    private fun drawPetal(canvas: Canvas, x: Float, y: Float, r: Float, dir: Direction) {
        val isActive = highlighted == dir
        canvas.drawCircle(x, y, if (isActive) r * 1.15f else r, if (isActive) activePaint else dimPaint)
        drawIcon(canvas, x, y, dir)
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, dir: Direction) {
        when (dir) {
            Direction.LEFT -> { // pause bars
                val barW = dp(4); val barH = dp(14)
                canvas.drawRect(cx - dp(7), cy - barH / 2, cx - dp(7) + barW, cy + barH / 2, iconPaint)
                canvas.drawRect(cx + dp(1), cy - barH / 2, cx + dp(1) + barW, cy + barH / 2, iconPaint)
            }
            Direction.RIGHT -> { // stop square
                val s = dp(12)
                canvas.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, iconPaint)
            }
            Direction.UP -> { // clock (duration picker)
                val clockPaint = Paint(iconPaint).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
                canvas.drawCircle(cx, cy, dp(9), clockPaint)
                canvas.drawLine(cx, cy, cx, cy - dp(6), iconPaint)
                canvas.drawLine(cx, cy, cx + dp(4), cy, iconPaint)
            }
            Direction.DOWN -> { // dock-to-edge arrow (downward chevron)
                val path = Path()
                path.moveTo(cx - dp(8), cy - dp(5))
                path.lineTo(cx, cy + dp(6))
                path.lineTo(cx + dp(8), cy - dp(5))
                canvas.drawPath(path, Paint(iconPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dp(3).toFloat()
                    strokeCap = Paint.Cap.ROUND
                })
            }
            Direction.NONE -> {}
        }
    }
}
