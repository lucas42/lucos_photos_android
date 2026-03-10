package eu.l42.lucos_photos_android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts an Android system notification when a newer version of the app is available.
 *
 * Notification posting is best-effort: any failure is logged and swallowed.
 * A notification failure must never cause a sync to fail or retry.
 */
class UpdateNotifier(private val context: Context) {

    /**
     * Posts (or updates) the "update available" notification.
     *
     * Safe to call repeatedly with the same [latestVersion] — Android will silently update the
     * existing notification rather than posting a duplicate.
     *
     * @param latestVersion The version string reported by the server (e.g. "1.2.3").
     */
    fun notifyUpdateAvailable(latestVersion: String) {
        try {
            ensureChannelExists()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text, latestVersion))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(false)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "Update notification posted (latest=$latestVersion)")
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted (API 33+). Log and swallow.
            Log.d(TAG, "POST_NOTIFICATIONS permission not granted — skipping notification")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post update notification: ${e.message}")
        }
    }

    /** Creates the notification channel if it does not already exist. */
    private fun ensureChannelExists() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "UpdateNotifier"
        internal const val CHANNEL_ID = "app_updates"
        internal const val NOTIFICATION_ID = 1001
    }
}
