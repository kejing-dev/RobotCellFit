package io.embodit.robotcell.ui.cell

import io.embodit.robotcell.domain.model.Approach
import io.embodit.robotcell.domain.model.BlockReason
import io.embodit.robotcell.domain.model.LanguagePref
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.domain.model.VirtualRoom
import io.embodit.robotcell.domain.usecase.DiskGeometry
import io.embodit.robotcell.domain.usecase.HitKind
import io.embodit.robotcell.domain.usecase.LinkHit
import io.embodit.robotcell.domain.usecase.PinLevel
import io.embodit.robotcell.domain.usecase.PinPlan
import io.embodit.robotcell.domain.usecase.PinReason
import io.embodit.robotcell.domain.usecase.Trajectory
import io.embodit.robotcell.domain.usecase.WallRing
import io.embodit.robotcell.domain.usecase.WallVerdict

/** Base modes of the design's states (S01 - S21); the panel tab and the flags refine PLACED. */
enum class Mode {
    /** S01: scene and resources coming up. */
    STARTING,
    /** S02: no TABLE and no FLOOR yet. */
    SCANNING,
    /** S03: a desk is known, no robot yet. */
    AIMING,
    /** S04: only the floor is known. */
    NO_TABLE,
    /** S05 - S12: the robot stands and is selected. */
    PLACED,
    /** S16: looking for the saved anchor. */
    RESTORING,
    /** S18: the saved cell is not in this room. */
    ELSEWHERE,
}

enum class Tab { POSE, JOINTS, PINS, RUN }

/** What the auto run is playing. */
enum class RunKind { PINS, PATH, GOTO }

enum class DialogKind { CLEAR, UNAVAILABLE }

/** A snackbar with this duration stays until its state goes away. */
const val SNACK_STICKY_MS = -1L

enum class SnackKind(val durationMs: Long, val hasAction: Boolean = false) {
    SCANNING(SNACK_STICKY_MS),
    NO_TABLE(SNACK_STICKY_MS),
    ELSEWHERE(SNACK_STICKY_MS, hasAction = true),
    UNSAVED(6000L, hasAction = true),
    LOST(SNACK_STICKY_MS),
    CLEARED(3000L),
    RESTORED(3000L),
    LIMIT_PINS(3000L),
    DELETED(6000L, hasAction = true),
    /** No desk after the scan timeout: a virtual room stands in (factory rule D7). */
    VIRTUAL_ROOM(6000L),
    /** A desk was scanned: the virtual room is gone. */
    REAL_DESK(3000L),
    /** Run pins with no reachable pin. */
    RUN_NONE(3000L),
    /** Go to a pin that has no solution. */
    PIN_UNREACHABLE(3000L),
    /** Some MoveL segments ran as MoveJ (no straight-line path). */
    RUN_FALLBACK(4000L),
}

data class SnackUi(val id: Int, val kind: SnackKind)

enum class WallState { NONE, IN, NEAR, FAR }

/** The three verdict chips (design `panels.cell.layouts.verdictChips`). */
data class VerdictUi(
    /** Share of the desk top within reach, or null when no desk is known / the sphere misses it. */
    val deskPercent: Int?,
    val wall: WallState,
    /** IN: how far past the wall the full extension goes; NEAR: the gap to the wall. Metres. */
    val wallM: Float,
    val pinsOk: Int,
    val pinsTotal: Int,
)

/** A pin with its kinematic verdict (v0.1.2): [number] is its 1-based label. */
data class PinUi(val id: String, val number: Int, val position: Vec3, val plan: PinPlan) {
    val reachable: Boolean
        get() = plan.reachable

    val level: PinLevel
        get() = plan.level

    val reason: PinReason
        get() = plan.reason

    val shortByM: Float
        get() = plan.shortByM
}

/** A program for the scene to play; a new [serial] starts it, [loop] repeats it from `trajectory.loopFrom`. */
class MotionUi(val serial: Int, val kind: RunKind, val trajectory: Trajectory, val loop: Boolean)

