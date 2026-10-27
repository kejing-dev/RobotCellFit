package io.embodit.robotcell.ui.cell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.SliderDefaults
import com.pico.spatial.ui.design.Text
import androidx.compose.foundation.background
import io.embodit.robotcell.R
import io.embodit.robotcell.domain.model.Approach
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.CellUiState
import io.embodit.robotcell.domain.usecase.PinLevel
import io.embodit.robotcell.domain.usecase.PinReason
import io.embodit.robotcell.ui.cell.Mode
import io.embodit.robotcell.ui.cell.PinUi
import io.embodit.robotcell.ui.cell.RunKind
import io.embodit.robotcell.ui.cell.Tab
import io.embodit.robotcell.ui.cell.WallState
import io.embodit.robotcell.ui.cell.debug.probe
import io.embodit.robotcell.ui.cell.scene.PanelSpec
import io.embodit.robotcell.ui.theme.CellColors

/**
 * The compact aiming panel (400 x 120 dp at 0.75; S01 - S04, S16, S18): one line "Tap a desk to place
 * the UR5e", a secondary line, the More key. Half transparent while nothing can be placed yet.
 */
@Composable
internal fun AimPanel(state: CellUiState, filled: Boolean, onEvent: (CellEvent) -> Unit) {
    val (w, h) = PanelSpec.AIM_SIZE
    Glass(w.dp, h.dp, corner = 28.dp) {
        if (!filled) return@Glass
        val busy = state.mode == Mode.STARTING || state.mode == Mode.RESTORING
        val enabled = state.aimEnabled
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight).alpha(if (enabled || busy) 1f else 0.55f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
            } else {
                Icon(painter = painterResource(R.drawable.ic_arm), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
            Text(
                text = if (state.mode == Mode.RESTORING) rs(R.string.aim_restoring) else if (state.mode == Mode.STARTING) rs(R.string.aim_starting) else rs(R.string.aim_title),
                color = PicoTheme.colorScheme.labelPrimary,
                style = CellType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Key("more", R.drawable.ic_more, rs(R.string.action_more), enabled = state.mode != Mode.STARTING) { onEvent(CellEvent.MoreClicked) }
        }
        Text(
            text = rs(R.string.aim_sub),
            color = PicoTheme.colorScheme.labelSecondary,
            style = CellType.figure,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 44.dp).alpha(if (enabled) 1f else 0.55f),
        )
    }
}

/**
 * The cell panel (400 x 400 dp at 0.75; S05 - S12): name badge row, the three verdict chips, the
 * pose / joints / pins segments, the tab content, the hint line and the bottom action row.
 */
@Composable
internal fun CellPanel(state: CellUiState, filled: Boolean, onEvent: (CellEvent) -> Unit) {
    val (w, h) = PanelSpec.CELL_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        val r = state.robot ?: return@Glass
        val disabled = state.trackingLost
        // row 1: name badge, collision flag, undo, more
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = rs(R.string.robot_name) + " · " + if (r.mount == Mount.DESK) rs(R.string.mount_desk) else rs(R.string.mount_floor),
                color = PicoTheme.colorScheme.labelPrimary,
                style = CellType.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.collides) {
                Chip(iconRes = R.drawable.ic_cross, label = rs(R.string.badge_collides), value = null, color = PicoTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.weight(1f))
            Key("undo", R.drawable.ic_undo, rs(R.string.action_undo), enabled = state.canUndo && !disabled) { onEvent(CellEvent.UndoClicked) }
            Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(CellEvent.MoreClicked) }
        }
        // row 2: verdict chips
        VerdictRow(state)
        // row 3: tabs
        Segments(
            items = listOf("tab_pose" to rs(R.string.tab_pose), "tab_joints" to rs(R.string.tab_joints), "tab_pins" to rs(R.string.tab_pins), "tab_run" to rs(R.string.tab_run)),
            selected = state.tab.ordinal,
            onSelect = { onEvent(CellEvent.TabSelected(Tab.entries[it])) },
            itemWidth = 90.dp,
            height = 40.dp,
        )
        // content 136
        Column(modifier = Modifier.fillMaxWidth().height(136.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.tab) {
                Tab.POSE -> PoseGrid(r.pose, disabled, onEvent)
                Tab.JOINTS -> JointsTab(state, disabled, onEvent)
                Tab.PINS -> PinsTab(state, disabled, onEvent)
                Tab.RUN -> RunTab(state, disabled, onEvent)
            }
        }
        // hint line (factory rule D1)
        Text(
            text = rs(R.string.hint_stick),
            color = PicoTheme.colorScheme.labelSecondary,
            style = CellType.figure,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(16.dp),
        )
        // bottom row
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("rot_ccw", R.drawable.ic_rot_ccw, rs(R.string.action_rot_ccw), enabled = !disabled) { onEvent(CellEvent.RotateClicked(15f)) }
            Key("rot_cw", R.drawable.ic_rot_cw, rs(R.string.action_rot_cw), enabled = !disabled) { onEvent(CellEvent.RotateClicked(-15f)) }
            val g = r.robot.gripperM
            WordButton(
                "gripper",
                if (g < 0.005f) rs(R.string.gripper_none) else rs(R.string.gripper_len, rs(R.string.gripper_2f)),
                { onEvent(CellEvent.GripperCycled) },
                enabled = !disabled,
                minWidth = 128.dp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Key("delete", R.drawable.ic_trash, rs(R.string.action_delete), enabled = !disabled) { onEvent(CellEvent.DeleteClicked) }
        }
    }
}

