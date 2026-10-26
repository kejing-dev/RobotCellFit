package io.embodit.robotcell

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.robotcell.ui.cell.CellScreen
import io.embodit.robotcell.ui.theme.RobotCellTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0) holds
 * the whole product; there is no WindowContainer and no Shared Space version (design package
 * `container`).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            RobotCellTheme {
                CellScreen()
            }
        }
    }
