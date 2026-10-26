package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Robot
import io.embodit.robotcell.domain.model.SurfaceKind
import io.embodit.robotcell.domain.model.Ur5e
import io.embodit.robotcell.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the reach sphere meets a desk top: a filled, clipped ring and its dashed edge, Stage metres. */
class DiskGeometry(
    val tableId: String,
    /** Triangle list (groups of three points) of the clipped work disk. */
    val fill: List<Vec3>,
    /** Triangle list of the dashed edge. */
    val edge: List<Vec3>,
    val normal: Vec3,
)

/** Where the reach sphere meets a wall: the orange ring (design `entities3d.wallRing`). */
class WallRing(val wallId: String, val fill: List<Vec3>, val edge: List<Vec3>, val normal: Vec3)

/** The nearest wall as the verdict strip and the tape see it (design `rules.wall`). */
class WallVerdict(
    val wall: PlaneSurface,
    /** Distance from the shoulder to the wall plane. */
    val d: Float,
    /** Reach radius of the moment. */
    val r: Float,
    /** The point of the wall nearest the shoulder. */
    val wallPoint: Vec3,
    /** The point of the reach sphere nearest the wall (on the line shoulder -> wall). */
    val spherePoint: Vec3,
) {
    /** Positive: the full extension goes past the wall by this much. */
    val over: Float
        get() = r - d

    val reachesWall: Boolean
        get() = d < r
}

/** A collision of one arm segment with a wall or a desk (design `entities3d.linkHit`). */
data class LinkHit(val segment: Int, val kind: HitKind, val point: Vec3)

enum class HitKind { WALL, DESK }

/**
 * Every reach, clearance and collision rule of the design (`rules.reach`, `deskPercent`, `wall`,
 * `pins`, `hit`, `place`, `move`). Pure geometry on the planes the tracker reported; nothing here
 * touches the SDK, so it compiles and tests on a plain JVM.
 */
class ReachUseCase {

    private val flats = HashMap<String, Flat>()

    /** The chart of a plane (cached by id and geometry). */
    fun flat(plane: PlaneSurface): Flat {
        val cached = flats[plane.id]
        if (cached != null && cached.plane === plane) return cached
        val f = Flat(plane)
        flats[plane.id] = f
        return f
    }

    fun forget(id: String) {
        flats.remove(id)
    }

    // ------------------------------------------------------------------ planes

    fun tables(planes: Collection<PlaneSurface>): List<PlaneSurface> = planes.filter { it.kind == SurfaceKind.TABLE && it.horizontal }

    fun walls(planes: Collection<PlaneSurface>): List<PlaneSurface> =
        planes.filter { (it.kind == SurfaceKind.WALL || it.kind == SurfaceKind.DOOR || it.kind == SurfaceKind.WINDOW) && !it.horizontal }

    fun floor(planes: Collection<PlaneSurface>): PlaneSurface? = planes.filter { it.kind == SurfaceKind.FLOOR }.maxByOrNull { it.vertices.size }

    fun floorY(planes: Collection<PlaneSurface>): Float? = planes.filter { it.kind == SurfaceKind.FLOOR }.minOfOrNull { it.minY }

    /** The desk whose top is nearest [p] horizontally (its polygon, grown by 0.1 m counts as distance 0). */
    fun nearestTable(p: Vec3, planes: Collection<PlaneSurface>, maxDistance: Float = Float.MAX_VALUE): PlaneSurface? {
        var best: PlaneSurface? = null
        var bestD = maxDistance
        for (t in tables(planes)) {
            val f = flat(t)
            val d = max(0f, -f.signedInset(f.to2(Vec3(p.x, t.maxY, p.z))))
            if (d < bestD) {
                bestD = d
                best = t
            }
        }
        return best
    }

    // ------------------------------------------------------------------ placing and moving

    /** Base centre kept inside the desk top with the base radius as margin (design `rules.place`). */
    fun clampOnTable(point: Vec3, table: PlaneSurface): Vec3 {
        val f = flat(table)
        val q = f.clampInside(f.to2(point), Ur5e.BASE_RADIUS_M)
        val p = f.to3(q)
        return Vec3(p.x, table.maxY, p.z)
    }

    /** Pedestal centre kept clear of every desk's floor projection by half a pedestal plus 1 cm (design `entities3d.pedestal`). */
    fun clampOffTables(point: Vec3, floorY: Float, planes: Collection<PlaneSurface>): Vec3 {
        var p = Vec3(point.x, floorY, point.z)
        for (t in tables(planes)) {
            val f = flat(t)
            val lifted = Vec3(p.x, t.maxY, p.z)
            val q = f.clampOutside(f.to2(lifted), PEDESTAL_HALF_M + 0.01f)
            val moved = f.to3(q)
            p = Vec3(moved.x, floorY, moved.z)
        }
        return p
    }

