package com.example.wikiwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val alarm = AlarmStore.get(context, intent.getIntExtra(EXTRA_ALARM_ID, -1)) ?: return
        if (!alarm.enabled) return

        try {
            if (alarm.days == 0) {
                AlarmStore.put(context, alarm.copy(enabled = false))
            } else {
                AlarmScheduler.schedule(context, alarm)
            }
        } catch (error: SecurityException) {
            AlarmNotifications.showError(context, "Allow exact alarms in system settings to keep your schedule.")
        }

        val playback = Intent(context, AlarmPlaybackService::class.java)
            .putExtra(AlarmPlaybackService.EXTRA_FROM_ALARM, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(playback)
        } else {
            context.startService(playback)
        }
    }

    companion object {
        const val ACTION_ALARM = "com.example.wikiwake.DAILY_ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
