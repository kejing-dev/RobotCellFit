package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Approach
import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Ur5eKinematics
import io.embodit.robotcell.domain.model.Ur5eKinematics.Mat4
import io.embodit.robotcell.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Traffic light of a pin: reachable / reachable but marginal / not reachable. */
enum class PinLevel { GREEN, AMBER, RED }

/** Why a pin got its colour; the first reason of a plan is the one shown on the pin's plate. */
enum class PinReason(val level: PinLevel) {
    OK(PinLevel.GREEN),
    // red: no usable solution
    OUT_OF_REACH(PinLevel.RED),
    INNER_VOID(PinLevel.RED),
    ORIENTATION(PinLevel.RED),
    HITS_DESK(PinLevel.RED),
    HITS_WALL(PinLevel.RED),
    SELF_HIT(PinLevel.RED),
    // amber: a solution, but close to a singularity, a joint limit, or no straight approach
    WRIST_SINGULAR(PinLevel.AMBER),
    ELBOW_STRETCHED(PinLevel.AMBER),
    SHOULDER_SINGULAR(PinLevel.AMBER),
    ILL_CONDITIONED(PinLevel.AMBER),
    NEAR_LIMIT(PinLevel.AMBER),
    NO_STRAIGHT_APPROACH(PinLevel.AMBER),
}

/**
 * The verdict for one pin. [grasp] / [pre] are joint angles in degrees (UR order J1 ... J6), [pre] the pose
 * [Grasp.APPROACH_M] back along the approach direction (null when the straight approach failed).
 * [wristDeg] = |theta5| folded to 0 ... 90 (0 = wrist singular), [elbowDeg] = |theta3| folded likewise
 * (0 = stretched), [shoulderM] = the shoulder term, [kappa] = Jacobian condition number.
 */
class PinPlan(
    val level: PinLevel,
    val reasons: List<PinReason>,
    val grasp: FloatArray?,
    val pre: FloatArray?,
    val shortByM: Float,
    val wristDeg: Float,
    val elbowDeg: Float,
    val shoulderM: Float,
    val kappa: Float,
) {
    val reason: PinReason
        get() = reasons.firstOrNull() ?: PinReason.OK

    val reachable: Boolean
        get() = level != PinLevel.RED
}

/**
 * Pin reachability with real kinematics (design polish v0.1.2): for a pin and an approach direction the
 * TCP (fingertip midpoint of the 2F-85, [Gripper.TCP_M] from the flange) is put on the pin head, the tool
 * roll about the approach axis is sampled, every closed-form IK solution is checked against the joint limits,
 * the desks and walls (the capsules of [ReachUseCase.collisions]) and the arm itself, and the best solution
 * is graded by the three singularity factors and the condition number. A straight 10 cm approach (MoveL)
 * is then tried from the chosen solution. Pure JVM.
 */
class GraspUseCase(private val reach: ReachUseCase) {

    private val k = Ur5eKinematics

    /** TCP target of a pin: the centre of its head. */
    fun target(pin: Vec3): Vec3 = pin + Vec3(0f, PIN_HEAD_Y, 0f)

    /** World approach direction (unit, the tool z axis) for [approach]. */
    fun approachDir(robot: Robot, pin: Vec3, approach: Approach): Vec3 =
        when (approach) {
            Approach.TOP -> Vec3(0f, -1f, 0f)
            Approach.SIDE -> {
                val d = Vec3(pin.x - robot.base.x, 0f, pin.z - robot.base.z)
                if (d.length < 1e-3f) Robot.forwardOf(robot.yaw) else d.normalized()
            }
        }

