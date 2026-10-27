package io.embodit.robotcell.domain.model

import io.embodit.robotcell.domain.model.Ur5eKinematics.Mat4
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ur5eKinematicsTest {

    private val k = Ur5eKinematics

    private fun poseError(a: Mat4, b: Mat4): Double {
        var e = 0.0
        for (i in 0 until 12) e = maxOf(e, abs(a.m[i] - b.m[i]))
        return e
    }

    private fun randomJoints(rnd: Random): DoubleArray = DoubleArray(6) { (rnd.nextDouble() * 2 - 1) * PI }

    @Test
    fun zeroPoseFlangeMatchesUrTable() {
        // all joints zero: the arm stretched along -x, flange at x = a2 + a3, y = -(d4 + d6), z = d1 - d5
        val p = k.fk(DoubleArray(6)).position
        assertEquals(-0.8172, p[0], 1e-9)
        assertEquals(-0.2329, p[1], 1e-9)
        assertEquals(0.0628, p[2], 1e-9)
    }

    @Test
    fun uprightPoseFlangeAboveBase() {
        // UR "home" [0, -90, 0, -90, 0, 0]: the arm points straight up
        val q = k.radians(floatArrayOf(0f, -90f, 0f, -90f, 0f, 0f))
        val p = k.fk(q).position
        assertEquals(0.0, p[0], 1e-6)
        assertEquals(-(Ur5eKinematics.D4 + Ur5eKinematics.D6), p[1], 1e-6)
        assertEquals(Ur5eKinematics.D1 - Ur5eKinematics.A2 - Ur5eKinematics.A3 + Ur5eKinematics.D5, p[2], 1e-6)
    }

    @Test
    fun doubleFkMatchesFloatFkUsedByTheScene() {
        val rnd = Random(7)
        repeat(50) {
            val q = randomJoints(rnd)
            val flange = k.fromDh(k.fk(q).position)
            val frames = Ur5e.frames(k.degrees(q))
            assertTrue(flange.distanceTo(frames[6].origin) < 1e-4f)
        }
    }

    @Test
    fun ikReproducesEveryRandomPose() {
        val rnd = Random(42)
        var checked = 0
        repeat(500) {
            val q = randomJoints(rnd)
            val t = k.fk(q)
            val res = k.ik(t, q[5])
            assertNull(res.failure)
            assertTrue("expected solutions", res.solutions.isNotEmpty())
            for (s in res.solutions) {
                assertTrue("FK(IK) error", poseError(k.fk(s), t) < 1e-7)
                checked++
            }
            // the original joints are one of the solutions
            val back = res.solutions.minOf { s -> (0 until 6).maxOf { i -> abs(k.wrapPi(s[i] - q[i])) } }
            assertTrue("original among solutions: $back", back < 1e-6)
        }
        assertTrue(checked > 3000)
    }

    @Test
    fun genericPoseHasEightDistinctSolutions() {
        val q = k.radians(floatArrayOf(30f, -70f, 80f, -100f, -60f, 20f))
        val sols = k.ik(k.fk(q)).solutions
        assertEquals(8, sols.size)
        for (i in sols.indices) for (j in i + 1 until sols.size) {
            assertTrue(k.maxJointDelta(sols[i], sols[j]) > 1e-3)
        }
    }

    @Test
    fun tooFarAndInnerCylinderAreRecognised() {
        val down = Mat4.of(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.2, 0.0, 0.2))
        assertEquals(Ur5eKinematics.IkFailure.OUT_OF_REACH, k.ik(down).failure)
        // straight above the base: the wrist centre is inside the d4 cylinder around J1
        val above = Mat4.of(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(0.02, 0.02, 0.4))
        assertEquals(Ur5eKinematics.IkFailure.INNER_CYLINDER, k.ik(above).failure)
    }

    @Test
    fun determinantEqualsClosedFormSingularityProduct() {
        val rnd = Random(3)
        repeat(200) {
            val q = randomJoints(rnd)
            val det = abs(k.determinant(k.jacobian(q, 0.17)))
            val closed = k.singularity(q).manipulability
            assertEquals(closed, det, 1e-9 + 1e-6 * closed)
        }
    }

    @Test
    fun singularitiesDriveConditionNumberUp() {
        val good = k.radians(floatArrayOf(0f, -75f, 100f, -115f, -90f, 0f))
        assertTrue(k.conditionNumber(good, 0.17) < 30.0)
        // wrist: theta5 = 0 aligns J4 and J6
        val wrist = k.radians(floatArrayOf(0f, -75f, 100f, -115f, 0f, 0f))
        assertEquals(0.0, k.singularity(wrist).wrist, 1e-12)
        assertTrue(k.conditionNumber(wrist, 0.17) > 1e6)
        // elbow: theta3 = 0 is the arm stretched straight
        val elbow = k.radians(floatArrayOf(0f, -45f, 0f, -45f, -90f, 0f))
        assertEquals(0.0, k.singularity(elbow).elbow, 1e-12)
        assertTrue(k.conditionNumber(elbow, 0.17) > 1e6)
    }

    @Test
    fun nearestEquivalentStaysWithinLimitsAndNearReference() {
        val q = doubleArrayOf(3.0, -3.0, 0.5, 0.0, 1.0, -2.9)
        val ref = doubleArrayOf(-3.0, 3.0, 0.5, 6.0, 1.0, 3.3)
        val e = k.nearestEquivalent(q, ref)
        for (i in 0 until 6) {
            assertTrue(abs(e[i] - ref[i]) <= PI + 1e-9)
            assertTrue(abs(e[i]) <= Ur5eKinematics.LIMIT_RAD)
            assertEquals(0.0, k.wrapPi(e[i] - q[i]), 1e-9)
        }
    }

    @Test
    fun dhFrameMappingRoundTrips() {
        val v = Vec3(0.3f, 0.5f, -0.2f)
        val back = k.fromDh(k.toDh(v))
        assertNotNull(back)
        assertTrue(back.distanceTo(v) < 1e-6f)
    }
}
