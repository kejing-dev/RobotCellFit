package io.embodit.robotcell.ui.cell

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.robotcell.data.AnchorOutcome
import io.embodit.robotcell.data.AnchorPose
import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.LanguagePref
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.Pin
import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.Settings
import io.embodit.robotcell.domain.model.StoredCell
import io.embodit.robotcell.domain.model.StoredSession
import io.embodit.robotcell.domain.model.SurfaceKind
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.usecase.DiskGeometry
import io.embodit.robotcell.domain.usecase.LinkHit
import io.embodit.robotcell.domain.usecase.PlaceRobotUseCase
import io.embodit.robotcell.domain.usecase.ReachUseCase
import io.embodit.robotcell.domain.usecase.WallRing
import io.embodit.robotcell.domain.usecase.WallVerdict
import io.embodit.robotcell.platform.CellGraph
import io.embodit.robotcell.platform.CellLog
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states`, `rules`). Events come from the panels, the
 * scene (taps, drags, the stick, collisions, head samples, planes, tracking) and the debug hooks; the
 * state goes out as [CellUiState]. Main thread only.
 */
class CellViewModel(private val appContext: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CellViewModel(context.applicationContext) as T
    }

    private val repository = CellGraph.repository(appContext)
    private val anchors = CellGraph.anchors
    private val planeTracker = CellGraph.planes
    private val reach = ReachUseCase()
    private val placeRule = PlaceRobotUseCase(reach)

    private val _state = MutableStateFlow(CellUiState())
    val state: StateFlow<CellUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<CellUiEffect>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects = _effects.asSharedFlow()

    // ---- the model
    private var robot: Robot? = null
    private val pins = ArrayList<Pin>()
    private var settings = Settings()
    private var anchorId: String? = null
    private var anchorPose: AnchorPose? = null
    private var anchorFailed = false
    private var stored: StoredCell? = null
    private var loaded = false
    private var uid = 1

    private class Snapshot(val robot: Robot?, val pins: List<Pin>)

    private val undo = ArrayDeque<Snapshot>()
    private var deleted: Snapshot? = null

    // ---- session facts
    private var mode = Mode.STARTING
    private var tab = Tab.POSE
    private var selectedJoint = 1
    private var sceneReady = false
    private var hasTable = false
    private var hasFloor = false
    private var hasWall = false
    private var floorY = 0f
    private var menuOpen = false
    private var dialog: DialogKind? = null
    private var snack: SnackUi? = null
    private var snackSerial = 0
    private var snackJob: Job? = null
    private var coach = false
    private var trackingLost = false
    private var blockedSinceMs = NEVER
    private var dragging = false
    private var hits: List<LinkHit> = emptyList()
    private var animSerial = 0
    private var freezeAt: Float? = null
    private var posingUntilMs = NEVER
    private var posingJob: Job? = null
    private var restoreJob: Job? = null
    private var saveJob: Job? = null
    private var head: Vec3? = null
    private var headForward = Vec3.AHEAD
    private var lastStateName = ""
    private var forcedState: String? = null
    private var debugWallPlane: PlaneSurface? = null

    // ---- derived geometry (recomputed when the robot, the pins or the planes change)
    private var disks: List<DiskGeometry> = emptyList()
    private var lastMarksKey = ""
    private var rings: List<WallRing> = emptyList()
    private var wallVerdict: WallVerdict? = null
    private var deskPercent: Int? = null
    private var pinsUi: List<PinUi> = emptyList()
    private var edgeReadings: List<Pair<Vec3, Float>> = emptyList()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            anchors.moves.collect { record ->
                if (record.id == anchorId) {
                    anchorPose = record.pose
                    if (mode == Mode.RESTORING || mode == Mode.ELSEWHERE) restoreFromAnchor(record.pose)
                }
            }
        }
    }

    private suspend fun load() {
        val session = runCatching { repository.load() }.getOrNull()
        settings = session?.settings ?: Settings()
        stored = session?.cell
        loaded = true
        CellLog.i("LOAD cell=${stored != null} anchor=${stored?.anchorId} lang=${settings.language} units=${settings.units}")
        settle()
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: CellEvent) {
        when (event) {
            // ---- scene
            CellEvent.SceneReady -> {
                sceneReady = true
                settle()
            }
            is CellEvent.PlanesChanged -> {
                hasTable = event.hasTable
                hasFloor = event.hasFloor
                hasWall = event.hasWall
                floorY = event.floorY
                // a restored robot may have come back before any desk was reported: adopt its desk now
                robot?.let { r ->
                    if (r.tableId == null) {
                        val t = reach.nearestTable(r.base, planes(), if (r.mount == Mount.DESK) 0.5f else 3f)
                        if (t != null) {
                            robot = r.copy(tableId = t.id)
                            CellLog.i("ROBOT adopted desk ${t.id.take(8)}")
                        }
                    }
                }
                recompute()
                settle()
            }
            is CellEvent.TrackingChanged -> {
                trackingLost = event.lost
                if (event.lost) showSnack(SnackKind.LOST) else clearSnack(SnackKind.LOST)
                publish()
            }
            is CellEvent.SurfaceTapped -> onSurfaceTapped(event.planeId, event.point)
            CellEvent.NothingTapped -> {
                if (menuOpen) menuOpen = false
                publish()
            }
            is CellEvent.PinTapped -> {
                if (menuOpen) {
                    menuOpen = false
                } else if (tab == Tab.PINS && robot != null) {
                    removePin(event.id)
                }
                publish()
            }
            CellEvent.RobotTapped -> {
                if (menuOpen) menuOpen = false
                publish()
            }
            CellEvent.DragStarted -> {
                val r = robot ?: return
                pushUndo()
                dragging = true
                menuOpen = false
                CellLog.i("ROBOT drag start base=${CellLog.pos(r.base)}")
                recompute()
                publish()
            }
            is CellEvent.DragMoved -> {
                val r = robot ?: return
                if (!dragging) return
                val moved = placeRule.constrain(r.copy(base = r.base + Vec3(event.delta.x, 0f, event.delta.z)), planes())
                robot = moved
                recompute()
                publish()
            }
            CellEvent.DragEnded -> {
                dragging = false
                robot?.let { CellLog.i("ROBOT drag end base=${CellLog.pos(it.base)}") }
                recompute()
                scheduleSave()
                publish()
            }
            is CellEvent.StickTurn -> {
                val r = robot ?: return
                robot = r.copy(yaw = r.yaw + event.radians)
                recompute()
                publish()
            }
            is CellEvent.StickReleased -> {
                val r = robot ?: return
                if (event.snap) {
                    val step = Math.toRadians(15.0).toFloat()
                    val snapped = Math.round(r.yaw / step) * step
                    if (abs(snapped - r.yaw) < Math.toRadians(5.0)) robot = r.copy(yaw = snapped)
                }
                CellLog.i("ROBOT turn yaw=${CellLog.f(Math.toDegrees(robot!!.yaw.toDouble()).toFloat(), 1)}")
                recompute()
                scheduleSave()
                publish()
            }
            is CellEvent.HitsChanged -> {
                val wasHit = hits.isNotEmpty()
                hits = event.hits
                if (!wasHit && hits.isNotEmpty()) emit(CellUiEffect.Sound(SoundKind.HIT))
                publish()
            }
            is CellEvent.HeadSample -> {
                head = event.position
                headForward = event.forward
            }

            // ---- panel
            CellEvent.MoreClicked -> {
                menuOpen = !menuOpen
                publish()
            }
            CellEvent.MenuDismissed -> {
                menuOpen = false
                publish()
            }
            is CellEvent.TabSelected -> {
                tab = event.tab
                CellLog.i("TAB ${tab.name}")
                publish()
            }
            is CellEvent.PoseClicked -> setPose(event.pose, null)
            is CellEvent.JointSelected -> {
                selectedJoint = event.index.coerceIn(0, 5)
                publish()
            }
            is CellEvent.JointChanged -> setJoint(selectedJoint, event.degrees)
            is CellEvent.JointStepped -> {
                val r = robot ?: return
                setJoint(selectedJoint, r.joints[selectedJoint] + event.degrees)
            }
            CellEvent.JointsZeroed -> setPose(Pose.UPRIGHT, null)
            CellEvent.GripperCycled -> {
                val r = robot ?: return
                pushUndo()
                robot = r.copy(gripperM = Gripper.next(r.gripperM))
                CellLog.i("ROBOT gripper=${CellLog.f(robot!!.gripperM)}")
                recompute()
                scheduleSave()
                publish()
            }
            is CellEvent.RotateClicked -> {
                val r = robot ?: return
                pushUndo()
                robot = r.copy(yaw = r.yaw + Math.toRadians(event.degrees.toDouble()).toFloat())
                recompute()
                scheduleSave()
                publish()
            }
            CellEvent.DeleteClicked -> deleteRobot()
            CellEvent.UndoClicked -> undoLast()
            CellEvent.ClearPinsClicked -> {
                if (pins.isEmpty()) return
                pushUndo()
                pins.clear()
                recompute()
                scheduleSave()
                publish()
            }
            CellEvent.ClearAllClicked -> {
                menuOpen = false
                dialog = DialogKind.CLEAR
                publish()
            }
            CellEvent.ClearConfirmed -> {
                dialog = null
                clearAll()
            }
            CellEvent.DialogDismissed -> {
                dialog = null
                publish()
            }
            CellEvent.ExitClicked -> emit(CellUiEffect.ExitApp)
            CellEvent.RetryClicked -> {
                dialog = null
                blockedSinceMs = NEVER
                mode = Mode.STARTING
                settle()
            }
            CellEvent.CoachDismissed -> dismissCoach()
            is CellEvent.SnackActionClicked -> onSnackAction(event.id)
            is CellEvent.LanguageSelected -> {
                settings = settings.copy(language = event.language)
                scheduleSave()
                publish()
            }
            is CellEvent.UnitsSelected -> {
                settings = settings.copy(units = event.units)
                scheduleSave()
                publish()
            }
            is CellEvent.SphereToggled -> {
                settings = settings.copy(showSphere = event.on)
                scheduleSave()
                publish()
            }

            // ---- lifecycle
            CellEvent.WentBackground -> {
                menuOpen = false
                saveNow()
            }
            CellEvent.CameForeground -> {
                recompute()
                publish()
            }

            // ---- debug
            is CellEvent.DebugGoto -> debugGoto(event.state)
            is CellEvent.DebugPlace -> debugPlace(event.surface, event.x, event.z, event.yawDeg)
            is CellEvent.DebugPose -> {
                event.yawDeg?.let { deg -> robot?.let { robot = it.copy(yaw = Math.toRadians(deg.toDouble()).toFloat()) } }
                setPose(event.pose, event.freeze)
            }
            is CellEvent.DebugWall -> debugWall(event.d)
            is CellEvent.DebugPin -> debugPin(event.x, event.z)
            CellEvent.DebugCoachReset -> {
                settings = settings.copy(coachSeen = false)
                coach = robot != null
                publish()
            }
            is CellEvent.DebugTracking -> {
                CellGraph.head.forceLost = event.lost == true
                if (event.lost != null) onEvent(CellEvent.TrackingChanged(event.lost))
            }
            CellEvent.DebugClear -> clearAll()
            is CellEvent.DebugDragging -> {
                dragging = event.on && robot != null
                recompute()
                publish()
            }
        }
    }

    // =========================================================================================
    // modes
    // =========================================================================================

    /** Decides the unplaced modes from what is known (design S01 - S04, S16, S18). */
    private fun settle() {
        if (!loaded || !sceneReady) {
            mode = Mode.STARTING
            publish()
            return
        }
        checkBlocked()
        if (mode == Mode.PLACED || mode == Mode.RESTORING || mode == Mode.ELSEWHERE) {
            publish()
            return
        }
        val s = stored
        if (s != null && robot == null && restoreJob == null && mode != Mode.ELSEWHERE) {
            startRestore(s)
            return
        }
        mode =
            when {
                hasTable -> Mode.AIMING
                hasFloor -> Mode.NO_TABLE
                else -> Mode.SCANNING
            }
        when (mode) {
            Mode.SCANNING -> showSnack(SnackKind.SCANNING)
            Mode.NO_TABLE -> {
                clearSnack(SnackKind.SCANNING)
                showSnack(SnackKind.NO_TABLE)
            }
            else -> {
                clearSnack(SnackKind.SCANNING)
                clearSnack(SnackKind.NO_TABLE)
            }
        }
        publish()
    }

    private fun checkBlocked() {
        val reason = CellGraph.head.blockReason()
        val now = SystemClock.elapsedRealtime()
        if (reason == null) {
            blockedSinceMs = NEVER
            if (dialog == DialogKind.UNAVAILABLE) dialog = null
            return
        }
        if (blockedSinceMs == NEVER) blockedSinceMs = now
        // the support state is NOT_IN_FULL_SPACE while the Stage opens: only a lasting block counts
        if (now - blockedSinceMs > BLOCK_GRACE_MS && dialog == null) {
            dialog = DialogKind.UNAVAILABLE
            CellLog.i("BLOCKED $reason")
        }
    }

    // =========================================================================================
    // placing, moving, pins
    // =========================================================================================

    private fun onSurfaceTapped(planeId: String, point: Vec3) {
        if (menuOpen) {
            menuOpen = false
            publish()
            return
        }
        if (dialog != null) return
        val plane = planeTracker.get(planeId) ?: return
        val r = robot
        if (r == null) {
            if (mode != Mode.AIMING && mode != Mode.NO_TABLE) return
            if (plane.kind == SurfaceKind.TABLE && !hasTable) return
            val placed = placeRule.place(plane, point, planes(), head) ?: run {
                CellLog.i("ROBOT refused plane=${plane.label}")
                return
            }
            placeRobot(placed, "tap")
            return
        }
        // placed: only the pins tab acts on desk taps
        if (tab == Tab.PINS && plane.kind == SurfaceKind.TABLE) addPin(Vec3(point.x, plane.maxY, point.z), plane.id)
    }

    private fun placeRobot(placed: Robot, via: String) {
        pushUndo()
        robot = placed
        mode = Mode.PLACED
        tab = Tab.POSE
        deleted = null
        // a new robot replaces a saved cell that was not found here: stop looking for it
        restoreJob?.cancel()
        restoreJob = null
        stored = null
        clearSnack(SnackKind.DELETED)
        clearSnack(SnackKind.NO_TABLE)
        clearSnack(SnackKind.SCANNING)
        clearSnack(SnackKind.ELSEWHERE)
        animSerial++
        freezeAt = null
        if (!settings.coachSeen) coach = true
        emit(CellUiEffect.Sound(SoundKind.PLACE))
        CellLog.i("ROBOT placed via=$via mount=${placed.mount} base=${CellLog.pos(placed.base)} yaw=${CellLog.f(Math.toDegrees(placed.yaw.toDouble()).toFloat(), 1)} pedestal=${CellLog.f(placed.pedestalH)} table=${placed.tableId?.take(8)}")
        createAnchor(placed.base)
        recompute()
        scheduleSave()
        publish()
    }

    private fun addPin(point: Vec3, tableId: String) {
        if (pins.size >= MAX_PINS) {
            showSnack(SnackKind.LIMIT_PINS)
            publish()
            return
        }
        pushUndo()
        val pin = Pin("p${uid++}", point, tableId)
        pins += pin
        emit(CellUiEffect.Sound(SoundKind.PIN))
        CellLog.i("PIN add id=${pin.id} at=${CellLog.pos(point)} reachable=${robot?.let { reach.pinReachable(it, point) }}")
        recompute()
        scheduleSave()
        publish()
    }

    private fun removePin(id: String) {
        val i = pins.indexOfFirst { it.id == id }
        if (i < 0) return
        pushUndo()
        pins.removeAt(i)
        CellLog.i("PIN remove id=$id")
        recompute()
        scheduleSave()
        publish()
    }

    private fun setPose(pose: Pose, freeze: Float?) {
        val r = robot ?: return
        pushUndo()
        robot = r.copy(joints = pose.joints.copyOf())
        animSerial++
        freezeAt = freeze
        posingUntilMs = SystemClock.elapsedRealtime() + (if (freeze != null) Long.MAX_VALUE / 4 else 800L)
        posingJob?.cancel()
        if (freeze == null) {
            posingJob =
                viewModelScope.launch {
                    delay(820L)
                    posingUntilMs = NEVER
                    publish()
                }
        }
        CellLog.i("POSE ${pose.tag} freeze=$freeze")
        recompute()
        scheduleSave()
        publish()
    }

    private fun setJoint(index: Int, degrees: Float) {
        val r = robot ?: return
        val joints = r.joints.copyOf()
        joints[index] = Ur5e.wrap(degrees).coerceIn(-180f, 180f)
        robot = r.copy(joints = joints)
        freezeAt = null
        posingUntilMs = NEVER
        recompute()
        scheduleSave()
        publish()
    }

    private fun deleteRobot() {
        val r = robot ?: return
        deleted = Snapshot(r, pins.toList())
        undo.clear()
        robot = null
        pins.clear()
        hits = emptyList()
        coach = false
        dragging = false
        mode = if (hasTable) Mode.AIMING else if (hasFloor) Mode.NO_TABLE else Mode.SCANNING
        emit(CellUiEffect.Sound(SoundKind.DELETE))
        CellLog.i("ROBOT deleted")
        showSnack(SnackKind.DELETED)
        recompute()
        scheduleSave()
        publish()
    }

    private fun undoLast() {
        val s = undo.removeLastOrNull() ?: return
        robot = s.robot
        pins.clear()
        pins += s.pins
        if (robot != null) mode = Mode.PLACED
        CellLog.i("UNDO depth=${undo.size}")
        recompute()
        scheduleSave()
        publish()
    }

    private fun pushUndo() {
        undo.addLast(Snapshot(robot, pins.toList()))
        while (undo.size > UNDO_DEPTH) undo.removeFirst()
    }

    private fun clearAll() {
        robot = null
        pins.clear()
        undo.clear()
        deleted = null
        hits = emptyList()
        coach = false
        dragging = false
        dialog = null
        menuOpen = false
        restoreJob?.cancel()
        restoreJob = null
        stored = null
        val id = anchorId
        anchorId = null
        anchorPose = null
        if (id != null) viewModelScope.launch { anchors.remove(id) }
        mode = if (hasTable) Mode.AIMING else if (hasFloor) Mode.NO_TABLE else Mode.SCANNING
        clearSnack(SnackKind.ELSEWHERE)
        clearSnack(SnackKind.DELETED)
        showSnack(SnackKind.CLEARED)
        CellLog.i("CLEAR all")
        recompute()
        saveNow()
        publish()
    }

    private fun dismissCoach() {
        if (!coach) return
        coach = false
        settings = settings.copy(coachSeen = true)
        CellLog.i("COACH dismissed")
        scheduleSave()
        publish()
    }

    private fun onSnackAction(id: Int) {
        val s = snack ?: return
        if (s.id != id) return
        when (s.kind) {
            SnackKind.DELETED -> {
                val d = deleted ?: return
                deleted = null
                clearSnack(SnackKind.DELETED)
                robot = d.robot
                pins.clear()
                pins += d.pins
                mode = Mode.PLACED
                animSerial++
                CellLog.i("ROBOT undeleted")
                recompute()
                scheduleSave()
                publish()
            }
            SnackKind.ELSEWHERE -> clearAll()
            SnackKind.UNSAVED -> {
                clearSnack(SnackKind.UNSAVED)
                robot?.let { createAnchor(it.base) }
            }
            else -> Unit
        }
    }

    // =========================================================================================
    // derived geometry
    // =========================================================================================

    private fun planes(): Collection<PlaneSurface> = planeTracker.all

    /** Work disks, wall rings, the wall verdict, the desk share, the pins' reach (design `rules`). */
    private fun recompute() {
        val r = robot
        val planes = planes()
        if (r == null) {
            lastMarksKey = ""
            disks = emptyList()
            rings = emptyList()
            wallVerdict = null
            deskPercent = null
            pinsUi = emptyList()
            edgeReadings = emptyList()
            return
        }
        // the mount desk may have been re-reported with a new polygon: follow the id, not the object
        val table = r.tableId?.let { id -> planes.firstOrNull { it.id == id } }
        // the meshes behind these lists are rebuilt whenever a list changes: keep the same lists while the shoulder,
        // the radius, the mount desk and the planes they are cut from stay the same
        val tables = reach.tables(planes)
        val walls = reach.walls(planes)
        val s = r.shoulder
        val marksKey =
            String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,%s|", s.x, s.y, s.z, r.reachRadius(), r.tableId) +
                tables.joinToString(",") { System.identityHashCode(it).toString() } + "|" +
                walls.joinToString(",") { System.identityHashCode(it).toString() }
        if (marksKey != lastMarksKey) {
            lastMarksKey = marksKey
            disks = tables.mapNotNull { t -> reach.workDisk(r, t) }
            rings = reach.wallRings(r, planes)
        }
        wallVerdict = reach.wallVerdict(r, planes)
        deskPercent = table?.let { reach.deskPercent(r, it) }
        pinsUi = pins.map { p -> PinUi(p.id, p.position, reach.pinReachable(r, p.position), reach.pinShortBy(r, p.position)) }
        edgeReadings = if (dragging && table != null) reach.edgeReadings(r, table) else emptyList()
    }

    // =========================================================================================
    // anchors, saving, restoring
    // =========================================================================================

    private fun createAnchor(at: Vec3) {
        val old = anchorId
        anchorId = null
        anchorPose = null
        viewModelScope.launch {
            if (old != null) runCatching { anchors.remove(old) }
            when (val r = anchors.create(at, "cell")) {
                is AnchorOutcome.Ok -> {
                    anchorId = r.value.id
                    anchorPose = r.value.pose
                    anchorFailed = false
                    CellLog.i("ANCHOR saved id=${r.value.id} at=${CellLog.pos(r.value.pose.position)}")
                    scheduleSave()
                }
                is AnchorOutcome.Failed -> {
                    anchorFailed = true
                    CellLog.w("ANCHOR failed code=${r.code} ${r.message}")
                    showSnack(SnackKind.UNSAVED)
                    scheduleSave()
                    publish()
                }
            }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveNow()
            }
    }

    private fun saveNow() {
        saveJob?.cancel()
        saveJob = null
        val r = robot
        val pose = anchorPose
        val cell =
            if (r == null) null
            else {
                val origin = pose?.position ?: r.base
                val inv = pose?.rotation?.conjugate()
                fun rel(p: Vec3): Vec3 = inv?.rotate(p - origin) ?: (p - origin)
                StoredCell(anchorId, rel(r.base), r.yaw, r.mount, r.pedestalH, r.gripperM, r.joints.copyOf(), pins.map { rel(it.position) }, r.base)
            }
        val session = StoredSession(cell, settings)
        viewModelScope.launch { runCatching { repository.save(session) }.onFailure { CellLog.w("SAVE failed: $it") } }
    }

    private fun startRestore(s: StoredCell) {
        mode = Mode.RESTORING
        CellLog.i("RESTORE start anchor=${s.anchorId}")
        publish()
        if (s.anchorId == null) {
            // no anchor could be made last time: the stored Stage position is the best there is
            restoreAt(s, s.basePos, null)
            return
        }
        restoreJob =
            viewModelScope.launch {
                val start = SystemClock.elapsedRealtime()
                var found = false
                while (!found) {
                    when (val r = anchors.loadAll()) {
                        is AnchorOutcome.Ok -> {
                            val a = r.value.firstOrNull { it.id == s.anchorId }
                            if (a != null) {
                                anchorId = a.id
                                anchorPose = a.pose
                                restoreFromAnchor(a.pose)
                                found = true
                            }
                        }
                        is AnchorOutcome.Failed -> CellLog.w("ANCHOR load failed code=${r.code} ${r.message}")
                    }
                    if (found) break
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (elapsed > RESTORE_TIMEOUT_MS && mode == Mode.RESTORING) {
                        mode = Mode.ELSEWHERE
                        CellLog.i("RESTORE elsewhere")
                        showSnack(SnackKind.ELSEWHERE)
                        publish()
                    }
                    delay(if (mode == Mode.ELSEWHERE) 3000L else 1500L)
                }
                restoreJob = null
            }
    }

    private fun restoreFromAnchor(pose: AnchorPose) {
        val s = stored ?: return
        restoreAt(s, pose.position, pose)
    }

    private fun restoreAt(s: StoredCell, origin: Vec3, pose: AnchorPose?) {
        fun abs(p: Vec3): Vec3 = origin + (pose?.rotation?.rotate(p) ?: p)
        val base = abs(s.offset)
        val planes = planes()
        val table = if (s.mount == Mount.DESK) reach.nearestTable(base, planes, 0.5f) else reach.nearestTable(base, planes, 3f)
        robot = Robot(base, s.mount, s.yaw, s.pedestalH, s.gripperM, s.joints.copyOf(), table?.id)
        pins.clear()
        for (p in s.pins) pins += Pin("p${uid++}", abs(p), reach.nearestTable(abs(p), planes, 0.3f)?.id)
        mode = Mode.PLACED
        animSerial++
        restoreJob?.cancel()
        restoreJob = null
        clearSnack(SnackKind.ELSEWHERE)
        showSnack(SnackKind.RESTORED)
        CellLog.i("RESTORE done base=${CellLog.pos(base)} pins=${pins.size}")
        recompute()
        publish()
    }

    // =========================================================================================
    // snackbar
    // =========================================================================================

    private fun showSnack(kind: SnackKind) {
        if (snack?.kind == kind) return
        snackJob?.cancel()
        val s = SnackUi(++snackSerial, kind)
        snack = s
        CellLog.i("SNACK ${kind.name}")
        if (kind.durationMs != SNACK_STICKY_MS) {
            snackJob =
                viewModelScope.launch {
                    delay(kind.durationMs)
                    if (snack?.id == s.id) {
                        snack = null
                        if (kind == SnackKind.DELETED) deleted = null
                        publish()
                    }
                }
        }
    }

    private fun clearSnack(kind: SnackKind) {
        if (snack?.kind == kind) {
            snack = null
            snackJob?.cancel()
        }
    }

    private fun emit(effect: CellUiEffect) {
        _effects.tryEmit(effect)
    }

    // =========================================================================================
    // debug hooks
    // =========================================================================================

    private fun firstTable(): PlaneSurface? = reach.tables(planes()).maxByOrNull { reach.flat(it).area }

    private fun debugPlace(surface: String, x: Float, z: Float, yawDeg: Float?) {
        val planes = planes()
        val table = firstTable()
        val center = table?.let { reach.flat(it).centroid } ?: (head ?: Vec3.ZERO)
        val plane =
            if (surface == "floor") reach.floor(planes) ?: return CellLog.i("CMD place: no floor")
            else table ?: return CellLog.i("CMD place: no table")
        val y = if (surface == "floor") floorY else plane.maxY
        val point = Vec3(center.x + x, y, center.z + z)
        val placed = placeRule.place(plane, point, planes, head) ?: return CellLog.i("CMD place refused")
        placeRobot(if (yawDeg != null) placed.copy(yaw = Math.toRadians(yawDeg.toDouble()).toFloat()) else placed, "debug")
    }

    /** Moves the robot so that its shoulder is [d] metres from the nearest wall (along the wall normal). */
    private fun debugWall(d: Float) {
        val r = robot ?: run {
            debugPlace("table", 0f, 0f, null)
            robot ?: return
        }
        val w = reach.wallVerdict(r, planes()) ?: return CellLog.i("CMD wall: no wall")
        debugWallPlane = w.wall
        val toward = (w.wallPoint - r.shoulder).let { if (it.length < 1e-4f) Vec3.AHEAD else it.normalized() }
        // slide along the wall so that the shoulder faces the wall itself, not the air beyond its end
        val wf = reach.flat(w.wall)
        val foot = wf.to3(wf.clampInside(wf.to2(w.wallPoint), 0.2f))
        val target = foot - toward * d
        val shift = Vec3(target.x - r.shoulder.x, 0f, target.z - r.shoulder.z)
        var moved = placeRule.constrain(r.copy(base = r.base + Vec3(shift.x, 0f, shift.z)), planes())
        val got = reach.wallVerdict(moved, planes())
        if (got != null && abs(got.d - d) > 0.05f) {
            // the desk ends before the wall: go to the floor (pedestal) at the wanted spot instead
            val floorPlane = reach.floor(planes())
            if (floorPlane != null) {
                val wanted = r.base + Vec3(shift.x, 0f, shift.z)
                val placed = placeRule.place(floorPlane, Vec3(wanted.x, floorPlane.maxY, wanted.z), planes(), head)
                if (placed != null) {
                    val again = reach.wallVerdict(placed, planes())
                    val fix = if (again != null) (again.wallPoint - placed.shoulder).let { if (it.length < 1e-4f) Vec3.ZERO else it.normalized() * (again.d - d) } else Vec3.ZERO
                    moved = placed.copy(base = placed.base + Vec3(fix.x, 0f, fix.z), yaw = r.yaw, joints = r.joints, gripperM = r.gripperM)
                    CellLog.i("CMD wall: moved to the floor at ${CellLog.pos(moved.base)}")
                }
            }
        }
        robot = moved
        recompute()
        publish()
    }

    private fun debugPin(x: Float, z: Float) {
        val r = robot ?: return CellLog.i("CMD pin: no robot")
        val table = r.tableId?.let { id -> planes().firstOrNull { it.id == id } } ?: firstTable() ?: return
        val f = reach.flat(table)
        val p = f.to3(f.clampInside(f.to2(Vec3(r.base.x + x, table.maxY, r.base.z + z)), 0.01f))
        addPin(Vec3(p.x, table.maxY, p.z), table.id)
    }

    /** `goto Sxx`: sets the model up for the design's state (design `qaHooks`). */
    private fun debugGoto(name: String) {
        val s = name.uppercase(Locale.US)
        forcedState = null
        CellLog.i("CMD goto $s")
        fun ensureRobot(floor: Boolean = false) {
            if (robot == null || (floor && robot!!.mount != Mount.FLOOR) || (!floor && robot!!.mount != Mount.DESK)) {
                if (floor) {
                    // on the floor one metre from the desk's centre towards the user, so that it stays in view
                    val c = firstTable()?.let { reach.flat(it).centroid } ?: Vec3.ZERO
                    val h = head ?: Vec3.ZERO
                    val d = Vec3(h.x - c.x, 0f, h.z - c.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                    debugPlace("floor", d.x * 0.7f, d.z * 0.7f, null)
                } else {
                    debugPlace("table", 0f, 0f, null)
                }
            }
            coach = false
            menuOpen = false
            dialog = null
            dragging = false
            tab = Tab.POSE
            // every designed state starts from the upright pose with no gripper (a previous S09 leaves the reach pose)
            robot?.let { r ->
                if (!Ur5e.same(r.joints, Pose.UPRIGHT.joints) || r.gripperM != 0f) {
                    robot = r.copy(joints = Pose.UPRIGHT.joints.copyOf(), gripperM = 0f)
                    animSerial++
                    freezeAt = null
                    posingUntilMs = NEVER
                }
            }
        }
        when (s) {
            "S01" -> {
                robot = null
                pins.clear()
                mode = Mode.STARTING
                clearSnack(SnackKind.SCANNING)
                clearSnack(SnackKind.NO_TABLE)
                forcedState = "S01"
            }
            "S02" -> {
                robot = null
                pins.clear()
                mode = Mode.SCANNING
                showSnack(SnackKind.SCANNING)
                forcedState = "S02"
            }
            "S03" -> {
                robot = null
                pins.clear()
                mode = Mode.AIMING
            }
            "S04" -> {
                robot = null
                pins.clear()
                mode = Mode.NO_TABLE
                showSnack(SnackKind.NO_TABLE)
            }
            "S05" -> {
                ensureRobot()
                setPose(Pose.UPRIGHT, null)
            }
            "S06" -> {
                robot = null
                ensureRobot(floor = true)
            }
            "S07" -> {
                ensureRobot()
                debugWall(0.78f)
            }
            "S08" -> {
                ensureRobot()
                setPose(Pose.REACH, 0.5f)
            }
            "S09" -> {
                ensureRobot()
                debugWall(0.6f)
                val r = robot
                if (r != null) {
                    // turn in 15 degree steps until the reach pose runs into a wall (the scene's own collision rule)
                    val frames = Ur5e.frames(Pose.REACH.joints)
                    val step = Math.toRadians(15.0).toFloat()
                    var best: Float? = null
                    for (k in 0 until 24) {
                        val yaw = r.yaw + step * k
                        val hits = reach.collisions(r.copy(yaw = yaw, joints = Pose.REACH.joints.copyOf()), frames, planes())
                        if (hits.any { it.kind == io.embodit.robotcell.domain.usecase.HitKind.WALL }) {
                            best = yaw
                            break
                        }
                    }
                    CellLog.i("CMD S09 heading ${best?.let { CellLog.f(Math.toDegrees(it.toDouble()).toFloat(), 0) } ?: "none hits a wall"}")
                    if (best != null) robot = r.copy(yaw = best)
                }
                setPose(Pose.REACH, null)
            }
            "S10" -> {
                ensureRobot()
                tab = Tab.JOINTS
                selectedJoint = 1
            }
            "S11" -> {
                ensureRobot()
                tab = Tab.PINS
                pins.clear()
                val r = robot!!
                val f = Robot.forwardOf(r.yaw)
                val side = Vec3.UP.cross(f).normalized()
                debugPin(f.x * 0.45f, f.z * 0.45f)
                debugPin(f.x * 0.3f + side.x * 0.35f, f.z * 0.3f + side.z * 0.35f)
                debugPin(f.x * 0.3f - side.x * 0.35f, f.z * 0.3f - side.z * 0.35f)
                debugPin(f.x * 0.98f + side.x * 0.25f, f.z * 0.98f + side.z * 0.25f)
                // a small desk may have no unreachable spot: the red pin then goes on the nearest other desk out of reach
                if (pins.isNotEmpty() && reach.pinReachable(r, pins.last().position)) {
                    val other =
                        reach.tables(planes())
                            .map { t -> t to reach.flat(t).centroid.let { c -> Vec3(c.x, t.maxY, c.z) } }
                            .filter { (t, c) -> t.id != r.tableId && !reach.pinReachable(r, c) }
                            .minByOrNull { (_, c) -> c.distanceTo(r.base) }
                    if (other != null) {
                        pins.removeAt(pins.size - 1)
                        addPin(other.second, other.first.id)
                    }
                }
            }
            "S12" -> {
                ensureRobot()
                dragging = true
            }
            "S13" -> {
                ensureRobot()
                menuOpen = true
            }
            "S14" -> {
                ensureRobot()
                dialog = DialogKind.CLEAR
            }
            "S15" -> {
                ensureRobot()
                deleteRobot()
            }
            "S16" -> {
                robot = null
                mode = Mode.RESTORING
                forcedState = "S16"
            }
            "S17" -> {
                ensureRobot()
                showSnack(SnackKind.RESTORED)
                forcedState = "S17"
            }
            "S18" -> {
                robot = null
                mode = Mode.ELSEWHERE
                showSnack(SnackKind.ELSEWHERE)
            }
            "S19" -> {
                ensureRobot()
                onEvent(CellEvent.DebugTracking(true))
            }
            "S20" -> {
                dialog = DialogKind.UNAVAILABLE
                forcedState = "S20"
            }
            "S21" -> {
                ensureRobot()
                coach = true
            }
            else -> CellLog.i("CMD goto unknown $s")
        }
        if (s != "S19" && trackingLost) onEvent(CellEvent.DebugTracking(false))
        recompute()
        publish()
    }

    // =========================================================================================
    // state out
    // =========================================================================================

    private fun stateName(): String {
        forcedState?.let { return it }
        if (dialog == DialogKind.UNAVAILABLE) return "S20"
        if (trackingLost) return "S19"
        return when (mode) {
            Mode.STARTING -> "S01"
            Mode.SCANNING -> "S02"
            Mode.AIMING -> if (snack?.kind == SnackKind.DELETED) "S15" else "S03"
            Mode.NO_TABLE -> if (snack?.kind == SnackKind.DELETED) "S15" else "S04"
            Mode.RESTORING -> "S16"
            Mode.ELSEWHERE -> "S18"
            Mode.PLACED -> {
                val r = robot
                when {
                    dialog == DialogKind.CLEAR -> "S14"
                    menuOpen -> "S13"
                    snack?.kind == SnackKind.RESTORED -> "S17"
                    coach -> "S21"
                    dragging -> "S12"
                    tab == Tab.JOINTS -> "S10"
                    tab == Tab.PINS -> "S11"
                    posingUntilMs != NEVER && SystemClock.elapsedRealtime() < posingUntilMs -> "S08"
                    hits.isNotEmpty() -> "S09"
                    wallVerdict?.reachesWall == true -> "S07"
                    r?.mount == Mount.FLOOR -> "S06"
                    else -> "S05"
                }
            }
        }
    }

    private fun verdict(): VerdictUi {
        val r = robot ?: return VerdictUi(null, WallState.NONE, 0f, 0, 0)
        val w = wallVerdict
        val wallState =
            when {
                w == null -> WallState.NONE
                w.d < w.r -> WallState.IN
                w.d < w.r + 0.5f -> WallState.NEAR
                else -> WallState.FAR
            }
        val wallM = when (wallState) {
            WallState.IN -> w!!.over
            WallState.NEAR -> w!!.d - w.r
            else -> 0f
        }
        return VerdictUi(deskPercent, wallState, wallM, pinsUi.count { it.reachable }, pinsUi.size).also { if (r.tableId == null) Unit }
    }

    private fun publish() {
        val r = robot
        val name = stateName()
        if (name != lastStateName) {
            lastStateName = name
            CellLog.i("STATE $name mode=$mode tab=$tab robot=${r != null} pins=${pins.size} hits=${hits.size}")
        }
        _state.value =
            CellUiState(
                mode = mode,
                tab = tab,
                robot = r?.let { RobotUi(it, animSerial, freezeAt) },
                pins = pinsUi,
                verdict = verdict(),
                hits = HitUi.of(hits),
                hitPoint = hits.firstOrNull()?.point,
                selectedJoint = selectedJoint,
                menuOpen = menuOpen,
                dialog = dialog,
                snack = snack,
                coach = coach && r != null,
                trackingLost = trackingLost,
                blocked = CellGraph.head.blockReason(),
                language = settings.language,
                units = settings.units,
                showSphere = settings.showSphere,
                dragging = dragging,
                canUndo = undo.isNotEmpty(),
                hasTable = hasTable,
                hasFloor = hasFloor,
                hasWall = hasWall,
                wallVerdict = wallVerdict,
                edgeReadings = edgeReadings,
                disks = disks,
                rings = rings,
                stateName = name,
            )
    }

    /** The whole model as one JSON line (debug `dump`). */
    fun dumpJson(): String {
        val o = JSONObject()
        o.put("state", stateName())
        o.put("mode", mode.name)
        o.put("tab", tab.name)
        robot?.let { r ->
            o.put(
                "robot",
                JSONObject()
                    .put("base", vec(r.base))
                    .put("mount", r.mount.tag)
                    .put("yawDeg", Math.toDegrees(r.yaw.toDouble()))
                    .put("pedestal", r.pedestalH.toDouble())
                    .put("gripper", r.gripperM.toDouble())
                    .put("joints", JSONArray().apply { r.joints.forEach { put(it.toDouble()) } })
                    .put("pose", r.pose?.tag ?: JSONObject.NULL)
                    .put("table", r.tableId ?: JSONObject.NULL),
            )
        }
        o.put("deskPercent", deskPercent ?: JSONObject.NULL)
        wallVerdict?.let { w -> o.put("wall", JSONObject().put("d", w.d.toDouble()).put("r", w.r.toDouble()).put("over", w.over.toDouble())) }
        o.put("pins", JSONArray().apply { pinsUi.forEach { put(JSONObject().put("id", it.id).put("pos", vec(it.position)).put("reachable", it.reachable)) } })
        o.put("hits", JSONArray().apply { hits.forEach { put(JSONObject().put("segment", it.segment).put("kind", it.kind.name)) } })
        o.put("anchor", anchorId ?: JSONObject.NULL)
        o.put("snack", snack?.kind?.name ?: JSONObject.NULL)
        o.put("dialog", dialog?.name ?: JSONObject.NULL)
        o.put("menu", menuOpen)
        o.put("coach", coach)
        o.put("lost", trackingLost)
        o.put("planes", JSONObject().put("table", hasTable).put("floor", hasFloor).put("wall", hasWall).put("floorY", floorY.toDouble()))
        head?.let { o.put("head", vec(it)) }
        return o.toString()
    }

    private fun vec(v: Vec3): JSONObject = JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("z", v.z.toDouble())

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val MAX_PINS = 8
        private const val UNDO_DEPTH = 20
        private const val SAVE_DEBOUNCE_MS = 500L
        private const val RESTORE_TIMEOUT_MS = 10_000L
        private const val BLOCK_GRACE_MS = 4000L
    }
}
