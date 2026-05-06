package com.andebugulin.awareen

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom HSV color picker: horizontal hue bar on top,
 * saturation-value square below. No external dependencies.
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnColorChangedListener {
        fun onColorChanged(color: Int)
    }

    var listener: OnColorChangedListener? = null

    private val hsv = floatArrayOf(0f, 1f, 1f)

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val indicatorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.BLACK
    }

    private val hueRect = RectF()
    private val svRect = RectF()

    private var hueBitmap: Bitmap? = null
    private var svBitmap: Bitmap? = null
    private var lastSvHue = -1f

    private var draggingHue = false
    private var draggingSV = false

    private val hueBarHeight = 48f
    private val gapHeight = 16f
    private val cornerRadius = 12f

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        lastSvHue = -1f
        invalidate()
    }

    fun getColor(): Int = Color.HSVToColor(hsv)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hueH = dpToPx(hueBarHeight).toInt()
        val gap = dpToPx(gapHeight).toInt()
        val svH = w
        val totalH = paddingTop + hueH + gap + svH + paddingBottom
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hueH = dpToPx(hueBarHeight)
        val gap = dpToPx(gapHeight)

        hueRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (w - paddingRight).toFloat(),
            paddingTop + hueH
        )

        val svTop = hueRect.bottom + gap
        val svSize = (w - paddingLeft - paddingRight).toFloat()
        svRect.set(
            paddingLeft.toFloat(),
            svTop,
            paddingLeft + svSize,
            svTop + svSize
        )

        // Pre-build BOTH bitmaps so first draw is fully rendered
        hueBitmap = buildHueBitmap(
            hueRect.width().toInt().coerceAtLeast(1),
            hueRect.height().toInt().coerceAtLeast(1)
        )
        svBitmap = buildSvBitmap(
            svRect.width().toInt().coerceAtLeast(1),
            svRect.height().toInt().coerceAtLeast(1),
            hsv[0]
        )
        lastSvHue = hsv[0]
    }

    private fun buildHueBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hueColors = IntArray(361) { i ->
            Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
        }
        val shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            hueColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        return bmp
    }

    private fun buildSvBitmap(w: Int, h: Int, hue: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val satShader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        val valShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP
        )
        val compose = ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = compose })
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dpToPx(cornerRadius)

        // Draw hue bar
        hueBitmap?.let { bmp ->
            val path = Path().apply { addRoundRect(hueRect, r, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, hueRect.left, hueRect.top, null)
            canvas.restore()

            // Hue indicator
            val hueX = hueRect.left + (hsv[0] / 360f) * hueRect.width()
            val hueY = hueRect.centerY()
            val indicR = hueRect.height() / 2f - 2f
            canvas.drawCircle(hueX, hueY, indicR, indicatorShadowPaint)
            canvas.drawCircle(hueX, hueY, indicR, indicatorPaint)
        }

        // Rebuild SV bitmap if hue changed since last build
        if (lastSvHue != hsv[0] && svRect.width() > 0 && svRect.height() > 0) {
            svBitmap = buildSvBitmap(
                svRect.width().toInt().coerceAtLeast(1),
                svRect.height().toInt().coerceAtLeast(1),
                hsv[0]
            )
            lastSvHue = hsv[0]
        }

        // Draw SV square
        svBitmap?.let { bmp ->
            val path = Path().apply { addRoundRect(svRect, r, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, svRect.left, svRect.top, null)
            canvas.restore()

            // SV indicator
            val svX = svRect.left + hsv[1] * svRect.width()
            val svY = svRect.top + (1f - hsv[2]) * svRect.height()
            val circleR = dpToPx(10f)
            canvas.drawCircle(svX, svY, circleR, indicatorShadowPaint)
            canvas.drawCircle(svX, svY, circleR, indicatorPaint)

            // Fill indicator with current color
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor() }
            canvas.drawCircle(svX, svY, dpToPx(7f), fillPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Prevent ScrollView from stealing our touches
                parent?.requestDisallowInterceptTouchEvent(true)

                val hueHit = RectF(hueRect).apply { inset(0f, -dpToPx(12f)) }
                val svHit = RectF(svRect).apply { inset(-dpToPx(4f), -dpToPx(4f)) }

                if (hueHit.contains(x, y)) {
                    draggingHue = true
                    updateHue(x)
                    return true
                } else if (svHit.contains(x, y)) {
                    draggingSV = true
                    updateSV(x, y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHue) { updateHue(x); return true }
                if (draggingSV) { updateSV(x, y); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                draggingHue = false
                draggingSV = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateHue(x: Float) {
        val fraction = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f)
        hsv[0] = fraction * 360f
        invalidate()
        listener?.onColorChanged(getColor())
    }

    private fun updateSV(x: Float, y: Float) {
        hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
        hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        invalidate()
        listener?.onColorChanged(getColor())
    }
}