package com.example.wikiwake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // Throws SecurityException when exact alarms aren't allowed.
    fun schedule(context: Context, alarm: WakeAlarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        if (!canScheduleExact(context)) {
            throw SecurityException("Allow exact alarms for Wiki Wake in system settings.")
        }
        val triggerAt = alarm.nextTrigger().toInstant().toEpochMilli()
        val showApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showApp), pendingIntent(context, alarm.id))
    }

    fun scheduleAll(context: Context) {
        AlarmStore.all(context).forEach { schedule(context, it) }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_ALARM)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
