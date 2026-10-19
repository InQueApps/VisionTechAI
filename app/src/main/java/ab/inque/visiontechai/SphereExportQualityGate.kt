package ab.inque.visiontechai

import ab.inque.visiontechai.sphere.debug.SphereStitchQualityEvaluator
import android.graphics.Bitmap
import android.util.Log

/**
 * S39: Runtime quality gate before writing a 360° photo to the gallery.
 */
object SphereExportQualityGate {

    private const val TAG = "SphereExportQuality"

    data class Result(
        val passed: Boolean,
        val ghostDelta: Double,
        val overlapBandCorrelation: Double,
        val summary: String
    )

    fun evaluate(previewEquirect: Bitmap, exportEquirect: Bitmap): Result {
        val compared = if (
            exportEquirect.width != previewEquirect.width ||
            exportEquirect.height != previewEquirect.height
        ) {
            Bitmap.createScaledBitmap(
                exportEquirect,
                previewEquirect.width,
                previewEquirect.height,
                true
            )
        } else {
            exportEquirect
        }
        try {
            val fields = SphereStitchQualityEvaluator.evaluateFinish(
                fastEquirect = previewEquirect,
                finishEquirect = compared,
                ssdBefore = 0f,
                ssdAfter = 0f
            )
            val passed = fields.passesFinishGhostGate && fields.passesBandCorrelationGate
            val summary = buildString {
                append("ghost Δ=${"%.3f".format(fields.finishGhostDelta)}")
                append(" (≤${SphereRefinePolicy.maxFinishExportGhostDelta()})")
                append(", band corr=${"%.3f".format(fields.finishOverlapBandCorrelation)}")
                append(" (≥${SphereRefinePolicy.minFinishOverlapBandCorrelation()})")
            }
            Log.i(TAG, "Save quality gate passed=$passed $summary")
            return Result(
                passed = passed,
                ghostDelta = fields.finishGhostDelta,
                overlapBandCorrelation = fields.finishOverlapBandCorrelation,
                summary = summary
            )
        } finally {
            if (compared !== exportEquirect && !compared.isRecycled) compared.recycle()
        }
    }
}
