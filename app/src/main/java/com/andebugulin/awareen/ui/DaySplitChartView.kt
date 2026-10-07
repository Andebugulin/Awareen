package com.andebugulin.awareen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** One slice of the day: its share in minutes and its color. */
data class DaySegment(val minutes: Int, val color: Int)

/**
 * Donut showing how a 24-hour day splits up (sleep, work, free time, screen
 * time), with a headline in the middle. Identity is carried by the legend
 * rows next to it, so the ring itself has no labels. Segments are separated
 * by a small surface-colored gap.
 */
class DaySplitChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<DaySegment> = emptyList()
    private var centerValue = ""
    private var centerCaption = ""

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    init {
        arcPaint.strokeWidth = dp(18f)
        valuePaint.textSize = 24f * resources.displayMetrics.scaledDensity
        captionPaint.textSize = 12f * resources.displayMetrics.scaledDensity
    }

    /** [valueColor] for the headline, [captionColor] for the line under it. */
    fun setTextColors(valueColor: Int, captionColor: Int) {
        valuePaint.color = valueColor
        captionPaint.color = captionColor
        invalidate()
    }

    fun setValueTypeface(typeface: android.graphics.Typeface?) {
        valuePaint.typeface = typeface
        invalidate()
    }

    fun setData(newSegments: List<DaySegment>, value: String, caption: String) {
        segments = newSegments
        centerValue = value
        centerCaption = caption
        contentDescription = "$value $caption"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = segments.sumOf { it.minutes }
        if (total <= 0) return

        val stroke = arcPaint.strokeWidth
        val size = min(width, height) - stroke
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)

        val radius = size / 2f
        val gapDegrees = Math.toDegrees((dp(2f) / radius).toDouble()).toFloat()
        val visible = segments.filter { it.minutes > 0 }
        var start = -90f
        visible.forEach { seg ->
            val sweep = 360f * seg.minutes / total
            arcPaint.color = seg.color
            val gap = if (visible.size > 1) gapDegrees else 0f
            canvas.drawArc(oval, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, arcPaint)
            start += sweep
        }

        canvas.drawText(centerValue, cx, cy + valuePaint.textSize / 4f, valuePaint)
        canvas.drawText(centerCaption, cx, cy + valuePaint.textSize / 4f + captionPaint.textSize * 1.5f, captionPaint)
    }
}
