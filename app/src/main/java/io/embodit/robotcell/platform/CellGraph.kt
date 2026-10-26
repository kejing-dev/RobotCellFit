package io.embodit.robotcell.platform

import android.content.Context
import io.embodit.robotcell.data.CellRepository
import io.embodit.robotcell.data.PrefsCellRepository
import io.embodit.robotcell.platform.anchor.WorldAnchorGateway
import io.embodit.robotcell.platform.input.ControllerInput
import io.embodit.robotcell.platform.sense.PlaneTracker
import io.embodit.robotcell.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `CellScene.attach` / `detach`); the ViewModel only sees interfaces.
 */
object CellGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    @Volatile private var repository: CellRepository? = null

    fun repository(context: Context): CellRepository =
        repository ?: synchronized(this) { repository ?: PrefsCellRepository(context).also { repository = it } }
}
