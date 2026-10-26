package io.embodit.robotcell.ui.cell.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector3
import io.embodit.robotcell.domain.model.Vec3
import io.embodit.robotcell.platform.CellLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Programmatic meshes of this app on top of the shared [MeshFactory]. */
internal object CellMeshes {

    /** Unit sphere (radius 1) about the origin. */
    fun unitSphere(name: String, around: Int = 20, rings: Int = 12): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (j in 0..rings) {
            val phi = PI * j / rings - PI / 2
            val y = sin(phi).toFloat()
            val r = cos(phi).toFloat()
            for (i in 0..around) {
                val a = 2 * PI * i / around
                val v = Vector3(r * cos(a).toFloat(), y, r * sin(a).toFloat())
                positions += v
                normals += v
            }
        }
        val row = around + 1
        for (j in 0 until rings) {
            for (i in 0 until around) {
                val a = j * row + i
                val b = a + row
                indices += a
                indices += b
                indices += a + 1
                indices += a + 1
                indices += b
                indices += b + 1
            }
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(1f, 1f, 1f)),
            name = name,
        )
    }

    /**
     * Unit reach sphere drawn as ribbons on its surface: latitude every 15 degrees, meridians every
     * 30 degrees, ribbon width 2 x [halfWidth] (design `entities3d.reachSphere`: 6 mm lines).
     */
    fun wireSphere(name: String, halfWidth: Float = 0.0032f, segments: Int = 72): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3) {
            val base = positions.size
            positions += a
            positions += b
            positions += c
            positions += d
            for (p in listOf(a, b, c, d)) normals += Vector3(p.x, p.y, p.z)
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        // latitude rings
        var latDeg = -75
        while (latDeg <= 75) {
            val lat = Math.toRadians(latDeg.toDouble())
            val r = cos(lat).toFloat()
            val y = sin(lat).toFloat()
            val ty = cos(lat).toFloat()
            for (i in 0 until segments) {
                val a0 = 2 * PI * i / segments
                val a1 = 2 * PI * (i + 1) / segments
                fun at(a: Double, s: Float): Vector3 {
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    // meridian tangent (towards the north pole) at this point
                    val tx = -sin(lat).toFloat() * c
                    val tz = -sin(lat).toFloat() * sn
                    return Vector3(r * c + tx * s, y + ty * s, r * sn + tz * s)
                }
                quad(at(a0, -halfWidth), at(a0, halfWidth), at(a1, halfWidth), at(a1, -halfWidth))
            }
            latDeg += 15
        }
        // meridians: full great circles every 30 degrees of longitude (6 circles)
        val steps = segments
        for (k in 0 until 6) {
            val lon = Math.toRadians(30.0 * k)
            val ex = -sin(lon).toFloat()
            val ez = cos(lon).toFloat()
            for (i in 0 until steps) {
                val p0 = 2 * PI * i / steps
                val p1 = 2 * PI * (i + 1) / steps
                fun at(phi: Double, s: Float): Vector3 {
                    val c = cos(phi).toFloat()
                    val sn = sin(phi).toFloat()
                    return Vector3(c * cos(lon).toFloat() + ex * s, sn, c * sin(lon).toFloat() + ez * s)
                }
                quad(at(p0, -halfWidth), at(p0, halfWidth), at(p1, halfWidth), at(p1, -halfWidth))
            }
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(1.01f, 1.01f, 1.01f)),
            name = name,
        )
    }

    /** A free triangle list in Stage metres (work disks, wall rings), or null when empty. */
    fun triangles(points: List<Vec3>, normal: Vec3, name: String): MeshResource? {
        if (points.size < 3) return null
        val positions = ArrayList<Vector3>(points.size)
        val normals = ArrayList<Vector3>(points.size)
        val indices = ArrayList<Int>(points.size)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        val n = Vector3(normal.x, normal.y, normal.z)
        for ((i, p) in points.withIndex()) {
            positions += Vector3(p.x, p.y, p.z)
            normals += n
            indices += i
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }
        val count = points.size - points.size % 3
        while (indices.size > count) indices.removeAt(indices.size - 1)
        return runCatching {
                MeshResource.createWithMeshModel(
                    model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
                    bounds =
                        BoundingBox(
                            center = Vector3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f),
                            halfExtent = Vector3((maxX - minX) / 2f + 0.001f, (maxY - minY) / 2f + 0.001f, (maxZ - minZ) / 2f + 0.001f),
                        ),
                    name = name,
                )
            }
            .onFailure { CellLog.e("MESH $name failed: $it", it) }
            .getOrNull()
    }

    /**
     * Hazard stripes around the top of the pedestal: a band of [height] around a square of [half]
     * half size, alternating [perFace] segments; returns the yellow quads (the dark ones come from the
     * pedestal body behind them).
     */
    fun stripeBand(half: Float, height: Float, perFace: Int, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val corners = listOf(Vector3(-half, 0f, -half), Vector3(half, 0f, -half), Vector3(half, 0f, half), Vector3(-half, 0f, half))
        val faceNormals = listOf(Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f), Vector3(-1f, 0f, 0f))
        for (f in 0 until 4) {
            val a = corners[f]
            val b = corners[(f + 1) % 4]
            val n = faceNormals[f]
            for (s in 0 until perFace) {
                if (s % 2 == 1) continue
                val t0 = s.toFloat() / perFace
                val t1 = (s + 1).toFloat() / perFace
                // slanted stripes: the top edge is shifted by one segment share
                val shift = 0.5f / perFace
                val p0 = lerp(a, b, t0)
                val p1 = lerp(a, b, t1)
                val p2 = lerp(a, b, (t1 + shift).coerceAtMost(1f))
                val p3 = lerp(a, b, (t0 + shift).coerceAtMost(1f))
                val base = positions.size
                positions += Vector3(p0.x + n.x * 0.001f, -height / 2f, p0.z + n.z * 0.001f)
                positions += Vector3(p1.x + n.x * 0.001f, -height / 2f, p1.z + n.z * 0.001f)
                positions += Vector3(p2.x + n.x * 0.001f, height / 2f, p2.z + n.z * 0.001f)
                positions += Vector3(p3.x + n.x * 0.001f, height / 2f, p3.z + n.z * 0.001f)
                repeat(4) { normals += n }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(half + 0.002f, height / 2f, half + 0.002f)),
            name = name,
        )
    }

    private fun lerp(a: Vector3, b: Vector3, t: Float): Vector3 = Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
}

/** One entity whose mesh is rebuilt from a triangle list when the geometry changes (work disks, wall rings). */
internal class TriMesh(private val parent: Entity, private val material: Material, private val name: String) {
    private var entity: Entity? = null
    private var mesh: MeshResource? = null

    fun set(points: List<Vec3>, normal: Vec3) {
        clear()
        val m = CellMeshes.triangles(points, normal, name) ?: return
        val e = ModelEntity(m, material).named(name)
        parent.addChild(e)
        entity = e
        mesh = m
    }

    fun setMaterial(value: Material) = entity?.setMaterial(value)

    fun clear() {
        entity?.let { runCatching { it.destroy(true) } }
        entity = null
        mesh = null
    }
}
