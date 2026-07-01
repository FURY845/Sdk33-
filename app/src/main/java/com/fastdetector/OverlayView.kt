package com.fastdetector

import android.content.*
import android.graphics.*
import android.view.*

class OverlayView(context: Context) : View(context) {
    
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val bgPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private var currentBoxes = listOf<RectF>()
    
    fun updateBoxes(boxes: List<RectF>) {
        currentBoxes = boxes
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val scaleX = width.toFloat() / 320f
        val scaleY = height.toFloat() / 320f
        
        currentBoxes.forEachIndexed { index, box ->
            val scaledBox = RectF(
                box.left * scaleX,
                box.top * scaleY,
                box.right * scaleX,
                box.bottom * scaleY
            )
            
            canvas.drawRect(scaledBox, boxPaint)
            
            val label = "لاعب ${index + 1}"
            val textWidth = textPaint.measureText(label)
            
            canvas.drawRect(
                scaledBox.left,
                scaledBox.top - 45,
                scaledBox.left + textWidth + 20,
                scaledBox.top,
                bgPaint
            )
            
            canvas.drawText(label, scaledBox.left + 10, scaledBox.top - 10, textPaint)
        }
    }
}
