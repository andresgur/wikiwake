package com.example.wikiwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            AlarmScheduler.scheduleAll(context)
        } catch (error: SecurityException) {
            AlarmNotifications.showError(context, "Allow exact alarms in system settings to restore your schedule.")
        }
    }
}
