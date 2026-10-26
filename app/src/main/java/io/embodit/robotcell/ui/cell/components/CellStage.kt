package io.embodit.robotcell.ui.cell.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.robotcell.ui.cell.CellEvent
import io.embodit.robotcell.ui.cell.CellUiState
import io.embodit.robotcell.ui.cell.debug.LocalProbePanel
import io.embodit.robotcell.ui.cell.scene.CellScene
import io.embodit.robotcell.ui.cell.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popups. The cell panel (or its compact aiming form) is the only
 * standing panel; menu, dialog, snackbar, coachmark, hit tag and plates are floating surfaces.
 */
@Composable
internal fun CellStage(state: CellUiState, scene: CellScene, onEvent: (CellEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CELL) {
                PoolPanel(PanelSpec.CELL, scene, watched = true) { inView -> CellPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.AIM) {
                PoolPanel(PanelSpec.AIM, scene, watched = true) { inView -> AimPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene) { inView -> DialogPanel(dialog = if (inView) state.dialog else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.SNACK) {
                PoolPanel(PanelSpec.SNACK, scene, hugs = true) { inView ->
                    SnackPanel(
                        snack = if (inView) state.snack else null,
                        onEvent = onEvent,
                        applied = scene.panelStretch[PanelSpec.SNACK] ?: 1f,
                        onWidth = { scene.reportWidth(PanelSpec.SNACK, it) },
                    )
                }
            }
            AttachmentPanel(PanelSpec.COACH) {
                PoolPanel(PanelSpec.COACH, scene) { inView -> CoachPanel(shown = inView && state.coach, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.HIT) {
                PoolPanel(PanelSpec.HIT, scene) { inView -> HitTag(state = state, shown = inView) }
            }
            AttachmentPanel(PanelSpec.PLATE_WALL) {
                PoolPanel(PanelSpec.PLATE_WALL, scene, hugs = true) { inView ->
                    WallPlate(state = state, shown = inView, applied = scene.panelStretch[PanelSpec.PLATE_WALL] ?: 1f, onWidth = { scene.reportWidth(PanelSpec.PLATE_WALL, it) })
                }
            }
            AttachmentPanel(PanelSpec.PLATE_EDGE1) {
                PoolPanel(PanelSpec.PLATE_EDGE1, scene, hugs = true) { inView ->
                    EdgePlate(state = state, index = 0, shown = inView, applied = scene.panelStretch[PanelSpec.PLATE_EDGE1] ?: 1f, onWidth = { scene.reportWidth(PanelSpec.PLATE_EDGE1, it) })
                }
            }
            AttachmentPanel(PanelSpec.PLATE_EDGE2) {
                PoolPanel(PanelSpec.PLATE_EDGE2, scene, hugs = true) { inView ->
                    EdgePlate(state = state, index = 1, shown = inView, applied = scene.panelStretch[PanelSpec.PLATE_EDGE2] ?: 1f, onWidth = { scene.reportWidth(PanelSpec.PLATE_EDGE2, it) })
                }
            }
            for ((k, id) in PanelSpec.pinPlates.withIndex()) {
                AttachmentPanel(id) {
                    PoolPanel(id, scene, hugs = true) { inView ->
                        PinPlate(state = state, index = k, shown = inView, applied = scene.panelStretch[id] ?: 1f, onWidth = { scene.reportWidth(id, it) })
                    }
                }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled
 * panel must draw its content only then (what it draws while parked out of sight stays broken on the
 * emulator). [watched]: presses on it hold the lazy follow still. [hugs]: the content is narrower
 * than the panel (snackbar, plates) and reports its width to the scene.
 */
@Composable
private fun PoolPanel(id: String, scene: CellScene, watched: Boolean = false, hugs: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched && !hugs) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
