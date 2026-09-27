package com.andebugulin.awareen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** One bar: a short x-axis label and the value it represents, in seconds. */
data class ChartBar(val label: String, val seconds: Int)

/**
 * Minimal bar chart for screen-time trends. No external dependency: bars,
 * a baseline, and a label per bar, drawn directly on Canvas. Mirrors the
 * hand-rolled-View precedent set by ColorPickerView rather than pulling in
 * a charting library for one bar chart.
 */
class ScreenTimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bars: List<ChartBar> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
    }

    private val barRect = RectF()

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    init {
        baselinePaint.strokeWidth = dpToPx(1f)
        labelPaint.textSize = spToPx(11f)
    }

    /** [barColor] fills every bar; [baselineColor] is the floor line; [labelColor] is the axis text. */
    fun setColors(barColor: Int, baselineColor: Int, labelColor: Int) {
        barPaint.color = barColor
        baselinePaint.color = baselineColor
        labelPaint.color = labelColor
        invalidate()
    }

    fun setBars(newBars: List<ChartBar>) {
        bars = newBars
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bars
        if (data.isEmpty() || width == 0 || height == 0) return

        val labelSpace = dpToPx(18f)
        val topInset = dpToPx(6f)
        val chartBottom = height - labelSpace
        val chartHeight = (chartBottom - topInset).coerceAtLeast(dpToPx(1f))

        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, baselinePaint)

        val maxSeconds = data.maxOf { it.seconds }.coerceAtLeast(1)
        val gap = dpToPx(6f)
        val n = data.size
        val barWidth = ((width - gap * (n + 1)) / n).coerceAtLeast(dpToPx(2f))
        val cornerRadius = (barWidth / 2f).coerceAtMost(dpToPx(5f))
        val minBarHeight = dpToPx(2f)

        data.forEachIndexed { index, bar ->
            val fraction = bar.seconds.toFloat() / maxSeconds
            val barHeight = (chartHeight * fraction).coerceAtLeast(minBarHeight)
            val left = gap + index * (barWidth + gap)
            val top = (chartBottom - barHeight).coerceAtLeast(topInset)

            barRect.set(left, top, left + barWidth, chartBottom)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)

            canvas.drawText(
                bar.label,
                left + barWidth / 2f,
                height.toFloat() - dpToPx(3f),
                labelPaint
            )
        }
    }
}