/** The three verdict chips: desk share, wall, pins (design `layouts.verdictChips`). */
@Composable
private fun VerdictRow(state: CellUiState) {
    val v = state.verdict
    val neutral = PicoTheme.colorScheme.fillTertiary
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val p = v.deskPercent
        val deskColor =
            when {
                p == null -> neutral
                p >= 60 -> PicoTheme.colorScheme.passable
                p >= 30 -> PicoTheme.colorScheme.alert
                else -> PicoTheme.colorScheme.error
            }
        Chip(
            iconRes = if (p == null) R.drawable.ic_half else if (p >= 60) R.drawable.ic_check else if (p >= 30) R.drawable.ic_half else R.drawable.ic_cross,
            label = if (p == null) rs(R.string.chip_desk_none) else rs(R.string.chip_desk, p),
            value = null,
            color = deskColor,
            neutral = p == null,
        )
        val wallText =
            when (v.wall) {
                WallState.NONE -> rs(R.string.chip_wall_none)
                WallState.IN -> rs(R.string.chip_wall_in, fmtLen(v.wallM, state.units))
                WallState.NEAR -> rs(R.string.chip_wall_ok, fmtLen(v.wallM, state.units))
                WallState.FAR -> rs(R.string.chip_wall_far)
            }
        Chip(
            iconRes = R.drawable.ic_wall,
            label = wallText,
            value = null,
            color = when (v.wall) {
                WallState.NONE -> neutral
                WallState.IN -> PicoTheme.colorScheme.alert
                else -> PicoTheme.colorScheme.passable
            },
            neutral = v.wall == WallState.NONE,
        )
        if (v.pinsTotal > 0) {
            Chip(
                iconRes = R.drawable.ic_pin,
                label = rs(R.string.chip_pins, v.pinsOk, v.pinsTotal),
                value = null,
                color = when {
                    v.pinsOk == v.pinsTotal -> PicoTheme.colorScheme.passable
                    v.pinsOk == 0 -> PicoTheme.colorScheme.error
                    else -> PicoTheme.colorScheme.alert
                },
            )
        }
    }
}

/** A verdict chip: 40 high, 20 dp corners, icon 20 + label, content in stripe ink on a semantic fill. */
@Composable
internal fun Chip(iconRes: Int, label: String, value: String?, color: Color, neutral: Boolean = false) {
    val content = if (neutral) PicoTheme.colorScheme.labelPrimary else CellColors.StripeInk
    Row(
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(color).padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        Text(text = label, color = content, style = CellType.title, maxLines = 1, softWrap = false)
        if (value != null) Text(text = value, color = content, style = CellType.title, maxLines = 1, softWrap = false)
    }
}

