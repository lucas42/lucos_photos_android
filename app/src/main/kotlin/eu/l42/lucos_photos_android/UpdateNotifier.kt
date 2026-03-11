package eu.l42.lucos_photos_android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
     * Tapping the notification opens [MainActivity.APP_DOWNLOAD_URL] in the device browser.
     *
     * @param latestVersion The version string reported by the server (e.g. "1.2.3").
     */
    fun notifyUpdateAvailable(latestVersion: String) {
        try {
            ensureChannelExists()

            // Use an explicit Intent targeting MainActivity rather than an implicit ACTION_VIEW
            // intent. An implicit PendingIntent handed to NotificationManager can be redirected
            // by a malicious component to an arbitrary destination. By targeting our own activity
            // explicitly and passing the URL as an extra, we avoid this risk — MainActivity then
            // opens the URL in the browser from within our own trusted code.
            val openUrlIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_URL, MainActivity.APP_DOWNLOAD_URL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openUrlIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text, latestVersion))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)
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

    /**
     * Cancels the "update available" notification if it is currently showing.
     *
     * Call this when the version check confirms the app is up to date, so stale notifications
     * are dismissed automatically without requiring user action.
     *
     * Safe to call when no notification is showing — the cancel is a no-op in that case.
     */
    fun cancelUpdateNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            Log.i(TAG, "Update notification cancelled — app is up to date")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel update notification: ${e.message}")
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
