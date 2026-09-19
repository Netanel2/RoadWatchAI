package com.netanel.roadwatch.storage

import android.content.Context
import com.netanel.roadwatch.core.DailyCounts
import java.time.LocalDate

class DailyStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("roadwatch_daily", Context.MODE_PRIVATE)

    fun load(): DailyCounts = DailyCounts(
        dateIso = prefs.getString("date", LocalDate.now().toString()) ?: LocalDate.now().toString(),
        passedToday = prefs.getInt("passed", 0),
        parkedToday = prefs.getInt("parked", 0),
        leftParkingToday = prefs.getInt("left", 0)
    )

    fun save(counts: DailyCounts) {
        prefs.edit()
            .putString("date", counts.dateIso)
            .putInt("passed", counts.passedToday)
            .putInt("parked", counts.parkedToday)
            .putInt("left", counts.leftParkingToday)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
