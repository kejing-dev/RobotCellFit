package io.embodit.robotcell.ui.cell.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.robotcell.R
import io.embodit.robotcell.domain.usecase.HitKind
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.CellUiState
import io.embodit.robotcell.ui.cell.DialogKind
import io.embodit.robotcell.ui.cell.SnackKind
import io.embodit.robotcell.ui.cell.SnackUi
import io.embodit.robotcell.ui.cell.WallState
import io.embodit.robotcell.ui.cell.debug.probe
import io.embodit.robotcell.ui.cell.scene.PanelSpec
import io.embodit.robotcell.ui.theme.CellColors
import kotlin.math.abs

private const val FADE_IN_MS = 150

/**
 * The dialogs (self-built attachment panel, 400 x 208 dp at 1.125, 0.8 m ahead): clear confirmation
 * (S14) and "spatial features unavailable" (S20). In a Stage the SpatialUI `AlertDialog` composes
 * nothing (Spatial Ruler finding), so the card is built to its metrics.
 */
@Composable
internal fun DialogPanel(dialog: DialogKind?, onEvent: (CellEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (dialog == null) return@Box
        val fade = remember(dialog) { Animatable(0f) }
        LaunchedEffect(dialog) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(24.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painter = painterResource(if (dialog == DialogKind.CLEAR) R.drawable.ic_trash else R.drawable.ic_unavailable),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (dialog == DialogKind.CLEAR) PicoTheme.colorScheme.alert else ink,
                )
                Text(
                    text = if (dialog == DialogKind.CLEAR) rs(R.string.dialog_clear_title) else rs(R.string.dialog_blocked_title),
                    color = ink,
                    style = PicoTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (dialog == DialogKind.CLEAR) rs(R.string.dialog_clear_body) else rs(R.string.dialog_blocked_body),
                color = PicoTheme.colorScheme.labelSecondary,
                style = CellType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (dialog) {
                    DialogKind.CLEAR -> {
                        WordButton("dialog_cancel", rs(R.string.action_cancel), { onEvent(CellEvent.DialogDismissed) }, minWidth = 120.dp, iconRes = R.drawable.ic_close)
                        WordButton("dialog_confirm", rs(R.string.action_clear), { onEvent(CellEvent.ClearConfirmed) }, danger = true, minWidth = 120.dp, iconRes = R.drawable.ic_trash)
                    }
                    DialogKind.UNAVAILABLE -> {
                        WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(CellEvent.ExitClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                        WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(CellEvent.RetryClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
                    }
                }
            }
        }
    }
}

@Composable
private fun snackText(s: SnackUi): String =
    when (s.kind) {
        SnackKind.SCANNING -> rs(R.string.snack_scanning)
        SnackKind.NO_TABLE -> rs(R.string.snack_no_table)
        SnackKind.ELSEWHERE -> rs(R.string.snack_elsewhere)
        SnackKind.UNSAVED -> rs(R.string.snack_unsaved)
        SnackKind.LOST -> rs(R.string.snack_lost)
        SnackKind.CLEARED -> rs(R.string.snack_cleared)
        SnackKind.RESTORED -> rs(R.string.snack_restored)
        SnackKind.LIMIT_PINS -> rs(R.string.snack_limit_pins)
        SnackKind.DELETED -> rs(R.string.snack_deleted)
        SnackKind.VIRTUAL_ROOM -> rs(R.string.snack_virtual_room)
        SnackKind.REAL_DESK -> rs(R.string.snack_real_desk)
        SnackKind.RUN_NONE -> rs(R.string.snack_run_none)
        SnackKind.PIN_UNREACHABLE -> rs(R.string.snack_pin_unreachable)
        SnackKind.RUN_FALLBACK -> rs(R.string.snack_run_fallback)
    }

/**
 * The snackbar (head-locked attachment panel 14 degrees above the view centre, 360 x 56 dp at 1.125):
 * 28 dp corners, Material.Thick, a leading icon or spinner, one line of text, an optional action.
 * The capsule takes the width of its message inside the fixed-size panel ([applied]: the scene
 * squeezes the panel to the capsule's width and the capsule is drawn stretched back).
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, onEvent: (CellEvent) -> Unit, applied: Float = 1f, onWidth: (Float) -> Unit = {}) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { w.dp.toPx() }
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack.id) { Animatable(0f) }
        LaunchedEffect(snack.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 200.dp, max = w.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("snackbar")
                    .padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val spinner = snack.kind == SnackKind.SCANNING || snack.kind == SnackKind.NO_TABLE
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (snack.kind) {
                                SnackKind.RESTORED, SnackKind.CLEARED -> R.drawable.ic_check
                                SnackKind.DELETED -> R.drawable.ic_trash
                                SnackKind.LIMIT_PINS -> R.drawable.ic_pin
                                SnackKind.UNSAVED, SnackKind.RUN_FALLBACK -> R.drawable.ic_warning
                                SnackKind.VIRTUAL_ROOM, SnackKind.REAL_DESK -> R.drawable.ic_arm
                                SnackKind.RUN_NONE, SnackKind.PIN_UNREACHABLE -> R.drawable.ic_pin
                                else -> R.drawable.ic_unavailable
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ink,
                )
            }
            Text(text = snackText(snack), color = ink, style = CellType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
            if (snack.kind.hasAction) {
                val (label, icon) =
                    when (snack.kind) {
                        SnackKind.DELETED -> rs(R.string.action_undo) to R.drawable.ic_undo
                        SnackKind.ELSEWHERE -> rs(R.string.action_clear) to R.drawable.ic_trash
                        else -> rs(R.string.action_retry) to R.drawable.ic_retry
                    }
                WordButton("snackbar_action", label, { onEvent(CellEvent.SnackActionClicked(snack.id)) }, primary = true, minWidth = 88.dp, height = 40.dp, iconRes = icon)
            }
        }
    }
}

/** First-run coachmark (280 x 96 dp at 0.75, S21) above the base: pictogram, one line, got it. */
@Composable
internal fun CoachPanel(shown: Boolean, onEvent: (CellEvent) -> Unit) {
    val (w, h) = PanelSpec.COACH_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!shown) return@Box
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("coach")
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_hand_drag), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Text(
                text = rs(R.string.coach_hint),
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLargeMultiline,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Key("coach_got_it", R.drawable.ic_check, rs(R.string.action_got_it)) { onEvent(CellEvent.CoachDismissed) }
        }
    }
}

