package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineOfSightTest {

    private val eye = Vec3(0f, 1.6f, 0f)
    private val panel = Vec3(0f, 1.6f, -0.9f)

    @Test
    fun nothingInTheWayKeepsThePanel() {
        val side = Capsule(Vec3(0.8f, 0.8f, -0.6f), Vec3(0.8f, 1.8f, -0.6f), 0.06f)
        assertEquals(1f, LineOfSight.pullFactor(eye, panel, 0.17f, listOf(side)), 0f)
        // behind the panel does not count either
        val behind = Capsule(Vec3(0f, 0.8f, -1.3f), Vec3(0f, 2f, -1.3f), 0.06f)
        assertEquals(1f, LineOfSight.pullFactor(eye, panel, 0.17f, listOf(behind)), 0f)
    }

    @Test
    fun anUprightArmBetweenEyesAndPanelPullsThePanelInFront() {
        val arm = Capsule(Vec3(0.05f, 0.8f, -0.6f), Vec3(0.05f, 1.9f, -0.6f), 0.06f)
        val f = LineOfSight.pullFactor(eye, panel, 0.17f, listOf(arm))
        val d = 0.9f * f
        // in front of the arm's near surface with the margin
        assertTrue("d=$d", d <= 0.6f - 0.06f - LineOfSight.MARGIN_M + 1e-4f)
        assertTrue(d >= LineOfSight.MIN_DIST_M - 1e-4f)
    }

    @Test
    fun anArmVeryCloseToTheEyesStopsAtTheMinimum() {
        val arm = Capsule(Vec3(0f, 1.0f, -0.3f), Vec3(0f, 2f, -0.3f), 0.06f)
        val f = LineOfSight.pullFactor(eye, panel, 0.17f, listOf(arm))
        assertEquals(LineOfSight.MIN_DIST_M / 0.9f, f, 1e-4f)
    }

    @Test
    fun virtualDeskStopsShortOfAScannedWallAhead() {
        val free = io.embodit.robotcell.domain.model.VirtualRoom.inFront(eye, Vec3.AHEAD, 0f)
        assertEquals(-0.95f, free.center.z, 1e-5f)
        // a wall 1.1 m ahead: far edge (depth 0.7) 0.25 m short of it -> centre at 1.1 - 0.35 - 0.25 = 0.5, clamped to 0.6
        val near = io.embodit.robotcell.domain.model.VirtualRoom.inFront(eye, Vec3.AHEAD, 0f, wallAheadM = 1.1f)
        assertEquals(-0.6f, near.center.z, 1e-5f)
        val mid = io.embodit.robotcell.domain.model.VirtualRoom.inFront(eye, Vec3.AHEAD, 0f, wallAheadM = 1.4f)
        assertEquals(-0.8f, mid.center.z, 1e-5f)
    }

    @Test
    fun closePlatesAreStackedApart() {
        val lifts = LineOfSight.stack(listOf(Vec3(0f, 1f, 0f), Vec3(0.05f, 1f, 0f), Vec3(0.5f, 1f, 0f), Vec3(0.02f, 1f, 0.03f)), 0.16f, 0.04f)
        assertEquals(0f, lifts[0], 0f)
        assertEquals(0.04f, lifts[1], 1e-5f)
        assertEquals(0f, lifts[2], 0f)
        assertEquals(0.08f, lifts[3], 1e-5f)
    }
}
