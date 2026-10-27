package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Ur5eKinematics
import io.embodit.robotcell.domain.model.Ur5eKinematics.Mat4
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One instruction of a robot program (UR script vocabulary). Joint targets are degrees. */
sealed interface Move {
    /** Joint-space move: every joint starts and stops together (quintic time scaling). */
    data class J(val target: FloatArray) : Move

    /** Linear move of the TCP to the pose of [target] (position along a line, orientation by slerp). */
    data class L(val target: FloatArray) : Move

    /** Opens or closes the fingers to [widthM] (0 ... 0.085 m). */
    data class Grip(val widthM: Float) : Move

    data class Wait(val ms: Int) : Move
}

/**
 * A sampled program: joint angles (degrees) and finger opening every [stepMs]. When looping, playback
 * jumps from the end back to sample [loopFrom] (the program is built so that both are the same pose).
 * [fallbacks]: MoveL segments that could not be followed in Cartesian space (singularity, no solution,
 * a joint flip) and were run as MoveJ instead.
 */
class Trajectory(
    val joints: List<FloatArray>,
    val grip: FloatArray,
    val loopFrom: Int,
    val fallbacks: Int,
    val stepMs: Int = STEP_MS,
) {
    val size: Int
        get() = joints.size

    val durationMs: Long
        get() = size.toLong() * stepMs

    companion object {
        const val STEP_MS = 20
    }
}

/**
 * Turns a program into samples (design polish v0.1.2, "auto run"). MoveJ: quintic s(t) = 10t^3 - 15t^4 + 6t^5,
 * duration from the largest joint travel at [J_SPEED_DEG_S] peak. MoveL: the TCP moves on a straight line at
 * [L_SPEED_M_S] (orientation slerped), every sample solved by IK nearest the previous one; a sample with no
 * solution or a joint step over [MAX_STEP_DEG] turns the whole segment into a MoveJ. Pure JVM.
 */
class MotionPlanner(private val grasp: GraspUseCase) {

    private val k = Ur5eKinematics

    fun build(robot: Robot, start: FloatArray, startGrip: Float, moves: List<Move>, loopAt: Int = 0): Trajectory {
        val joints = ArrayList<FloatArray>()
        val grip = ArrayList<Float>()
        var cur = start.copyOf()
        var w = startGrip
        var loopFrom = 0
        var fallbacks = 0
        joints += cur.copyOf()
        grip += w
        for ((index, m) in moves.withIndex()) {
            if (index == loopAt) loopFrom = joints.size - 1
            when (m) {
                is Move.J -> cur = moveJ(cur, nearest(m.target, cur), w, joints, grip)
                is Move.L -> {
                    val path = moveL(robot, cur, m.target)
                    if (path == null) {
                        fallbacks++
                        cur = moveJ(cur, m.target, w, joints, grip)
                    } else {
                        for (q in path) {
                            joints += q
                            grip += w
                        }
                        cur = path.lastOrNull() ?: cur
                    }
                }
                is Move.Grip -> {
                    val n = max(1, GRIP_MS / Trajectory.STEP_MS)
                    val from = w
                    for (i in 1..n) {
                        joints += cur.copyOf()
                        grip += from + (m.widthM - from) * smooth(i.toFloat() / n)
                    }
                    w = m.widthM
                }
                is Move.Wait -> {
                    val n = max(1, m.ms / Trajectory.STEP_MS)
                    repeat(n) {
                        joints += cur.copyOf()
                        grip += w
                    }
                }
            }
        }
        return Trajectory(joints, grip.toFloatArray(), loopFrom, fallbacks)
    }

    /** Joint samples of a MoveJ (appended, excluding the start); returns the end pose. */
    fun moveJ(from: FloatArray, to: FloatArray, grip: Float, outJ: MutableList<FloatArray>, outG: MutableList<Float>): FloatArray {
        var maxD = 0f
        for (i in 0 until 6) maxD = maxOf(maxD, abs(to[i] - from[i]))
        // quintic peak speed = 1.875 x the average speed
        val seconds = maxOf(MIN_J_S, 1.875f * maxD / J_SPEED_DEG_S)
        val n = max(1, (seconds * 1000f / Trajectory.STEP_MS).toInt())
        for (i in 1..n) {
            val s = quintic(i.toFloat() / n)
            outJ += FloatArray(6) { j -> from[j] + (to[j] - from[j]) * s }
            outG += grip
        }
        return to.copyOf()
    }

    /** Joint samples of a MoveL (excluding the start), or null when the line cannot be followed. */
    fun moveL(robot: Robot, from: FloatArray, to: FloatArray): List<FloatArray>? {
        val a = grasp.tcpPose(robot, from)
        val b = grasp.tcpPose(robot, to)
        val pa = a.position
        val pb = b.position
        val dist = sqrt((0 until 3).sumOf { (pb[it] - pa[it]) * (pb[it] - pa[it]) })
        val qa = quatOf(a)
        val qb = quatOf(b)
        val angle = 2 * acos(abs(dot(qa, qb)).coerceAtMost(1.0))
        val seconds = maxOf(MIN_L_S, 1.875f * maxOf(dist.toFloat() / L_SPEED_M_S, Math.toDegrees(angle).toFloat() / J_SPEED_DEG_S))
        val n = max(2, (seconds * 1000f / Trajectory.STEP_MS).toInt())
        val out = ArrayList<FloatArray>(n)
        var prev = from
        for (i in 1..n) {
            val s = quintic(i.toFloat() / n).toDouble()
            val p = DoubleArray(3) { pa[it] + (pb[it] - pa[it]) * s }
            val q = slerp(qa, qb, s)
            val t = poseOf(q, p)
            val sol = grasp.solveNear(robot, t, prev, MAX_STEP_DEG) ?: return null
            out += sol
            prev = sol
        }
        // a line ending on another branch of the inverse is not the target pose
        for (j in 0 until 6) if (abs(Ur5e.wrap(prev[j] - to[j])) > END_TOLERANCE_DEG) return null
        out[out.size - 1] = nearest(to, prev)
        return out
    }

