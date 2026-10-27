package io.embodit.robotcell.domain.model

/**
 * The stand-in room when the scan finds no desk (factory rule D7): a desk of [width] x [depth] whose top is
 * [height] above the floor, the floor itself (only when no real floor is known) and a wall [WALL_GAP_M]
 * behind the desk's far edge. [center] is the centre of the desk top's footprint (y ignored), [forward]
 * the horizontal direction from the user to the desk. Its planes are fed to the tracker like scanned ones,
 * so placing, pins, reach, the wall verdict and collisions all work the same on it.
 */
data class VirtualRoom(
    val center: Vec3,
    val forward: Vec3,
    val width: Float,
    val depth: Float,
    val height: Float,
    val floorY: Float,
    val withFloor: Boolean,
) {
    val deskTopY: Float
        get() = floorY + height

    val right: Vec3
        get() = forward.cross(Vec3.UP).normalized()

    val deskCenter: Vec3
        get() = Vec3(center.x, deskTopY, center.z)

    /** The four corners of the desk top (counter-clockwise seen from above). */
    fun deskCorners(): List<Vec3> {
        val c = deskCenter
        val r = right * (width / 2f)
        val f = forward * (depth / 2f)
        return listOf(c - r - f, c + r - f, c + r + f, c - r + f)
    }

    fun table(): PlaneSurface = PlaneSurface(TABLE_ID, SurfaceKind.TABLE, "VIRTUAL_TABLE", deskCorners(), QUAD)

    fun floor(): PlaneSurface {
        val c = Vec3(center.x, floorY, center.z)
        val r = right * FLOOR_HALF_M
        val f = forward * FLOOR_HALF_M
        return PlaneSurface(FLOOR_ID, SurfaceKind.FLOOR, "VIRTUAL_FLOOR", listOf(c - r - f, c + r - f, c + r + f, c - r + f), QUAD)
    }

    /** The wall behind the desk: [WALL_HALF_W_M] to each side, floor to [WALL_H_M]. */
    fun wall(): PlaneSurface {
        val base = Vec3(center.x, floorY, center.z) + forward * (depth / 2f + WALL_GAP_M)
        val r = right * WALL_HALF_W_M
        val up = Vec3(0f, WALL_H_M, 0f)
        return PlaneSurface(WALL_ID, SurfaceKind.WALL, "VIRTUAL_WALL", listOf(base - r, base + r, base + r + up, base - r + up), QUAD)
    }

    fun planes(): List<PlaneSurface> = listOfNotNull(table(), if (withFloor) floor() else null, wall())

    fun moved(delta: Vec3): VirtualRoom = copy(center = Vec3(center.x + delta.x, center.y, center.z + delta.z))

    companion object {
        const val TABLE_ID = "virtual-table"
        const val FLOOR_ID = "virtual-floor"
        const val WALL_ID = "virtual-wall"

        const val WALL_GAP_M = 0.9f
        const val WALL_HALF_W_M = 2.0f
        const val WALL_H_M = 2.6f
        const val FLOOR_HALF_M = 3.0f

        /** Desk sizes offered in the menu: small, standard, large (width x depth, metres). */
        val SIZES = listOf(0.8f to 0.6f, 1.2f to 0.7f, 1.6f to 0.8f)
        const val DEFAULT_SIZE = 1
        const val DEFAULT_HEIGHT_M = 0.75f
        const val MIN_HEIGHT_M = 0.55f
        const val MAX_HEIGHT_M = 1.10f

        /** The eye height assumed when no floor was scanned (between seated and standing). */
        const val EYE_HEIGHT_M = 1.45f

        /** The desk centre is this far ahead of the head. */
        const val AHEAD_M = 0.95f

        private val QUAD = listOf(0, 1, 2, 0, 2, 3)

        fun isVirtual(id: String?): Boolean = id != null && id.startsWith("virtual-")

        /** A room in front of the head: desk centre [AHEAD_M] ahead, floor at [floorY] or eye height below the head. */
        fun inFront(head: Vec3, headForward: Vec3, floorY: Float?, size: Int = DEFAULT_SIZE, height: Float = DEFAULT_HEIGHT_M): VirtualRoom {
            val f = Vec3(headForward.x, 0f, headForward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
            val (w, d) = SIZES[size.coerceIn(0, SIZES.size - 1)]
            val c = head + f * AHEAD_M
            return VirtualRoom(Vec3(c.x, 0f, c.z), f, w, d, height, floorY ?: (head.y - EYE_HEIGHT_M), withFloor = floorY == null)
        }
    }
}
