package io.embodit.robotcell.ui.cell.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.robotcell.domain.model.Quat4
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Vec3

/**
 * The UR5e as a procedural white model (design `entities3d.ur5e.fallback`): a base cylinder, five link
 * cylinders between the FK joint points, six darker joint drums on the joint axes with a blue ring each,
 * and a flange disc. Every part is a scaled unit mesh, so a pose change only writes transforms.
 * Parts are grouped by the design's seven segments for the collision colouring.
 */
internal class RobotView(parent: Entity, private val m: SceneMaterials, private val meshes: RobotMeshes, private val ghost: Boolean) {

    enum class Tone { NORMAL, GREY }

    private class Part(val entity: Entity, val segment: Int, val kind: Kind) {
        enum class Kind { LINK, DRUM, RING }
    }

    val root: Entity = Entity().named(if (ghost) "ghost" else "robot")
    private val parts = ArrayList<Part>()
    private val baseTap: Entity
    private var tone = Tone.NORMAL
    private var hits: Set<Int> = emptySet()
    private var shown = true
    /** The drag target entity (null on the ghost). */
    var grab: Entity? = null
        private set

    init {
        parent.addChild(root)
        // base cylinder (segment 0)
        baseTap = ModelEntity(meshes.cylinder, mat(0, Part.Kind.LINK)).named(if (ghost) "ghost:base" else "robot:base")
        baseTap.setLocal(position = Vec3.ZERO, scale = Vec3(Ur5e.BASE_RADIUS_M, Ur5e.BASE_HEIGHT_M, Ur5e.BASE_RADIUS_M))
        root.addChild(baseTap)
        parts += Part(baseTap, 0, Part.Kind.LINK)
        if (!ghost) {
            // the tap target for dragging: the whole base and the J1 drum
            val tap = Entity().named("robot:grab")
            tap.tapBox(Vec3(0.2f, 0.2f, 0.2f), Vec3(0f, 0.1f, 0f))
            root.addChild(tap)
            grab = tap
        }
        // joint drums J1..J6 (segments 0..5) with a ring each
        for (j in 0 until 6) {
            val drum = ModelEntity(meshes.cylinder, mat(j, Part.Kind.DRUM)).named("drum$j")
            root.addChild(drum)
            parts += Part(drum, j, Part.Kind.DRUM)
            val ring = ModelEntity(meshes.cylinder, mat(j, Part.Kind.RING)).named("ring$j")
            root.addChild(ring)
            parts += Part(ring, j, Part.Kind.RING)
        }
        // links: shoulder->elbow (1), elbow->wrist1 (2), wrist1->wrist2 (3), wrist2->wrist3 (4), wrist3->flange (5)
        for (i in 1..5) {
            val link = ModelEntity(meshes.cylinder, mat(i, Part.Kind.LINK)).named("link$i")
            root.addChild(link)
            parts += Part(link, i, Part.Kind.LINK)
        }
        // the elbow and wrist joints get spheres so that the cylinders meet cleanly
        for (i in 2..5) {
            val ball = ModelEntity(meshes.sphere, mat(i, Part.Kind.LINK)).named("ball$i")
            root.addChild(ball)
            parts += Part(ball, i, Part.Kind.LINK)
        }
        // flange disc (segment 6)
        val flange = ModelEntity(meshes.cylinder, mat(6, Part.Kind.DRUM)).named("flange")
        root.addChild(flange)
        parts += Part(flange, 6, Part.Kind.DRUM)
        if (ghost) root.setOpacity(GHOST_OPACITY)
        setPose(Ur5e.frames(floatArrayOf(0f, -90f, 0f, -90f, 0f, 0f)))
    }

    fun isGrab(entity: Entity): Boolean = grab?.id == entity.id || baseTap.id == entity.id

    /** Mount point and yaw in Stage space. */
    fun place(mount: Vec3, yaw: Float) {
        root.setLocal(position = mount, rotation = Quat4.axisAngle(Vec3.UP, yaw))
    }

    fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        root.enabled = value
    }

    /** Writes the transforms of every part for the FK [frames] (robot local frame). */
    fun setPose(frames: Array<Ur5e.Frame>) {
        val up = Vec3.UP
        for (p in parts) {
            when (p.kind) {
                Part.Kind.DRUM -> {
                    if (p.segment == 6) {
                        val f = frames[6]
                        val h = 0.012f
                        p.entity.setLocal(position = f.origin - f.z * h, rotation = Quat4.fromTo(up, f.z), scale = Vec3(0.045f, h, 0.045f))
                    } else {
                        val j = p.segment
                        val (center, axis) = drumAxis(frames, j)
                        val h = Ur5e.JOINT_H[j]
                        val r = Ur5e.JOINT_R[j]
                        p.entity.setLocal(position = center - axis * (h / 2f), rotation = Quat4.fromTo(up, axis), scale = Vec3(r, h, r))
                    }
                }
                Part.Kind.RING -> {
                    val j = p.segment
                    val (center, axis) = drumAxis(frames, j)
                    val r = Ur5e.JOINT_R[j] + 0.0025f
                    val h = 0.014f
                    p.entity.setLocal(position = center - axis * (h / 2f), rotation = Quat4.fromTo(up, axis), scale = Vec3(r, h, r))
                }
                Part.Kind.LINK -> {
                    if (p.segment == 0) continue
                    val name = runCatching { p.entity.getName() }.getOrNull() ?: ""
                    if (name.startsWith("ball")) {
                        val r = Ur5e.LINK_R[p.segment]
                        p.entity.setLocal(position = frames[p.segment].origin, rotation = Quat4.IDENTITY, scale = Vec3(r, r, r))
                    } else {
                        val a = frames[p.segment].origin
                        val b = frames[p.segment + 1].origin
                        val d = b - a
                        val len = d.length
                        val r = Ur5e.LINK_R[p.segment]
                        if (len < 1e-4f) {
                            p.entity.setLocal(position = a, scale = Vec3(r, 0.001f, r))
                        } else {
                            p.entity.setLocal(position = a, rotation = Quat4.fromTo(up, d / len), scale = Vec3(r, len, r))
                        }
                    }
                }
            }
        }
    }

    /** Joint drum j (0 = J1): centre and axis in the robot's local frame. */
    private fun drumAxis(frames: Array<Ur5e.Frame>, j: Int): Pair<Vec3, Vec3> =
        if (j == 0) Vec3(0f, Ur5e.D1 - 0.03f, 0f) to Vec3.UP
        else frames[j].origin to frames[j].z.normalized()

    fun setTone(value: Tone) {
        if (tone == value) return
        tone = value
        refreshMaterials()
    }

    /** Segments (0..6) that collide: they are drawn in the error colour. */
    fun setHits(value: Set<Int>) {
        if (hits == value) return
        hits = value
        refreshMaterials()
    }

    /** Overall opacity (the "too close" rule and the ghost). */
    fun setOpacity(value: Float) {
        root.setOpacity(if (ghost) minOf(value, GHOST_OPACITY) else value)
    }

    private fun refreshMaterials() {
        for (p in parts) p.entity.setMaterial(mat(p.segment, p.kind))
    }

    private fun mat(segment: Int, kind: Part.Kind): Material =
        when {
            ghost -> m.ghost
            tone == Tone.GREY -> m.stale
            segment in hits -> if (kind == Part.Kind.RING) m.hitDark else m.hit
            kind == Part.Kind.LINK -> m.arm
            kind == Part.Kind.DRUM -> m.joint
            else -> m.blue
        }

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val GHOST_OPACITY = 0.3f
    }
}

/** Unit meshes shared by the robot and the ghost. */
internal class RobotMeshes(val cylinder: MeshResource, val sphere: MeshResource)
