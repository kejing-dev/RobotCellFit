package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Approach
import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.SurfaceKind
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Ur5eKinematics
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.model.VirtualRoom
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraspUseCaseTest {

    private val reach = ReachUseCase()
    private val grasp = GraspUseCase(reach)
    private val motion = MotionPlanner(grasp)

    /** A 1.6 x 0.8 desk 0.75 m high, floor at 0, wall 1.3 m ahead of the desk centre. */
    private val room = VirtualRoom(Vec3(0f, 0f, 0f), Vec3.AHEAD, 1.6f, 0.8f, 0.75f, 0f, withFloor = true)
    private val planes = room.planes()

    /** The UR5e in the middle of the desk, reaching towards -z (ahead). */
    private val robot = Robot(Vec3(0f, 0.75f, 0f), Mount.DESK, -Math.PI.toFloat() / 2f, 0f, Gripper.TCP_M, Pose.UPRIGHT.joints.copyOf(), VirtualRoom.TABLE_ID)

    private fun pin(x: Float, z: Float) = Vec3(x, 0.75f, z)

    @Test
    fun robotFacesAhead() {
        val f = Robot.forwardOf(robot.yaw)
        assertTrue(f.distanceTo(Vec3.AHEAD) < 1e-5f)
    }

    @Test
    fun pinInFrontIsGreenAndTheGraspPutsTheTcpOnThePinHead() {
        val p = pin(0.1f, -0.45f)
        val plan = grasp.plan(robot, p, Approach.TOP, planes)
        assertEquals(plan.reasons.toString(), PinLevel.GREEN, plan.level)
        val g = assertNotNullAnd(plan.grasp)
        val tcp = grasp.tcpWorld(robot, g)
        assertTrue("tcp $tcp", tcp.distanceTo(grasp.target(p)) < 0.001f)
        // the tool points straight down
        val z = Ur5e.frames(g)[6].z
        val worldZ = robot.rotation.rotate(z)
        assertTrue("tool z $worldZ", worldZ.dot(Vec3(0f, -1f, 0f)) > 0.999f)
        // the straight approach starts 10 cm above
        val pre = assertNotNullAnd(plan.pre)
        assertTrue(grasp.tcpWorld(robot, pre).distanceTo(grasp.target(p) + Vec3(0f, GraspUseCase.APPROACH_M, 0f)) < 0.001f)
        assertTrue(grasp.checkPose(robot, g, planes).clear)
        assertTrue(plan.kappa.isFinite())
    }

    @Test
    fun sideApproachPointsTheToolAwayFromTheBase() {
        val p = pin(-0.2f, -0.5f)
        val plan = grasp.plan(robot, p, Approach.SIDE, planes)
        assertTrue(plan.reasons.toString(), plan.reachable)
        val worldZ = robot.rotation.rotate(Ur5e.frames(plan.grasp!!)[6].z)
        val expected = Vec3(p.x, 0f, p.z).normalized()
        assertTrue(worldZ.dot(expected) > 0.999f)
    }

    @Test
    fun farPinIsOutOfReachWithAShortfall() {
        val plan = grasp.plan(robot, pin(0f, -1.25f), Approach.TOP, planes)
        assertEquals(PinLevel.RED, plan.level)
        assertEquals(PinReason.OUT_OF_REACH, plan.reason)
        assertTrue("short ${plan.shortByM}", plan.shortByM > 0.1f)
        assertNull(plan.grasp)
    }

    @Test
    fun pinNextToTheBaseIsInTheInnerVoid() {
        val plan = grasp.plan(robot, pin(0.03f, -0.05f), Approach.TOP, planes)
        assertEquals(PinLevel.RED, plan.level)
        assertEquals(PinReason.INNER_VOID, plan.reason)
    }

    @Test
    fun pinBehindAWallIsBlockedByTheWall() {
        val wall = PlaneSurface("w", SurfaceKind.WALL, "WALL", listOf(Vec3(-2f, 0f, -0.40f), Vec3(2f, 0f, -0.40f), Vec3(2f, 2.5f, -0.40f), Vec3(-2f, 2.5f, -0.40f)), listOf(0, 1, 2, 0, 2, 3))
        val withWall = listOf(room.table(), room.floor(), wall)
        val plan = grasp.plan(robot, pin(0f, -0.55f), Approach.TOP, withWall)
        assertEquals(PinLevel.RED, plan.level)
        assertEquals(PinReason.HITS_WALL, plan.reason)
        // the same pin without the wall is fine
        assertTrue(grasp.plan(robot, pin(0f, -0.55f), Approach.TOP, listOf(room.table(), room.floor())).reachable)
    }

    @Test
    fun radialSweepGoesGreenThenRedWithAStretchedElbowInBetween() {
        val levels = ArrayList<Pair<Float, PinPlan>>()
        var d = 0.25f
        while (d <= 1.2f) {
            levels += d to grasp.plan(robot, pin(0f, -d), Approach.TOP, planes)
            d += 0.025f
        }
        for ((dist, p) in levels) println("sweep d=%.3f %s %s kappa=%.1f wrist=%.0f elbow=%.0f shoulder=%.3f".format(dist, p.level, p.reasons, p.kappa, p.wristDeg, p.elbowDeg, p.shoulderM))
        val lastGreen = levels.last { it.second.level == PinLevel.GREEN }.first
        val firstRed = levels.first { it.first > 0.3f && it.second.level == PinLevel.RED }.first
        assertTrue("last green $lastGreen", lastGreen in 0.55f..0.9f)
        assertTrue("first red $firstRed", firstRed > lastGreen && firstRed < 1.05f)
        // everything past the first red stays red; the band just before it is marginal for a reason
        assertTrue(levels.filter { it.first >= firstRed }.all { it.second.level == PinLevel.RED })
        val amber = levels.filter { it.first > lastGreen && it.first < firstRed }.flatMap { it.second.reasons }
        assertTrue("amber $amber", amber.all { it.level == PinLevel.AMBER })
        if (amber.isNotEmpty()) assertTrue(amber.toString(), PinReason.ELBOW_STRETCHED in amber || PinReason.ILL_CONDITIONED in amber || PinReason.NO_STRAIGHT_APPROACH in amber)
    }

    @Test
    fun tourIsContinuousGraspsEveryPinAndReturns() {
        val pins = listOf(pin(0.25f, -0.4f), pin(-0.3f, -0.35f))
        val plans = pins.map { grasp.plan(robot, it, Approach.TOP, planes) }
        assertTrue(plans.all { it.level == PinLevel.GREEN })
        val start = Pose.UPRIGHT.joints
        val t = motion.pinTour(robot, start, plans)
        assertEquals(0, t.fallbacks)
        assertTrue(t.size > 100)
        for (i in 1 until t.size) {
            for (j in 0 until 6) assertTrue("step $i joint $j", abs(t.joints[i][j] - t.joints[i - 1][j]) < MotionPlanner.MAX_STEP_DEG)
        }
        for (p in pins) {
            val best = t.joints.minOf { grasp.tcpWorld(robot, it).distanceTo(grasp.target(p)) }
            assertTrue("reached $best", best < 0.001f)
        }
        assertTrue(t.grip.any { abs(it - MotionPlanner.PIN_GRIP_M) < 1e-4f })
        assertTrue(Ur5e.same(t.joints.last(), start))
        assertTrue(abs(t.grip.last() - Gripper.STROKE_M) < 1e-4f)
    }

    @Test
    fun moveLKeepsTheTcpOnTheLine() {
        val plan = grasp.plan(robot, pin(0.1f, -0.45f), Approach.TOP, planes)
        val from = plan.pre!!
        val to = plan.grasp!!
        val path = assertNotNullAnd(motion.moveL(robot, from, to))
        val a = grasp.tcpWorld(robot, from)
        val b = grasp.tcpWorld(robot, to)
        for (q in path) {
            val p = grasp.tcpWorld(robot, q)
            assertTrue(ReachUseCase.segmentDistance(p, a, b) < 0.001f)
        }
    }

    @Test
    fun taughtPathLoopsBackToItsFirstWaypoint() {
        val wps = listOf(Pose.PICK.joints, Pose.TUCKED.joints, floatArrayOf(60f, -75f, 100f, -115f, -90f, 0f))
        val t = motion.path(robot, Pose.UPRIGHT.joints, Gripper.STROKE_M, wps, linear = false, loop = true)
        assertTrue(Ur5e.same(t.joints[t.loopFrom], wps.first()))
        assertTrue(Ur5e.same(t.joints.last(), wps.first()))
    }

    @Test
    fun segmentDistanceOfCrossingAndParallelSegments() {
        assertEquals(0f, GraspUseCase.segmentSegment(Vec3(-1f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 1f, 0f)), 1e-6f)
        assertEquals(0.5f, GraspUseCase.segmentSegment(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0.5f, 0f), Vec3(1f, 0.5f, 0f)), 1e-6f)
        assertEquals(1f, GraspUseCase.segmentSegment(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(3f, 0f, 0f)), 1e-6f)
    }

    @Test
    fun virtualRoomPlanesHaveTheRightShape() {
        val t = room.table()
        assertTrue(t.horizontal)
        assertEquals(0.75f, t.maxY, 1e-6f)
        assertEquals(1.6f * 0.8f, reach.flat(t).area, 1e-3f)
        val w = room.wall()
        assertTrue(!w.horizontal)
        assertEquals(1.3f, abs(w.centroid.z), 1e-5f)
        assertTrue(reach.wallVerdict(robot, planes) != null)
        assertTrue(VirtualRoom.isVirtual(t.id))
        val moved = room.moved(Vec3(0.3f, 5f, 0f))
        assertEquals(0.3f, moved.table().centroid.x, 1e-5f)
        assertEquals(0.75f, moved.table().maxY, 1e-6f)
    }

    @Test
    fun planIsFastEnoughForTheMainThread() {
        val t0 = System.nanoTime()
        repeat(8) { i -> grasp.plan(robot, pin(-0.5f + 0.14f * i, -0.5f), Approach.TOP, planes) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("8 plans ms=$ms")
        assertTrue("8 plans took $ms ms", ms < 400)
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    @Suppress("unused")
    private val k = Ur5eKinematics
}
