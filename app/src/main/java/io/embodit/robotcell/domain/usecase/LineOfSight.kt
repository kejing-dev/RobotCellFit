package io.embodit.robotcell.domain.usecase

import io.embodit.robotcell.domain.model.Vec3

/** A capsule of the arm (or the gripper) in Stage metres. */
data class Capsule(val a: Vec3, val b: Vec3, val r: Float)

/**
 * Keeps panels in front of the robot arm (v0.1.2 UI audit): when a capsule of the arm comes within a panel's
 * radius of the line from the eyes to the panel, nearer than the panel itself, the panel is brought towards the
 * eyes until it is in front of the arm (and scaled by the same factor, so it keeps its angular size). Pure JVM.
 */
object LineOfSight {
    /**
     * The share (0 ... 1] of its distance a panel at [target] of radius [radius] must come to the eyes at [eye]
     * to be in front of every capsule; 1 when nothing is in the way. Never nearer than [minDistM].
     */
    fun pullFactor(eye: Vec3, target: Vec3, radius: Float, capsules: List<Capsule>, minDistM: Float = MIN_DIST_M, margin: Float = MARGIN_M): Float {
        val to = target - eye
        val d = to.length
        if (d < 1e-3f) return 1f
        val u = to / d
        var wanted = d
        for (c in capsules) {
            for (i in 0..SAMPLES) {
                val q = c.a.lerp(c.b, i.toFloat() / SAMPLES)
                val t = (q - eye).dot(u)
                if (t <= NEAR_IGNORE_M || t - c.r - margin >= wanted) continue
                if (t > d + c.r) continue
                val lateral = (q - eye - u * t).length
                if (lateral >= radius + c.r) continue
                wanted = t - c.r - margin
            }
        }
        if (wanted >= d) return 1f
        return (maxOf(wanted, minDistM) / d).coerceIn(0.05f, 1f)
    }

    /** Vertical offsets that stack plates whose anchors are closer than [minGapM] horizontally and [heightM] vertically. */
    fun stack(anchors: List<Vec3>, minGapM: Float, heightM: Float): List<Float> {
        val out = ArrayList<Float>(anchors.size)
        for (i in anchors.indices) {
            var lift = 0f
            // each pass can only raise the plate, and at most i passes are needed (bounded: no float ping-pong)
            repeat(i + 1) {
                for (j in 0 until i) {
                    val a = anchors[i] + Vec3(0f, lift, 0f)
                    val b = anchors[j] + Vec3(0f, out[j], 0f)
                    val flat = Vec3(a.x - b.x, 0f, a.z - b.z).length
                    if (flat < minGapM && kotlin.math.abs(a.y - b.y) < heightM - EPS_M) {
                        lift = maxOf(lift, (b.y - anchors[i].y) + heightM)
                    }
                }
            }
            out += lift
        }
        return out
    }

    const val MIN_DIST_M = 0.32f
    const val MARGIN_M = 0.04f
    private const val NEAR_IGNORE_M = 0.08f
    private const val EPS_M = 1e-4f
    private const val SAMPLES = 10
}
