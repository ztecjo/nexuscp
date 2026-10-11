// SPDX-License-Identifier: GPL-3.0-only
package com.ztec.cplay

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"), DAY("day"), NIGHT("night"), SCHEDULE("schedule");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

internal data class CarPlayNightSchedule(val start: Int = 18 * 60, val end: Int = 6 * 60) {
    init { require(start in 0..1439 && end in 0..1439) }
    fun isNight(minute: Int): Boolean {
        require(minute in 0..1439)
        return when {
            start == end -> false
            start < end -> minute >= start && minute < end
            else -> minute >= start || minute < end
        }
    }
}

internal object NightModeSettings {
    const val PREFS = "carplay_night_mode"
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun mode(context: Context) = CarPlayNightMode.fromKey(preferences(context).getString("mode", null))
    fun saveMode(context: Context, mode: CarPlayNightMode) {
        preferences(context).edit { putString("mode", mode.key) }
    }
    fun schedule(context: Context): CarPlayNightSchedule {
        val p = preferences(context)
        return CarPlayNightSchedule(p.getInt("start", 1080).takeIf { it in 0..1439 } ?: 1080,
            p.getInt("end", 360).takeIf { it in 0..1439 } ?: 360)
    }
    fun saveSchedule(context: Context, schedule: CarPlayNightSchedule) {
        preferences(context).edit { putInt("start", schedule.start); putInt("end", schedule.end) }
    }
    fun resolve(mode: CarPlayNightMode, schedule: CarPlayNightSchedule, systemNight: Boolean, minute: Int) = when (mode) {
        CarPlayNightMode.SYSTEM -> systemNight
        CarPlayNightMode.DAY -> false
        CarPlayNightMode.NIGHT -> true
        CarPlayNightMode.SCHEDULE -> schedule.isNight(minute)
    }
    fun current(context: Context): Boolean {
        val now = Calendar.getInstance()
        return resolve(mode(context), schedule(context), isDarkMode(context.resources.configuration.uiMode),
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }
}
