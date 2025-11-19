package project.aio.batteryreminder.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class BracketBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND // Makes the ends of the lines rounded
        strokeJoin = Paint.Join.ROUND
    }

    private val leftPath = Path()
    private val rightPath = Path()

    // Configuration
    private var cornerRadius = 0f
    private var extensionLength = 0f

    init {
        // Convert DP to Pixels for consistent UI across devices
        val displayMetrics = context.resources.displayMetrics
        val strokeWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, displayMetrics)
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, displayMetrics) // Good curve
        extensionLength = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, displayMetrics) // The "little extension"

        paint.strokeWidth = strokeWidthPx
        paint.color = 0xFFFFFFFF.toInt() // Default White
    }

    fun setBorderColor(color: Int) {
        paint.color = color
        invalidate() // Redraw
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createPaths(w.toFloat(), h.toFloat())
    }

    private fun createPaths(w: Float, h: Float) {
        leftPath.reset()
        rightPath.reset()

        // Inset slightly so the stroke isn't cut off by screen edge (half stroke width)
        val inset = paint.strokeWidth / 2

        // --- LEFT BRACKET [ ---
        // Top-Left extension tip
        leftPath.moveTo(extensionLength, inset)
        // Top-Left Horizontal line to corner start
        leftPath.lineTo(cornerRadius, inset)
        // Top-Left Corner (Arc)
        leftPath.arcTo(RectF(inset, inset, cornerRadius * 2, cornerRadius * 2), 270f, -90f)
        // Left Vertical Line
        leftPath.lineTo(inset, h - cornerRadius)
        // Bottom-Left Corner (Arc)
        leftPath.arcTo(RectF(inset, h - cornerRadius * 2, cornerRadius * 2, h - inset), 180f, -90f)
        // Bottom-Left extension tip
        leftPath.lineTo(extensionLength, h - inset)

        // --- RIGHT BRACKET ] ---
        // Top-Right extension tip
        rightPath.moveTo(w - extensionLength, inset)
        // Top-Right Horizontal line
        rightPath.lineTo(w - cornerRadius, inset)
        // Top-Right Corner
        rightPath.arcTo(RectF(w - cornerRadius * 2, inset, w - inset, cornerRadius * 2), 270f, 90f)
        // Right Vertical Line
        rightPath.lineTo(w - inset, h - cornerRadius)
        // Bottom-Right Corner
        rightPath.arcTo(RectF(w - cornerRadius * 2, h - cornerRadius * 2, w - inset, h - inset), 0f, 90f)
        // Bottom-Right extension tip
        rightPath.lineTo(w - extensionLength, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(leftPath, paint)
        canvas.drawPath(rightPath, paint)
    }
}