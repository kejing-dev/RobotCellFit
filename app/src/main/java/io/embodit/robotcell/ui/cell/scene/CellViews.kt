package io.embodit.robotcell.ui.cell.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.robotcell.domain.model.Quat4
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.usecase.DiskGeometry
import io.embodit.robotcell.domain.usecase.PinLevel
import io.embodit.robotcell.domain.usecase.WallRing
import io.embodit.robotcell.ui.cell.PinUi
import kotlin.math.floor

/** The reach sphere: one ribbon mesh scaled to the radius (design `entities3d.reachSphere`). */
internal class ReachSphereView(parent: Entity, m: SceneMaterials, mesh: MeshResource) {
    private val entity: Entity = ModelEntity(mesh, m.sphere).named("reachSphere")
    private var on = false

    init {
        parent.addChild(entity)
        entity.enabled = false
    }

    fun set(center: Vec3, radius: Float, shown: Boolean) {
        if (shown) entity.setLocal(position = center, scale = Vec3(radius, radius, radius))
        if (shown != on) {
            on = shown
            entity.enabled = shown
        }
    }

    fun setMaterial(material: Material) = entity.setMaterial(material)

    fun destroy() = runCatching { entity.destroy(true) }
}

/** Work disks on the desks and orange rings on the walls: rebuilt when the geometry changes. */
internal class ReachMarks(
    private val parent: Entity,
    private val m: SceneMaterials,
    /** Queues a mesh rebuild under a key (a newer rebuild with the same key replaces a pending one). */
    private val enqueue: (key: String, work: () -> Unit) -> Unit,
) {
    private val diskFill = ArrayList<TriMesh>()
    private val diskEdge = ArrayList<TriMesh>()
    private val ringFill = ArrayList<TriMesh>()
    private val ringEdge = ArrayList<TriMesh>()
    private var lastDisks: List<DiskGeometry>? = null
    private var lastRings: List<WallRing>? = null
    private var shown = true
    private val holder: Entity = Entity().named("reachMarks")

    init {
        parent.addChild(holder)
    }

    fun setDisks(disks: List<DiskGeometry>) {
        if (disks === lastDisks) return
        lastDisks = disks
        resize(diskFill, disks.size, m.diskFill, "diskFill")
        resize(diskEdge, disks.size, m.diskEdge, "diskEdge")
        for ((i, d) in disks.withIndex()) {
            val fill = diskFill[i]
            val edge = diskEdge[i]
            enqueue("diskFill$i") { fill.set(d.fill, d.normal) }
            enqueue("diskEdge$i") { edge.set(d.edge, d.normal) }
        }
    }

    fun setRings(rings: List<WallRing>) {
        if (rings === lastRings) return
        lastRings = rings
        resize(ringFill, rings.size, m.ringFill, "ringFill")
        resize(ringEdge, rings.size, m.ringEdge, "ringEdge")
        for ((i, r) in rings.withIndex()) {
            val fill = ringFill[i]
            val edge = ringEdge[i]
            enqueue("ringFill$i") { fill.set(r.fill, r.normal) }
            enqueue("ringEdge$i") { edge.set(r.edge, r.normal) }
        }
    }

    fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        holder.enabled = value
    }

    private fun resize(list: ArrayList<TriMesh>, n: Int, material: Material, name: String) {
        while (list.size > n) list.removeAt(list.size - 1).clear()
        while (list.size < n) list += TriMesh(holder, material, "$name${list.size}")
    }

    fun clear() {
        for (l in listOf(diskFill, diskEdge, ringFill, ringEdge)) {
            l.forEach { it.clear() }
            l.clear()
        }
        lastDisks = null
        lastRings = null
    }

    fun destroy() {
        clear()
        runCatching { holder.destroy(true) }
    }
}

/** The dashed dead-zone circle on the mount surface and the floor pedestal. */
internal class BaseMarks(parent: Entity, m: SceneMaterials, meshes: BaseMeshes) {
    private val dead: Entity = ModelEntity(meshes.dead, m.deadZone).named("deadZone")
    private val body: Entity = ModelEntity(meshes.body, m.pedestal).named("pedestal")
    private val stripes: Entity = ModelEntity(meshes.stripes, m.stripe).named("stripes")
    private val snap: Entity = ModelEntity(meshes.snap, m.snap).named("snapRing")
    private var deadOn = false
    private var pedestalOn = false
    private var snapOn = false