    /** The +-360 degree equivalent of [target] nearest [reference], joint by joint (a MoveJ never turns the long way). */
    fun nearest(target: FloatArray, reference: FloatArray): FloatArray =
        k.degrees(k.nearestEquivalent(k.radians(target), k.radians(reference)))

    // ------------------------------------------------------------------ programs

    /** The pin tour: for every plan pre-grasp (MoveJ), down (MoveL), close, up (MoveL), open; then back to [start]. */
    fun pinTour(robot: Robot, start: FloatArray, plans: List<PinPlan>): Trajectory {
        val moves = ArrayList<Move>()
        moves += Move.Grip(Gripper.STROKE_M)
        for (p in plans) {
            val g = p.grasp ?: continue
            val pre = p.pre
            if (pre != null) {
                moves += Move.J(pre)
                moves += Move.L(g)
            } else {
                moves += Move.J(g)
            }
            moves += Move.Grip(PIN_GRIP_M)
            moves += Move.Wait(250)
            moves += if (pre != null) Move.L(pre) else Move.J(start)
            moves += Move.Grip(Gripper.STROKE_M)
        }
        moves += Move.J(start)
        return build(robot, start, Gripper.STROKE_M, moves, loopAt = 0)
    }

    /** A taught path: to the first waypoint, then each next one by [linear] MoveL / MoveJ, and back to the first when [loop]. */
    fun path(robot: Robot, start: FloatArray, grip: Float, waypoints: List<FloatArray>, linear: Boolean, loop: Boolean): Trajectory {
        val moves = ArrayList<Move>()
        moves += Move.J(waypoints.first())
        for (wp in waypoints.drop(1)) {
            moves += Move.Wait(150)
            moves += if (linear) Move.L(wp) else Move.J(wp)
        }
        if (loop && waypoints.size > 1) {
            moves += Move.Wait(150)
            moves += if (linear) Move.L(waypoints.first()) else Move.J(waypoints.first())
        }
        // loop from the arrival at the first waypoint (after the lead-in MoveJ)
        return build(robot, start, grip, moves, loopAt = 1)
    }

    // ------------------------------------------------------------------ math

    private fun quintic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (10f + x * (-15f + 6f * x))
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

    companion object {
        const val J_SPEED_DEG_S = 60f
        const val L_SPEED_M_S = 0.25f
        const val MIN_J_S = 0.6f
        const val MIN_L_S = 0.4f
        const val GRIP_MS = 500
        const val MAX_STEP_DEG = 12f
        const val END_TOLERANCE_DEG = 1f

        /** The fingers close on the 2 cm pin head. */
        const val PIN_GRIP_M = 0.02f

        /** Rotation part of [m] as a unit quaternion (x, y, z, w). */
        fun quatOf(m: Mat4): DoubleArray {
            val m00 = m[0, 0]; val m01 = m[0, 1]; val m02 = m[0, 2]
            val m10 = m[1, 0]; val m11 = m[1, 1]; val m12 = m[1, 2]
            val m20 = m[2, 0]; val m21 = m[2, 1]; val m22 = m[2, 2]
            val tr = m00 + m11 + m22
            val q =
                if (tr > 0) {
                    val s = sqrt(tr + 1.0) * 2
                    doubleArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
                } else if (m00 > m11 && m00 > m22) {
                    val s = sqrt(1.0 + m00 - m11 - m22) * 2
                    doubleArrayOf(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                } else if (m11 > m22) {
                    val s = sqrt(1.0 + m11 - m00 - m22) * 2
                    doubleArrayOf((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
                } else {
                    val s = sqrt(1.0 + m22 - m00 - m11) * 2
                    doubleArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
                }
            val l = sqrt(q.sumOf { it * it })
            return DoubleArray(4) { q[it] / l }
        }

        fun poseOf(q: DoubleArray, p: DoubleArray): Mat4 {
            val (x, y, z, w) = listOf(q[0], q[1], q[2], q[3])
            return Mat4(
                doubleArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), p[0],
                    2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), p[1],
                    2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), p[2],
                    0.0, 0.0, 0.0, 1.0,
                )
            )
        }

        fun slerp(a: DoubleArray, b0: DoubleArray, t: Double): DoubleArray {
            var b = b0
            var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            if (d < 0) {
                b = DoubleArray(4) { -b0[it] }
                d = -d
            }
            if (d > 0.9995) {
                val r = DoubleArray(4) { a[it] + (b[it] - a[it]) * t }
                val l = sqrt(r.sumOf { it * it })
                return DoubleArray(4) { r[it] / l }
            }
            val th = acos(d)
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return DoubleArray(4) { wa * a[it] + wb * b[it] }
        }
    }
}
