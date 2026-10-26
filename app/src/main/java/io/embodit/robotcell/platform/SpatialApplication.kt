package io.embodit.robotcell.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.robotcell.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CellGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