/** 2 x 2 preset tiles (180 x 64): side-view pictogram 32 + name; the current pose gets the interaction frame. */
@Composable
private fun ColumnScope.PoseGrid(current: Pose?, disabled: Boolean, onEvent: (CellEvent) -> Unit) {
    val rows = listOf(listOf(Pose.UPRIGHT, Pose.TUCKED), listOf(Pose.REACH, Pose.PICK))
    for (row in rows) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pose in row) {
                val selected = pose == current
                val onClick = { onEvent(CellEvent.PoseClicked(pose)) }
                val content = if (disabled) PicoTheme.colorScheme.labelQuaternary else PicoTheme.colorScheme.labelPrimary
                Button(
                    onClick = onClick,
                    modifier = Modifier.weight(1f).fillMaxHeight().probe("pose_${pose.tag}", onClick),
                    size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 160.dp),
                    shape = RoundedCornerShape(20.dp),
                    enabled = !disabled,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (selected) PicoTheme.colorScheme.interaction.copy(alpha = 0.32f) else PicoTheme.colorScheme.fillTertiary,
                            contentColor = content,
                        ),
                ) {
                    Icon(painter = painterResource(poseIcon(pose)), contentDescription = null, modifier = Modifier.size(32.dp), tint = if (selected) PicoTheme.colorScheme.interaction else content)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = poseName(pose), color = content, style = CellType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** J1 - J6 segments, the big slider with -5 / +5, the angle line with Zero (design `tabs.joints`). */
@Composable
private fun ColumnScope.JointsTab(state: CellUiState, disabled: Boolean, onEvent: (CellEvent) -> Unit) {
    val r = state.robot ?: return
    val j = state.selectedJoint.coerceIn(0, 5)
    Segments(
        items = (1..6).map { "joint_$it" to "J$it" },
        selected = j,
        onSelect = { onEvent(CellEvent.JointSelected(it)) },
        itemWidth = 60.dp,
        height = 40.dp,
    )
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("joint_minus", R.drawable.ic_minus, rs(R.string.action_minus5), enabled = !disabled) { onEvent(CellEvent.JointStepped(-5f)) }
        Slider(
            value = r.robot.joints[j].coerceIn(-180f, 180f),
            onValueChange = { onEvent(CellEvent.JointChanged(it)) },
            valueRange = -180f..180f,
            onValueChangeFinished = {},
            enabled = !disabled,
            sliderSpec = SliderDefaults.Regular,
            modifier = Modifier.weight(1f).probe("joint_slider"),
        )
        Key("joint_plus", R.drawable.ic_plus, rs(R.string.action_plus5), enabled = !disabled) { onEvent(CellEvent.JointStepped(5f)) }
    }
    Row(modifier = Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = jointName(j), color = PicoTheme.colorScheme.labelPrimary, style = CellType.figure, maxLines = 1)
        Text(text = fmtDeg(r.robot.joints[j]), color = PicoTheme.colorScheme.labelPrimary, style = CellType.angle, maxLines = 1, softWrap = false)
        Spacer(modifier = Modifier.weight(1f))
        WordButton("joint_zero", rs(R.string.action_zero), { onEvent(CellEvent.JointsZeroed) }, enabled = !disabled, minWidth = 88.dp, height = 32.dp)
    }
}

/**
 * The pins tab (v0.1.2): tapping the desk drops a pin, tapping a pin selects it. The selected pin's verdict
 * (traffic light + reason, from IK, joint limits, singularities and collisions) and its metrics; the approach
 * (from top / from side), Go (the arm moves there and closes the fingers), remove, clear.
 */
