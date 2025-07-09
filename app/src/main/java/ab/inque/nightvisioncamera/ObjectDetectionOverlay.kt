package ab.inque.nightvisioncamera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ObjectDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val detections = mutableListOf<DetectionResult>()
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.RED
    }
    
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 24f
        style = Paint.Style.FILL
    }
    
    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        alpha = 128
        style = Paint.Style.FILL
    }
    
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        color = Color.RED
        style = Paint.Style.FILL
    }

    fun updateDetections(newDetections: List<DetectionResult>) {
        detections.clear()
        detections.addAll(newDetections)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        for (detection in detections) {
            drawDetection(canvas, detection)
        }
    }

    private fun drawDetection(canvas: Canvas, detection: DetectionResult) {
        val boundingBox = detection.boundingBox
        val label = detection.label
        val confidence = detection.confidence
        
        // Calculate center point of the object
        val centerX = (boundingBox.left + boundingBox.right) / 2f
        val centerY = (boundingBox.top + boundingBox.bottom) / 2f
        
        // Draw red dot at center (8px radius)
        canvas.drawCircle(centerX, centerY, 8f, dotPaint)
        
        // Draw bounding box
        canvas.drawRect(boundingBox, paint)
        
        // Prepare label text
        val labelText = "$label (${(confidence * 100).toInt()}%)"
        
        // Measure text bounds
        val textBounds = Rect()
        textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
        
        // Calculate background rectangle for text
        val textBackgroundRect = RectF(
            boundingBox.left,
            boundingBox.top - textBounds.height() - 8f,
            boundingBox.left + textBounds.width() + 16f,
            boundingBox.top
        )
        
        // Draw text background
        canvas.drawRect(textBackgroundRect, backgroundPaint)
        
        // Draw text
        canvas.drawText(
            labelText,
            boundingBox.left + 8f,
            boundingBox.top - 8f,
            textPaint
        )
    }
} 