    /** Plans one pin. [reference] (degrees) is the configuration solutions are compared with (the "home" branch). */
    fun plan(robot: Robot, pin: Vec3, approach: Approach, planes: Collection<PlaneSurface>, reference: FloatArray = HOME): PinPlan {
        val toolZ = toolOf(robot)
        val tcp = target(pin)
        val a = approachDir(robot, pin, approach)
        val ref = k.radians(reference)
        val failures = IntArray(Ur5eKinematics.IkFailure.entries.size)
        var desk = 0
        var wall = 0
        var self = 0
        val valid = ArrayList<Candidate>()
        for (s in 0 until ROLL_STEPS) {
            val roll = 2 * PI * s / ROLL_STEPS
            val t = flangeTarget(robot, tcp, a, roll, toolZ)
            val res = k.ik(t, ref[5])
            if (res.failure != null) {
                failures[res.failure.ordinal]++
                continue
            }
            for (sol in res.solutions) {
                val q = k.nearestEquivalent(sol, ref)
                val deg = k.degrees(q)
                val check = checkPose(robot, deg, planes)
                when {
                    check.desk -> desk++
                    check.wall -> wall++
                    check.self -> self++
                    else -> valid += Candidate(q, deg, roll, amberOf(q), cost(q, ref))
                }
            }
        }
        val shortBy = reach.pinShortBy(robot, pin)
        if (valid.isEmpty()) {
            val reason =
                when {
                    desk + wall + self > 0 -> when {
                        desk >= wall && desk >= self -> PinReason.HITS_DESK
                        wall >= self -> PinReason.HITS_WALL
                        else -> PinReason.SELF_HIT
                    }
                    failures[Ur5eKinematics.IkFailure.INNER_CYLINDER.ordinal] > 0 && failures[Ur5eKinematics.IkFailure.OUT_OF_REACH.ordinal] == 0 -> PinReason.INNER_VOID
                    failures[Ur5eKinematics.IkFailure.OUT_OF_REACH.ordinal] > 0 -> PinReason.OUT_OF_REACH
                    failures[Ur5eKinematics.IkFailure.INNER_CYLINDER.ordinal] > 0 -> PinReason.INNER_VOID
                    else -> PinReason.ORIENTATION
                }
            val short = if (reason == PinReason.OUT_OF_REACH) maxOf(shortBy, wristShortBy(robot, tcp, a, toolZ)) else 0f
            return PinPlan(PinLevel.RED, listOf(reason), null, null, short, 0f, 0f, 0f, Float.POSITIVE_INFINITY)
        }
        valid.sortWith(compareBy<Candidate> { it.amber.size }.thenBy { it.cost })
        // the first candidate (up to a few) whose straight approach also works
        var chosen = valid.first()
        var pre: DoubleArray? = null
        for (c in valid.take(STRAIGHT_TRIES)) {
            if (c.amber.size > chosen.amber.size) break
            val p = straightApproach(robot, c, tcp, a, toolZ, planes)
            if (p != null) {
                chosen = c
                pre = p
                break
            }
        }
        val reasons = ArrayList(chosen.amber)
        if (pre == null) reasons += PinReason.NO_STRAIGHT_APPROACH
        val sg = k.singularity(chosen.q)
        val kappa = k.conditionNumber(chosen.q, toolZ)
        if (reasons.isEmpty() && kappa > KAPPA_AMBER) reasons += PinReason.ILL_CONDITIONED
        val level = if (reasons.isEmpty()) PinLevel.GREEN else PinLevel.AMBER
        return PinPlan(
            level = level,
            reasons = if (reasons.isEmpty()) listOf(PinReason.OK) else reasons,
            grasp = chosen.deg,
            pre = pre?.let { k.degrees(it) },
            shortByM = 0f,
            wristDeg = foldDeg(asinDeg(sg.wrist)),
            elbowDeg = foldDeg(asinDeg(sg.elbow)),
            shoulderM = abs(sg.shoulderM).toFloat(),
            kappa = kappa.toFloat(),
        )
    }

    /** The flange pose (DH base frame) that puts the TCP at [tcp] (world) with the tool z along [a] and roll [roll]. */
    fun flangeTarget(robot: Robot, tcp: Vec3, a: Vec3, roll: Double, toolZ: Double): Mat4 {
        val inv = robot.rotation.conjugate()
        val tcpDh = k.toDh(inv.rotate(tcp - robot.mountPoint))
        val z = unit(k.toDh(inv.rotate(a)))
        val e1 = unit(perpendicular(z))
        val e2 = cross(z, e1)
        val x = DoubleArray(3) { cos(roll) * e1[it] + sin(roll) * e2[it] }
        val y = cross(z, x)
        val p = DoubleArray(3) { tcpDh[it] - z[it] * toolZ }
        return Mat4.of(x, y, z, p)
    }

    /** Tool pose (TCP, DH frame) of a joint vector, for the Cartesian moves. */
    fun tcpPose(robot: Robot, deg: FloatArray): Mat4 = k.fkTcp(k.radians(deg), toolOf(robot))

    /** World TCP position of a joint vector. */
    fun tcpWorld(robot: Robot, deg: FloatArray): Vec3 {
        val p = tcpPose(robot, deg).position
        return robot.mountPoint + robot.rotation.rotate(k.fromDh(p))
    }

