package io.embodit.robotcell.data

import android.content.Context
import io.embodit.robotcell.domain.model.Approach
import io.embodit.robotcell.domain.model.Gripper
import io.embodit.robotcell.domain.model.LanguagePref
import io.embodit.robotcell.domain.model.Mount
import io.embodit.robotcell.domain.model.Settings
import io.embodit.robotcell.domain.model.StoredCell
import io.embodit.robotcell.domain.model.StoredSession
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.domain.model.Vec3
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers between launches (design `persistence`): the cell anchor, the robot's pose
 * relative to it, the pins and the preferences. One JSON document in SharedPreferences.
 */
interface CellRepository {
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)
}

class PrefsCellRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : CellRepository {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): StoredSession? =
        withContext(io) {
            val text = prefs.getString(KEY, null) ?: return@withContext null
            runCatching { CellJsonCodec.decode(text) }.getOrNull()
        }

    override suspend fun save(session: StoredSession) {
        withContext(io) { prefs.edit().putString(KEY, CellJsonCodec.encode(session)).commit() }
    }

    private companion object {
        const val FILE = "robot_cell"
        const val KEY = "session_v1"
    }
}

object CellJsonCodec {
    fun encode(s: StoredSession): String {
        val root = JSONObject()
        root.put("v", 1)
        val c = s.cell
        if (c != null) {
            root.put(
                "cell",
                JSONObject()
                    .put("anchor", c.anchorId ?: JSONObject.NULL)
                    .put("offset", vec(c.offset))
                    .put("yaw", c.yaw.toDouble())
                    .put("mount", c.mount.tag)
                    .put("pedestal", c.pedestalH.toDouble())
                    .put("gripper", c.gripperM.toDouble())
                    .put("joints", JSONArray().apply { c.joints.forEach { put(it.toDouble()) } })
                    .put("pins", JSONArray().apply { c.pins.forEach { put(vec(it)) } })
                    .put("base", vec(c.basePos))
                    .put("waypoints", JSONArray().apply { c.waypoints.forEach { w -> put(JSONArray().apply { w.forEach { put(it.toDouble()) } }) } }),
            )
        }
        root.put(
            "settings",
            JSONObject()
                .put("lang", s.settings.language.tag)
                .put("units", s.settings.units.tag)
                .put("sphere", s.settings.showSphere)
                .put("coach", s.settings.coachSeen)
                .put("sound", s.settings.sound)
                .put("approach", s.settings.approach.tag)
                .put("linear", s.settings.linear)
                .put("loop", s.settings.loop),
        )
        return root.toString()
    }

    fun decode(text: String): StoredSession {
        val root = JSONObject(text)
        val cell =
            root.optJSONObject("cell")?.let { o ->
                val joints = o.optJSONArray("joints")
                StoredCell(
                    anchorId = if (o.isNull("anchor")) null else o.optString("anchor"),
                    offset = vec(o.getJSONObject("offset")),
                    yaw = o.optDouble("yaw", 0.0).toFloat(),
                    mount = Mount.fromTag(o.optString("mount")),
                    pedestalH = o.optDouble("pedestal", 0.0).toFloat(),
                    gripperM = Gripper.migrate(o.optDouble("gripper", 0.0).toFloat()),
                    joints = FloatArray(6) { i -> joints?.optDouble(i, 0.0)?.toFloat() ?: 0f },
                    pins = o.optJSONArray("pins")?.let { a -> List(a.length()) { vec(a.getJSONObject(it)) } } ?: emptyList(),
                    basePos = o.optJSONObject("base")?.let { vec(it) } ?: Vec3.ZERO,
                    waypoints = o.optJSONArray("waypoints")?.let { a -> List(a.length()) { i -> a.getJSONArray(i).let { w -> FloatArray(6) { j -> w.optDouble(j, 0.0).toFloat() } } } } ?: emptyList(),
                )
            }
        val st = root.optJSONObject("settings")
        val settings =
            Settings(
                language = LanguagePref.fromTag(st?.optString("lang")),
                units = UnitPref.fromTag(st?.optString("units")),
                showSphere = st?.optBoolean("sphere", true) ?: true,
                coachSeen = st?.optBoolean("coach", false) ?: false,
                sound = st?.optBoolean("sound", true) ?: true,
                approach = Approach.fromTag(st?.optString("approach")),
                linear = st?.optBoolean("linear", false) ?: false,
                loop = st?.optBoolean("loop", true) ?: true,
            )
        return StoredSession(cell, settings)
    }

    private fun vec(v: Vec3): JSONObject = JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("z", v.z.toDouble())

    private fun vec(o: JSONObject): Vec3 = Vec3(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("z").toFloat())
}
