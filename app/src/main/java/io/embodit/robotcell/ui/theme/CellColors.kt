package io.embodit.robotcell.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [RobotCellTheme]; the
 * other eleven inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the
 * solid-coloured faces (plates, chips), where adaptive colours must not land.
 * Reach = cyan, warning = amber, real collision = red, selection / dimensions = yellow; never mixed.
 */
object CellColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: colliding segments, hit tag, unreachable pins
    val Alert = Color(0xFFFFA62B) // design-style: fixed-figma-color alert: wall ring, "past wall", partly reachable desk
    val Passable = Color(0xFF5FD38A) // design-style: fixed-figma-color passable: reachable, clear of the wall
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: current pose, edge readings, stripes

    // ---- brand tokens (design `palette.brandTokens`)
    val EnvelopeCyan = Color(0xFF3FD0E0) // design-style: fixed-figma-color envelopeCyan: reach sphere, work disk
    val StripeInk = Color(0xFF16181C) // design-style: fixed-figma-color stripeInk: stripe black, content on semantic fills
    val PedestalGrey = Color(0xFF3A3D42) // design-style: fixed-figma-color pedestalGrey
    val PlateInk = Color(0xFF121417) // design-style: fixed-figma-color plateInk (94 %)
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: lost tracking
    val Ghost = Color(0xFFBFC3C8) // design-style: fixed-figma-color ghost: placement preview at 30 %

    // ---- UR5e white model: light grey arm, dark joint drums, blue rings (design `entities3d.ur5e.fallback`)
    val ArmGrey = Color(0xFFCFD2D6) // design-style: fixed-figma-color armGrey
    val JointDark = Color(0xFF2F3439) // design-style: fixed-figma-color jointDark
    val UrBlue = Color(0xFF2D8FD6) // design-style: fixed-figma-color urBlue
    val Pole = Color(0xFFFFFFFF) // design-style: fixed-figma-color pole: pin stems

    // ---- v0.1.2: the 2F-85 class gripper and the virtual room
    val GripperBody = Color(0xFF3B4046) // design-style: fixed-figma-color gripperBody: anodised dark grey body and fingers
    val FingerPad = Color(0xFF1B1E22) // design-style: fixed-figma-color fingerPad: rubber pads
    val VirtualDesk = Color(0xFFB8AD97) // design-style: fixed-figma-color virtualDesk: stand-in desk top
    val VirtualWall = Color(0xFFD8DCE0) // design-style: fixed-figma-color virtualWall: stand-in wall and floor
}
