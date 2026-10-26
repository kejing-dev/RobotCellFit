package io.embodit.robotcell.debug

import android.content.Context
import io.embodit.robotcell.domain.model.LanguagePref
import io.embodit.robotcell.domain.model.Pose
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.platform.CellLog
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.Tab
import io.embodit.robotcell.ui.cell.debug.CellProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `RobotCell`) answers every command.
 * Extras: either the design's typed form (`--es cmd goto --es s S05`, `--es cmd place --es surface table
 * --ef x 0 --ef z 0 --ef yaw 90`, `--es cmd pose --es name reach --ef freeze 0.5`, `--es cmd wall --ef d
 * 0.78`, `--es cmd pin --ef x 0.3 --ef z 0.2`) or everything in one string (`--es cmd 'goto S05'`).
 *
 * - `goto Sxx` / `state Sxx`   straight to a designed state (S01 - S21), the scene set up for it
 * - `place table|floor [x z [yaw]]`  the robot at an offset (metres) from the first desk's centre, heading in degrees
 * - `pose upright|tucked|reach|pick [yaw] [freeze]`  a preset; `freeze` 0..1 stops the easing part way (S08)
 * - `wall d`                   moves the robot so that the shoulder is d metres from the nearest wall
 * - `pin x z`                  a pin at an offset from the base on the mount desk
 * - `drag on|off`              shows the dragging readings (S12) without a hand
 * - `coach_reset`, `tracking lost|ok`, `lang zh|en|system`, `units cm|in`, `sphere on|off`
 * - `tab pose|joints|pins`, `menu`, `clear` (the dialog), `clear_now`, `delete`, `undo`, `exit`
 * - `ui <control>`             presses a panel control by name (more, undo, delete, rot_ccw, rot_cw, gripper,
 *                              tab_pose, tab_joints, tab_pins, pose_upright, pose_tucked, pose_reach, pose_pick,
 *                              joint_1..6, joint_minus, joint_plus, joint_zero, clear_pins, menu_sphere, lang_*,
 *                              unit_cm, unit_in, menu_clear, menu_exit, dialog_cancel, dialog_confirm, dialog_exit,
 *                              dialog_retry, coach_got_it, snackbar_action)
 * - `dump`                     the whole model as one JSON line (`DUMP {...}`), also written to files/dump.json
 * - `hmd`, `bounds`, `planes`, `park`, `fps on|off`
 */
internal object DebugCommands {

    fun run(context: Context, extras: Map<String, String>) {
        val raw = extras["cmd"] ?: return
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val host = CellProbe.host
        if (host == null) {
            CellLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, rest, extras)
        } catch (t: Throwable) {
            CellLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: CellProbe.Host, name: String, rest: List<String>, extras: Map<String, String>) {
        val scene = host.scene()
        fun word(i: Int, key: String): String? = extras[key] ?: rest.getOrNull(i)
        fun num(i: Int, key: String): Float? = (extras[key] ?: rest.getOrNull(i))?.toFloatOrNull()
        val first = (word(0, "s") ?: "").lowercase(Locale.US)
        when (name) {
            "goto", "state" -> host.dispatch(CellEvent.DebugGoto(word(0, "s") ?: return CellLog.i("CMD goto needs S01..S21")))
            "place" -> {
                val surface = (word(0, "surface") ?: "table").lowercase(Locale.US)
                host.dispatch(CellEvent.DebugPlace(surface, num(1, "x") ?: 0f, num(2, "z") ?: 0f, num(3, "yaw")))
            }
            "pose" -> {
                val pose = Pose.fromTag((word(0, "name") ?: "").lowercase(Locale.US)) ?: return CellLog.i("CMD pose needs upright|tucked|reach|pick")
                host.dispatch(CellEvent.DebugPose(pose, num(1, "yaw"), num(2, "freeze")))
            }
            "wall" -> host.dispatch(CellEvent.DebugWall(num(0, "d") ?: 0.78f))
            "pin" -> host.dispatch(CellEvent.DebugPin(num(0, "x") ?: 0.4f, num(1, "z") ?: 0f))
            "drag" -> host.dispatch(CellEvent.DebugDragging(first != "off"))
            "coach_reset", "coach" -> host.dispatch(CellEvent.DebugCoachReset)
            "tracking" -> host.dispatch(CellEvent.DebugTracking(if (first == "lost") true else if (first == "ok") false else null))
            "lang" -> host.dispatch(CellEvent.LanguageSelected(LanguagePref.fromTag(first)))
            "units", "unit" -> host.dispatch(CellEvent.UnitsSelected(UnitPref.fromTag(first)))
            "sphere" -> host.dispatch(CellEvent.SphereToggled(first != "off"))
            "tab" -> host.dispatch(CellEvent.TabSelected(Tab.entries.firstOrNull { it.name.lowercase(Locale.US) == first } ?: Tab.POSE))
            "menu" -> host.dispatch(CellEvent.MoreClicked)
            "clear" -> host.dispatch(CellEvent.ClearAllClicked)
            "clear_now" -> host.dispatch(CellEvent.DebugClear)
            "delete" -> host.dispatch(CellEvent.DeleteClicked)
            "undo" -> host.dispatch(CellEvent.UndoClicked)
            "exit" -> host.dispatch(CellEvent.ExitClicked)
            "ui" -> {
                val control = word(0, "s") ?: return
                CellLog.i("UI $control pressed=${host.pressControl(control)}")
            }
            "dump" -> {
                val json = host.dumpJson()
                CellLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "hmd" -> CellLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { CellLog.i(it) } ?: CellLog.i(NO_SCENE)
                for (panel in io.embodit.robotcell.ui.cell.scene.PanelSpec.pool) {
                    for ((ctrl, b) in CellProbe.controlsOf(panel)) {
                        CellLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { CellLog.i(it) } ?: CellLog.i(NO_SCENE)
            "park" -> CellLog.i(scene?.parkPanel() ?: NO_SCENE)
            "fps" -> CellLog.i(scene?.setFpsLine(first == "on") ?: NO_SCENE)
            else -> CellLog.i("CMD unknown '$name'")
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