    /** Pedestal height: the nearest desk within 1.5 m, else 0.75 m (design `entities3d.pedestal`). */
    fun pedestalHeight(point: Vec3, floorY: Float, planes: Collection<PlaneSurface>): Float {
        val t = nearestTable(point, planes, 1.5f) ?: return DEFAULT_PEDESTAL_M
        return (t.maxY - floorY).coerceIn(0.3f, 1.5f)
    }

    /** Distances from the base centre to the two nearest edges of its desk (for the drag readings). */
    fun edgeReadings(robot: Robot, table: PlaneSurface): List<Pair<Vec3, Float>> {
        val f = flat(table)
        val base2 = f.to2(Vec3(robot.base.x, table.maxY, robot.base.z))
        return f.edgePoints(base2).take(2).map { (q, d) ->
            val p = f.to3(q)
            Vec3(p.x, table.maxY, p.z) to d
        }
    }

    // ------------------------------------------------------------------ the desk

    /**
     * Share of the desk top covered by the work disk and outside the dead zone, 0..100 (design
     * `rules.deskPercent`), or null when the sphere does not reach the top.
     */
    fun deskPercent(robot: Robot, table: PlaneSurface): Int? {
        val f = flat(table)
        val r = robot.reachRadius()
        val h = robot.shoulder.y - table.maxY
        val rr = r * r - h * h
        if (rr <= 0f) return 0
        val rd = sqrt(rr)
        val c = f.to2(Vec3(robot.shoulder.x, table.maxY, robot.shoulder.z))
        val dead = Ur5e.DEAD_ZONE_R
        val b = f.bounds()
        val step = GRID_M
        var total = 0
        var hit = 0
        var wv = b[2] + step / 2f
        while (wv < b[3]) {
            var uv = b[0] + step / 2f
            while (uv < b[1]) {
                val p = P2(uv, wv)
                if (f.contains(p)) {
                    total++
                    val du = p.u - c.u
                    val dw = p.w - c.w
                    val d2 = du * du + dw * dw
                    if (d2 <= rd * rd && d2 >= dead * dead) hit++
                }
                uv += step
            }
            wv += step
        }
        if (total == 0) return null
        return (100f * hit / total + 0.5f).toInt().coerceIn(0, 100)
    }

    /** The work disk on one desk, clipped to its polygon and minus the dead zone (design `entities3d.workDisk`). */
    fun workDisk(robot: Robot, table: PlaneSurface): DiskGeometry? {
        val f = flat(table)
        val r = robot.reachRadius()
        val h = robot.shoulder.y - table.maxY
        val rr = r * r - h * h
        if (rr <= 0.0004f) return null
        val rd = sqrt(rr)
        val c = f.to2(Vec3(robot.shoulder.x, table.maxY, robot.shoulder.z))
        val inner = if (table.id == robot.tableId || f.contains(c, -0.3f)) Ur5e.DEAD_ZONE_R else 0f
        val lift = Vec3(0f, LIFT_M, 0f)
        val fill = ArrayList<Vec3>()
        val sectors = 64
        for (i in 0 until sectors) {
            val a0 = 2.0 * Math.PI * i / sectors
            val a1 = 2.0 * Math.PI * (i + 1) / sectors
            val d0 = P2(kotlin.math.cos(a0).toFloat(), kotlin.math.sin(a0).toFloat())
            val d1 = P2(kotlin.math.cos(a1).toFloat(), kotlin.math.sin(a1).toFloat())
            val quad =
                if (inner > 0f) listOf(c + d0 * inner, c + d0 * rd, c + d1 * rd, c + d1 * inner)
                else listOf(c, c + d0 * rd, c + d1 * rd)
            val poly = f.clip(quad)
            fan(poly, f, lift, fill)
        }
        val edge = ArrayList<Vec3>()
        dashedArc(f, c, rd, DASH_ON_M, DASH_OFF_M, EDGE_W_M, lift, edge, clipToHull = true)
        if (fill.isEmpty()) return null
        return DiskGeometry(table.id, fill, edge, f.normal)
    }

    // ------------------------------------------------------------------ walls

