package io.embodit.robotcell.domain.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * UR5e kinematics in double precision, in the DH base frame (Z up, metres, radians): forward kinematics,
 * the closed-form inverse (up to eight solutions: shoulder left / right x wrist up / down x elbow up / down),
 * the geometric Jacobian and the three closed-form singularity terms.
 *
 * Same DH table as [Ur5e]. The robot's local Y-up frame maps to DH as local (x, y, z) -> DH (x, -z, y)
 * ([toDh] / [fromDh]). Pure JVM: tested in `Ur5eKinematicsTest`.
 */
object Ur5eKinematics {
    const val D1 = 0.1625
    const val A2 = -0.425
    const val A3 = -0.3922
    const val D4 = 0.1333
    const val D5 = 0.0997
    const val D6 = 0.0996

    private val D = doubleArrayOf(D1, 0.0, 0.0, D4, D5, D6)
    private val A = doubleArrayOf(0.0, A2, A3, 0.0, 0.0, 0.0)
    private val ALPHA = doubleArrayOf(PI / 2, 0.0, 0.0, PI / 2, -PI / 2, 0.0)

    /** UR5e software limits: every joint +-360 degrees. */
    const val LIMIT_RAD = 2 * PI

    // ------------------------------------------------------------------ 4x4 homogeneous, row major

    /** 4x4 row-major homogeneous transform. */
    class Mat4(val m: DoubleArray = identityArray()) {
        operator fun get(r: Int, c: Int): Double = m[r * 4 + c]

        operator fun times(o: Mat4): Mat4 {
            val n = DoubleArray(16)
            for (r in 0 until 4) for (c in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += m[r * 4 + k] * o.m[k * 4 + c]
                n[r * 4 + c] = s
            }
            return Mat4(n)
        }

        /** Inverse of a rigid transform (rotation transposed). */
        fun rigidInverse(): Mat4 {
            val n = DoubleArray(16)
            for (r in 0 until 3) for (c in 0 until 3) n[r * 4 + c] = m[c * 4 + r]
            for (r in 0 until 3) n[r * 4 + 3] = -(n[r * 4] * m[3] + n[r * 4 + 1] * m[7] + n[r * 4 + 2] * m[11])
            n[15] = 1.0
            return Mat4(n)
        }

        val position: DoubleArray
            get() = doubleArrayOf(m[3], m[7], m[11])

        /** Column [c] of the rotation (0 = x axis, 1 = y, 2 = z). */
        fun axis(c: Int): DoubleArray = doubleArrayOf(m[c], m[4 + c], m[8 + c])

        companion object {
            fun identityArray(): DoubleArray = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

            /** From three orthonormal axes and a position (all length 3). */
            fun of(x: DoubleArray, y: DoubleArray, z: DoubleArray, p: DoubleArray): Mat4 =
                Mat4(doubleArrayOf(x[0], y[0], z[0], p[0], x[1], y[1], z[1], p[1], x[2], y[2], z[2], p[2], 0.0, 0.0, 0.0, 1.0))
        }
    }

    /** DH link transform A_i(theta) = Rz(theta) Tz(d) Tx(a) Rx(alpha). */
    fun link(i: Int, theta: Double): Mat4 {
        val ct = cos(theta)
        val st = sin(theta)
        val ca = cos(ALPHA[i])
        val sa = sin(ALPHA[i])
        return Mat4(
            doubleArrayOf(
                ct, -st * ca, st * sa, A[i] * ct,
                st, ct * ca, -ct * sa, A[i] * st,
                0.0, sa, ca, D[i],
                0.0, 0.0, 0.0, 1.0,
            )
        )
    }

    /** Base -> flange (tool0). */
    fun fk(q: DoubleArray): Mat4 {
        var t = Mat4()
        for (i in 0 until 6) t = t * link(i, q[i])
        return t
    }

    /** The seven frames base, 1 ... 6 (frame i is after joint i). */
    fun fkAll(q: DoubleArray): List<Mat4> {
        val out = ArrayList<Mat4>(7)
        var t = Mat4()
        out += t
        for (i in 0 until 6) {
            t = t * link(i, q[i])
            out += t
        }
        return out
    }

    /** Flange pose plus a tool offset of [toolZ] metres along the flange z axis (the TCP). */
    fun fkTcp(q: DoubleArray, toolZ: Double): Mat4 = fk(q) * translationZ(toolZ)

    fun translationZ(z: Double): Mat4 = Mat4(Mat4.identityArray().also { it[11] = z })

    // ------------------------------------------------------------------ inverse kinematics

