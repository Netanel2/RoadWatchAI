package com.netanel.roadwatch.storage

import android.content.Context
import com.netanel.roadwatch.core.Line2
import com.netanel.roadwatch.core.Polygon2
import com.netanel.roadwatch.core.Vec2
import com.netanel.roadwatch.core.ZoneConfig

class ZoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("roadwatch_zones", Context.MODE_PRIVATE)

    fun load(): ZoneConfig {
        val newParking = readPolygonList("parkingZones")
        val legacyParking = Polygon2(readPoints("parking")).takeIf { it.isValid() }
        val parkingZones = if (newParking.isNotEmpty()) newParking else listOfNotNull(legacyParking)
        return ZoneConfig(
            road = Polygon2(readPoints("road")),
            parkingZones = parkingZones,
            countLine = readPoints("line").takeIf { it.size == 2 }?.let { Line2(it[0], it[1]) }
        )
    }

    fun save(config: ZoneConfig) {
        writePoints("road", config.road.points)
        writePolygonList("parkingZones", config.parkingZones.filter { it.isValid() })
        writePoints("line", config.countLine?.let { listOf(it.a, it.b) } ?: emptyList())
        // Clean legacy key after a successful write.
        prefs.edit().remove("parking").apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun writePolygonList(key: String, polygons: List<Polygon2>) {
        val encoded = polygons.joinToString("|") { polygon ->
            polygon.points.joinToString(";") { "${it.x},${it.y}" }
        }
        prefs.edit().putString(key, encoded).apply()
    }

    private fun readPolygonList(key: String): List<Polygon2> {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('|').mapNotNull { encoded ->
            val points = parsePoints(encoded)
            Polygon2(points).takeIf { it.isValid() }
        }
    }

    private fun writePoints(key: String, points: List<Vec2>) {
        val encoded = points.joinToString(";") { "${it.x},${it.y}" }
        prefs.edit().putString(key, encoded).apply()
    }

    private fun readPoints(key: String): List<Vec2> =
        parsePoints(prefs.getString(key, "").orEmpty())

    private fun parsePoints(raw: String): List<Vec2> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { token ->
            val parts = token.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            Vec2(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
    }
}