/** What the scene needs of the robot plus the animation request. */
data class RobotUi(
    val robot: Robot,
    /** Bumped when a preset was chosen: the scene eases from the shown pose to `robot.joints` in 0.8 s. */
    val animSerial: Int,
    /** Debug: freeze the easing at this share of the way. */
    val freezeAt: Float?,
) {
    val mount: Mount
        get() = robot.mount

    val pose: Pose?
        get() = robot.pose
}

data class HitUi(val segment: Int, val kind: HitKind) {
    companion object {
        fun of(hits: List<LinkHit>): List<HitUi> = hits.map { HitUi(it.segment, it.kind) }
    }
}

data class CellUiState(
    val mode: Mode = Mode.STARTING,
    val tab: Tab = Tab.POSE,
    val robot: RobotUi? = null,
    val pins: List<PinUi> = emptyList(),
    val verdict: VerdictUi = VerdictUi(null, WallState.NONE, 0f, 0, 0),
    val hits: List<HitUi> = emptyList(),
    /** First contact point of the pose collision (the hit tag hangs 6 cm above it). */
    val hitPoint: Vec3? = null,
    val selectedJoint: Int = 1,
    val menuOpen: Boolean = false,
    val dialog: DialogKind? = null,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    val trackingLost: Boolean = false,
    val blocked: BlockReason? = null,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val units: UnitPref = UnitPref.CM,
    val showSphere: Boolean = true,
    /** Sound on (the mute key on the main panel, D15). */
    val sound: Boolean = true,
    val dragging: Boolean = false,
    val canUndo: Boolean = false,
    val hasTable: Boolean = false,
    val hasFloor: Boolean = false,
    val hasWall: Boolean = false,
    /** Nearest wall and the two desk edges (while dragging) for the tapes and plates. */
    val wallVerdict: WallVerdict? = null,
    val edgeReadings: List<Pair<Vec3, Float>> = emptyList(),
    val disks: List<DiskGeometry> = emptyList(),
    val rings: List<WallRing> = emptyList(),
    // ---- v0.1.2: pins, auto run, gripper, virtual room
    val selectedPin: String? = null,
    val approach: Approach = Approach.TOP,
    val motion: MotionUi? = null,
    val waypoints: Int = 0,
    val linear: Boolean = false,
    val loop: Boolean = true,
    /** Finger opening while no program plays, metres. */
    val gripWidth: Float = 0.085f,
    val jogging: Boolean = false,
    val virtualRoom: VirtualRoom? = null,
    val virtualSize: Int = VirtualRoom.DEFAULT_SIZE,
    /** The design's state id of the moment (S01 ... S21), for logs and QA. */
    val stateName: String = "S01",
) {
    val placed: Boolean
        get() = mode == Mode.PLACED && robot != null

    /** The compact aiming panel is shown in every unplaced mode. */
    val compact: Boolean
        get() = !placed

    val aimEnabled: Boolean
        get() = mode == Mode.AIMING || mode == Mode.NO_TABLE

    val collides: Boolean
        get() = hits.isNotEmpty()

    val running: Boolean
        get() = motion != null

    val selected: PinUi?
        get() = pins.firstOrNull { it.id == selectedPin }
}

sealed interface CellUiEffect {
    data object ExitApp : CellUiEffect

    data class Sound(val kind: SoundKind) : CellUiEffect
}

enum class SoundKind { PLACE, DELETE, PIN, HIT, DONE, FAIL }

/** Everything that can happen: panel controls, scene callbacks, lifecycle and the debug hooks. */
sealed interface CellEvent {
    // ---- scene
    data object SceneReady : CellEvent

    data class PlanesChanged(val count: Int, val hasTable: Boolean, val hasFloor: Boolean, val hasWall: Boolean, val floorY: Float) : CellEvent

    data class TrackingChanged(val lost: Boolean) : CellEvent

    data class SurfaceTapped(val planeId: String, val point: Vec3) : CellEvent

    data object NothingTapped : CellEvent

    data class PinTapped(val id: String) : CellEvent

    data object RobotTapped : CellEvent

    data object DragStarted : CellEvent

