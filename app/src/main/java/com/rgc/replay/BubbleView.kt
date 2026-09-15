package com.rgc.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The floating circle itself. Drawing only — all touch/gesture handling
 * lives in ScreenCaptureService, since it needs to move the view within
 * the WindowManager, which the view itself has no direct access to.
 */
class BubbleView(context: Context) : View(context) {

    enum class VisualState { IDLE, RECORDING, PAUSED, DOCKED }

    var state: VisualState = VisualState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    companion object {
        const val SIZE_DP = 56
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f

        fillPaint.color = when (state) {
            VisualState.IDLE -> Color.parseColor("#CC1976D2")     // blue: ready, not recording
            VisualState.RECORDING -> Color.parseColor("#CCD32F2F") // red: live
            VisualState.PAUSED -> Color.parseColor("#CCF9A825")    // amber: paused
            VisualState.DOCKED -> Color.parseColor("#992F2F2F")    // dim: docked
        }
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // Simple icon: dot for idle/recording, two bars for paused.
        when (state) {
            VisualState.PAUSED -> {
                val barW = dp(4)
                val barH = dp(16)
                canvas.drawRect(cx - dp(6), cy - barH / 2, cx - dp(6) + barW, cy + barH / 2, iconPaint)
                canvas.drawRect(cx + dp(2), cy - barH / 2, cx + dp(2) + barW, cy + barH / 2, iconPaint)
            }
            VisualState.DOCKED -> {
                // no icon, just dimmed circle
            }
            else -> {
                canvas.drawCircle(cx, cy, dp(6), iconPaint)
            }
        }
    }
}