    /** Why a target has no inverse solution (the first step of the closed form that failed). */
    enum class IkFailure {
        /** The wrist centre lies within d4 of the J1 axis: the hollow cylinder around the base. */
        INNER_CYLINDER,
        /** The tool cannot point that way from here (|cos theta5| > 1 for both shoulder solutions). */
        ORIENTATION,
        /** Shoulder -> wrist distance outside |a2| - |a3| ... |a2| + |a3| (too far, or folded too short). */
        OUT_OF_REACH,
    }

    class IkResult(val solutions: List<DoubleArray>, val failure: IkFailure?)

    /**
     * Closed-form inverse of [fk] for a flange target [t] (DH base frame). Solutions are in -pi..pi.
     * [q6Hint] is used for joint 6 when the wrist is singular (sin theta5 ~ 0, joint 6 free).
     */
    fun ik(t: Mat4, q6Hint: Double = 0.0): IkResult {
        val sols = ArrayList<DoubleArray>(8)
        // wrist centre: flange minus d6 along the flange z axis
        val p05x = t[0, 3] - D6 * t[0, 2]
        val p05y = t[1, 3] - D6 * t[1, 2]
        val r = hypot(p05x, p05y)
        if (r < abs(D4) + 1e-9) return IkResult(sols, IkFailure.INNER_CYLINDER)
        val psi = atan2(p05y, p05x)
        val phi = acos((D4 / r).coerceIn(-1.0, 1.0))
        var orientationOk = false
        var reachOk = false
        for (s1 in intArrayOf(1, -1)) {
            val q1 = wrapPi(psi + s1 * phi + PI / 2)
            val c1 = cos(q1)
            val sn1 = sin(q1)
            // theta5 from the flange position across the shoulder plane
            val c5 = (t[0, 3] * sn1 - t[1, 3] * c1 - D4) / D6
            if (abs(c5) > 1.0 + 1e-9) continue
            orientationOk = true
            val q5a = acos(c5.coerceIn(-1.0, 1.0))
            for (s5 in intArrayOf(1, -1)) {
                val q5 = s5 * q5a
                val sn5 = sin(q5)
                // theta6 from the base x / y axes seen from the flange
                val q6 =
                    if (abs(sn5) < 1e-7) q6Hint
                    else atan2((-t[0, 1] * sn1 + t[1, 1] * c1) / sn5, (t[0, 0] * sn1 - t[1, 0] * c1) / sn5)
                // frame 1 -> frame 4 = A1^-1 T A6^-1 A5^-1
                val t14 = link(0, q1).rigidInverse() * t * link(5, q6).rigidInverse() * link(4, q5).rigidInverse()
                // joints 2 - 4 turn about frame 1's z axis: frame 4's origin is a planar two-link point in x / y
                val px = t14[0, 3]
                val py = t14[1, 3]
                val l2 = px * px + py * py
                val c3 = (l2 - A2 * A2 - A3 * A3) / (2 * A2 * A3)
                if (abs(c3) > 1.0 + 1e-9) continue
                reachOk = true
                val q3a = acos(c3.coerceIn(-1.0, 1.0))
                for (s3 in intArrayOf(1, -1)) {
                    val q3 = s3 * q3a
                    val q2 = atan2(py, px) - atan2(A3 * sin(q3), A2 + A3 * cos(q3))
                    // the rest of the wrist: theta4 = angle of (frame 3 -> frame 4) about z
                    val t34 = (link(1, q2) * link(2, q3)).rigidInverse() * t14
                    val q4 = atan2(t34[1, 0], t34[0, 0])
                    sols += doubleArrayOf(q1, wrapPi(q2), wrapPi(q3), wrapPi(q4), wrapPi(q5), wrapPi(q6))
                }
            }
        }
        val failure =
            when {
                sols.isNotEmpty() -> null
                !orientationOk -> IkFailure.ORIENTATION
                !reachOk -> IkFailure.OUT_OF_REACH
                else -> IkFailure.OUT_OF_REACH
            }
        return IkResult(sols, failure)
    }

    /**
     * The 2 pi equivalent of every joint of [q] nearest to [reference], kept within the +-360 degree limits.
     */
    fun nearestEquivalent(q: DoubleArray, reference: DoubleArray): DoubleArray =
        DoubleArray(6) { i ->
            var a = q[i]
            while (a - reference[i] > PI) a -= 2 * PI
            while (a - reference[i] < -PI) a += 2 * PI
            if (a > LIMIT_RAD) a -= 2 * PI
            if (a < -LIMIT_RAD) a += 2 * PI
            a
        }

    /** Largest single-joint distance, radians. */
    fun maxJointDelta(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in 0 until 6) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    // ------------------------------------------------------------------ Jacobian and singularities

