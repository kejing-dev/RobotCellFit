package io.embodit.robotcell.ui.cell

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.robotcell.platform.CellLog
import io.embodit.robotcell.ui.cell.components.CellStage
import io.embodit.robotcell.ui.cell.components.LocalCellResources
import io.embodit.robotcell.ui.cell.components.localizedResources
import io.embodit.robotcell.ui.cell.debug.CellProbe
import io.embodit.robotcell.ui.cell.scene.CellFrameSystem
import io.embodit.robotcell.ui.cell.scene.CellScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [CellScene] turns state into entities and reports taps, drags, the
 * stick and the head, and [CellStage] declares the panels.
 */
@Composable
fun CellScreen() {
    val context = LocalContext.current
    val viewModel: CellViewModel = viewModel(factory = CellViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        CellProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { CellScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<CellFrameSystem>()
        CellProbe.host =
            object : CellProbe.Host {
                override fun dispatch(event: CellEvent) = viewModel.onEvent(event)

                override fun uiState(): CellUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): CellProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = CellProbe.click(name)
            }
        CellLog.i("STAGE enter")
        onDispose {
            CellProbe.host = null
            unregisterSystem<CellFrameSystem>()
            scene.detach()
            CellLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(CellEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(CellEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: CellUiEffect ->
            when (effect) {
                CellUiEffect.ExitApp -> {
                    CellLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { CellLog.e("STAGE close failed: $it", it) }
                }
                is CellUiEffect.Sound ->
                    runCatching {
                        when (effect.kind) {
                            SoundKind.PLACE -> audio.playSystem(SpatialSoundEffect.OpClick)
                            SoundKind.DELETE -> audio.playSystem(SpatialSoundEffect.OpClose)
                            SoundKind.PIN -> audio.playSystem(SpatialSoundEffect.OpDragEnd)
                            SoundKind.HIT -> audio.playSystem(SpatialSoundEffect.StateFailure)
                            SoundKind.DONE -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.FAIL -> audio.playSystem(SpatialSoundEffect.StateFailure)
                        }
                    }.onFailure { CellLog.w("SOUND failed: $it") }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalCellResources provides resources) {
        CellStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
