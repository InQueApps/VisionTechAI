package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10

/**
 * Stitches planar views extracted from a reference equirect (see scripts/extract_rectilinear_views.py)
 * and compares the result to the source panorama using [SphereNative].
 *
 * Assets are synced by [scripts/stitch_planar_roundtrip.py] into assets/planar_replay/.
 */
@RunWith(AndroidJUnit4::class)
class SpherePlanarReplayE2ETest {

    companion object {
        private const val TAG = "SpherePlanarReplay"
        private const val ASSET_DIR = "planar_replay"
        private const val MANIFEST = "manifest.json"

        private const val MIN_EQUIRECT_CORRELATION = 0.98
        private const val MIN_EQUIRECT_CORRELATION_FULL_SPHERE = 0.98
        private const val MIN_EQUIRECT_PSNR_DB = 18.0
        private const val MIN_EQUIRECT_PSNR_FULL_SPHERE_DB = 14.0
        /** Calibrated on scripts/images/img1 planar round-trip (partial ~48% coverage). */
        private const val MIN_PLANAR_ROUNDTRIP_PSNR_DB = 10.0
        private const val MIN_PLANAR_ROUNDTRIP_PSNR_EQ2X_DB = 9.5
        private const val MAX_GHOST_SCORE = 0.45
        private const val MAX_GHOST_SCORE_FULL_SPHERE = 0.65
        /** Fast @1024 truth roundtrip: nearest-center export can read high on ghost metric at corr≥0.98. */
        private const val MAX_GHOST_SCORE_FAST_TRUTH_FULL_SPHERE = 0.74
        private const val MAX_GHOST_SCORE_IMU_DEGRADED = 0.72
        /** Device roundtrip E2E must finish within 30s (fast smoke only). */
        private const val E2E_WALL_MS_FAST = 30_000L
        /** Production worker (capture refine + DONE + SAVE + polish) budget on device. */
        private const val E2E_WALL_MS_PRODUCTION = 900_000L
        private const val FRAME_SUBMIT_TIMEOUT_MS = 30_000L
        private const val FRAME_SUBMIT_TIMEOUT_PRODUCTION_MS = 180_000L
        private const val PREVIEW_TIMEOUT_MS = 30_000L
        private const val PRE_FINISH_TIMEOUT_MS = 420_000L
        private const val EXPORT_TIMEOUT_MS = 480_000L
        private const val POLISH_TIMEOUT_MS = 180_000L
        private const val MAX_GHOST_SCORE_IMU_FAST_FULL_SPHERE = 0.74
        private const val MIN_EQUIRECT_CORRELATION_IMU_FAST_FULL_SPHERE = 0.85
        private const val MIN_EQUIRECT_CORRELATION_IMU_FAST = 0.82
        private const val MIN_EQUIRECT_PSNR_IMU_FAST_DB = 15.0
        private const val MIN_EQUIRECT_PSNR_IMU_FAST_FULL_SPHERE_DB = 14.0
        /** Full-quality IMU recovery (slow E2E / --slow-e2e). */
        private const val MIN_EQUIRECT_CORRELATION_IMU_RECOVERY = 0.90
        private const val MIN_EQUIRECT_PSNR_IMU_RECOVERY_DB = 22.0
        private const val LIVE_OVERLAP_GATE_FIRST_FRAME = 0f
        private const val POSE_REFINE_PASSES = 3
        private const val SEAMS_LIGHT = "light"
        private const val SEAMS_NONE = "none"
        private const val SEAMS_FULL = "full"
    }

    data class ReplayOptions(
        val refinePasses: Int = POSE_REFINE_PASSES,
        val refineSeams: String = SEAMS_LIGHT
    )

    data class FrameSpec(
        val fileName: String,
        val azimuthDeg: Float,
        val elevationDeg: Float = 0f,
        val rollDeg: Float = 0f,
        val index: Int = 0
    )

