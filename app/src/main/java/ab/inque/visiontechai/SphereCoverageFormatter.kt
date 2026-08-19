package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage


object SphereCoverageFormatter {

    const val MIN_SOLID_ANGLE_TO_FINISH = 0.85f

    fun format(areaCoverage: Float, solidAngleCoverage: Float): String {
        val area = (areaCoverage * 100).toInt()
        val solid = (solidAngleCoverage * 100).toInt()
        return "Coverage: $area% area | $solid% sphere"
    }

    fun canFinish(solidAngleCoverage: Float): Boolean =
        solidAngleCoverage >= MIN_SOLID_ANGLE_TO_FINISH
}
