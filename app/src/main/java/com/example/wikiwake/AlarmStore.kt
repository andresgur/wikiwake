package com.example.wikiwake

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.ZonedDateTime

// days: bit 0 = Monday ... bit 6 = Sunday. 0 means a one-time alarm.
data class WakeAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val enabled: Boolean
) {
    fun nextTrigger(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val base = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        for (offset in 0L..7L) {
            val candidate = base.plusDays(offset)
            if (!candidate.isAfter(now)) continue
            if (days == 0 || days and (1 shl (candidate.dayOfWeek.value - 1)) != 0) return candidate
        }
        return base.plusDays(1)
    }

    fun repeatText(): String = when (days) {
        0 -> "Once"
        ALL_DAYS -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        else -> DayOfWeek.values()
            .filter { days and (1 shl (it.value - 1)) != 0 }
            .joinToString(", ") { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
    }

    companion object {
        const val ALL_DAYS = 127
        const val WEEKDAYS = 31
        const val WEEKEND = 96
    }
}

object AlarmStore {
    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "alarm_next_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)

    fun all(context: Context): List<WakeAlarm> {
        val preferences = prefs(context)
        val raw = preferences.getString(KEY_ALARMS, null)
        if (raw == null) {
            // Carry over the single alarm from earlier versions.
            if (!preferences.contains(MainActivity.HOUR)) return emptyList()
            val migrated = listOf(
                WakeAlarm(
                    1,
                    preferences.getInt(MainActivity.HOUR, 7),
                    preferences.getInt(MainActivity.MINUTE, 0),
                    WakeAlarm.ALL_DAYS,
                    preferences.getBoolean(MainActivity.ENABLED, false)
                )
            )
            save(context, migrated)
            preferences.edit().putInt(KEY_NEXT_ID, 2).apply()
            return migrated
        }
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            WakeAlarm(o.getInt("id"), o.getInt("hour"), o.getInt("minute"), o.getInt("days"), o.getBoolean("enabled"))
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(context: Context, id: Int): WakeAlarm? = all(context).firstOrNull { it.id == id }

    fun nextId(context: Context): Int {
        val preferences = prefs(context)
        val id = preferences.getInt(KEY_NEXT_ID, 1)
        preferences.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    fun put(context: Context, alarm: WakeAlarm) =
        save(context, all(context).filter { it.id != alarm.id } + alarm)

    fun remove(context: Context, id: Int) = save(context, all(context).filter { it.id != id })

    private fun save(context: Context, alarms: List<WakeAlarm>) {
        val array = JSONArray()
        alarms.forEach {
            array.put(
                JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute)
                    .put("days", it.days).put("enabled", it.enabled)
            )
        }
        prefs(context).edit().putString(KEY_ALARMS, array.toString()).apply()
    }
}