    data class Manifest(
        val sourceEquirect: String,
        val referenceEquirect: String,
        val eqW: Int,
        val eqH: Int,
        val refW: Int,
        val refH: Int,
        val bandY0: Int,
        val fovDeg: Float,
        val vfovDeg: Float,
        val elevationDeg: Float,
        val frames: List<FrameSpec>,
        val eqScale: Int = 1,
        val options: ReplayOptions = ReplayOptions(),
        val fullSphere: Boolean = false,
        val compareFullEquirect: Boolean = false,
        val imuDegradeDeg: Float = 0f,
        val imuDegradeSeed: Long = 42L,
        val runCaptureRefinement: Boolean = false,
        val useFastPath: Boolean = false,
        val overlapGate: Float = 0.10f,
        /** When true, uses legacy fast E2E shortcuts (rotation-only global). */
        val fastE2eTest: Boolean = false,
        /** When true, runs [SphereProductionStitchPipeline] (same as live DONE). */
        val productionReplay: Boolean = true,
        /** Live path: [SphereCaptureController] + [SphereStitchWorker] through SAVE + polish. */
        val useProductionWorker: Boolean = false,
        val exportEqW: Int? = null,
        val exportEqH: Int? = null,
        val skipPlanarRoundtrip: Boolean = false
    )