    /** Geometric Jacobian at a point [toolZ] metres down the flange z axis: rows vx vy vz wx wy wz. */
    fun jacobian(q: DoubleArray, toolZ: Double = 0.0): Array<DoubleArray> {
        val frames = fkAll(q)
        val tcp = (frames[6] * translationZ(toolZ)).position
        val j = Array(6) { DoubleArray(6) }
        for (i in 0 until 6) {
            val f = frames[i]
            val z = f.axis(2)
            val o = f.position
            val d = doubleArrayOf(tcp[0] - o[0], tcp[1] - o[1], tcp[2] - o[2])
            j[0][i] = z[1] * d[2] - z[2] * d[1]
            j[1][i] = z[2] * d[0] - z[0] * d[2]
            j[2][i] = z[0] * d[1] - z[1] * d[0]
            j[3][i] = z[0]
            j[4][i] = z[1]
            j[5][i] = z[2]
        }
        return j
    }

    /** Determinant of a square matrix (Gaussian elimination with partial pivoting). */
    fun determinant(a: Array<DoubleArray>): Double {
        val n = a.size
        val m = Array(n) { a[it].copyOf() }
        var det = 1.0
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-15) return 0.0
            if (p != c) {
                val t = m[p]; m[p] = m[c]; m[c] = t
                det = -det
            }
            det *= m[c][c]
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
            }
        }
        return det
    }

    /**
     * The three factors of det J (Villalobos et al., UR singularities): wrist sin(theta5), elbow sin(theta3),
     * and the shoulder term a2 cos(theta2) + a3 cos(theta2 + theta3) + d5 sin(theta2 + theta3 + theta4), metres:
     * the horizontal distance of the wrist point from the plane through the J1 axis and the J2 axis.
     */
    class Singularity(val wrist: Double, val elbow: Double, val shoulderM: Double) {
        /** |det J| = |a2 a3| |s3| |s5| |shoulder| (6x6 geometric Jacobian, any reference point). */
        val manipulability: Double
            get() = abs(A2 * A3 * elbow * wrist * shoulderM)
    }

    fun singularity(q: DoubleArray): Singularity =
        Singularity(
            wrist = sin(q[4]),
            elbow = sin(q[2]),
            shoulderM = A2 * cos(q[1]) + A3 * cos(q[1] + q[2]) + D5 * sin(q[1] + q[2] + q[3]),
        )

    /**
     * Condition number of the Jacobian with its angular rows scaled by a characteristic length [lengthM]
     * (so that both halves are metres): sqrt(max eigenvalue / min eigenvalue) of J^T J. Infinity when singular.
     */
    fun conditionNumber(q: DoubleArray, toolZ: Double = 0.0, lengthM: Double = 0.4): Double {
        val j = jacobian(q, toolZ)
        for (r in 3 until 6) for (c in 0 until 6) j[r][c] *= lengthM
        val jtj = Array(6) { r -> DoubleArray(6) { c -> (0 until 6).sumOf { k -> j[k][r] * j[k][c] } } }
        val ev = symmetricEigenvalues(jtj)
        val mx = ev.max()
        val mn = ev.min()
        if (mn <= 1e-14) return Double.POSITIVE_INFINITY
        return sqrt(mx / mn)
    }

    /** Eigenvalues of a symmetric matrix (cyclic Jacobi rotations). */
    fun symmetricEigenvalues(input: Array<DoubleArray>): DoubleArray {
        val n = input.size
        val a = Array(n) { input[it].copyOf() }
        repeat(60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-22) return DoubleArray(n) { a[it][it] }
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]
                    val akq = a[k][q]
                    a[k][p] = c * akp - s * akq
                    a[k][q] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p][k]
                    val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk
                    a[q][k] = s * apk + c * aqk
                }
            }
        }
        return DoubleArray(n) { a[it][it] }
    }

    // ------------------------------------------------------------------ frames

    /** Robot local Y-up -> DH Z-up: (x, y, z) -> (x, -z, y). */
    fun toDh(v: Vec3): DoubleArray = doubleArrayOf(v.x.toDouble(), -v.z.toDouble(), v.y.toDouble())

    /** DH Z-up -> robot local Y-up: (x, y, z) -> (x, z, -y). */
    fun fromDh(v: DoubleArray): Vec3 = Vec3(v[0].toFloat(), v[2].toFloat(), (-v[1]).toFloat())

    fun degrees(q: DoubleArray): FloatArray = FloatArray(6) { Math.toDegrees(q[it]).toFloat() }

    fun radians(q: FloatArray): DoubleArray = DoubleArray(6) { Math.toRadians(q[it].toDouble()) }

    fun wrapPi(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}
