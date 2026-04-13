package com.gustav.mlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

class BatteryIconDrawable(
    context: Context,
    level: Int,
    private val isCharging: Boolean,
    private val inkColor: Int = Color.BLACK,
    private val backgroundColor: Int = Color.WHITE,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val normalizedLevel = level.coerceIn(0, 100) / 100f
    private val strokeWidth = 1.4f * density
    private val bodyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            this.strokeWidth = this@BatteryIconDrawable.strokeWidth
            strokeJoin = Paint.Join.ROUND
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.FILL
        }
    private val boltFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
    private val boltStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = 0.85f * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val intrinsicWidthPx = (28 * density).roundToInt()
    private val intrinsicHeightPx = (18 * density).roundToInt()

    override fun draw(canvas: Canvas) {
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()

        val bodyRect =
            RectF(
                left + width * 0.05f,
                top + height * 0.18f,
                left + width * 0.84f,
                top + height * 0.82f,
            )
        val capRect =
            RectF(
                left + width * 0.86f,
                top + height * 0.36f,
                left + width * 0.96f,
                top + height * 0.64f,
            )
        val bodyRadius = height * 0.08f
        val capRadius = height * 0.04f

        canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, bodyPaint)
        canvas.drawRoundRect(capRect, capRadius, capRadius, bodyPaint)

        val innerRect =
            RectF(
                bodyRect.left + strokeWidth * 1.2f,
                bodyRect.top + strokeWidth * 1.2f,
                bodyRect.right - strokeWidth * 1.2f,
                bodyRect.bottom - strokeWidth * 1.2f,
            )

        if (normalizedLevel > 0f) {
            val minimumFill = 1.6f * density
            val fillWidth = max(minimumFill, innerRect.width() * normalizedLevel)
            val fillRect =
                RectF(
                    innerRect.left,
                    innerRect.top,
                    (innerRect.left + fillWidth).coerceAtMost(innerRect.right),
                    innerRect.bottom,
                )
            canvas.drawRoundRect(fillRect, bodyRadius * 0.7f, bodyRadius * 0.7f, fillPaint)
        }

        if (isCharging) {
            val boltPath =
                Path().apply {
                    moveTo(left + width * 0.54f, top + height * 0.20f)
                    lineTo(left + width * 0.42f, top + height * 0.49f)
                    lineTo(left + width * 0.52f, top + height * 0.49f)
                    lineTo(left + width * 0.46f, top + height * 0.78f)
                    lineTo(left + width * 0.66f, top + height * 0.40f)
                    lineTo(left + width * 0.55f, top + height * 0.40f)
                    close()
                }

            canvas.drawPath(boltPath, boltFillPaint)
            canvas.drawPath(boltPath, boltStrokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        fillPaint.alpha = alpha
        boltFillPaint.alpha = alpha
        boltStrokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        boltFillPaint.colorFilter = colorFilter
        boltStrokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicWidthPx

    override fun getIntrinsicHeight(): Int = intrinsicHeightPx
}