@Composable
private fun ColumnScope.PinsTab(state: CellUiState, disabled: Boolean, onEvent: (CellEvent) -> Unit) {
    val v = state.verdict
    val sel = state.selected
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sel == null) {
            Text(text = if (v.pinsTotal == 0) rs(R.string.pins_hint) else rs(R.string.pin_none_selected), color = PicoTheme.colorScheme.labelPrimary, style = CellType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Chip(
                iconRes = levelIcon(sel.level),
                label = rs(R.string.pin_label, sel.number) + " · " + reasonText(sel, state),
                value = null,
                color = levelColor(sel.level),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val metrics =
            if (sel != null && sel.plan.grasp != null) {
                rs(R.string.pin_metrics, fmtDeg(sel.plan.wristDeg), fmtDeg(sel.plan.elbowDeg), String.format(java.util.Locale.US, "%.1f", sel.plan.kappa))
            } else {
                if (v.pinsTotal == 0) rs(R.string.pins_none) else rs(R.string.pins_count, v.pinsTotal, v.pinsOk)
            }
        Text(text = metrics, color = PicoTheme.colorScheme.labelSecondary, style = CellType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton(
            "pin_approach",
            if (state.approach == Approach.TOP) rs(R.string.approach_top) else rs(R.string.approach_side),
            { onEvent(CellEvent.ApproachToggled) },
            enabled = !disabled && !state.running,
            minWidth = 120.dp,
        )
        WordButton("pin_go", rs(R.string.action_go), { onEvent(CellEvent.GoToPinClicked) }, primary = true, enabled = !disabled && !state.running && (sel?.reachable ?: (v.pinsOk > 0)), minWidth = 96.dp)
        Spacer(modifier = Modifier.weight(1f))
        Key("pin_remove", R.drawable.ic_trash, rs(R.string.action_remove_pin), enabled = sel != null && !disabled) { onEvent(CellEvent.RemovePinClicked) }
        Key("clear_pins", R.drawable.ic_close, rs(R.string.action_clear_pins), enabled = v.pinsTotal > 0 && !disabled) { onEvent(CellEvent.ClearPinsClicked) }
    }
}

/**
 * The run tab (v0.1.2): run the reachable pins (pre-grasp MoveJ, MoveL down, close, MoveL up, open), play the
 * taught path (MoveJ / MoveL, loop), stop, open / close the fingers; teach a point (the current pose), clear.
 * Dragging the yellow ball at the fingertips moves the arm by inverse kinematics.
 */
@Composable
private fun ColumnScope.RunTab(state: CellUiState, disabled: Boolean, onEvent: (CellEvent) -> Unit) {
    val running = state.running
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton("run_pins", rs(R.string.action_run_pins), { onEvent(CellEvent.RunPinsClicked) }, primary = !running, enabled = !disabled && !running && state.verdict.pinsOk > 0, minWidth = 112.dp, iconRes = R.drawable.ic_play)
        WordButton("run_path", rs(R.string.action_play_path), { onEvent(CellEvent.PlayPathClicked) }, enabled = !disabled && !running, minWidth = 112.dp, iconRes = R.drawable.ic_play)
        Spacer(modifier = Modifier.weight(1f))
        Key("run_stop", R.drawable.ic_stop, rs(R.string.action_stop), enabled = running) { onEvent(CellEvent.StopClicked) }
        Key("run_grip", R.drawable.ic_grip, rs(R.string.action_grip), enabled = !disabled && !running && (state.robot?.robot?.gripperM ?: 0f) > 0.005f) { onEvent(CellEvent.GripToggled) }
    }
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordButton("run_point", rs(R.string.action_add_point, state.waypoints + 1), { onEvent(CellEvent.WaypointAdded) }, enabled = !disabled && !running && state.waypoints < 12, minWidth = 96.dp, iconRes = R.drawable.ic_point)
        Segments(
            items = listOf("move_j" to rs(R.string.move_j), "move_l" to rs(R.string.move_l)),
            selected = if (state.linear) 1 else 0,
            onSelect = { onEvent(CellEvent.LinearSelected(it == 1)) },
            itemWidth = 60.dp,
            height = 40.dp,
        )
        Spacer(modifier = Modifier.weight(1f))
        ToggleKey("run_loop", R.drawable.ic_loop, rs(R.string.action_loop), on = state.loop, enabled = !disabled) { onEvent(CellEvent.LoopToggled) }
        Key("run_clear", R.drawable.ic_close, rs(R.string.action_clear_path), enabled = state.waypoints > 0 && !disabled) { onEvent(CellEvent.PathCleared) }
    }
    val m = state.motion
    val status =
        when {
            m?.kind == RunKind.PINS -> rs(R.string.run_playing_pins, state.verdict.pinsOk)
            m?.kind == RunKind.PATH -> rs(R.string.run_playing_path)
            m?.kind == RunKind.GOTO -> rs(R.string.run_playing_goto, state.selected?.number ?: 0)
            state.waypoints >= 2 -> rs(R.string.run_idle_points, state.waypoints) + " · " + rs(R.string.run_hint)
            else -> rs(R.string.run_hint)
        }
    Text(text = status, color = PicoTheme.colorScheme.labelSecondary, style = CellType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(24.dp))
}