/** The hit tag (132 x 40, error fill): cross + "Hits wall" / "Hits desk" above the first contact (design `panels.hitTag`). */
@Composable
internal fun HitTag(state: CellUiState, shown: Boolean) {
    val (w, h) = PanelSpec.HIT_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!shown || !state.collides) return@Box
        val kind = state.hits.first().kind
        Row(
            modifier = Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.error).probe("hit_tag").padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_cross), contentDescription = null, modifier = Modifier.size(20.dp), tint = CellColors.StripeInk)
            Text(text = if (kind == HitKind.WALL) rs(R.string.hit_wall) else rs(R.string.hit_desk), color = CellColors.StripeInk, style = CellType.title, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A reading plate (design `panels.plates`): TagCard = 3 dp glass edge + a solid inner card, one line of
 * tabular figures, 120 - 200 dp wide by content. [fill] null = plate ink with white text, else a
 * semantic fill with stripe-ink text.
 */
@Composable
internal fun Plate(panelW: Dp, panelH: Dp, text: String?, fill: Color?, applied: Float, onWidth: (Float) -> Unit, name: String) {
    Box(modifier = Modifier.size(panelW, panelH), contentAlignment = Alignment.Center) {
        if (text == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { panelW.toPx() }
        val content = if (fill == null) CellColors.LabelPrimaryLight else CellColors.StripeInk
        Box(
            modifier =
                Modifier
                    .widthIn(min = 96.dp, max = panelW)
                    .height(panelH)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) 1f else 0f
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .backgroundMaterial(enable = true, style = Material.Thin)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background((fill ?: CellColors.PlateInk).copy(alpha = if (fill == null) 0.94f else 1f))
                    .probe(name)
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, color = content, style = CellType.figure, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
internal fun WallPlate(state: CellUiState, shown: Boolean, applied: Float, onWidth: (Float) -> Unit) {
    val (w, h) = PanelSpec.PLATE_SIZE
    val v = state.verdict
    val text =
        if (!shown || state.robot == null) null
        else when (v.wall) {
            WallState.NEAR -> rs(R.string.plate_to_wall, fmtLen(v.wallM, state.units))
            WallState.IN -> rs(R.string.plate_over_wall, fmtLen(v.wallM, state.units))
            else -> null
        }
    Plate(w.dp, h.dp, text, if (v.wall == WallState.IN) PicoTheme.colorScheme.alert else null, applied, onWidth, "plate_wall")
}

@Composable
internal fun EdgePlate(state: CellUiState, index: Int, shown: Boolean, applied: Float, onWidth: (Float) -> Unit) {
    val (w, h) = PanelSpec.PLATE_SIZE
    val e = state.edgeReadings.getOrNull(index)
    val text = if (!shown || e == null || !state.dragging) null else rs(R.string.plate_edge, fmtLen(e.second, state.units))
    Plate(w.dp, h.dp, text, PicoTheme.colorScheme.interaction, applied, onWidth, "plate_edge$index")
}

@Composable
internal fun PinPlate(state: CellUiState, index: Int, shown: Boolean, applied: Float, onWidth: (Float) -> Unit) {
    val (w, h) = PanelSpec.PIN_PLATE_SIZE
    // the same order as the scene's plates: non-green pins, the selected one first
    val pin = state.pins.filter { it.level != io.embodit.robotcell.domain.usecase.PinLevel.GREEN }.sortedBy { if (it.id == state.selectedPin) 0 else 1 }.getOrNull(index)
    val text = if (!shown || pin == null) null else rs(R.string.pin_label, pin.number) + " · " + plateReason(pin, state)
    Plate(w.dp, h.dp, text, if (pin != null) levelColor(pin.level) else PicoTheme.colorScheme.error, applied, onWidth, "plate_pin$index")
}
