package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.SurfaceKind
import io.embodit.robotcell.domain.model.Vec3
import kotlin.math.hypot

/**
 * Where a tap (or the aiming ray) puts the robot (design `rules.place`): on a TABLE the base stays
 * inside the top; on the FLOOR a pedestal is added and the base is kept clear of every desk. The
 * default heading points the arm's reach direction at the desk's centre (the nearest desk when on the
 * floor); with no desk the robot faces away from the user. Anything else (walls, the air) is refused.
 */
class PlaceRobotUseCase(private val reach: ReachUseCase) {

    fun place(
        plane: PlaneSurface,
        point: Vec3,
        planes: Collection<PlaneSurface>,
        head: Vec3?,
        joints: FloatArray = Pose.UPRIGHT.joints.copyOf(),
        gripperM: Float = Gripper.TCP_M,
    ): Robot? =
        when {
            plane.kind == SurfaceKind.TABLE && plane.horizontal -> {
                val base = clearOnTable(point, plane, head)
                val yaw = heading(base, reach.flat(plane).centroid, head)
                Robot(base, Mount.DESK, yaw, 0f, gripperM, joints, plane.id)
            }
            plane.kind == SurfaceKind.FLOOR -> {
                val floorY = plane.maxY
                val base = clearOffTables(point, floorY, planes, head)
                val table = reach.nearestTable(base, planes, 3f)
                val pedestal = reach.pedestalHeight(base, floorY, planes)
                val yaw = heading(base, table?.let { reach.flat(it).centroid }, head)
                Robot(base, Mount.FLOOR, yaw, pedestal, gripperM, joints, table?.id)
            }
            else -> null
        }

    /** Keeps a moved base legal for its mount (design `rules.move`) and, with [head], clear of the user (D14). */
    fun constrain(robot: Robot, planes: Collection<PlaneSurface>, head: Vec3? = null): Robot {
        val table = robot.tableId?.let { id -> planes.firstOrNull { it.id == id } }
        return when (robot.mount) {
            Mount.DESK -> if (table != null) robot.copy(base = clearOnTable(robot.base, table, head)) else robot
            Mount.FLOOR -> robot.copy(base = clearOffTables(robot.base, robot.base.y, planes, head))
        }
    }

    /**
     * Factory rule D14 (nothing large stands in the user's face): the base is pushed horizontally away from the
     * head to [HEAD_CLEAR_M] (a UR5e with its reach is a large object). Null head: unchanged.
     */
    fun clearOfHead(p: Vec3, head: Vec3?, min: Float = HEAD_CLEAR_M): Vec3 {
        if (head == null) return p
        val d = Vec3(p.x - head.x, 0f, p.z - head.z)
        val l = d.length
        if (l >= min) return p
        val dir = if (l < 1e-3f) Vec3.AHEAD else d / l
        return Vec3(head.x + dir.x * min, p.y, head.z + dir.z * min)
    }

    fun headClearance(p: Vec3, head: Vec3?): Float = if (head == null) Float.MAX_VALUE else hypot(p.x - head.x, p.z - head.z)

    /** On a desk: push out, keep inside the top; a desk too near the user gets the point of it farthest out along the push. */
    private fun clearOnTable(point: Vec3, table: PlaneSurface, head: Vec3?): Vec3 {
        var best = reach.clampOnTable(clearOfHead(point, head), table)
        var k = 1
        while (headClearance(best, head) < HEAD_CLEAR_M - 1e-3f && k <= PUSH_STEPS) {
            val tryP = reach.clampOnTable(clearOfHead(point, head, HEAD_CLEAR_M + PUSH_STEP_M * k), table)
            if (headClearance(tryP, head) > headClearance(best, head)) best = tryP
            k++
        }
        return best
    }

    /** On the floor: push out, then keep the pedestal off the desks (and push again if that brought it back in). */
    private fun clearOffTables(point: Vec3, floorY: Float, planes: Collection<PlaneSurface>, head: Vec3?): Vec3 {
        var p = reach.clampOffTables(clearOfHead(point, head), floorY, planes)
        var k = 1
        while (headClearance(p, head) < HEAD_CLEAR_M - 1e-3f && k <= PUSH_STEPS) {
            p = reach.clampOffTables(clearOfHead(point, head, HEAD_CLEAR_M + PUSH_STEP_M * k), floorY, planes)
            k++
        }
        return p
    }

    companion object {
        /** Factory rule D14: large objects stay at least this far from the head, horizontally. */
        const val HEAD_CLEAR_M = 0.8f
        private const val PUSH_STEP_M = 0.1f
        private const val PUSH_STEPS = 8
    }

    private fun heading(base: Vec3, target: Vec3?, head: Vec3?): Float {
        if (target != null) {
            val d = Vec3(target.x - base.x, 0f, target.z - base.z)
            if (hypot(d.x, d.z) > 0.05f) return Robot.yawFacing(d.normalized())
        }
        if (head != null) {
            val d = Vec3(base.x - head.x, 0f, base.z - head.z)
            if (hypot(d.x, d.z) > 0.05f) return Robot.yawFacing(d.normalized())
        }
        return Robot.yawFacing(Vec3.AHEAD)
    }
}
