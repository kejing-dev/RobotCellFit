package io.embodit.robotcell.domain.usecase

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
        gripperM: Float = 0f,
    ): Robot? =
        when {
            plane.kind == SurfaceKind.TABLE && plane.horizontal -> {
                val base = reach.clampOnTable(point, plane)
                val yaw = heading(base, reach.flat(plane).centroid, head)
                Robot(base, Mount.DESK, yaw, 0f, gripperM, joints, plane.id)
            }
            plane.kind == SurfaceKind.FLOOR -> {
                val floorY = plane.maxY
                val base = reach.clampOffTables(point, floorY, planes)
                val table = reach.nearestTable(base, planes, 3f)
                val pedestal = reach.pedestalHeight(base, floorY, planes)
                val yaw = heading(base, table?.let { reach.flat(it).centroid }, head)
                Robot(base, Mount.FLOOR, yaw, pedestal, gripperM, joints, table?.id)
            }
            else -> null
        }

    /** Keeps a moved base legal for its mount (design `rules.move`). */
    fun constrain(robot: Robot, planes: Collection<PlaneSurface>): Robot {
        val table = robot.tableId?.let { id -> planes.firstOrNull { it.id == id } }
        return when (robot.mount) {
            Mount.DESK -> if (table != null) robot.copy(base = reach.clampOnTable(robot.base, table)) else robot
            Mount.FLOOR -> robot.copy(base = reach.clampOffTables(robot.base, robot.base.y, planes))
        }
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