    /** The wall nearest the shoulder, or null when no wall is known (design `rules.wall`). */
    fun wallVerdict(robot: Robot, planes: Collection<PlaneSurface>): WallVerdict? {
        var best: WallVerdict? = null
        val s = robot.shoulder
        val r = robot.reachRadius()
        for (wall in walls(planes)) {
            val f = flat(wall)
            val h = f.height(s)
            val d = abs(h)
            val wallPoint = f.project(s)
            // only the part of the wall near the shoulder counts: within 1 m of the polygon in the wall's chart
            if (!f.contains(f.to2(wallPoint), -1.0f)) continue
            val toward = if (h >= 0f) -f.normal else f.normal
            val spherePoint = s + toward * r
            val v = WallVerdict(wall, d, r, wallPoint, spherePoint)
            if (best == null || v.d < best.d) best = v
        }
        return best
    }

    /** Orange rings on every wall the sphere reaches (design `entities3d.wallRing`). */
    fun wallRings(robot: Robot, planes: Collection<PlaneSurface>): List<WallRing> {
        val out = ArrayList<WallRing>()
        val s = robot.shoulder
        val r = robot.reachRadius()
        for (wall in walls(planes)) {
            val f = flat(wall)
            val h = f.height(s)
            val d = abs(h)
            if (d >= r) continue
            val rr = sqrt(r * r - d * d)
            val c = f.to2(f.project(s))
            if (!f.contains(c, -rr)) continue
            val lift = (if (h >= 0f) f.normal else -f.normal) * LIFT_M
            val fill = ArrayList<Vec3>()
            val sectors = 48
            for (i in 0 until sectors) {
                val a0 = 2.0 * Math.PI * i / sectors
                val a1 = 2.0 * Math.PI * (i + 1) / sectors
                val tri = listOf(c, c + P2(kotlin.math.cos(a0).toFloat(), kotlin.math.sin(a0).toFloat()) * rr, c + P2(kotlin.math.cos(a1).toFloat(), kotlin.math.sin(a1).toFloat()) * rr)
                fan(f.clip(tri), f, lift, fill)
            }
            val edge = ArrayList<Vec3>()
            dashedArc(f, c, rr, 10f, 0f, EDGE_W_M, lift, edge, clipToHull = true)
            if (fill.isNotEmpty()) out += WallRing(wall.id, fill, edge, f.normal)
        }
        return out
    }

    // ------------------------------------------------------------------ pins

    /** True when a pin is within reach: inside the sphere and outside the dead zone (design `rules.pins`). */
    fun pinReachable(robot: Robot, pin: Vec3): Boolean {
        val s = robot.shoulder
        val d = pin.distanceTo(s)
        val flat = hypot(pin.x - robot.base.x, pin.z - robot.base.z)
        return d <= robot.reachRadius() && flat >= Ur5e.DEAD_ZONE_R
    }

    /** How far a pin is beyond the reach sphere (0 when reachable). */
    fun pinShortBy(robot: Robot, pin: Vec3): Float = max(0f, pin.distanceTo(robot.shoulder) - robot.reachRadius())

    // ------------------------------------------------------------------ collisions

    /**
     * Capsule checks of the current pose: walls / doors / windows as planes (the room side is the side
     * of the shoulder), every desk as a 4 cm slab. Segments 0 and 1 are not checked against the robot's
     * own mount desk; the pedestal is never checked (design `entities3d.linkHit`).
     * [frames] are the local FK frames; [gripperM] adds the tool capsule.
     */
    fun collisions(robot: Robot, frames: Array<Ur5e.Frame>, planes: Collection<PlaneSurface>): List<LinkHit> {
        val out = ArrayList<LinkHit>()
        val rot = robot.rotation
        val mount = robot.mountPoint
        fun world(p: Vec3): Vec3 = mount + rot.rotate(p)
        val pts = Array(7) { world(frames[it].origin) }
        val tipLocal = Ur5e.tip(frames, robot.gripperM)
        val tip = world(tipLocal)
        // segment i: from pts[i] to pts[i+1] (0 = base: from the base bottom to the shoulder)
        val segA = ArrayList<Vec3>(7)
        val segB = ArrayList<Vec3>(7)
        segA += robot.base
        segB += pts[1]
        for (i in 1 until 6) {
            segA += pts[i]
            segB += pts[i + 1]
        }
        val hasTool = robot.gripperM > 0.005f
        if (hasTool) {
            segA += pts[6]
            segB += tip
        }
        val wallList = walls(planes)
        val tableList = tables(planes)
        for (i in segA.indices) {
            val a = segA[i]
            val b = segB[i]
            val r = Ur5e.CAPSULE_R[min(i, 6)]
            var hit: LinkHit? = null
            for (wall in wallList) {
                val f = flat(wall)
                val hs = f.height(robot.shoulder)
                val sign = if (hs >= 0f) 1f else -1f
                val ha = f.height(a) * sign
                val hb = f.height(b) * sign
                val minH = min(ha, hb)
                val depth = r - minH
                if (depth <= HIT_EPS_M) continue
                val deep = if (ha <= hb) a else b
                if (!f.contains(f.to2(f.project(deep)), -r)) continue
                hit = LinkHit(i, HitKind.WALL, f.project(deep))
                break
            }
            if (hit == null) {
                for (table in tableList) {
                    if (i <= 1 && robot.mount == Mount.DESK && table.id == robot.tableId) continue
                    val f = flat(table)
                    val top = table.maxY
                    val steps = 8
                    for (k in 0..steps) {
                        val p = a.lerp(b, k.toFloat() / steps)
                        if (p.y - r < top - HIT_EPS_M && p.y + r > top - SLAB_M && f.contains(f.to2(Vec3(p.x, top, p.z)), -r * 0.5f)) {
                            hit = LinkHit(i, HitKind.DESK, Vec3(p.x, top, p.z))
                            break
                        }
                    }
                    if (hit != null) break
                }
            }
            if (hit != null) out += hit
        }
        return out
    }

