package io.embodit.robotcell.ui.cell.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val CELL = "cellPanel"
    const val AIM = "aimPanel"
    const val MENU = "menuPanel"
    const val DIALOG = "dialogPanel"
    const val SNACK = "snackPanel"
    const val COACH = "coachPanel"
    const val HIT = "hitTag"
    const val PLATE_WALL = "plateWall"
    const val PLATE_EDGE1 = "plateEdge1"
    const val PLATE_EDGE2 = "plateEdge2"
    const val PLATE_PIN1 = "platePin1"
    const val PLATE_PIN2 = "platePin2"
    const val PLATE_PIN3 = "platePin3"
    const val PLATE_PIN4 = "platePin4"

    /** The design's 376 dp plus one 24 dp hint line ("turn with the stick · hold the base to move"). */
    val CELL_SIZE = 400f to 400f
    val AIM_SIZE = 400f to 120f
    /** v0.1.2: three more rows for the virtual desk (switch + move here, size, height). */
    val MENU_SIZE = 300f to 408f
    val DIALOG_SIZE = 400f to 208f
    val SNACK_SIZE = 360f to 56f
    val COACH_SIZE = 280f to 96f
    val HIT_SIZE = 132f to 40f
    val PLATE_SIZE = 200f to 40f
    val PIN_PLATE_SIZE = 200f to 40f

    /** The cell panel family is shown at 0.75 (1 dp = 0.6 mm). */
    const val MAIN_SCALE = 0.75f

    /** Dialog and snackbar: 0.8 / 0.9 m from the head, shown at 1.125. */
    const val FLOATING_SCALE = 1.125f
    const val DIALOG_DISTANCE_M = 0.8f

    val pinPlates: List<String> = listOf(PLATE_PIN1, PLATE_PIN2, PLATE_PIN3, PLATE_PIN4)

    val pool: List<String> = listOf(CELL, AIM, MENU, DIALOG, SNACK, COACH, HIT, PLATE_WALL, PLATE_EDGE1, PLATE_EDGE2) + pinPlates

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CELL -> CELL_SIZE
            AIM -> AIM_SIZE
            MENU -> MENU_SIZE
            DIALOG -> DIALOG_SIZE
            SNACK -> SNACK_SIZE
            COACH -> COACH_SIZE
            HIT -> HIT_SIZE
            PLATE_WALL, PLATE_EDGE1, PLATE_EDGE2 -> PLATE_SIZE
            else -> PIN_PLATE_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