/** A [Key] that shows an on state (the interaction tint behind the icon). */
@Composable
private fun ToggleKey(name: String, iconRes: Int, description: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.labelPrimaryLight
    com.pico.spatial.ui.design.IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors =
            com.pico.spatial.ui.design.IconButtonDefaults.iconButtonColors(
                containerColor = if (on) PicoTheme.colorScheme.interaction.copy(alpha = 0.32f) else PicoTheme.colorScheme.fillPrimary,
                contentColor = content,
            ),
        size = com.pico.spatial.ui.design.IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

@Composable
internal fun levelColor(level: PinLevel): Color =
    when (level) {
        PinLevel.GREEN -> PicoTheme.colorScheme.passable
        PinLevel.AMBER -> PicoTheme.colorScheme.alert
        PinLevel.RED -> PicoTheme.colorScheme.error
    }

internal fun levelIcon(level: PinLevel): Int =
    when (level) {
        PinLevel.GREEN -> R.drawable.ic_check
        PinLevel.AMBER -> R.drawable.ic_half
        PinLevel.RED -> R.drawable.ic_cross
    }

/** The long reason of a pin's verdict (the panel chip). */
@Composable
internal fun reasonText(pin: PinUi, state: CellUiState): String =
    when (pin.reason) {
        PinReason.OK -> rs(R.string.reason_ok)
        PinReason.OUT_OF_REACH -> if (pin.shortByM > 0.005f) rs(R.string.reason_out_of_reach, fmtLen(pin.shortByM, state.units)) else rs(R.string.reason_out_of_reach_short)
        PinReason.INNER_VOID -> rs(R.string.reason_inner_void)
        PinReason.ORIENTATION -> rs(R.string.reason_orientation)
        PinReason.HITS_DESK -> rs(R.string.reason_hits_desk)
        PinReason.HITS_WALL -> rs(R.string.reason_hits_wall)
        PinReason.SELF_HIT -> rs(R.string.reason_self_hit)
        PinReason.WRIST_SINGULAR -> rs(R.string.reason_wrist)
        PinReason.ELBOW_STRETCHED -> rs(R.string.reason_elbow)
        PinReason.SHOULDER_SINGULAR -> rs(R.string.reason_shoulder)
        PinReason.ILL_CONDITIONED -> rs(R.string.reason_conditioning)
        PinReason.NEAR_LIMIT -> rs(R.string.reason_limit)
        PinReason.NO_STRAIGHT_APPROACH -> rs(R.string.reason_no_straight)
    }

/** The short reason on a pin's plate. */
@Composable
internal fun plateReason(pin: PinUi, state: CellUiState): String =
    when (pin.reason) {
        PinReason.OK -> rs(R.string.reason_ok)
        PinReason.OUT_OF_REACH -> if (pin.shortByM > 0.005f) rs(R.string.plate_pin_short, fmtLen(pin.shortByM, state.units)) else rs(R.string.reason_out_of_reach_short)
        PinReason.INNER_VOID -> rs(R.string.plate_reason_void)
        PinReason.ORIENTATION -> rs(R.string.plate_reason_orient)
        PinReason.HITS_DESK -> rs(R.string.plate_reason_desk)
        PinReason.HITS_WALL -> rs(R.string.plate_reason_wall)
        PinReason.SELF_HIT -> rs(R.string.plate_reason_self)
        PinReason.WRIST_SINGULAR -> rs(R.string.plate_reason_wrist)
        PinReason.ELBOW_STRETCHED -> rs(R.string.plate_reason_elbow)
        PinReason.SHOULDER_SINGULAR -> rs(R.string.plate_reason_shoulder)
        PinReason.ILL_CONDITIONED -> rs(R.string.plate_reason_cond)
        PinReason.NEAR_LIMIT -> rs(R.string.plate_reason_limit)
        PinReason.NO_STRAIGHT_APPROACH -> rs(R.string.plate_reason_straight)
    }

/** A SegmentControl of [itemWidth] x [height] items. */
@Composable
internal fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, leadingIcon: Int? = null) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}

@Composable
internal fun poseName(pose: Pose): String =
    when (pose) {
        Pose.UPRIGHT -> rs(R.string.pose_upright)
        Pose.TUCKED -> rs(R.string.pose_tucked)
        Pose.REACH -> rs(R.string.pose_reach)
        Pose.PICK -> rs(R.string.pose_pick)
    }

internal fun poseIcon(pose: Pose): Int =
    when (pose) {
        Pose.UPRIGHT -> R.drawable.po_upright
        Pose.TUCKED -> R.drawable.po_tucked
        Pose.REACH -> R.drawable.po_reach
        Pose.PICK -> R.drawable.po_pick
    }

@Composable
internal fun jointName(index: Int): String =
    when (index) {
        0 -> rs(R.string.joint_name_1)
        1 -> rs(R.string.joint_name_2)
        2 -> rs(R.string.joint_name_3)
        3 -> rs(R.string.joint_name_4)
        4 -> rs(R.string.joint_name_5)
        else -> rs(R.string.joint_name_6)
    }