    data class DragMoved(val delta: Vec3) : CellEvent

    data object DragEnded : CellEvent

    /** Thumbstick: turn the robot by [radians] about its vertical axis this frame. */
    data class StickTurn(val radians: Float) : CellEvent

    data class StickReleased(val snap: Boolean) : CellEvent

    data class HitsChanged(val hits: List<LinkHit>) : CellEvent

    data class HeadSample(val position: Vec3, val forward: Vec3) : CellEvent

    // ---- panel
    data object MoreClicked : CellEvent

    data object MenuDismissed : CellEvent

    data class TabSelected(val tab: Tab) : CellEvent

    data class PoseClicked(val pose: Pose) : CellEvent

    data class JointSelected(val index: Int) : CellEvent

    data class JointChanged(val degrees: Float) : CellEvent

    data class JointStepped(val degrees: Float) : CellEvent

    data object JointsZeroed : CellEvent

    data object GripperCycled : CellEvent

    data class RotateClicked(val degrees: Float) : CellEvent

    data object DeleteClicked : CellEvent

    data object UndoClicked : CellEvent

    data object ClearPinsClicked : CellEvent

    data object ClearAllClicked : CellEvent

    data object ClearConfirmed : CellEvent

    data object DialogDismissed : CellEvent

    data object ExitClicked : CellEvent

    data object RetryClicked : CellEvent

    data object CoachDismissed : CellEvent

    data class SnackActionClicked(val id: Int) : CellEvent

    data class LanguageSelected(val language: LanguagePref) : CellEvent

    data class UnitsSelected(val units: UnitPref) : CellEvent

    data class SphereToggled(val on: Boolean) : CellEvent

    data object SoundToggled : CellEvent

    // ---- v0.1.2: pins, auto run, jog, virtual room
    data object ApproachToggled : CellEvent

    data object GoToPinClicked : CellEvent

    data object RemovePinClicked : CellEvent

    data object RunPinsClicked : CellEvent

    data object PlayPathClicked : CellEvent

    data object StopClicked : CellEvent

    data object WaypointAdded : CellEvent

    data object PathCleared : CellEvent

    data class LinearSelected(val linear: Boolean) : CellEvent

    data object LoopToggled : CellEvent

    data object GripToggled : CellEvent

    /** The scene stopped or finished program [serial] at [joints] with the fingers at [grip]. */
    data class MotionEnded(val serial: Int, val joints: FloatArray, val grip: Float, val finished: Boolean) : CellEvent

    data object JogStarted : CellEvent

    /** The TCP drag ended; the arm stands at [joints] (solved per frame in the scene). */
    data class JogEnded(val joints: FloatArray) : CellEvent

    data class VirtualToggled(val on: Boolean) : CellEvent

    data class VirtualSizeSelected(val index: Int) : CellEvent

    data class VirtualHeightStepped(val deltaM: Float) : CellEvent

    data object VirtualHereClicked : CellEvent

    data object VirtualDeskDragStarted : CellEvent

    data class VirtualDeskDragged(val delta: Vec3) : CellEvent

    data object VirtualDeskDragEnded : CellEvent

    // ---- lifecycle
    data object WentBackground : CellEvent

    data object CameForeground : CellEvent

    // ---- debug hooks (design `qaHooks`)
    data class DebugGoto(val state: String) : CellEvent

    data class DebugPlace(val surface: String, val x: Float, val z: Float, val yawDeg: Float?) : CellEvent

    data class DebugPose(val pose: Pose, val yawDeg: Float?, val freeze: Float?) : CellEvent

    data class DebugWall(val d: Float) : CellEvent

    data class DebugPin(val x: Float, val z: Float) : CellEvent

    data object DebugCoachReset : CellEvent

    data class DebugTracking(val lost: Boolean?) : CellEvent

    data object DebugClear : CellEvent

    data class DebugDragging(val on: Boolean) : CellEvent

    data class DebugSelectPin(val index: Int) : CellEvent

    /** Moves the TCP by a world offset through IK, as a drag would (metres). */
    data class DebugJog(val delta: Vec3) : CellEvent
}
