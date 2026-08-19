package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/**
 * Shared planar replay assets for device E2E (see scripts/stitch_planar_roundtrip.py).
 */
object PlanarReplayFixtures {

    private const val ASSET_DIR = "planar_replay"
    private const val MANIFEST = "manifest.json"

    data class Frame(
        val fileName: String,
        val azimuthDeg: Float,
        val elevationDeg: Float,
        val rollDeg: Float,
        val index: Int
    )

    data class Manifest(
        val sourceEquirect: String,
        val eqW: Int,
        val eqH: Int,
        val fovDeg: Float,
        val frames: List<Frame>
    )

    fun loadManifest(context: Context): Manifest {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("$ASSET_DIR/$MANIFEST").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val frames = obj.getJSONArray("frames")
        val list = (0 until frames.length()).map { i ->
            val f = frames.getJSONObject(i)
            Frame(
                fileName = f.getString("file"),
                azimuthDeg = f.getDouble("azimuth").toFloat(),
                elevationDeg = f.optDouble("elevation", obj.optDouble("elevation", 0.0)).toFloat(),
                rollDeg = f.optDouble("roll", 0.0).toFloat(),
                index = f.optInt("index", i)
            )
        }.sortedBy { it.index }
        return Manifest(
            sourceEquirect = obj.getString("sourceEquirect"),
            eqW = obj.getInt("eqW"),
            eqH = obj.getInt("eqH"),
            fovDeg = obj.getDouble("fov").toFloat(),
            frames = list
        )
    }

    fun loadBitmap(context: Context, name: String): Bitmap? =
        try {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

    /** Matches scripts/extract_rectilinear_views.py + nativeGetRectilinear. */
    fun quaternionForPlanarView(
        azimuthDeg: Double,
        elevationDeg: Double,
        rollDeg: Double = 0.0
    ): FloatArray {
        val qAz = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(-azimuthDeg))
        val qEl = QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(elevationDeg))
        val qLook = QuaternionMath.multiply(qAz, qEl)
        val qRoll = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(rollDeg))
        val qWithRoll = QuaternionMath.multiply(qLook, qRoll)
        val qPitch = QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(90.0))
        return QuaternionMath.multiply(qWithRoll, qPitch)
    }
}
