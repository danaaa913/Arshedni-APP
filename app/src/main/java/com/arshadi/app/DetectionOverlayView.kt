package com.arshadi.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<DetectionResult> = emptyList()
    private var scaleX = 1f
    private var scaleY = 1f

    private val boxPaint   = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val labelPaint = Paint().apply { color = Color.WHITE; textSize = 36f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    private val bgPaint    = Paint().apply { style = Paint.Style.FILL }

    private val colorMap = mapOf(
        "car"      to Color.rgb(255, 68, 68),
        "person"   to Color.rgb(68, 255, 68),
        "stairs"   to Color.rgb(255, 170, 0),
        "chair"    to Color.rgb(0, 170, 255),
        "trash"    to Color.rgb(187, 68, 255),
        "tree"     to Color.rgb(0, 255, 136),
        "obstacle" to Color.rgb(255, 0, 136),
        "hole"     to Color.rgb(255, 0, 0),
        "sidewalk" to Color.rgb(136, 255, 255),
        "sill"     to Color.rgb(255, 255, 0)
    )

    fun update(dets: List<DetectionResult>, camW: Int, camH: Int) {
        detections = dets
        // Camera preview may be scaled to fill the view
        scaleX = width.toFloat() / camW
        scaleY = height.toFloat() / camH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (det in detections) {
            val color = colorMap[det.label.lowercase()] ?: Color.parseColor("#FFA500")

            val l = det.x1 * width
            val t = det.y1 * height
            val r = det.x2 * width
            val b = det.y2 * height

            // Box
            boxPaint.color = color
            canvas.drawRect(l, t, r, b, boxPaint)

            // Corner accents
            val cl = ((r - l) * 0.15f).coerceIn(8f, 30f)
            boxPaint.strokeWidth = 6f
            canvas.drawLine(l, t, l + cl, t, boxPaint)
            canvas.drawLine(l, t, l, t + cl, boxPaint)
            canvas.drawLine(r, t, r - cl, t, boxPaint)
            canvas.drawLine(r, t, r, t + cl, boxPaint)
            canvas.drawLine(l, b, l + cl, b, boxPaint)
            canvas.drawLine(l, b, l, b - cl, boxPaint)
            canvas.drawLine(r, b, r - cl, b, boxPaint)
            canvas.drawLine(r, b, r, b - cl, boxPaint)
            boxPaint.strokeWidth = 3f

            // Label
            val labelText = "${det.label}  ${"%.1f".format(det.distance)}Ù…  " +
                    "${(det.conf * 100).toInt()}%"
            val textBounds = Rect()
            labelPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            val lx = l
            val ly = if (t > 50f) t - 10f else b + textBounds.height() + 10f

            bgPaint.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(
                lx - 4f, ly - textBounds.height() - 6f,
                lx + textBounds.width() + 14f, ly + 6f,
                6f, 6f, bgPaint
            )
            canvas.drawText(labelText, lx + 5f, ly, labelPaint)

            // Direction arrow
            val arrow = when (det.position) {
                "Ø£Ù…Ø§Ù…Ùƒ Ù…Ø¨Ø§Ø´Ø±Ø©" -> "â–²"
                "Ø¹Ù„Ù‰ ÙŠØ³Ø§Ø±Ùƒ"    -> "â—„"
                else           -> "â–º"
            }
            val arrowPaint = Paint(labelPaint).also { it.color = color; it.textSize = 48f }
            canvas.drawText(arrow, (l + r) / 2f - 14f, (t + b) / 2f + 16f, arrowPaint)
        }
    }
}
