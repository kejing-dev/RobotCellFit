package io.embodit.robotcell.ui.cell.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import io.embodit.robotcell.domain.model.Quat4
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.model.VirtualRoom

/**
 * What the virtual room looks like (factory rule D7): a see-through desk top with a solid rim and four legs,
 * a faint wall behind it and, when no floor was scanned, a faint floor. The tap / drag targets are the
 * plane colliders the tracker's virtual planes get, like scanned planes. Built from the shared unit meshes.
 */
internal class VirtualRoomView(parent: Entity, private val m: SceneMaterials, private val meshes: RobotMeshes) {
    val root: Entity = Entity().named("vroom")
    private val top = ModelEntity(meshes.box, m.vDesk).named("vroomTop")
    private val rims = List(4) { ModelEntity(meshes.box, m.vDeskEdge).named("vroomRim$it") }
    private val legs = List(4) { ModelEntity(meshes.cylinder, m.vDeskEdge).named("vroomLeg$it") }
    private val wall = ModelEntity(meshes.box, m.vWall).named("vroomWall")
    private val floor = ModelEntity(meshes.box, m.vFloor).named("vroomFloor")
    private var shown: VirtualRoom? = null

    init {
        parent.addChild(root)
        root.addChild(top)
        rims.forEach { root.addChild(it) }
        legs.forEach { root.addChild(it) }
        root.addChild(wall)
        root.addChild(floor)
        root.enabled = false
    }

    fun set(room: VirtualRoom?) {
        if (room == shown) return
        shown = room
        if (room == null) {
            root.enabled = false
            return
        }
        root.enabled = true
        val f = room.forward
        val r = room.right
        val rot = Quat4.fromBasis(r, Vec3.UP, r.cross(Vec3.UP).normalized())
        val c = room.deskCenter
        top.setLocal(position = c - Vec3(0f, TOP_H / 2f, 0f), rotation = rot, scale = Vec3(room.width, TOP_H, room.depth))
        // rim: front / back along the width, left / right along the depth
        val y = c - Vec3(0f, TOP_H / 2f, 0f)
        rims[0].setLocal(position = y + f * (room.depth / 2f), rotation = rot, scale = Vec3(room.width + RIM, TOP_H + 0.002f, RIM))
        rims[1].setLocal(position = y - f * (room.depth / 2f), rotation = rot, scale = Vec3(room.width + RIM, TOP_H + 0.002f, RIM))
        rims[2].setLocal(position = y + r * (room.width / 2f), rotation = rot, scale = Vec3(RIM, TOP_H + 0.002f, room.depth + RIM))
        rims[3].setLocal(position = y - r * (room.width / 2f), rotation = rot, scale = Vec3(RIM, TOP_H + 0.002f, room.depth + RIM))
        val legH = (room.height - TOP_H).coerceAtLeast(0.05f)
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        for ((i, k) in corners.withIndex()) {
            val p = Vec3(c.x, room.floorY, c.z) + r * (k.first * (room.width / 2f - LEG_INSET)) + f * (k.second * (room.depth / 2f - LEG_INSET))
            legs[i].setLocal(position = p, rotation = Quat4.IDENTITY, scale = Vec3(LEG_R, legH, LEG_R))
        }
        val w = room.wall()
        wall.setLocal(position = w.centroid, rotation = rot, scale = Vec3(VirtualRoom.WALL_HALF_W_M * 2f, VirtualRoom.WALL_H_M, 0.01f))
        floor.enabled = room.withFloor
        if (room.withFloor) floor.setLocal(position = Vec3(c.x, room.floorY - 0.002f, c.z), rotation = rot, scale = Vec3(VirtualRoom.FLOOR_HALF_M * 2f, 0.002f, VirtualRoom.FLOOR_HALF_M * 2f))
    }

    fun destroy() = runCatching { root.destroy(true) }

    private companion object {
        const val TOP_H = 0.03f
        const val RIM = 0.012f
        const val LEG_R = 0.02f
        const val LEG_INSET = 0.06f
    }
}
