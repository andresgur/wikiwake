package com.example.wikiwake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmNotifications {
    const val CHANNEL_ID = "wiki_wake_alarm"
    const val NOTIFICATION_ID = 4108
    private const val ERROR_NOTIFICATION_ID = 4109

    fun showAlarm(context: Context, title: String, text: String, url: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(alarmActivityIntent(context))
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        context,
                        android.R.drawable.ic_media_pause
                    ),
                    "Stop",
                    PendingIntent.getService(
                        context,
                        NOTIFICATION_ID,
                        Intent(context, AlarmPlaybackService::class.java)
                            .setAction(AlarmPlaybackService.ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
        if (!url.isNullOrEmpty()) {
            notification.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "Wikipedia",
                    PendingIntent.getActivity(
                        context,
                        NOTIFICATION_ID + 1,
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
        }

        if (canUseFullScreenIntent(manager)) {
            notification.setFullScreenIntent(alarmActivityIntent(context), true)
        }
        manager.notify(NOTIFICATION_ID, notification.build())
    }

    fun showError(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wiki Wake couldn't start")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    ERROR_NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Daily Wikipedia alarm",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Spoken Wikipedia article alarms"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun canUseFullScreenIntent(manager: NotificationManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()

    private fun alarmActivityIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
