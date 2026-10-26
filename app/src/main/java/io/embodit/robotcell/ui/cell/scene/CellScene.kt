package io.embodit.robotcell.ui.cell.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.PointerRay
import io.embodit.robotcell.domain.model.Quat4
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.SurfaceKind
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.usecase.HitSurfaceUseCase
import io.embodit.robotcell.domain.usecase.LinkHit
import io.embodit.robotcell.domain.usecase.PlaceRobotUseCase
import io.embodit.robotcell.domain.usecase.ReachUseCase
import io.embodit.robotcell.platform.CellGraph
import io.embodit.robotcell.platform.CellLog
import io.embodit.robotcell.platform.sense.PlaneTracker
import io.embodit.robotcell.platform.tracking.HeadPose
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.CellUiState
import io.embodit.robotcell.ui.cell.Mode
import io.embodit.robotcell.ui.cell.Tab
import io.embodit.robotcell.ui.cell.WallState
import io.embodit.robotcell.ui.cell.debug.CellProbe
import java.util.Locale
import kotlin.math.abs
import com.pico.spatial.core.ecs.resource.MeshResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: CellScene? = null
}

/** The one per-frame ECS system of the app. */
class CellFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the UR5e and its ghost, the reach sphere, work disks and wall rings, the dead
 * zone, pedestal, pins and tapes, the tap targets, and the placement of the pre-built panels. Compose
 * state is rendered through [render] (idempotent diff); per-frame work (pose easing, FK, collisions,
 * the aiming ghost, the thumbstick, plates) happens in [onFrame] only and never goes through Compose.
 * Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class CellScene(private val sink: (CellEvent) -> Unit) : CellProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Share of its panel's width that a panel's content fills (plates, snackbar). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = CellGraph.planes
    private val headTracker = CellGraph.head
    private val controller = CellGraph.controller
    private val reach = ReachUseCase()
    private val placeRule = PlaceRobotUseCase(reach)
    private val hits = HitSurfaceUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var robotMeshes: RobotMeshes? = null
    private var robot: RobotView? = null
    private var ghost: RobotView? = null
    private var sphere: ReachSphereView? = null
    private var marks: ReachMarks? = null
    private var baseMarks: BaseMarks? = null
    private var landing: LandingRing? = null
    private var pins: PinViews? = null
    private var wallTape: TapeView? = null
    private var edgeTapes: List<TapeView> = emptyList()
    private var attached = false

    private val follower = Follower("cell", MAIN_OFFSET, MAIN_HALF_EXTENTS, MAIN_LEAN)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = CellUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastHeadSentMs = 0L
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var lastTable = false
    private var lastFloor = false
    private var lastWall = false
    private var lastLost = false
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var dialogPlaced = false

    // the shown pose: eased towards the state's joints
    private var shownJoints: FloatArray = floatArrayOf(0f, -90f, 0f, -90f, 0f, 0f)
    private var animFrom: FloatArray? = null
    private var animTo: FloatArray? = null
    private var animStartMs = NEVER
    private var animFreeze: Float? = null
    private var animSerialSeen = -1
    private var poseDirty = true
    private var lastHits: List<LinkHit> = emptyList()
    private var frames: Array<Ur5e.Frame> = Ur5e.frames(shownJoints)

    // dragging the base with the system pointer
    private var dragging = false
    private var dragPxToM = 0.001f

    // thumbstick
    private var stickActive = false
    private var lastStickMs = NEVER

    private var fpsOn = false
    private var framesCount = 0
    private var fpsStartMs = 0L

    /** The cell panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z for a
        // full-size view). Move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        CellLog.i("STAGE root offset=${CellLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            if (id == PanelSpec.SNACK) rig.head(id, SNACK_HEAD_OFFSET, PanelSpec.FLOATING_SCALE, w, h) else rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else {
                    colliders?.remove(id)
                    reach.forget(id)
                }
            }
        )
        ActiveScene.scene = this
        bindPanels(attachments)
        follower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) CellLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /**
     * Materials and meshes. Every mesh waits for its turn ([meshTurn]): a `MeshResource` blocks the main thread
     * until the render thread has drawn a frame, and the idle emulator draws 1 - 2 frames a second, so building
     * the scene's dozen meshes in one go held the main thread for more than 5 s (QA round 1: "isn't responding"
     * at every launch). Turns are handed out by [runMeshWork] from the frame system.
     */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        suspend fun mesh(name: String, make: () -> MeshResource): MeshResource {
            meshTurn()
            val t = SystemClock.elapsedRealtime()
            return sh.keep(make()).also { CellLog.i("MESH $name ms=${SystemClock.elapsedRealtime() - t}") }
        }
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        val cylinder = mesh("unitCylinder") { MeshFactory.cylinder(1f, 1f, "unitCylinder", segments = 24) }
        val ball = mesh("unitSphere") { CellMeshes.unitSphere("unitSphere") }
        val rm = RobotMeshes(cylinder, ball)
        robotMeshes = rm
        meshTurn()
        robot = RobotView(root, m, rm, ghost = false).also { it.setShown(false) }
        meshTurn()
        ghost = RobotView(root, m, rm, ghost = true).also { it.setShown(false) }
        sphere = ReachSphereView(root, m, mesh("wireSphere") { CellMeshes.wireSphere("wireSphere") })
        marks = ReachMarks(root, m) { key, work -> meshWork[key] = work }
        val dead = mesh("deadZone") { MeshFactory.ring(Ur5e.DEAD_ZONE_R - 0.004f, Ur5e.DEAD_ZONE_R + 0.004f, "deadZone", dashes = 28, dashFill = 0.55f) }
        val body = mesh("pedestal") { MeshFactory.box(Vec3(0.2f, 1f, 0.2f)) }
        val stripes = mesh("stripes") { CellMeshes.stripeBand(0.1f, 0.06f, 6, "stripes") }
        val snap = mesh("snapRing") { MeshFactory.ring(0.10f, 0.108f, "snapRing", dashes = 24, dashFill = 0.5f) }
        baseMarks = BaseMarks(root, m, BaseMeshes(dead, body, stripes, snap))
        landing = LandingRing(root, m, mesh("landRing") { MeshFactory.ring(0.085f, 0.1f, "landRing") })
        val stem = mesh("pinStem") { MeshFactory.cylinder(0.003f, PinViews.STEM_H, "pinStem", segments = 10) }
        val head = mesh("pinHead") { CellMeshes.unitSphere("pinHead", around = 14, rings = 8) }
        meshTurn()
        pins = PinViews(root, m, stem, head).also { it.prepare() }
        val band = mesh("band") { MeshFactory.unitBand("band") }
        wallTape = TapeView(root, m, band, yellow = false, name = "wallTape")
        edgeTapes = listOf(TapeView(root, m, band, yellow = true, name = "edgeTape0"), TapeView(root, m, band, yellow = true, name = "edgeTape1"))
        CellGraph.anchors.start()
        attached = true
        CellLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(CellEvent.SceneReady)
    }

    // ---- mesh pacing (see [build])
    private val meshTurns = ArrayDeque<CompletableDeferred<Unit>>()
    private val meshWork = LinkedHashMap<String, () -> Unit>()
    private var lastFrameMs = -1L
    private var lastSlowMeshMs = 0L

    /** Suspends until the frame system hands out a mesh turn. */
    private suspend fun meshTurn() {
        val turn = CompletableDeferred<Unit>()
        meshTurns.addLast(turn)
        turn.await()
    }

    /**
     * Hands out mesh turns (scene build first, then queued rebuilds): [MESH_PER_FRAME] per frame while frames are
     * faster than [FAST_FRAME_MS] (a headset), one every [SLOW_MESH_GAP_MS] while they are slow (the idle emulator),
     * so that the main thread always gets back to input and broadcasts between two meshes.
     */
    private fun runMeshWork(now: Long) {
        if (meshTurns.isEmpty() && meshWork.isEmpty()) return
        val fast = lastFrameMs > 0 && now - lastFrameMs < FAST_FRAME_MS
        if (!fast && now - lastSlowMeshMs < SLOW_MESH_GAP_MS) return
        val budget = if (fast) MESH_PER_FRAME else 1
        var n = 0
        while (n < budget) {
            val turn = meshTurns.removeFirstOrNull()
            if (turn != null) {
                turn.complete(Unit)
                n++
                continue
            }
            val key = meshWork.keys.firstOrNull() ?: break
            val work = meshWork.remove(key) ?: continue
            runCatching { work() }.onFailure { CellLog.w("MESH $key failed: $it") }
            n++
        }
        if (!fast) lastSlowMeshMs = now
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        meshTurns.forEach { it.cancel() }
        meshTurns.clear()
        meshWork.clear()
        lastFrameMs = -1L
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        CellGraph.anchors.stop()
        panelShown.clear()
        panelStretch.clear()
        runCatching { rig?.release() }
        robot?.destroy()
        ghost?.destroy()
        sphere?.destroy()
        marks?.destroy()
        baseMarks?.destroy()
        landing?.destroy()
        pins?.destroy()
        wallTape?.destroy()
        edgeTapes.forEach { it.destroy() }
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        robot = null
        ghost = null
        sphere = null
        marks = null
        baseMarks = null
        landing = null
        pins = null
        wallTape = null
        edgeTapes = emptyList()
        robotMeshes = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        dialogPlaced = false
        dragging = false
        stickActive = false
        lastHits = emptyList()
        CellLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: CellUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        val robotView = robot ?: return
        val r = state.robot
        if (r == null) {
            robotView.setShown(false)
            sphere?.set(Vec3.ZERO, 1f, shown = false)
            baseMarks?.setDeadZone(null, 0f)
            baseMarks?.setPedestal(null, 0f, 0f)
            baseMarks?.setSnap(null, 0f)
            wallTape?.hide()
            edgeTapes.forEach { it.hide() }
            if (lastHits.isNotEmpty()) {
                lastHits = emptyList()
                robotView.setHits(emptySet())
            }
        } else {
            val rb = r.robot
            robotView.setShown(true)
            robotView.place(rb.mountPoint, rb.yaw)
            if (r.animSerial != animSerialSeen) {
                animSerialSeen = r.animSerial
                if (force || previous.robot == null) {
                    shownJoints = rb.joints.copyOf()
                    animStartMs = NEVER
                } else {
                    animFrom = shownJoints.copyOf()
                    animTo = rb.joints.copyOf()
                    animStartMs = SystemClock.elapsedRealtime()
                    animFreeze = r.freezeAt
                }
                poseDirty = true
            } else if (animStartMs == NEVER && !Ur5e.same(shownJoints, rb.joints)) {
                // the joint slider: immediate
                shownJoints = rb.joints.copyOf()
                poseDirty = true
            }
            if (force || previous.robot?.robot !== rb) poseDirty = true
            sphere?.set(rb.shoulder, rb.reachRadius(), shown = state.showSphere && !state.trackingLost)
            baseMarks?.setDeadZone(rb.mountPoint, rb.yaw)
            baseMarks?.setPedestal(if (rb.mount == Mount.FLOOR) rb.base else null, rb.pedestalH, rb.yaw)
        }
        marks?.setDisks(if (r == null) emptyList() else state.disks)
        marks?.setRings(if (r == null) emptyList() else state.rings)
        marks?.setShown(!state.trackingLost)
        robotView.setTone(if (state.trackingLost) RobotView.Tone.GREY else RobotView.Tone.NORMAL)
        pins?.sync(state.pins)
        if (previous.trackingLost != state.trackingLost || force) pins?.setMaterials(state.trackingLost)
        syncPanels(state)
    }

    private fun syncPanels(state: CellUiState) {
        val rig = rig ?: return
        if (state.compact) rig[PanelSpec.CELL]?.hide() else rig[PanelSpec.AIM]?.hide()
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (!state.coach) rig[PanelSpec.COACH]?.hide()
        if (state.hitPoint == null || !state.collides) rig[PanelSpec.HIT]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.dialog != null) {
            if (!dialogPlaced) {
                val flat = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                dialog?.show(head.position + flat * PanelSpec.DIALOG_DISTANCE_M, PanelSpec.FLOATING_SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
        val snack = rig[PanelSpec.SNACK]
        if (state.snack != null) snack?.show(null, null) else snack?.hide()
    }

    /** Width of a hugging panel's content, dp; called by the panel. */
    fun reportWidth(id: String, widthDp: Float) {
        val slot = rig?.get(id) ?: return
        if (widthDp <= 0f) return
        slot.widthFactor = widthDp / slot.widthDp
        if (panelStretch[id] != slot.widthFactor) panelStretch[id] = slot.widthFactor
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        runMeshWork(now)
        rig.stowBehind(head.view)
        val state = ui
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.dialog != null || dragging
        if (hasHead && head.position.length > 1e-3f) follower.follow(head, planeTracker.all, planeTracker.count, now, hold)
        placeFamily(state, rig)
        countFrame(now)
        if (!attached) {
            lastFrameMs = now
            return
        }

        pollTracking(now)
        pollPlanes(now)
        poseFrame(state, now)
        ghostFrame(state)
        stickFrame(state, now)
        placeMarks(state, rig)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(CellEvent.HeadSample(head.position, head.forward))
        }
        rig.faceViewer(head.position)
        lastFrameMs = now
    }

    /** The cell / aiming panel and the menu beside it ride on the lazy follow. */
    private fun placeFamily(state: CellUiState, rig: PanelRig) {
        if (!hasHead) return
        val pose = follower.pose
        val rot = follower.rotation
        val k = PanelSpec.MAIN_SCALE
        val shownId = if (state.compact) PanelSpec.AIM else PanelSpec.CELL
        rig[shownId]?.place(pose.position, rot, k)
        val (hw, hh) = PanelSpec.halfM(shownId, k)
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(hw - mw, hh + 0.012f + mh, 0.012f)), rot, k)
        }
    }

    /** Pose easing (0.8 s, ease-in-out), FK, collisions and the "too close" rule. */
    private fun poseFrame(state: CellUiState, now: Long) {
        val robotView = robot ?: return
        val r = state.robot?.robot
        if (r == null) return
        if (animStartMs != NEVER) {
            val from = animFrom
            val to = animTo
            if (from != null && to != null) {
                var q = ((now - animStartMs) / ANIM_MS).coerceIn(0f, 1f)
                val freeze = animFreeze
                if (freeze != null) q = q.coerceAtMost(freeze)
                shownJoints = Ur5e.lerpJoints(from, to, Ease.inOutCubic(q))
                poseDirty = true
                if (q >= 1f) {
                    animStartMs = NEVER
                    shownJoints = to.copyOf()
                }
            } else {
                animStartMs = NEVER
            }
        }
        if (poseDirty) {
            poseDirty = false
            frames = Ur5e.frames(shownJoints)
            robotView.setPose(frames)
            val found = reach.collisions(r, frames, planeTracker.all)
            if (found != lastHits) {
                lastHits = found
                robotView.setHits(found.map { it.segment }.toSet())
                CellLog.i("HIT ${if (found.isEmpty()) "none" else found.joinToString { "${Ur5e.SEGMENT_NAMES[minOf(it.segment, 6)]}:${it.kind}" }}")
                sink(CellEvent.HitsChanged(found))
            }
        }
        // too close: the whole robot at 40 % while the head is within 0.25 m of any segment
        if (hasHead) {
            val near = reach.distanceToArm(r, frames, head.position) < NEAR_M
            robotView.setOpacity(if (near) NEAR_OPACITY else 1f)
        }
    }

    /**
     * The aiming ghost (design `entities3d.ghost`): the system pointer never reports where it hovers,
     * so the ghost follows the controller ray when a controller is tracked, else the head's view line.
     * It lands on TABLE / FLOOR with a green ring; elsewhere a grey ring and no ghost.
     */
    private fun ghostFrame(state: CellUiState) {
        val g = ghost ?: return
        val land = landing ?: return
        if (!state.aimEnabled || !hasHead) {
            g.setShown(false)
            land.set(null, Vec3.UP, true)
            return
        }
        val ray = controller.sample()?.ray ?: PointerRay(head.position, head.forward)
        val planes = planeTracker.all.filter { it.kind == SurfaceKind.TABLE || it.kind == SurfaceKind.FLOOR || it.kind == SurfaceKind.WALL || it.kind == SurfaceKind.DOOR || it.kind == SurfaceKind.WINDOW }
        val hit = hits.nearest(planes, ray.origin, ray.direction, 6f)
        if (hit == null) {
            g.setShown(false)
            land.set(null, Vec3.UP, true)
            return
        }
        val good = (hit.plane.kind == SurfaceKind.TABLE && state.hasTable) || hit.plane.kind == SurfaceKind.FLOOR
        if (!good) {
            g.setShown(false)
            land.set(hit.point, hit.normal, false)
            return
        }
        val placed = placeRule.place(hit.plane, hit.point, planeTracker.all, head.position)
        if (placed == null) {
            g.setShown(false)
            land.set(hit.point, hit.normal, false)
            return
        }
        g.setShown(true)
        g.place(placed.mountPoint, placed.yaw)
        land.set(placed.base, Vec3.UP, true)
    }

    /** Thumbstick X turns the robot about its vertical axis (factory rule D1): dead zone 0.15, 60 degrees per second at full tilt. */
    private fun stickFrame(state: CellUiState, now: Long) {
        val r = state.robot?.robot
        val sample = if (r != null && !dragging) controller.sample() else null
        val x = sample?.stickX ?: 0f
        if (abs(x) > STICK_DEAD) {
            val dt = if (lastStickMs == NEVER) 0f else ((now - lastStickMs) / 1000f).coerceAtMost(0.1f)
            lastStickMs = now
            if (!stickActive) {
                stickActive = true
                CellLog.i("STICK start")
            }
            val sign = if (x > 0f) -1f else 1f
            val rate = (abs(x) - STICK_DEAD) / (1f - STICK_DEAD)
            if (dt > 0f) sink(CellEvent.StickTurn(sign * rate * STICK_RATE_RAD_S * dt))
            baseMarks?.setSnap(r?.mountPoint, snappedYaw(r?.yaw ?: 0f))
        } else {
            lastStickMs = NEVER
            if (stickActive) {
                stickActive = false
                CellLog.i("STICK release")
                sink(CellEvent.StickReleased(snap = true))
            }
            baseMarks?.setSnap(null, 0f)
        }
    }

    private fun snappedYaw(yaw: Float): Float {
        val step = Math.toRadians(15.0).toFloat()
        return Math.round(yaw / step) * step
    }

    /** Tapes, plates and the hit tag of the frame. */
    private fun placeMarks(state: CellUiState, rig: PanelRig) {
        val r = state.robot?.robot
        val wall = state.wallVerdict
        val tape = wallTape
        val plateK = plateScale(r?.shoulder)
        // wall: tape + plate when within 50 cm, plate on the ring when the reach goes past the wall
        if (r != null && wall != null && state.mode == Mode.PLACED && !state.trackingLost) {
            when (state.verdict.wall) {
                WallState.NEAR -> {
                    tape?.draw(wall.spherePoint, wall.wallPoint, head.position - wall.spherePoint.lerp(wall.wallPoint, 0.5f))
                    rig[PanelSpec.PLATE_WALL]?.show(wall.spherePoint.lerp(wall.wallPoint, 0.5f) + Vec3(0f, PLATE_LIFT_M, 0f), plateK)
                }
                WallState.IN -> {
                    tape?.hide()
                    val toward = (r.shoulder - wall.wallPoint).normalized()
                    rig[PanelSpec.PLATE_WALL]?.show(wall.wallPoint + toward * 0.02f + Vec3(0f, PLATE_LIFT_M + 0.02f, 0f), plateK)
                }
                else -> {
                    tape?.hide()
                    rig[PanelSpec.PLATE_WALL]?.hide()
                }
            }
        } else {
            tape?.hide()
            rig[PanelSpec.PLATE_WALL]?.hide()
        }
        // desk edges while dragging
        val edges = if (r != null && state.dragging) state.edgeReadings else emptyList()
        val edgeIds = listOf(PanelSpec.PLATE_EDGE1, PanelSpec.PLATE_EDGE2)
        for ((i, t) in edgeTapes.withIndex()) {
            val e = edges.getOrNull(i)
            if (e == null || r == null) {
                t.hide()
                rig[edgeIds[i]]?.hide()
            } else {
                val from = Vec3(r.base.x, e.first.y, r.base.z)
                t.draw(from, e.first, Vec3.UP)
                rig[edgeIds[i]]?.show(from.lerp(e.first, 0.5f) + Vec3(0f, PLATE_LIFT_M, 0f), plateK)
            }
        }
        // pins out of reach: a plate above each (pins tab only, nearest four)
        val pinPlates = PanelSpec.pinPlates
        val shortPins = if (state.tab == Tab.PINS && state.mode == Mode.PLACED) state.pins.withIndex().filter { !it.value.reachable }.take(pinPlates.size) else emptyList()
        for ((k, id) in pinPlates.withIndex()) {
            val p = shortPins.getOrNull(k)
            if (p == null) {
                rig[id]?.hide()
            } else {
                rig[id]?.show(p.value.position + Vec3(0f, PinViews.STEM_H + 0.05f, 0f), plateK)
            }
        }
        // the hit tag 6 cm above the first contact
        val hp = state.hitPoint
        if (hp != null && state.collides && !state.trackingLost) rig[PanelSpec.HIT]?.show(hp + Vec3(0f, 0.06f, 0f), plateK) else rig[PanelSpec.HIT]?.hide()
        // coachmark 30 cm above the base, pulled 30 cm towards the viewer so that the upright arm does not hide it
        if (state.coach && r != null) {
            val (_, ch) = PanelSpec.halfM(PanelSpec.COACH, PanelSpec.MAIN_SCALE)
            val toward = Vec3(head.position.x - r.base.x, 0f, head.position.z - r.base.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
            rig[PanelSpec.COACH]?.show(r.mountPoint + toward * 0.30f + Vec3(0f, 0.30f + ch, 0f), PanelSpec.MAIN_SCALE)
        }
    }

    /** Plates keep a constant visual size: scale with the distance from the head (1.0 at 1 m). */
    private fun plateScale(near: Vec3?): Float {
        if (!hasHead || near == null) return 1f
        return (head.position.distanceTo(near) / 1.0f).coerceIn(0.6f, 1.6f)
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val planes = planeTracker.all
        val hasTable = planes.any { it.kind == SurfaceKind.TABLE && it.horizontal }
        val hasFloor = planes.any { it.kind == SurfaceKind.FLOOR }
        val hasWall = planes.any { (it.kind == SurfaceKind.WALL || it.kind == SurfaceKind.DOOR || it.kind == SurfaceKind.WINDOW) && !it.horizontal }
        if (count == lastPlaneCount && hasTable == lastTable && hasFloor == lastFloor && hasWall == lastWall) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastTable = hasTable
        lastFloor = hasFloor
        lastWall = hasWall
        val floorY = reach.floorY(planes) ?: 0f
        CellLog.i("PLANES n=$count table=$hasTable floor=$hasFloor wall=$hasWall floorY=${CellLog.f(floorY)}")
        poseDirty = true
        sink(CellEvent.PlanesChanged(count, hasTable, hasFloor, hasWall, floorY))
    }

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(CellEvent.TrackingChanged(lost))
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        framesCount++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            CellLog.i("FPS app=${framesCount * 1000f / (now - fpsStartMs)} frames=$framesCount ms=${now - fpsStartMs}")
            framesCount = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse)
    // =========================================================================================

    /** A system spatial tap on one of our entities. */
    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val kind = value.interactionKind
        if (target == null) {
            CellLog.i("TAP other target=none kind=$kind")
            return
        }
        val pinId = pins?.pinIdOf(target)
        val planeId = colliders?.planeIdOf(target)
        when {
            robot?.isGrab(target) == true || name.startsWith("robot") -> {
                CellLog.i("TAP robot kind=$kind")
                sink(CellEvent.RobotTapped)
            }
            pinId != null -> {
                CellLog.i("TAP pin id=$pinId kind=$kind")
                sink(CellEvent.PinTapped(pinId))
            }
            planeId != null && stage != null -> {
                CellLog.i("TAP surface plane=${name.removePrefix("plane:")} kind=$kind stage=${CellLog.pos(stage)}")
                sink(CellEvent.SurfaceTapped(planeId, stage))
            }
            colliders?.isBackstop(target) == true -> {
                CellLog.i("TAP backstop kind=$kind stage=${CellLog.pos(stage)}")
                sink(CellEvent.NothingTapped)
            }
            else -> CellLog.i("TAP other target=$name kind=$kind stage=${CellLog.pos(stage)}")
        }
    }

    /**
     * A system spatial drag that starts on the base moves the robot in its mount plane: the hand's
     * travel (`dragAmount`, view pixels, 0.4 mm each at 1 m times the depth factor) is turned into
     * metres along the head's horizontal right and forward directions (design `rules.move`).
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                val r = ui.robot?.robot ?: return
                if (robot?.isGrab(target) != true) return
                val dist = head.position.distanceTo(r.mountPoint).coerceAtLeast(0.3f)
                dragPxToM = PX_M * (0.67f + 1.186f * dist)
                dragging = true
                CellLog.i("DRAG start dist=${CellLog.f(dist)}")
                sink(CellEvent.DragStarted)
            }
            "move" -> {
                if (!dragging) return
                val amount = value.dragAmount
                val right = Vec3(head.right.x, 0f, head.right.z).let { if (it.length < 0.1f) Vec3.RIGHT else it.normalized() }
                val fwd = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                val delta = right * (amount.x * dragPxToM) - fwd * (amount.y * dragPxToM)
                sink(CellEvent.DragMoved(delta))
            }
            "end", "cancel" -> {
                if (!dragging) return
                dragging = false
                CellLog.i("DRAG end")
                sink(CellEvent.DragEnded)
            }
        }
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { CellLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    fun headPose(): HeadPose = head

    /** Planes for the ViewModel's rules (read on the main thread). */
    fun planes() = planeTracker.all

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${CellLog.pos5(head.position)} ${CellLog.quat5(head.rotation)} fwd=${CellLog.pos5(head.forward)} valid=$hasHead joints=${shownJoints.joinToString(",") { CellLog.f(it, 1) }}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, CellLog.pos5(slot.position), CellLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, CellLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${CellLog.pos(p.centroid)} normal=${CellLog.pos(p.normal)} verts=${p.vertices.size} top=${CellLog.f(p.maxY)}" }

    override fun parkPanel(): String {
        follower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW cell park requested"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        framesCount = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val HEAD_SAMPLE_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val ANIM_MS = 800f
        private const val NEAR_M = 0.25f
        private const val NEAR_OPACITY = 0.4f
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE_RAD_S = 1.0472f
        private const val PLATE_LIFT_M = 0.04f
        private const val MESH_PER_FRAME = 2
        private const val FAST_FRAME_MS = 40L
        private const val SLOW_MESH_GAP_MS = 1500L

        /** View pixels of a system drag -> metres at 1 m (0.4 mm each, times the depth factor). */
        private const val PX_M = 0.0004f

        /** 28 degrees left and 30 degrees down, 0.6 m from the head (design `panels.cell.placement`). */
        val MAIN_OFFSET = Vec3(-0.2439f, -0.3000f, -0.4588f)

        /** The cell panel leans back 12 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)

        /** Half sizes of the cell panel at 0.75 (0.24 x 0.24 m) with a little air, for the surface rule. */
        private val MAIN_HALF_EXTENTS = listOf(0.13f, 0.13f, 0.01f)

        /** 14 degrees above the view centre, 0.9 m away: (0, sin14, -cos14) * 0.9. */
        private val SNACK_HEAD_OFFSET = Vec3(0f, 0.2177f, -0.8733f)
    }
}
