package io.embodit.robotcell.ui.cell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.Material
import io.embodit.robotcell.R
import io.embodit.robotcell.domain.model.LanguagePref
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.CellUiState
import io.embodit.robotcell.ui.cell.debug.probe
import io.embodit.robotcell.ui.cell.scene.PanelSpec

/**
 * The More menu (300 x 408 dp at 0.75, S13): reach sphere switch, language and unit segments, the virtual
 * desk (factory rule D7: on / off + move here, size S / M / L = 80x60 / 120x70 / 160x80 cm, height -5 / +5 cm), "Clear all" and Exit;
 * rows 44 high. Segments act in place and leave the menu open.
 */
@Composable
internal fun MoreMenu(state: CellUiState, filled: Boolean, onEvent: (CellEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp, spacing = 4.dp) {
        if (!filled) return@Glass
        MenuRow(rs(R.string.menu_sphere)) {
            Spacer(modifier = Modifier.weight(1f))
            Switch(checked = state.showSphere, onCheckedChange = { onEvent(CellEvent.SphereToggled(it)) }, modifier = Modifier.probe("menu_sphere") { onEvent(CellEvent.SphereToggled(!state.showSphere)) })
        }
        MenuRow(rs(R.string.menu_language)) {
            Segments(
                items = listOf("lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en), "lang_system" to rs(R.string.lang_system_short)),
                selected = when (state.language) {
                    LanguagePref.ZH -> 0
                    LanguagePref.EN -> 1
                    LanguagePref.SYSTEM -> 2
                },
                onSelect = { onEvent(CellEvent.LanguageSelected(listOf(LanguagePref.ZH, LanguagePref.EN, LanguagePref.SYSTEM)[it])) },
                itemWidth = 64.dp,
                height = 40.dp,
                leadingIcon = R.drawable.ic_globe,
            )
        }
        MenuRow(rs(R.string.menu_units)) {
            Segments(
                items = listOf("unit_cm" to rs(R.string.unit_cm), "unit_in" to rs(R.string.unit_in)),
                selected = state.units.ordinal,
                onSelect = { onEvent(CellEvent.UnitsSelected(UnitPref.entries[it])) },
                itemWidth = 64.dp,
                height = 40.dp,
            )
        }
        val vr = state.virtualRoom
        MenuRow(rs(R.string.menu_virtual)) {
            Switch(checked = vr != null, onCheckedChange = { onEvent(CellEvent.VirtualToggled(it)) }, modifier = Modifier.probe("menu_virtual") { onEvent(CellEvent.VirtualToggled(vr == null)) })
            Spacer(modifier = Modifier.weight(1f))
            WordButton("menu_virtual_here", rs(R.string.menu_virtual_here), { onEvent(CellEvent.VirtualHereClicked) }, enabled = vr != null, minWidth = 96.dp, height = 40.dp)
        }
        MenuRow(rs(R.string.menu_virtual_size)) {
            Segments(
                items = listOf("vsize_s" to rs(R.string.vsize_s), "vsize_m" to rs(R.string.vsize_m), "vsize_l" to rs(R.string.vsize_l)),
                selected = state.virtualSize,
                onSelect = { onEvent(CellEvent.VirtualSizeSelected(it)) },
                itemWidth = 56.dp,
                height = 40.dp,
            )
        }
        MenuRow(rs(R.string.menu_virtual_height)) {
            Key("vheight_minus", R.drawable.ic_minus, rs(R.string.action_minus5), enabled = vr != null) { onEvent(CellEvent.VirtualHeightStepped(-0.05f)) }
            Text(text = vr?.let { fmtLen(it.height, state.units) } ?: "—", color = PicoTheme.colorScheme.labelPrimary, style = CellType.title, maxLines = 1, modifier = Modifier.width(64.dp))
            Key("vheight_plus", R.drawable.ic_plus, rs(R.string.action_plus5), enabled = vr != null) { onEvent(CellEvent.VirtualHeightStepped(0.05f)) }
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            WordButton("menu_clear", rs(R.string.menu_clear), { onEvent(CellEvent.ClearAllClicked) }, enabled = state.robot != null || state.pins.isNotEmpty(), minWidth = 160.dp, height = 40.dp, iconRes = R.drawable.ic_trash)
        }
        Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(CellEvent.ExitClicked) }, minWidth = 160.dp, height = 40.dp, iconRes = R.drawable.ic_exit)
        }
    }
}

@Composable
private fun MenuRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = CellType.figure, maxLines = 1, modifier = Modifier.width(76.dp))
        content()
    }
}
