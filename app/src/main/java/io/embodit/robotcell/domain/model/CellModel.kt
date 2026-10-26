package io.embodit.robotcell.domain.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Why the spatial features cannot be used (design S20 "blocked"). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

enum class UnitPref(val tag: String) {
    CM("cm"),
    IN("in");

    companion object {
        fun fromTag(tag: String?): UnitPref = entries.firstOrNull { it.tag == tag } ?: CM
    }
}

/** Where the robot stands: on a desk top (TABLE plane) or on the floor with the automatic pedestal. */
enum class Mount(val tag: String) {
    DESK("desk"),
    FLOOR("floor");

    companion object {
        fun fromTag(tag: String?): Mount = entries.firstOrNull { it.tag == tag } ?: DESK
    }
}

/** The four preset poses (design `rules.poses`), joint angles in degrees, UR convention J1..J6. */
enum class Pose(val tag: String, val joints: FloatArray) {
    UPRIGHT("upright", floatArrayOf(0f, -90f, 0f, -90f, 0f, 0f)),
    TUCKED("tucked", floatArrayOf(0f, -60f, 155f, -15f, -90f, 0f)),
    REACH("reach", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)),
    PICK("pick", floatArrayOf(0f, -75f, 100f, -115f, -90f, 0f));

    companion object {
        fun fromTag(tag: String?): Pose? = entries.firstOrNull { it.tag == tag }

        /** The preset whose joints equal [joints] (within half a degree), or null after a manual change. */
        fun matching(joints: FloatArray): Pose? =
            entries.firstOrNull { p -> p.joints.indices.all { i -> abs(Ur5e.wrap(p.joints[i] - joints[i])) < 0.5f } }
    }
}

/** Gripper length cycle: none / 10 / 15 / 20 cm (design `rules.gripper`). */
object Gripper {
    val STEPS_M = floatArrayOf(0f, 0.10f, 0.15f, 0.20f)

    fun next(current: Float): Float {
        val i = STEPS_M.indexOfFirst { abs(it - current) < 1e-3f }
        return STEPS_M[(i + 1) % STEPS_M.size]
    }
}

/** A target pin on a desk top (design `entities3d.pin`). [position] is Stage metres on the table plane. */
data class Pin(val id: String, val position: Vec3, val tableId: String?)

/**
 * The one robot of the cell: where its base bottom centre sits (Stage metres), its mount, the yaw about
 * the vertical (radians; the reach direction at J1 = 0 is [forwardOf] the yaw), the pedestal height when
 * on the floor, the gripper length and the six joint angles (degrees).
 */
data class Robot(
    val base: Vec3,
    val mount: Mount,
    val yaw: Float,
    /** Height of the floor pedestal (0 on a desk). The arm's mount surface is `base.y + pedestalH`. */
    val pedestalH: Float,
    val gripperM: Float,
    val joints: FloatArray,
    /** Id of the desk plane the robot stands on (DESK), or the nearest desk (FLOOR); null when none. */
    val tableId: String?,
) {
    /** The surface the arm is bolted to: the desk top, or the pedestal top. */
    val mountY: Float
        get() = base.y + pedestalH

    val mountPoint: Vec3
        get() = Vec3(base.x, mountY, base.z)

    /** Shoulder point = mount surface + d1 (design `rules.reach`). */
    val shoulder: Vec3
        get() = Vec3(base.x, mountY + Ur5e.D1, base.z)

    fun reachRadius(): Float = Ur5e.WORK_RADIUS_M + gripperM

    val pose: Pose?
        get() = Pose.matching(joints)

    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    companion object {
        /** Horizontal unit vector the arm reaches along at J1 = 0 for a given yaw (local -X turned by the yaw). */
        fun forwardOf(yaw: Float): Vec3 = Vec3(-cos(yaw), 0f, sin(yaw))

        /** Yaw whose [forwardOf] is the horizontal direction [dir]. */
        fun yawFacing(dir: Vec3): Float = atan2(dir.z, -dir.x)
    }
}

data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val units: UnitPref = UnitPref.CM,
    val showSphere: Boolean = true,
    val coachSeen: Boolean = false,
)

/** What the app remembers between launches (design `persistence`). */
data class StoredCell(
    val anchorId: String?,
    /** Robot base relative to the anchor (anchor frame). */
    val offset: Vec3,
    val yaw: Float,
    val mount: Mount,
    val pedestalH: Float,
    val gripperM: Float,
    val joints: FloatArray,
    /** Pins relative to the anchor. */
    val pins: List<Vec3>,
    /** Last known Stage position of the base (used when no anchor could be made). */
    val basePos: Vec3,
)

data class StoredSession(val cell: StoredCell?, val settings: Settings)