    private lateinit var appContext: Context
    private lateinit var testContext: Context

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        val inst = InstrumentationRegistry.getInstrumentation()
        appContext = inst.targetContext
        testContext = inst.context
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
    }

    @Test(timeout = 900_000)
    fun stitchPlanarViews_matchesSourceEquirect() {
        val wallStart = SystemClock.elapsedRealtime()
        val manifest = loadManifest()
        if (manifest.productionReplay && !manifest.fastE2eTest) {
            assumeFalse(
                "Production planar replay skipped on emulator (S47)",
                Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                    Build.HARDWARE.contains("ranchu", ignoreCase = true)
            )
        }
        val wallBudget = wallBudgetMs(manifest)
        Log.i(
            TAG,
            "eqScale=${manifest.eqScale} eq=${manifest.eqW}x${manifest.eqH} " +
                "imuDegrade=${manifest.imuDegradeDeg}° fastPath=${manifest.useFastPath} " +
                "production=${manifest.productionReplay} worker=${manifest.useProductionWorker} " +
                "fastE2e=${manifest.fastE2eTest}"
        )
        val source = loadAssetBitmap(manifest.sourceEquirect)
            ?: error("Missing source equirect asset: ${manifest.sourceEquirect}")
        val reference = loadAssetBitmap(manifest.referenceEquirect)
            ?: if (manifest.compareFullEquirect) source else null
            ?: error(
                "Missing reference band asset: ${manifest.referenceEquirect}. " +
                    "Run: python scripts/stitch_planar_roundtrip.py --sync-only"
            )

        assumeTrue(
            "Source size ${source.width}x${source.height} != manifest ${manifest.eqW}x${manifest.eqH}",
            source.width == manifest.eqW && source.height == manifest.eqH
        )
        if (!manifest.compareFullEquirect) {
            assumeTrue(
                "Reference band ${reference.width}x${reference.height} != manifest ${manifest.refW}x${manifest.refH}",
                reference.width == manifest.refW && reference.height == manifest.refH
            )
        }

        val sampleFrame = loadAssetBitmap(manifest.frames.first().fileName)
            ?: error("Missing frame asset: ${manifest.frames.first().fileName}")
        val (initFovH, initFovV) = stitchFovForManifest(manifest, sampleFrame)
        if (SphereRefinePolicy.applyCenterSquareToPlanarReplay()) {
            Log.i(TAG, "Center-square replay FOV ${initFovH}x${initFovV} (manifest ${manifest.fovDeg}x${manifest.vfovDeg})")
        }

        val stitchedFull = if (manifest.useProductionWorker) {
            runProductionWorkerPipeline(manifest, initFovH, initFovV)
        } else {
            runDirectNativePipeline(manifest, initFovH, initFovV)
        }
        val stitched = if (manifest.compareFullEquirect) {
            stitchedFull
        } else {
            Bitmap.createBitmap(
                stitchedFull,
                0,
                manifest.bandY0,
                manifest.refW,
                manifest.refH
            )
        }
        val referenceCompare = if (manifest.compareFullEquirect) source else reference

        saveOutputBitmap(stitched, "stitched_equirect.jpg")
        if (!manifest.compareFullEquirect) {
            saveOutputBitmap(reference, "reference_equirect.jpg")
        }

        val content = SphereImageMetrics.requireContentRegion(stitched, "stitched")
        val refContent = SphereImageMetrics.requireContentRegion(referenceCompare, "reference")
        val compareRegion = SphereImageMetrics.Region(
            x0 = maxOf(content.x0, refContent.x0),
            y0 = maxOf(content.y0, refContent.y0),
            w = minOf(content.w, refContent.w),
            h = minOf(content.h, refContent.h)
        )

        val correlation = SphereImageMetrics.equirectOverlapCorrelation(
            referenceCompare, stitched, compareRegion
        )
        val psnr = equirectPsnr(referenceCompare, stitched, compareRegion)
        val ghost = SphereImageMetrics.ghostingScore(
            stitched,
            SphereImageMetrics.overlapBandRegion(compareRegion)
        )
        val sharpRef = SphereImageMetrics.laplacianVariance(referenceCompare, refContent)
        val sharpStitch = SphereImageMetrics.laplacianVariance(stitched, content)
        val sharpRetention = SphereImageMetrics.sharpnessRetentionRatio(sharpRef, sharpStitch)

        Log.i(
            TAG,
            (if (manifest.compareFullEquirect) "Full equirect" else "Band") +
                " compare ${manifest.referenceEquirect} (${manifest.refW}x${manifest.refH} " +
                "y0=${manifest.bandY0}) vs stitched: " +
                "corr=${"%.4f".format(correlation)} " +
                "PSNR=${"%.2f".format(psnr)}dB ghost=${"%.3f".format(ghost)} " +
                "sharpRet=${"%.3f".format(sharpRetention)} coverage=${SphereNative.getCoverage()}"
        )

        var minPlanarPsnr = Double.MAX_VALUE
        if (!manifest.skipPlanarRoundtrip) {
            for (spec in manifest.frames) {
                val planar = loadAssetBitmap(spec.fileName) ?: continue
                val extracted = SphereNative.getRectilinearBitmap(
                    planar.width,
                    planar.height,
                    spec.azimuthDeg,
                    spec.elevationDeg,
                    manifest.fovDeg
                ) ?: error("Rectilinear extract null for ${spec.fileName}")
                val planarPsnr = planarPsnr(planar, extracted)
                minPlanarPsnr = minOf(minPlanarPsnr, planarPsnr)
                Log.i(
                    TAG,
                    "Planar round-trip ${spec.fileName} az=${spec.azimuthDeg}: " +
                        "PSNR=${"%.2f".format(planarPsnr)}dB"
                )
            }
        }

        val minCorr = when {
            manifest.productionReplay && manifest.imuDegradeDeg > 0f ->
                MIN_EQUIRECT_CORRELATION_IMU_RECOVERY
            manifest.productionReplay && manifest.compareFullEquirect ->
                MIN_EQUIRECT_CORRELATION_FULL_SPHERE
            manifest.fastE2eTest && manifest.imuDegradeDeg > 0f && manifest.compareFullEquirect ->
                MIN_EQUIRECT_CORRELATION_IMU_FAST_FULL_SPHERE
            manifest.fastE2eTest && manifest.imuDegradeDeg > 0f ->
                MIN_EQUIRECT_CORRELATION_IMU_FAST
            manifest.imuDegradeDeg > 0f -> MIN_EQUIRECT_CORRELATION_IMU_RECOVERY
            manifest.compareFullEquirect -> MIN_EQUIRECT_CORRELATION_FULL_SPHERE
            else -> MIN_EQUIRECT_CORRELATION
        }
        val minPsnr = when {
            manifest.fastE2eTest && manifest.imuDegradeDeg > 0f && manifest.compareFullEquirect ->
                MIN_EQUIRECT_PSNR_IMU_FAST_FULL_SPHERE_DB
            manifest.fastE2eTest && manifest.imuDegradeDeg > 0f ->
                MIN_EQUIRECT_PSNR_IMU_FAST_DB
            manifest.imuDegradeDeg > 0f -> MIN_EQUIRECT_PSNR_IMU_RECOVERY_DB
            manifest.compareFullEquirect -> MIN_EQUIRECT_PSNR_FULL_SPHERE_DB
            else -> MIN_EQUIRECT_PSNR_DB
        }
        assertTrue(
            "Equirect correlation vs reference must be >= $minCorr (was $correlation)",
            correlation >= minCorr
        )
        assertTrue(
            "Equirect PSNR vs reference must be >= $minPsnr dB (was $psnr)",
            psnr >= minPsnr
        )
        val maxGhost = when {
            manifest.fastE2eTest && manifest.imuDegradeDeg > 0f && manifest.compareFullEquirect ->
                MAX_GHOST_SCORE_IMU_FAST_FULL_SPHERE
            manifest.fastE2eTest && manifest.imuDegradeDeg == 0f && manifest.compareFullEquirect ->
                MAX_GHOST_SCORE_FAST_TRUTH_FULL_SPHERE
            manifest.imuDegradeDeg > 0f -> MAX_GHOST_SCORE_IMU_DEGRADED
            manifest.compareFullEquirect -> MAX_GHOST_SCORE_FULL_SPHERE
            else -> MAX_GHOST_SCORE
        }
        assertTrue(
            "Ghost score must be <= $maxGhost (was $ghost)",
            ghost <= maxGhost
        )
        if (!manifest.skipPlanarRoundtrip) {
            assertTrue(
                "Worst planar round-trip PSNR must be >= ${planarPsnrGate(manifest)} dB (was $minPlanarPsnr)",
                minPlanarPsnr >= planarPsnrGate(manifest)
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - wallStart
        Log.i(TAG, "E2E wall time: ${elapsed}ms (budget ${wallBudget}ms)")
        assertTrue(
            "E2E must finish within ${wallBudget}ms (was ${elapsed}ms)",
            elapsed < wallBudget
        )
    }

    private fun runDirectNativePipeline(
        manifest: Manifest,
        initFovH: Float,
        initFovV: Float
    ): Bitmap {
        SphereNative.init(manifest.eqW, manifest.eqH, initFovH, initFovV)

        for (spec in manifest.frames) {
            val raw = loadAssetBitmap(spec.fileName)
                ?: error("Missing frame asset: ${spec.fileName}")
            val frame = prepareStitchFrame(raw)
            val truthQuat = quaternionForExtractedPlanarView(
                spec.azimuthDeg.toDouble(),
                spec.elevationDeg.toDouble(),
                spec.rollDeg.toDouble()
            )
            if (manifest.useFastPath) {
                val seedQuat = seedQuaternion(manifest, truthQuat, spec.index)
                val gate = if (spec.index == 0) {
                    LIVE_OVERLAP_GATE_FIRST_FRAME
                } else {
                    manifest.overlapGate
                }
                val status = SphereNative.addFrameFast(
                    frame,
                    seedQuat,
                    gate,
                    nearnessMap = null,
                    allowReject = false
                )
                assumeTrue(
                    "addFrameFast failed for ${spec.fileName} status=$status",
                    status == STITCH_ACCEPTED
                )
            } else {
                val accepted = SphereNative.addFrameAligned(frame, truthQuat, allowReject = false)
                assumeTrue("addFrameAligned failed for ${spec.fileName}", accepted)
            }
        }

        if (manifest.runCaptureRefinement) {
            SphereProductionStitchPipeline.runCaptureRefinement(
                operations = SphereNativeOperations,
                onPass = { label -> Log.i(TAG, "Capture $label") }
            )
        }

        when {
            manifest.productionReplay -> {
                SphereProductionStitchPipeline.runFinishExport(
                    operations = SphereNativeOperations,
                    exportW = manifest.eqW,
                    exportH = manifest.eqH,
                    onPass = { label -> Log.i(TAG, "Finish $label") }
                )
            }
            manifest.fastE2eTest -> {
                if (manifest.compareFullEquirect) {
                    if (manifest.imuDegradeDeg > 0f) {
                        SphereNative.setFastFinishRefine(true)
                        SphereNative.setGlobalRefinePasses(1)
                        SphereNative.refineStoredPoses()
                        SphereNative.rebuildFullAlign()
                        Log.i(TAG, "Finish fast full-sphere: chained + full align")
                    } else {
                        SphereNative.rebuildExportNearestCenter()
                        Log.i(TAG, "Finish fast full-sphere: nearest-center export (truth poses)")
                    }
                } else {
                    SphereNative.setFastFinishRefine(true)
                    SphereNative.setGlobalRefinePasses(1)
                    SpherePoseRefinement.run(
                        operations = SphereNativeOperations,
                        config = SphereRefinePolicy.testImuRecoveryPasses(),
                        onPass = { label -> Log.i(TAG, "Finish $label") }
                    )
                }
            }
            else -> {
                SphereFinishRefinement.run(
                    operations = SphereNativeOperations,
                    onPass = { label -> Log.i(TAG, "Finish $label") }
                )
                when (manifest.options.refineSeams) {
                    SEAMS_NONE -> Log.i(TAG, "Skipping seam refine (manifest refineSeams=none)")
                    SEAMS_FULL -> SphereNative.refineSeams(light = false)
                    else -> SphereNative.refineSeams(light = true)
                }
            }
        }

        return SphereNative.getEquirectBitmap(manifest.eqW, manifest.eqH)
            ?: error("Stitched equirect null")
    }

    /**
     * Full live pipeline: worker submit → debounced capture refine → DONE pre-finish drain →
     * SAVE [runFinishGeneration] → background intrinsics + light seams.
     */
    private fun runProductionWorkerPipeline(
        manifest: Manifest,
        initFovH: Float,
        initFovV: Float
    ): Bitmap {
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = SphereNativeOperations,
            executor = executor,
            refineDelayMs = 60_000L
        )
        val controller = SphereCaptureController(
            orientationTracker = OrientationTracker(appContext),
            extrinsicCalibration = SphereExtrinsicCalibration(appContext).apply { reset() },
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            operations = SphereNativeOperations,
            stitchWorker = worker
        )
        controller.startSession(
            sensorFovH = manifest.fovDeg,
            sensorFovV = manifest.vfovDeg,
            fovH = initFovH,
            fovV = initFovV
        )

        for (spec in manifest.frames) {
            val raw = loadAssetBitmap(spec.fileName)
                ?: error("Missing frame asset: ${spec.fileName}")
            val frame = prepareStitchFrame(raw)
            val truthQuat = quaternionForExtractedPlanarView(
                spec.azimuthDeg.toDouble(),
                spec.elevationDeg.toDouble(),
                spec.rollDeg.toDouble()
            )
            val seedQuat = seedQuaternion(manifest, truthQuat, spec.index)
            submitFrameAndWait(worker, frame, seedQuat, productionReplay = true)
        }

        assertTrue("Controller must have frames", controller.hasCapturedFrames)
        waitForCaptureRefineIdle(worker)

        assertTrue("finishCapture failed", controller.finishCapture())
        waitForPreview(controller)

        waitForPreFinishComplete(worker, PRE_FINISH_TIMEOUT_MS)

        val exportW = manifest.exportEqW ?: SphereCaptureController.EQ_W
        val exportH = manifest.exportEqH ?: SphereCaptureController.EQ_H
        Log.i(TAG, "Production SAVE export at ${exportW}x${exportH}")

        val exportLatch = CountDownLatch(1)
        val polishLatch = CountDownLatch(1)
        var exportResult: SphereFinishResult? = null
        controller.onBackgroundPolishReady = { polishLatch.countDown() }
        assertTrue(
            controller.startBackgroundFullExport { result ->
                exportResult = result
                exportLatch.countDown()
            }
        )
        assertTrue(
            "SAVE export timed out after ${EXPORT_TIMEOUT_MS}ms",
            exportLatch.await(EXPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
        assertTrue(
            "Background polish timed out after ${POLISH_TIMEOUT_MS}ms",
            polishLatch.await(POLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )

        val finish = exportResult ?: error("export result null")
        assertTrue("Export equirect null", finish.equirect != null)
        assertEquals("Export width", exportW, finish.equirect!!.width)
        assertEquals("Export height", exportH, finish.equirect!!.height)
        Log.i(
            TAG,
            "Production SAVE done in ${finish.durationMs}ms; polish complete; " +
                "export=${finish.equirect!!.width}x${finish.equirect!!.height}"
        )

        val polished = controller.exportFullEquirect()
            ?: finish.equirect
            ?: error("No equirect after polish")
        val compare = equirectForCompare(polished, manifest.eqW, manifest.eqH)

        controller.release()
        worker.shutdown()
        executor.shutdown()

        return compare
    }

    private fun seedQuaternion(manifest: Manifest, truthQuat: FloatArray, frameIndex: Int): FloatArray =
        if (manifest.imuDegradeDeg > 0f) {
            degradeImuQuaternion(
                truthQuat,
                frameIndex,
                manifest.imuDegradeDeg,
                manifest.imuDegradeSeed
            )
        } else {
            truthQuat
        }

    private fun submitFrameAndWait(
        worker: SphereStitchWorker,
        frame: Bitmap,
        quat: FloatArray,
        productionReplay: Boolean = false
    ) {
        val latch = CountDownLatch(1)
        worker.submit(frame, quat, photoMode = true)
        worker.runWhenStitchIdle { latch.countDown() }
        val timeoutMs = if (productionReplay) {
            FRAME_SUBMIT_TIMEOUT_PRODUCTION_MS
        } else {
            FRAME_SUBMIT_TIMEOUT_MS
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue("Frame submit timed out", System.currentTimeMillis() < deadline)
        }
    }

    private fun waitForCaptureRefineIdle(worker: SphereStitchWorker) {
        Thread.sleep(SphereRefinePolicy.captureRefineDelayMs() + 400L)
        val deadline = SystemClock.elapsedRealtime() + 180_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (!worker.isBusy) {
                Thread.sleep(250)
                if (!worker.isBusy) return
            }
            Thread.sleep(100)
        }
        Log.w(TAG, "Capture refine idle wait timed out — continuing")
    }

    private fun waitForPreview(controller: SphereCaptureController) {
        val deadline = SystemClock.elapsedRealtime() + PREVIEW_TIMEOUT_MS
        while (
            controller.state != SphereCaptureController.State.PREVIEW &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(50)
        }
        assertEquals(
            "PREVIEW after DONE",
            SphereCaptureController.State.PREVIEW,
            controller.state
        )
    }

    private fun waitForPreFinishComplete(worker: SphereStitchWorker, timeoutMs: Long) {
        val config = SphereRefinePolicy.preFinishRefinementPasses()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isPreFinishComplete(worker, config)) {
                Log.i(TAG, "Pre-finish drain complete before SAVE")
                return
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(250)
        }
        Log.w(TAG, "Pre-finish did not complete before SAVE deadline — finish export will drain remainder")
    }

    private fun isPreFinishComplete(
        worker: SphereStitchWorker,
        config: SpherePoseRefinement.PassConfig
    ): Boolean {
        return try {
            val field = SphereStitchWorker::class.java.getDeclaredField("preFinishProgress")
            field.isAccessible = true
            val progress = field.get(worker) as SpherePoseRefinement.RefineProgress
            progress.isCompleteFor(config)
        } catch (e: ReflectiveOperationException) {
            false
        }
    }

    private fun equirectForCompare(source: Bitmap, eqW: Int, eqH: Int): Bitmap {
        if (source.width == eqW && source.height == eqH) return source
        return Bitmap.createScaledBitmap(source, eqW, eqH, true)
    }

    private fun wallBudgetMs(manifest: Manifest): Long =
        if ((manifest.productionReplay || manifest.useProductionWorker) && !manifest.fastE2eTest) {
            E2E_WALL_MS_PRODUCTION
        } else {
            E2E_WALL_MS_FAST
        }

    private fun planarPsnrGate(manifest: Manifest): Double =
        if (manifest.eqScale >= 2) MIN_PLANAR_ROUNDTRIP_PSNR_EQ2X_DB else MIN_PLANAR_ROUNDTRIP_PSNR_DB

    private fun stitchFovForManifest(manifest: Manifest, sampleFrame: Bitmap): Pair<Float, Float> {
        if (!SphereRefinePolicy.applyCenterSquareToPlanarReplay()) {
            return manifest.fovDeg to manifest.vfovDeg
        }
        return SphereStitchFramePrep.fovForCenterSquare(
            manifest.fovDeg,
            manifest.vfovDeg,
            sampleFrame.width,
            sampleFrame.height
        )
    }

    private fun prepareStitchFrame(frame: Bitmap): Bitmap {
        if (!SphereRefinePolicy.applyCenterSquareToPlanarReplay()) return frame
        return SphereStitchFramePrep.cropCenterSquare(frame)
    }

    /** Matches scripts/extract_rectilinear_views.py + nativeGetRectilinear. */
    private fun quaternionForExtractedPlanarView(
        azimuthDeg: Double,
        elevationDeg: Double,
        rollDeg: Double = 0.0
    ): FloatArray {
        val qAz = QuaternionMath.fromAxisAngle(
            0.0, 0.0, 1.0, Math.toRadians(-azimuthDeg)
        )
        val qEl = QuaternionMath.fromAxisAngle(
            1.0, 0.0, 0.0, Math.toRadians(elevationDeg)
        )
        val qLook = QuaternionMath.multiply(qAz, qEl)
        val qRoll = QuaternionMath.fromAxisAngle(
            0.0, 1.0, 0.0, Math.toRadians(rollDeg)
        )
        val qWithRoll = QuaternionMath.multiply(qLook, qRoll)
        val qPitch = QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(90.0))
        return QuaternionMath.multiply(qWithRoll, qPitch)
    }

    /**
     * Random rotation up to [degradeDeg] applied in earth frame before splat
     * (simulates IMU orientation error on the initial guess).
     */
    private fun degradeImuQuaternion(
        truth: FloatArray,
        frameIndex: Int,
        degradeDeg: Float,
        seed: Long
    ): FloatArray {
        val rng = kotlin.random.Random(seed + frameIndex * 31L)
        val u1 = rng.nextDouble()
        val u2 = rng.nextDouble()
        val u3 = rng.nextDouble()
        val angleRad = Math.toRadians(degradeDeg.toDouble() * u1)
        val z = 2.0 * u2 - 1.0
        val phi = 2.0 * Math.PI * u3
        val sinTheta = Math.sqrt(1.0 - z * z)
        val ax = sinTheta * Math.cos(phi)
        val ay = sinTheta * Math.sin(phi)
        val az = z
        val qErr = QuaternionMath.fromAxisAngle(ax, ay, az, angleRad)
        val degraded = QuaternionMath.multiply(qErr, truth)
        Log.i(
            TAG,
            "IMU degrade frame $frameIndex: up to ${degradeDeg}° draw=" +
                "${Math.toDegrees(angleRad)}°"
        )
        return degraded
    }

    private fun loadManifest(): Manifest {
        val json = testContext.assets.open("$ASSET_DIR/$MANIFEST").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val frames = obj.getJSONArray("frames")
        val list = (0 until frames.length()).map { i ->
            val f = frames.getJSONObject(i)
            FrameSpec(
                fileName = f.getString("file"),
                azimuthDeg = f.getDouble("azimuth").toFloat(),
                elevationDeg = f.optDouble("elevation", obj.optDouble("elevation", 0.0)).toFloat(),
                rollDeg = f.optDouble("roll", 0.0).toFloat(),
                index = f.optInt("index", i)
            )
        }.sortedBy { it.index }
        return Manifest(
            sourceEquirect = obj.getString("sourceEquirect"),
            referenceEquirect = obj.getString("referenceEquirect"),
            eqW = obj.getInt("eqW"),
            eqH = obj.getInt("eqH"),
            refW = obj.optInt("refW", obj.getInt("eqW")),
            refH = obj.optInt("refH", obj.getInt("eqH")),
            bandY0 = obj.optInt("bandY0", 0),
            fovDeg = obj.getDouble("fov").toFloat(),
            vfovDeg = obj.optDouble("vfov", obj.getDouble("fov")).toFloat(),
            elevationDeg = obj.getDouble("elevation").toFloat(),
            frames = list,
            eqScale = obj.optInt("eqScale", 1),
            options = ReplayOptions(
                refinePasses = obj.optInt("refinePasses", POSE_REFINE_PASSES),
                refineSeams = obj.optString("refineSeams", SEAMS_LIGHT)
            ),
            fullSphere = obj.optBoolean("fullSphere", false),
            compareFullEquirect = obj.optBoolean("compareFullEquirect", false),
            imuDegradeDeg = obj.optDouble("imuDegradeDeg", 0.0).toFloat(),
            imuDegradeSeed = obj.optLong("imuDegradeSeed", 42L),
            runCaptureRefinement = obj.optBoolean("runCaptureRefinement", false),
            useFastPath = obj.optBoolean("useFastPath", false),
            overlapGate = obj.optDouble("overlapGate", 0.10).toFloat(),
            fastE2eTest = obj.optBoolean("fastE2eTest", false),
            productionReplay = obj.optBoolean(
                "productionReplay",
                !obj.optBoolean("fastE2eTest", false)
            ),
            useProductionWorker = obj.optBoolean("useProductionWorker", false),
            exportEqW = if (obj.has("exportEqW") && !obj.isNull("exportEqW")) {
                obj.getInt("exportEqW")
            } else {
                null
            },
            exportEqH = if (obj.has("exportEqH") && !obj.isNull("exportEqH")) {
                obj.getInt("exportEqH")
            } else {
                null
            },
            skipPlanarRoundtrip = obj.optBoolean("skipPlanarRoundtrip", false)
        )
    }

    private fun loadAssetBitmap(name: String): Bitmap? {
        return try {
            testContext.assets.open("$ASSET_DIR/$name").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveOutputBitmap(bitmap: Bitmap, name: String) {
        val targets = mutableListOf<File>()
        // Internal filesDir first so scripts can pull via `adb shell run-as … cat files/…`.
        targets.add(appContext.filesDir)
        appContext.getExternalFilesDir(null)?.let { targets.add(it) }
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "visiontech_planar_roundtrip") }
            ?.let { targets.add(it) }
        Environment.getExternalStorageDirectory()
            ?.let { File(it, "visiontech_stitch_output") }
            ?.let { targets.add(it) }
        for (dir in targets) {
            try {
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                }
                Log.i(TAG, "Wrote ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write $name to ${dir.absolutePath}: $e")
            }
        }
    }

    private fun equirectPsnr(
        reference: Bitmap,
        stitched: Bitmap,
        region: SphereImageMetrics.Region
    ): Double {
        val xEnd = region.x0 + region.w
        val yEnd = region.y0 + region.h
        var mse = 0.0
        var count = 0
        for (y in region.y0 until yEnd) {
            for (x in region.x0 until xEnd) {
                val pRef = reference.getPixel(x, y)
                val pSt = stitched.getPixel(x, y)
                if (!isStitchContentPixel(pRef) && !isStitchContentPixel(pSt)) continue
                val dr = ((pRef shr 16) and 0xFF) - ((pSt shr 16) and 0xFF)
                val dg = ((pRef shr 8) and 0xFF) - ((pSt shr 8) and 0xFF)
                val db = (pRef and 0xFF) - (pSt and 0xFF)
                mse += (dr * dr + dg * dg + db * db) / 3.0
                count++
            }
        }
        if (count == 0) return 0.0
        mse /= count
        if (mse <= 1e-6) return 99.0
        return 10.0 * log10((255.0 * 255.0) / mse)
    }

    private fun isStitchContentPixel(pixel: Int): Boolean {
        if ((pixel ushr 24) == 0) return false
        return (pixel and 0x00FFFFFF) != 0x001A1A2E
    }

    private fun planarPsnr(source: Bitmap, extracted: Bitmap, centerFrac: Float = 0.6f): Double {
        val ew = extracted.width
        val eh = extracted.height
        val cx = (ew * (1f - centerFrac) / 2f).toInt()
        val cy = (eh * (1f - centerFrac) / 2f).toInt()
        val cw = (ew * centerFrac).toInt()
        val ch = (eh * centerFrac).toInt()
        var mse = 0.0
        var count = 0
        for (y in cy until cy + ch) {
            for (x in cx until cx + cw) {
                val ep = extracted.getPixel(x, y)
                val sx = ((x - cx).toFloat() / cw * source.width).toInt()
                    .coerceIn(0, source.width - 1)
                val sy = ((y - cy).toFloat() / ch * source.height).toInt()
                    .coerceIn(0, source.height - 1)
                val sp = source.getPixel(sx, sy)
                val dr = ((sp shr 16) and 0xFF) - ((ep shr 16) and 0xFF)
                val dg = ((sp shr 8) and 0xFF) - ((ep shr 8) and 0xFF)
                val db = (sp and 0xFF) - (ep and 0xFF)
                mse += (dr * dr + dg * dg + db * db) / 3.0
                count++
            }
        }
        if (count == 0) return 0.0
        mse /= count
        if (mse <= 1e-6) return 99.0
        return 10.0 * log10((255.0 * 255.0) / mse)
    }
}
