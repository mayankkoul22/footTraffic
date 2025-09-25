package com.datafy.foottraffic.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.datafy.foottraffic.ui.StartServiceActivity

/**
 * Posts a notification after boot asking the user to tap once
 * to start the 24/7 camera foreground service.
 */
object StartPromptNotification {

    private const val CHANNEL_ID = "boot_prompt_channel"
    private const val NOTIF_ID = 1001

    fun show(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FootTraffic start prompt",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setShowBadge(false)
                    description = "Notification prompting to start foot traffic counting service"
                }
            )
        }

        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, StartServiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Foot Traffic ready")
            .setContentText("Tap to start 24/7 counting (camera).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
    }
}
