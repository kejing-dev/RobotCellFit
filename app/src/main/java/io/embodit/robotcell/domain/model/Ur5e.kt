package io.embodit.robotcell.domain.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * UR5e geometry and forward kinematics (design `entities3d.ur5e`): the standard DH parameters of
 * Universal Robots' `default_kinematics.yaml` (d1 0.1625, a2 -0.425, a3 -0.3922, d4 0.1333, d5 0.0997,
 * d6 0.0996, alpha = [pi/2, 0, 0, pi/2, -pi/2, 0]). DH is Z-up; the robot's local frame here is Y-up:
 * DH (x, y, z) -> local (x, z, -y). At the zero pose the arm reaches along local -X.
 *
 * Checked against the design: zero pose flange at |x| 0.817, |y| 0.233, z 0.063 in the DH base frame.
 */
object Ur5e {
    const val D1 = 0.1625f
    const val A2 = -0.425f
    const val A3 = -0.3922f
    const val D4 = 0.1333f
    const val D5 = 0.0997f
    const val D6 = 0.0996f

    /** Official working radius, metres (from the shoulder point). */
    const val WORK_RADIUS_M = 0.85f

    /** Base flange diameter 149 mm; the base cylinder of the white model. */
    const val BASE_RADIUS_M = 0.0745f
    const val BASE_HEIGHT_M = 0.09f

    /** Dead zone above the base: a vertical cylinder of this radius (design `entities3d.deadZone`). */
    const val DEAD_ZONE_R = 0.15f

    private val D = floatArrayOf(D1, 0f, 0f, D4, D5, D6)
    private val A = floatArrayOf(0f, A2, A3, 0f, 0f, 0f)
    private val ALPHA = floatArrayOf((Math.PI / 2).toFloat(), 0f, 0f, (Math.PI / 2).toFloat(), (-Math.PI / 2).toFloat(), 0f)

    /** Capsule radii per segment: base, shoulder (upper arm), forearm, wrist1, wrist2, wrist3, gripper (design `entities3d.linkHit`). */
    val CAPSULE_R = floatArrayOf(0.075f, 0.06f, 0.055f, 0.045f, 0.045f, 0.045f, Gripper.BODY_R)

    /** Mesh radii of the white model's link cylinders (a little under the capsules). */
    val LINK_R = floatArrayOf(0.0745f, 0.056f, 0.048f, 0.038f, 0.038f, 0.036f)

    /** Radii and lengths of the joint drums (the darker caps on each joint axis). */
    val JOINT_R = floatArrayOf(0.0745f, 0.066f, 0.054f, 0.046f, 0.046f, 0.040f)
    val JOINT_H = floatArrayOf(0.09f, 0.13f, 0.11f, 0.095f, 0.095f, 0.08f)

    /** The design's segment names: 0 base, 1 shoulder, 2 upper arm, 3 forearm, 4 wrist 1, 5 wrist 2, 6 wrist 3 / tool. */
    val SEGMENT_NAMES = arrayOf("base", "shoulder", "upperarm", "forearm", "wrist1", "wrist2", "wrist3")

    /** One frame of the chain: origin and the three axes, in the robot's local Y-up frame. */
    class Frame(val origin: Vec3, val x: Vec3, val y: Vec3, val z: Vec3)

    /**
     * Forward kinematics. Returns the seven frames (frame 0 at the mount point, then the six DH frames)
     * in the robot's local Y-up frame, with the mount point at the origin and no yaw. Frame i's z axis
     * is the axis of joint i+1 (i < 6); frame 6's z axis is the tool direction.
     */
    fun frames(jointsDeg: FloatArray): Array<Frame> {
        // 3x4 affine, DH coordinates, row major
        var t = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        val out = ArrayList<Frame>(7)
        out += frameOf(t)
        for (i in 0 until 6) {
            val th = Math.toRadians(jointsDeg[i].toDouble())
            val ct = cos(th).toFloat()
            val st = sin(th).toFloat()
            val ca = cos(ALPHA[i])
            val sa = sin(ALPHA[i])
            val a =
                floatArrayOf(
                    ct, -st * ca, st * sa, A[i] * ct,
                    st, ct * ca, -ct * sa, A[i] * st,
                    0f, sa, ca, D[i],
                )
            t = mul(t, a)
            out += frameOf(t)
        }
        return out.toTypedArray()
    }

    /** Joint positions only (7 points): mount, shoulder, elbow, wrist 1, wrist 2, wrist 3, flange. */
    fun points(jointsDeg: FloatArray): List<Vec3> = frames(jointsDeg).map { it.origin }

    /** Tool tip: flange + gripper length along the tool axis. */
    fun tip(frames: Array<Frame>, gripperM: Float): Vec3 = frames[6].origin + frames[6].z * gripperM

    private fun frameOf(t: FloatArray): Frame =
        Frame(
            origin = toLocal(t[3], t[7], t[11]),
            x = toLocal(t[0], t[4], t[8]),
            y = toLocal(t[1], t[5], t[9]),
            z = toLocal(t[2], t[6], t[10]),
        )

    /** DH Z-up -> local Y-up. */
    private fun toLocal(x: Float, y: Float, z: Float): Vec3 = Vec3(x, z, -y)

    private fun mul(t: FloatArray, a: FloatArray): FloatArray {
        val n = FloatArray(12)
        for (r in 0 until 3) {
            for (c in 0 until 4) {
                var s = if (c == 3) t[r * 4 + 3] else 0f
                for (k in 0 until 3) s += t[r * 4 + k] * a[k * 4 + c]
                n[r * 4 + c] = s
            }
        }
        return n
    }

    /** Smallest-angle interpolation between two joint vectors (degrees). */
    fun lerpJoints(from: FloatArray, to: FloatArray, t: Float): FloatArray =
        FloatArray(6) { i -> from[i] + wrap(to[i] - from[i]) * t }

    /** Normalises an angle to -180..180. */
    fun wrap(deg: Float): Float {
        var d = deg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /** True when two joint vectors are the same to a hundredth of a degree. */
    fun same(a: FloatArray, b: FloatArray): Boolean {
        for (i in 0 until 6) if (kotlin.math.abs(wrap(a[i] - b[i])) > 0.01f) return false
        return true
    }
}
