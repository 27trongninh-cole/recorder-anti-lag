package com.rgc.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radial banner shown while the user is dragging out of the bubble to pick
 * a gesture. Design v2 (replaces the old petals-around-the-bubble look):
 * this ring sits at a FIXED spot at the top-middle of the screen — like a
 * MOBA system banner — independent of where the bubble itself is or how
 * far the finger has moved. Only the highlighted slice changes as the
 * finger direction changes; the bubble underneath stays a plain circle.
 *
 * Slice cut lines sit on the diagonals (45/135/225/315deg) so each slice's
 * own center lands exactly on an axis (right/down/left/up) — this gives a
 * "+" arrangement of highlight positions, not an "x".
 */
class RadialBannerView(context: Context) : View(context) {

    enum class Direction { NONE, UP, DOWN, LEFT, RIGHT }

    var highlighted: Direction = Direction.NONE
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    companion object {
        const val WIDTH_DP = 200
        const val HEIGHT_DP = 130
        private const val RADIUS_DP = 100f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun dp(v: Int): Float = dp(v.toFloat())

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(WIDTH_DP).toInt(), dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = 0f // ring hangs from the top edge of the view/screen
        val r = dp(RADIUS_DP)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        // startAngle -45 => slice spans -45..45, centered on 0 (right).
        drawSlice(canvas, oval, cx, cy, r, -45f, Direction.RIGHT)
        drawSlice(canvas, oval, cx, cy, r, 45f, Direction.DOWN)
        drawSlice(canvas, oval, cx, cy, r, 135f, Direction.LEFT)
        drawSlice(canvas, oval, cx, cy, r, 225f, Direction.UP)
    }

    private fun drawSlice(
        canvas: Canvas, oval: RectF, cx: Float, cy: Float, r: Float,
        startAngle: Float, dir: Direction
    ) {
        val isActive = highlighted == dir
        val centerColor = if (isActive) Color.parseColor("#EE1976D2") else Color.parseColor("#992F2F2F")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r, centerColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        val path = Path().apply {
            moveTo(cx, cy)
            arcTo(oval, startAngle, 90f)
            close()
        }
        canvas.drawPath(path, paint)

        val midAngleRad = Math.toRadians((startAngle + 45f).toDouble())
        val iconR = r * 0.55f
        val ix = cx + (iconR * cos(midAngleRad)).toFloat()
        val iy = cy + (iconR * sin(midAngleRad)).toFloat()
        drawIcon(canvas, ix, iy, dir)
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
                val clockPaint = Paint(iconPaint).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
                canvas.drawCircle(cx, cy, dp(9), clockPaint)
                canvas.drawLine(cx, cy, cx, cy - dp(6), iconPaint)
                canvas.drawLine(cx, cy, cx + dp(4), cy, iconPaint)
            }
            Direction.DOWN -> { // dock-to-edge chevron
                val path = Path()
                path.moveTo(cx - dp(8), cy - dp(5))
                path.lineTo(cx, cy + dp(6))
                path.lineTo(cx + dp(8), cy - dp(5))
                canvas.drawPath(path, Paint(iconPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dp(3f)
                    strokeCap = Paint.Cap.ROUND
                })
            }
            Direction.NONE -> {}
        }
    }
}