    init {
        for (e in listOf(dead, body, stripes, snap)) {
            parent.addChild(e)
            e.enabled = false
        }
    }

    fun setDeadZone(mount: Vec3?, yaw: Float) {
        val on = mount != null
        if (on) dead.setLocal(position = mount!! + Vec3(0f, 0.004f, 0f), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        if (on != deadOn) {
            deadOn = on
            dead.enabled = on
        }
    }

    /** The pedestal under a floor-mounted robot: [base] on the floor, [height] up to the mount surface. */
    fun setPedestal(base: Vec3?, height: Float, yaw: Float) {
        val on = base != null && height > 0.01f
        if (on) {
            val rot = Quat4.axisAngle(Vec3.UP, yaw)
            body.setLocal(position = base!! + Vec3(0f, height / 2f, 0f), rotation = rot, scale = Vec3(1f, height, 1f))
            stripes.setLocal(position = base + Vec3(0f, height - 0.03f, 0f), rotation = rot)
        }
        if (on != pedestalOn) {
            pedestalOn = on
            body.enabled = on
            stripes.enabled = on
        }
    }

    /** The snap frame shown while the stick turns the robot: a dashed ring that turns with the 15 degree steps. */
    fun setSnap(mount: Vec3?, yaw: Float) {
        val on = mount != null
        if (on) snap.setLocal(position = mount!! + Vec3(0f, 0.006f, 0f), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        if (on != snapOn) {
            snapOn = on
            snap.enabled = on
        }
    }

    fun destroy() {
        for (e in listOf(dead, body, stripes, snap)) runCatching { e.destroy(true) }
    }
}

/** The landing ring under the placement ghost: green where a tap would place, grey elsewhere. */
internal class LandingRing(parent: Entity, private val m: SceneMaterials, mesh: MeshResource) {
    private val entity: Entity = ModelEntity(mesh, m.landOk).named("landRing")
    private var on = false
    private var ok = true

    init {
        parent.addChild(entity)
        entity.enabled = false
    }

    fun set(at: Vec3?, normal: Vec3, good: Boolean) {
        val shown = at != null
        if (shown) {
            entity.setLocal(position = at!! + normal * 0.004f, rotation = Quat4.fromTo(Vec3.UP, normal))
            if (good != ok) {
                ok = good
                entity.setMaterial(if (good) m.landOk else m.landBad)
            }
        }
        if (shown != on) {
            on = shown
            entity.enabled = shown
        }
    }

    fun destroy() = runCatching { entity.destroy(true) }
}

/** Up to eight target pins (design `entities3d.pin`): stem, head, a 6 cm tap box. Head colour = the kinematic verdict (v0.1.2). */
internal class PinViews(private val parent: Entity, private val m: SceneMaterials, private val stemMesh: MeshResource, private val headMesh: MeshResource) {
    private class PinEntity(val root: Entity, val head: Entity, var id: String?, var level: PinLevel?) {
        var position: Vec3 = Vec3.ZERO
        var selected = false
    }

    private val pool = ArrayList<PinEntity>()
    private val idByEntity = HashMap<Long, String>()

    fun prepare() {
        while (pool.size < MAX) {
            val root = Entity().named("pin${pool.size}")
            val stem = ModelEntity(stemMesh, m.white).named("pinStem${pool.size}")
            root.addChild(stem)
            val head = ModelEntity(headMesh, m.pinOk).named("pinHead${pool.size}")
            head.setLocal(position = Vec3(0f, STEM_H, 0f), scale = Vec3(HEAD_R, HEAD_R, HEAD_R))
            root.addChild(head)
            val tap = Entity().named("pinTap${pool.size}")
            tap.tapBox(Vec3(0.06f, 0.08f, 0.06f), Vec3(0f, 0.04f, 0f))
            root.addChild(tap)
            parent.addChild(root)
            root.enabled = false
            pool += PinEntity(root, head, null, null)
            idByEntity[tap.id] = "slot${pool.size - 1}"
            idByEntity[head.id] = "slot${pool.size - 1}"
            idByEntity[stem.id] = "slot${pool.size - 1}"
        }
    }

    fun sync(pins: List<PinUi>, selectedId: String?) {
        for ((i, slot) in pool.withIndex()) {
            val pin = pins.getOrNull(i)
            if (pin == null) {
                if (slot.id != null) {
                    slot.id = null
                    slot.root.enabled = false
                }
                continue
            }
            slot.root.setLocal(position = pin.position)
            slot.position = pin.position
            if (slot.id == null) slot.root.enabled = true
            slot.id = pin.id
            if (slot.level != pin.level) {
                slot.level = pin.level
                slot.head.setMaterial(materialOf(pin.level))
            }
            val sel = pin.id == selectedId
            if (slot.selected != sel) {
                slot.selected = sel
                val r = if (sel) HEAD_R * 1.6f else HEAD_R
                slot.head.setLocal(position = Vec3(0f, STEM_H, 0f), scale = Vec3(r, r, r))
            }
        }
    }

    /** The pin id for a tapped entity, or null. */
    fun pinIdOf(entity: Entity): String? {
        val slotName = idByEntity[entity.id] ?: return null
        val index = slotName.removePrefix("slot").toIntOrNull() ?: return null
        return pool.getOrNull(index)?.id
    }

    /** Head position of a pin (for its plate). */
    fun headOf(pinIndex: Int): Vec3? {
        val slot = pool.getOrNull(pinIndex) ?: return null
        if (slot.id == null) return null
        return slot.position + Vec3(0f, STEM_H, 0f)
    }

    fun setMaterials(stale: Boolean) {
        for (slot in pool) {
            if (slot.id == null) continue
            slot.head.setMaterial(if (stale) m.stale else materialOf(slot.level ?: PinLevel.RED))
        }
    }

    private fun materialOf(level: PinLevel) =
        when (level) {
            PinLevel.GREEN -> m.pinOk
            PinLevel.AMBER -> m.pinWarn
            PinLevel.RED -> m.pinBad
        }

    fun destroy() {
        pool.forEach { runCatching { it.root.destroy(true) } }
        pool.clear()
        idByEntity.clear()
    }

    companion object {
        const val MAX = 8
        const val STEM_H = 0.06f
        const val HEAD_R = 0.01f
    }
}

/**
 * A measuring tape between two Stage points: a band with 10 cm tick marks, turned to face a normal
 * (design `entities3d.clearTape` / `edgeTapes`, taken from the Spatial Ruler's tape).
 */
internal class TapeView(parent: Entity, m: SceneMaterials, band: MeshResource, yellow: Boolean, name: String) {
    private val strip = Strip(parent, band, if (yellow) m.tapeYellow else m.tapeWhite, name)
    private val ticks = List(MAX_TICKS) { Strip(parent, band, m.tick, "$name:tick$it") }
    private var on = false
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO

    /** The midpoint of the tape (the plate rides 4 cm above it). */
    val mid: Vec3
        get() = from.lerp(to, 0.5f)

    fun draw(a: Vec3, b: Vec3, faceNormal: Vec3, width: Float = WIDTH_M) {
        from = a
        to = b
        on = true
        strip.draw(a, b, width, faceNormal)
        val d = b - a
        val len = d.length
        if (len < 1e-4f) {
            ticks.forEach { it.hide() }
            return
        }
        val dir = d / len
        var side = faceNormal.cross(dir)
        if (side.length < 1e-4f) side = Vec3.UP.cross(dir)
        side = side.normalized()
        val count = floor(len / 0.1f).toInt().coerceAtMost(MAX_TICKS)
        for ((i, t) in ticks.withIndex()) {
            if (i < count) {
                val p = a + dir * (0.1f * (i + 1))
                t.draw(p - side * (width * 0.9f), p + side * (width * 0.9f), 0.004f, faceNormal)
            } else {
                t.hide()
            }
        }
    }

    fun hide() {
        if (!on) return
        on = false
        strip.hide()
        ticks.forEach { it.hide() }
    }

    val shown: Boolean
        get() = on

    fun destroy() {
        strip.destroy()
        ticks.forEach { it.destroy() }
    }

    companion object {
        const val WIDTH_M = 0.014f
        const val MAX_TICKS = 9
    }
}

/** The four meshes of the base marks (dead zone, pedestal body, hazard stripes, snap ring), built one by one. */
internal class BaseMeshes(val dead: MeshResource, val body: MeshResource, val stripes: MeshResource, val snap: MeshResource)
