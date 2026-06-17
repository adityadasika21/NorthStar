package com.example.northstar.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.northstar.MainActivity
import com.example.northstar.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging receiver. Manifest-declared, so it's only ever invoked when a
 * Firebase project is configured and a push arrives — otherwise it lies dormant and the app
 * stays fully local. Displays incoming notification messages; [onNewToken] is where a future
 * sync layer would register the device token.
 */
class NorthstarMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // No backend registry yet — when multi-device sync lands, persist/upload this token.
        Log.d(TAG, "FCM registration token refreshed")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        getSystemService<NotificationManager>()
            ?.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Northstar notifications",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    private companion object {
        const val TAG = "NorthstarFCM"
        const val CHANNEL_ID = "northstar_general"
    }
}
