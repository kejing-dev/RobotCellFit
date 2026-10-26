package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.PlaneSurface
import io.embodit.robotcell.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A point in a plane's own 2D chart. */
data class P2(val u: Float, val w: Float) {
    operator fun plus(o: P2) = P2(u + o.u, w + o.w)

    operator fun minus(o: P2) = P2(u - o.u, w - o.w)

    operator fun times(s: Float) = P2(u * s, w * s)

    fun dot(o: P2): Float = u * o.u + w * o.w

    val length: Float
        get() = hypot(u, w)

    fun distanceTo(o: P2): Float = (this - o).length
}

/**
 * A detected plane as a flat chart: an origin, two in-plane unit axes and the normal, with the
 * surface's polygon as a convex hull in that chart. Every polygon operation of the app (clipping the
 * reach circle to a desk top, keeping the base inside a desk, distances to the edges) runs here in 2D.
 */
class Flat(val plane: PlaneSurface) {
    val origin: Vec3 = plane.vertices.firstOrNull() ?: Vec3.ZERO
    val normal: Vec3 = plane.normal
    val u: Vec3
    val w: Vec3

    /** Convex hull of the surface's vertices, counter-clockwise in the (u, w) chart. */
    val hull: List<P2>

    init {
        var a = Vec3.UP.rejectFrom(normal)
        if (a.length < 0.1f) a = Vec3.RIGHT.rejectFrom(normal)
        if (a.length < 0.1f) a = Vec3.AHEAD.rejectFrom(normal)
        u = a.normalized()
        w = normal.cross(u).normalized()
        hull = convexHull(plane.vertices.map { to2(it) })
    }

    fun to2(p: Vec3): P2 {
        val d = p - origin
        return P2(d.dot(u), d.dot(w))
    }

    fun to3(p: P2): Vec3 = origin + u * p.u + w * p.w

    /** Signed height of [p] above the plane along the normal. */
    fun height(p: Vec3): Float = (p - origin).dot(normal)

    /** Orthogonal projection of [p] onto the plane. */
    fun project(p: Vec3): Vec3 = p - normal * height(p)

    val area: Float
        get() = polygonArea(hull)

    val centroid2: P2
        get() {
            if (hull.isEmpty()) return P2(0f, 0f)
            var su = 0f
            var sw = 0f
            for (p in hull) {
                su += p.u
                sw += p.w
            }
            return P2(su / hull.size, sw / hull.size)
        }

    val centroid: Vec3
        get() = to3(centroid2)

    fun bounds(): FloatArray {
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minW = Float.MAX_VALUE
        var maxW = -Float.MAX_VALUE
        for (p in hull) {
            if (p.u < minU) minU = p.u
            if (p.u > maxU) maxU = p.u
            if (p.w < minW) minW = p.w
            if (p.w > maxW) maxW = p.w
        }
        return floatArrayOf(minU, maxU, minW, maxW)
    }

    /** True when [p] lies inside the hull shrunk by [margin] (negative margin grows it). */
    fun contains(p: P2, margin: Float = 0f): Boolean = signedInset(p) >= margin

    fun contains(p: Vec3, margin: Float = 0f): Boolean = contains(to2(p), margin)

    /**
     * Distance of [p] from the hull's boundary: positive inside, negative outside (the usual signed
     * distance for a convex polygon).
     */
    fun signedInset(p: P2): Float {
        val n = hull.size
        if (n < 3) return -1f
        var minSide = Float.MAX_VALUE
        var outside = false
        var minOut = Float.MAX_VALUE
        for (i in 0 until n) {
            val a = hull[i]
            val b = hull[(i + 1) % n]
            val e = b - a
            val len = e.length
            if (len < 1e-6f) continue
            // left of the edge is inside for a counter-clockwise hull
            val side = (e.u * (p.w - a.w) - e.w * (p.u - a.u)) / len
            if (side < minSide) minSide = side
            if (side < 0f) {
                outside = true
                val d = distanceToSegment(p, a, b)
                if (d < minOut) minOut = d
            }
        }
        return if (outside) -minOut else minSide
    }

    /** The nearest point of the hull's boundary to [p] and the index of that edge. */
    fun nearestEdgePoint(p: P2): Pair<P2, Int>? {
        val n = hull.size
        if (n < 2) return null
        var best: P2? = null
        var bestD = Float.MAX_VALUE
        var bestI = -1
        for (i in 0 until n) {
            val q = closestOnSegment(p, hull[i], hull[(i + 1) % n])
            val d = q.distanceTo(p)
            if (d < bestD) {
                bestD = d
                best = q
                bestI = i
            }
        }
        return best?.let { it to bestI }
    }

