package ab.inque.visiontechai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Custom view for displaying RGB histograms.
 */
class HistogramView(context: Context) : View(context) {
    private var redHistogram: IntArray = IntArray(256)
    private var greenHistogram: IntArray = IntArray(256)
    private var blueHistogram: IntArray = IntArray(256)
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 10f
        textAlign = Paint.Align.LEFT
    }

    fun updateHistograms(red: IntArray, green: IntArray, blue: IntArray) {
        redHistogram = red
        greenHistogram = green
        blueHistogram = blue
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val histogramHeight = h / 3f - 20f

        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.style = Paint.Style.FILL

        val maxRed = redHistogram.maxOrNull() ?: 1
        val maxGreen = greenHistogram.maxOrNull() ?: 1
        val maxBlue = blueHistogram.maxOrNull() ?: 1
        val barWidth = w / 256f

        paint.color = Color.RED
        paint.alpha = 255
        for (i in 0 until 256) {
            val barHeight = (redHistogram[i] / maxRed.toFloat()) * histogramHeight
            canvas.drawRect(
                i * barWidth,
                histogramHeight - barHeight + 10f,
                (i + 1) * barWidth,
                histogramHeight + 10f,
                paint
            )
        }
        canvas.drawText("Red", 5f, histogramHeight + 25f, textPaint)

        paint.color = Color.GREEN
        for (i in 0 until 256) {
            val barHeight = (greenHistogram[i] / maxGreen.toFloat()) * histogramHeight
            canvas.drawRect(
                i * barWidth,
                2 * histogramHeight - barHeight + 30f,
                (i + 1) * barWidth,
                2 * histogramHeight + 30f,
                paint
            )
        }
        canvas.drawText("Green", 5f, 2 * histogramHeight + 45f, textPaint)

        paint.color = Color.BLUE
        for (i in 0 until 256) {
            val barHeight = (blueHistogram[i] / maxBlue.toFloat()) * histogramHeight
            canvas.drawRect(
                i * barWidth,
                3 * histogramHeight - barHeight + 50f,
                (i + 1) * barWidth,
                3 * histogramHeight + 50f,
                paint
            )
        }
        canvas.drawText("Blue", 5f, 3 * histogramHeight + 65f, textPaint)
    }
}
