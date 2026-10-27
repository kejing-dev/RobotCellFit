package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.model.VirtualRoom
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Factory rule D14: the robot never stands in the user's face (base >= 0.8 m from the head, horizontally). */
class PlaceRobotClearanceTest {

    private val reach = ReachUseCase()
    private val place = PlaceRobotUseCase(reach)
    private val head = Vec3(0f, 1.6f, 0f)

    /** 1.6 x 0.8 desk whose near edge is 0.3 m in front of the user, floor at 0. */
    private val room = VirtualRoom(Vec3(0f, 0f, -0.7f), Vec3.AHEAD, 1.6f, 0.8f, 0.75f, 0f, withFloor = true)
    private val planes = room.planes()

    private fun flat(p: Vec3) = hypot(p.x - head.x, p.z - head.z)

    @Test
    fun aTapAtTheUsersFeetIsPushedOut() {
        val r = place.place(room.floor(), Vec3(0.1f, 0f, 0.05f), planes, head)
        assertNotNull(r)
        assertEquals(Mount.FLOOR, r!!.mount)
        assertTrue("clearance ${flat(r.base)}", flat(r.base) >= PlaceRobotUseCase.HEAD_CLEAR_M - 1e-3f)
    }

    @Test
    fun theNearEdgeOfADeskBecomesAPointFartherOnTheDesk() {
        val r = place.place(room.table(), Vec3(0f, 0.75f, -0.35f), planes, head)!!
        assertEquals(Mount.DESK, r.mount)
        assertTrue("clearance ${flat(r.base)}", flat(r.base) >= PlaceRobotUseCase.HEAD_CLEAR_M - 1e-3f)
        val f = reach.flat(room.table())
        assertTrue(f.contains(f.to2(r.base), Ur5eMargin))
    }

    @Test
    fun draggingTowardsTheUserStopsAtTheClearance() {
        val r = place.place(room.table(), Vec3(0f, 0.75f, -0.9f), planes, head)!!
        val dragged = place.constrain(r.copy(base = Vec3(0f, 0.75f, -0.4f)), planes, head)
        assertTrue("clearance ${flat(dragged.base)}", flat(dragged.base) >= PlaceRobotUseCase.HEAD_CLEAR_M - 1e-3f)
    }

    @Test
    fun farPointsAreLeftAlone() {
        val r = place.place(room.table(), Vec3(0.3f, 0.75f, -0.95f), planes, head)!!
        assertEquals(0.3f, r.base.x, 1e-4f)
        assertEquals(-0.95f, r.base.z, 1e-4f)
    }

    private companion object {
        const val Ur5eMargin = 0.07f
    }
}
