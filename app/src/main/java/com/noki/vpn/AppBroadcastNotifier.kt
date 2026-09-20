package com.noki.vpn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.noki.vpn.data.BackendAppNotification

object AppBroadcastNotifier {
    private const val CHANNEL_ID = "noki_app_broadcasts_high_v1"
    private const val NOTIFICATION_ID = 52_000

    fun show(
        context: Context,
        notification: BackendAppNotification,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Noki messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Important Noki messages"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                data = Uri.Builder()
                    .scheme("noki")
                    .authority("app-notification")
                    .appendPath(notification.id)
                    .build()
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val action = AppNotificationAction.parse(notification.action)
                putExtra(MainActivity.EXTRA_APP_NOTIFICATION_ACTION, action?.wireValue)
                putExtra(
                    MainActivity.EXTRA_APP_NOTIFICATION_ACTION_NONCE,
                    action?.let { AppNotificationActionNonceStore.issue(context) },
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = notification.title.trim().ifBlank { "Noki" }
        val message = notification.message.trim()
        if (message.isBlank()) return false

        val androidNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_noki_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

        manager.notify(notification.id, NOTIFICATION_ID, androidNotification)
        return true
    }
}