    /** Closest point of every edge to [p], sorted by distance (for the "to edge" readings). */
    fun edgePoints(p: P2): List<Pair<P2, Float>> {
        val n = hull.size
        val out = ArrayList<Pair<P2, Float>>(n)
        for (i in 0 until n) {
            val q = closestOnSegment(p, hull[i], hull[(i + 1) % n])
            out += q to q.distanceTo(p)
        }
        out.sortBy { it.second }
        return out
    }

    /** Moves [p] to the nearest point that is at least [margin] inside the hull. */
    fun clampInside(p: P2, margin: Float): P2 {
        var q = p
        for (pass in 0 until 4) {
            val inset = signedInset(q)
            if (inset >= margin - 1e-4f) return q
            val (edgePoint, i) = nearestEdgePoint(q) ?: return q
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val e = b - a
            val len = e.length
            if (len < 1e-6f) return q
            // inward normal of a counter-clockwise edge
            val inward = P2(-e.w / len, e.u / len)
            q = if (inset < 0f) edgePoint + inward * margin else q + inward * (margin - inset)
        }
        return q
    }

    /** Moves [p] out of the hull so that it is at least [margin] away from it; unchanged when already outside. */
    fun clampOutside(p: P2, margin: Float): P2 {
        val inset = signedInset(p)
        if (inset <= -margin) return p
        val (edgePoint, i) = nearestEdgePoint(p) ?: return p
        val a = hull[i]
        val b = hull[(i + 1) % hull.size]
        val e = b - a
        val len = e.length
        if (len < 1e-6f) return p
        val outward = P2(e.w / len, -e.u / len)
        return edgePoint + outward * margin
    }

    /** Sutherland-Hodgman clip of a convex [subject] polygon to the hull. */
    fun clip(subject: List<P2>): List<P2> {
        var out = subject
        val n = hull.size
        if (n < 3) return emptyList()
        for (i in 0 until n) {
            if (out.isEmpty()) return out
            val a = hull[i]
            val b = hull[(i + 1) % n]
            val input = out
            out = ArrayList(input.size + 2)
            for (j in input.indices) {
                val cur = input[j]
                val prev = input[(j + input.size - 1) % input.size]
                val curIn = side(a, b, cur) >= 0f
                val prevIn = side(a, b, prev) >= 0f
                if (curIn) {
                    if (!prevIn) intersect(prev, cur, a, b)?.let { out += it }
                    out += cur
                } else if (prevIn) {
                    intersect(prev, cur, a, b)?.let { out += it }
                }
            }
        }
        return out
    }

    companion object {
        fun side(a: P2, b: P2, p: P2): Float = (b.u - a.u) * (p.w - a.w) - (b.w - a.w) * (p.u - a.u)

        private fun intersect(p: P2, q: P2, a: P2, b: P2): P2? {
            val d1 = side(a, b, p)
            val d2 = side(a, b, q)
            val denom = d1 - d2
            if (abs(denom) < 1e-9f) return null
            val t = d1 / denom
            return p + (q - p) * t
        }

        fun closestOnSegment(p: P2, a: P2, b: P2): P2 {
            val e = b - a
            val len2 = e.dot(e)
            if (len2 < 1e-12f) return a
            val t = ((p - a).dot(e) / len2).coerceIn(0f, 1f)
            return a + e * t
        }

        fun distanceToSegment(p: P2, a: P2, b: P2): Float = closestOnSegment(p, a, b).distanceTo(p)

        fun polygonArea(poly: List<P2>): Float {
            if (poly.size < 3) return 0f
            var s = 0f
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                s += a.u * b.w - b.u * a.w
            }
            return abs(s) / 2f
        }

        /** Andrew's monotone chain; counter-clockwise, no duplicate end point. */
        fun convexHull(points: List<P2>): List<P2> {
            if (points.size < 3) return points
            val sorted = points.sortedWith(compareBy({ it.u }, { it.w }))
            val lower = ArrayList<P2>()
            for (p in sorted) {
                while (lower.size >= 2 && side(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
                lower += p
            }
            val upper = ArrayList<P2>()
            for (p in sorted.asReversed()) {
                while (upper.size >= 2 && side(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
                upper += p
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        /** Circle of [radius] about [c] as a polygon of [segments] points. */
        fun circle(c: P2, radius: Float, segments: Int): List<P2> =
            List(segments) { i ->
                val a = 2.0 * Math.PI * i / segments
                P2(c.u + (kotlin.math.cos(a) * radius).toFloat(), c.w + (kotlin.math.sin(a) * radius).toFloat())
            }

        fun safeSqrt(v: Float): Float = if (v <= 0f) 0f else sqrt(v)

        fun maxOf3(a: Float, b: Float, c: Float): Float = max(a, max(b, c))
    }
}
