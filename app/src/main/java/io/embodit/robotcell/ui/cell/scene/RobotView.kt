package io.embodit.robotcell.ui.cell.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.robotcell.domain.model.Gripper
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
    private val gripper: GripperView? = if (ghost) null else GripperView(root, m, meshes)
    private var lastFrames: Array<Ur5e.Frame>? = null
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
        lastFrames = frames
        gripper?.setPose(frames[6])
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

    /** Finger opening (0 ... 85 mm) and whether the gripper is mounted at all. */
    fun setGripper(present: Boolean, widthM: Float) {
        val g = gripper ?: return
        g.setPresent(present)
        g.setWidth(widthM)
        lastFrames?.let { g.setPose(it[6]) }
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
        gripper?.setLook(if (tone == Tone.GREY) GripperView.Look.GREY else if (6 in hits) GripperView.Look.HIT else GripperView.Look.NORMAL)
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
internal class RobotMeshes(val cylinder: MeshResource, val sphere: MeshResource, val box: MeshResource)

/**
 * A 1:1 two-finger adaptive gripper of the Robotiq 2F-85 class on tool0 (v0.1.2): a 75 mm coupling, an
 * 85 x 62 x 90 mm body, two outer finger links and two rubber pads that slide apart to the opening. Flange
 * to fingertips 172 mm, the TCP ([Gripper.TCP_M]) between the fingertips. Tool frame: z along the approach,
 * x the finger travel. Children of the robot root, so a pose change only writes transforms.
 */
internal class GripperView(root: Entity, private val m: SceneMaterials, meshes: RobotMeshes) {
    enum class Look { NORMAL, HIT, GREY }

    private val coupling = ModelEntity(meshes.cylinder, m.joint).named("robot:gripCoupling")
    private val body = ModelEntity(meshes.box, m.gripper).named("robot:gripBody")
    private val links = List(2) { ModelEntity(meshes.box, m.gripper).named("robot:gripLink$it") }
    private val pads = List(2) { ModelEntity(meshes.box, m.pad).named("robot:gripPad$it") }
    private var width = Gripper.STROKE_M
    private var present = true
    private var look = Look.NORMAL

    init {
        root.addChild(coupling)
        root.addChild(body)
        links.forEach { root.addChild(it) }
        pads.forEach { root.addChild(it) }
    }

    fun setPresent(value: Boolean) {
        if (present == value) return
        present = value
        coupling.enabled = value
        body.enabled = value
        links.forEach { it.enabled = value }
        pads.forEach { it.enabled = value }
    }

    fun setWidth(value: Float) {
        width = value.coerceIn(0f, Gripper.STROKE_M)
    }

    fun setLook(value: Look) {
        if (look == value) return
        look = value
        val main = when (value) {
            Look.NORMAL -> m.gripper
            Look.HIT -> m.hit
            Look.GREY -> m.stale
        }
        body.setMaterial(main)
        links.forEach { it.setMaterial(main) }
        coupling.setMaterial(if (value == Look.GREY) m.stale else m.joint)
        pads.forEach { it.setMaterial(if (value == Look.GREY) m.stale else m.pad) }
    }

    /** Places every part on the flange frame [f] (robot local). */
    fun setPose(f: Ur5e.Frame) {
        if (!present) return
        val rot = Quat4.fromBasis(f.x.normalized(), f.y.normalized(), f.z.normalized())
        fun at(x: Float, z: Float): Vec3 = f.origin + f.x * x + f.z * z
        coupling.setLocal(position = f.origin, rotation = Quat4.fromTo(Vec3.UP, f.z.normalized()), scale = Vec3(COUPLING_R, COUPLING_H, COUPLING_R))
        body.setLocal(position = at(0f, COUPLING_H + BODY_H / 2f), rotation = rot, scale = Vec3(BODY_W, BODY_D, BODY_H))
        val half = width / 2f
        for (s in 0..1) {
            val sign = if (s == 0) -1f else 1f
            links[s].setLocal(position = at(sign * (half + LINK_W / 2f + PAD_W), LINK_Z0 + LINK_H / 2f), rotation = rot, scale = Vec3(LINK_W, LINK_D, LINK_H))
            pads[s].setLocal(position = at(sign * (half + PAD_W / 2f), PAD_Z0 + PAD_H / 2f), rotation = rot, scale = Vec3(PAD_W, PAD_D, PAD_H))
        }
    }

    companion object {
        const val COUPLING_R = 0.0375f
        const val COUPLING_H = 0.012f
        const val BODY_W = 0.085f
        const val BODY_D = 0.062f
        const val BODY_H = 0.090f
        const val LINK_Z0 = COUPLING_H + BODY_H
        const val LINK_W = 0.012f
        const val LINK_D = 0.030f
        const val LINK_H = 0.046f
        const val PAD_W = 0.009f
        const val PAD_D = 0.022f
        const val PAD_H = 0.030f
        /** Pads from 142 to 172 mm: the TCP at 170 mm sits between the fingertips. */
        const val PAD_Z0 = 0.142f
    }
}

/**
 * The TCP handle (v0.1.2): a yellow ball at the fingertip midpoint with a larger tap box. Pinch and drag it
 * and the arm follows by inverse kinematics; it turns red where there is no solution.
 */
internal class TcpHandle(parent: Entity, private val m: SceneMaterials, mesh: MeshResource) {
    val root: Entity = Entity().named("robot:tcp")
    private val ball = ModelEntity(mesh, m.handle).named("robot:tcpBall")
    val tap: Entity = Entity().named("robot:tcpTap")
    private var on = true
    private var ok = true
    private var big = false

    init {
        parent.addChild(root)
        ball.setLocal(scale = Vec3(R, R, R))
        root.addChild(ball)
        tap.tapBox(Vec3(TAP_M, TAP_M, TAP_M))
        root.addChild(tap)
        setShown(false)
    }

    fun isHandle(entity: Entity): Boolean = entity.id == tap.id || entity.id == ball.id

    fun setShown(value: Boolean) {
        if (on == value) return
        on = value
        root.enabled = value
    }

    fun place(at: Vec3) {
        root.setLocal(position = at)
    }

    fun setState(reachable: Boolean, active: Boolean) {
        if (reachable != ok) {
            ok = reachable
            ball.setMaterial(if (reachable) m.handle else m.handleBad)
        }
        if (active != big) {
            big = active
            val r = if (active) R * 1.4f else R
            ball.setLocal(scale = Vec3(r, r, r))
        }
    }

    fun destroy() = runCatching { root.destroy(true) }

    companion object {
        const val R = 0.018f
        const val TAP_M = 0.07f
    }
}