    /**
     * IK for a TCP pose given in the DH base frame, nearest to [near] (degrees). Null when there is no
     * solution or the nearest one is more than [maxJumpDeg] away in some joint (it would flip the arm).
     */
    fun solveNear(robot: Robot, tcp: Mat4, near: FloatArray, maxJumpDeg: Float = 360f): FloatArray? {
        val toolZ = toolOf(robot)
        val flange = tcp * k.translationZ(-toolZ)
        val ref = k.radians(near)
        val res = k.ik(flange, ref[5])
        var best: DoubleArray? = null
        var bestD = Double.MAX_VALUE
        for (s in res.solutions) {
            val q = k.nearestEquivalent(s, ref)
            val d = k.maxJointDelta(q, ref)
            if (d < bestD) {
                bestD = d
                best = q
            }
        }
        val b = best ?: return null
        if (Math.toDegrees(bestD) > maxJumpDeg) return null
        return k.degrees(b)
    }

    /** Desk / wall / self collision of a pose. */
    class PoseCheck(val desk: Boolean, val wall: Boolean, val self: Boolean) {
        val clear: Boolean
            get() = !desk && !wall && !self
    }

    fun checkPose(robot: Robot, deg: FloatArray, planes: Collection<PlaneSurface>): PoseCheck {
        val r = robot.copy(joints = deg)
        val frames = Ur5e.frames(deg)
        val hits = reach.collisions(r, frames, planes)
        return PoseCheck(
            desk = hits.any { it.kind == HitKind.DESK },
            wall = hits.any { it.kind == HitKind.WALL },
            self = selfCollides(r, frames),
        )
    }

    /**
     * The tool and the last wrist link against the base column and the upper arm (non-adjacent capsules).
     * The DH chain has no lateral link offsets, so the folded forearm itself is not checked (the real arm's
     * offsets let it pass the base).
     */
    fun selfCollides(robot: Robot, frames: Array<Ur5e.Frame>): Boolean {
        val rot = robot.rotation
        val mount = robot.mountPoint
        fun w(p: Vec3) = mount + rot.rotate(p)
        val tip = w(Ur5e.tip(frames, robot.gripperM))
        val flange = w(frames[6].origin)
        val wrist3 = w(frames[5].origin)
        val shoulder = w(frames[1].origin)
        val elbow = w(frames[2].origin)
        val movers = listOf(Triple(wrist3, flange, Ur5e.CAPSULE_R[5]), Triple(flange, tip, if (robot.gripperM > 0.005f) Gripper.BODY_R else Ur5e.CAPSULE_R[5]))
        val fixed = listOf(Triple(robot.base, shoulder, Ur5e.CAPSULE_R[0]), Triple(shoulder, elbow, Ur5e.CAPSULE_R[1]))
        for ((a0, a1, ra) in movers) for ((b0, b1, rb) in fixed) {
            if (segmentSegment(a0, a1, b0, b1) < ra + rb - SELF_SLACK_M) return true
        }
        return false
    }

    // ------------------------------------------------------------------ internals

    private class Candidate(val q: DoubleArray, val deg: FloatArray, val roll: Double, val amber: List<PinReason>, val cost: Double)

    private fun toolOf(robot: Robot): Double = robot.gripperM.toDouble()

    private fun amberOf(q: DoubleArray): List<PinReason> {
        val out = ArrayList<PinReason>(3)
        val s = k.singularity(q)
        if (abs(s.wrist) < WRIST_SIN_AMBER) out += PinReason.WRIST_SINGULAR
        // only the stretched side (|theta3| < 90): a tightly folded elbow is not the edge of the workspace
        if (abs(s.elbow) < ELBOW_SIN_AMBER && cos(q[2]) > 0.0) out += PinReason.ELBOW_STRETCHED
        if (abs(s.shoulderM) < SHOULDER_AMBER_M) out += PinReason.SHOULDER_SINGULAR
        if (q.any { abs(it) > LIMIT_AMBER_RAD }) out += PinReason.NEAR_LIMIT
        return out
    }

    /** Joint-space distance from the reference; the big joints weigh more. */
    private fun cost(q: DoubleArray, ref: DoubleArray): Double {
        var c = 0.0
        for (i in 0 until 6) c += WEIGHTS[i] * abs(q[i] - ref[i])
        return c
    }

