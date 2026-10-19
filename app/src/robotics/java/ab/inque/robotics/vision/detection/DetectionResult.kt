package ab.inque.robotics.vision.detection

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)