    /** Smallest distance from [p] to any arm capsule of the pose (for the "too close" transparency rule). */
    fun distanceToArm(robot: Robot, frames: Array<Ur5e.Frame>, p: Vec3): Float {
        val rot = robot.rotation
        val mount = robot.mountPoint
        var best = Float.MAX_VALUE
        var prev = robot.base
        for (i in 1..6) {
            val cur = mount + rot.rotate(frames[i].origin)
            val d = segmentDistance(p, prev, cur) - Ur5e.CAPSULE_R[i - 1]
            if (d < best) best = d
            prev = cur
        }
        if (robot.gripperM > 0.005f) {
            val tip = mount + rot.rotate(Ur5e.tip(frames, robot.gripperM))
            val d = segmentDistance(p, prev, tip) - Ur5e.CAPSULE_R[6]
            if (d < best) best = d
        }
        return best
    }

    // ------------------------------------------------------------------ helpers

    private fun fan(poly: List<P2>, f: Flat, lift: Vec3, out: MutableList<Vec3>) {
        if (poly.size < 3) return
        val p0 = f.to3(poly[0]) + lift
        for (i in 1 until poly.size - 1) {
            out += p0
            out += f.to3(poly[i]) + lift
            out += f.to3(poly[i + 1]) + lift
        }
    }

    /** Dashes of [width] along a circle in the chart; [on] metres drawn, [off] metres gap (off = 0: solid). */
    private fun dashedArc(f: Flat, c: P2, radius: Float, on: Float, off: Float, width: Float, lift: Vec3, out: MutableList<Vec3>, clipToHull: Boolean) {
        if (radius < 1e-3f) return
        val circumference = (2.0 * Math.PI * radius).toFloat()
        val period = on + off
        val count = max(1, ceil(circumference / period).toInt())
        val stepA = 2.0 * Math.PI / count
        val onA = stepA * (if (off <= 0f) 1.0 else (on / period).toDouble())
        val sub = if (off <= 0f) 6 else 2
        for (i in 0 until count) {
            val start = stepA * i
            for (k in 0 until sub) {
                val a0 = start + onA * k / sub
                val a1 = start + onA * (k + 1) / sub
                val d0 = P2(kotlin.math.cos(a0).toFloat(), kotlin.math.sin(a0).toFloat())
                val d1 = P2(kotlin.math.cos(a1).toFloat(), kotlin.math.sin(a1).toFloat())
                val quad = listOf(c + d0 * (radius - width / 2f), c + d0 * (radius + width / 2f), c + d1 * (radius + width / 2f), c + d1 * (radius - width / 2f))
                val poly = if (clipToHull) f.clip(quad) else quad
                fan(poly, f, lift, out)
            }
        }
    }

    companion object {
        const val PEDESTAL_HALF_M = 0.10f
        const val DEFAULT_PEDESTAL_M = 0.75f
        const val GRID_M = 0.02f
        const val LIFT_M = 0.003f
        const val EDGE_W_M = 0.01f
        const val DASH_ON_M = 0.02f
        const val DASH_OFF_M = 0.012f
        const val HIT_EPS_M = 0.005f
        const val SLAB_M = 0.04f

        fun segmentDistance(p: Vec3, a: Vec3, b: Vec3): Float {
            val e = b - a
            val len2 = e.dot(e)
            if (len2 < 1e-12f) return p.distanceTo(a)
            val t = ((p - a).dot(e) / len2).coerceIn(0f, 1f)
            return p.distanceTo(a + e * t)
        }
    }
}