    /** Walks [APPROACH_M] back along the approach in [APPROACH_STEPS] Cartesian steps; returns the pre-grasp joints. */
    private fun straightApproach(robot: Robot, c: Candidate, tcp: Vec3, a: Vec3, toolZ: Double, planes: Collection<PlaneSurface>): DoubleArray? {
        var prev = c.q
        for (i in 1..APPROACH_STEPS) {
            val back = tcp - a * (APPROACH_M * i / APPROACH_STEPS)
            val t = flangeTarget(robot, back, a, c.roll, toolZ)
            val res = k.ik(t, prev[5])
            var best: DoubleArray? = null
            var bestD = Double.MAX_VALUE
            for (s in res.solutions) {
                val q = k.nearestEquivalent(s, prev)
                val d = k.maxJointDelta(q, prev)
                if (d < bestD) {
                    bestD = d
                    best = q
                }
            }
            val b = best ?: return null
            if (bestD > STEP_JUMP_RAD) return null
            if (!checkPose(robot, k.degrees(b), planes).clear) return null
            prev = b
        }
        return prev
    }

    /** How far the wrist centre is beyond |a2| + |a3| from the shoulder (the reach shortfall the plate shows). */
    private fun wristShortBy(robot: Robot, tcp: Vec3, a: Vec3, toolZ: Double): Float {
        val wristCentre = tcp - a * (toolZ.toFloat() + Ur5e.D6)
        val d = wristCentre.distanceTo(robot.shoulder)
        return maxOf(0f, d - (abs(Ur5e.A2) + abs(Ur5e.A3)))
    }

    private fun asinDeg(s: Double): Float = Math.toDegrees(kotlin.math.asin(abs(s).coerceIn(0.0, 1.0))).toFloat()

    private fun foldDeg(d: Float): Float = d.coerceIn(0f, 90f)

    companion object {
        /** Pin head centre above the desk (the pin is a 6 cm stem with a 1 cm head). */
        const val PIN_HEAD_Y = 0.06f

        /** Straight approach length, metres. */
        const val APPROACH_M = 0.10f
        const val APPROACH_STEPS = 5
        const val STRAIGHT_TRIES = 6

        /** Tool roll samples about the approach axis. */
        const val ROLL_STEPS = 12

        /** sin(15 degrees): |sin theta5| below this is close to the wrist singularity (J4 and J6 aligning). */
        const val WRIST_SIN_AMBER = 0.2588

        /** sin(25 degrees): |sin theta3| below this is the arm close to straight (the edge of the workspace). */
        const val ELBOW_SIN_AMBER = 0.4226
        const val SHOULDER_AMBER_M = 0.05
        const val KAPPA_AMBER = 25.0

        /** 340 degrees: within 20 degrees of the +-360 software limit. */
        val LIMIT_AMBER_RAD = Math.toRadians(340.0)

        /** A Cartesian step of 2 cm may not need more than this in any joint. */
        val STEP_JUMP_RAD = Math.toRadians(20.0)

        const val SELF_SLACK_M = 0.01f

        val WEIGHTS = doubleArrayOf(1.0, 1.0, 1.0, 0.6, 0.6, 0.3)

        /** The configuration plans are compared with: the upright home pose, elbow up, wrist down. */
        val HOME = floatArrayOf(0f, -90f, 90f, -90f, -90f, 0f)

        fun segmentSegment(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): Float {
            val d1 = q1 - p1
            val d2 = q2 - p2
            val r = p1 - p2
            val a = d1.dot(d1)
            val e = d2.dot(d2)
            val f = d2.dot(r)
            var s: Float
            var t: Float
            if (a <= 1e-9f && e <= 1e-9f) return p1.distanceTo(p2)
            if (a <= 1e-9f) {
                s = 0f
                t = (f / e).coerceIn(0f, 1f)
            } else {
                val c = d1.dot(r)
                if (e <= 1e-9f) {
                    t = 0f
                    s = (-c / a).coerceIn(0f, 1f)
                } else {
                    val b = d1.dot(d2)
                    val denom = a * e - b * b
                    s = if (denom > 1e-9f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
                    t = (b * s + f) / e
                    if (t < 0f) {
                        t = 0f
                        s = (-c / a).coerceIn(0f, 1f)
                    } else if (t > 1f) {
                        t = 1f
                        s = ((b - c) / a).coerceIn(0f, 1f)
                    }
                }
            }
            return (p1 + d1 * s).distanceTo(p2 + d2 * t)
        }

        private fun unit(v: DoubleArray): DoubleArray {
            val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun perpendicular(z: DoubleArray): DoubleArray =
            if (abs(z[0]) < 0.9) cross(z, doubleArrayOf(1.0, 0.0, 0.0)) else cross(z, doubleArrayOf(0.0, 1.0, 0.0))
    }
}
