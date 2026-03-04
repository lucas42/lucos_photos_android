package eu.l42.lucos_photos_android

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Application class responsible for scheduling the periodic background photo sync.
 *
 * WorkManager is initialised here on app start, and the periodic sync is enqueued
 * using [ExistingPeriodicWorkPolicy.KEEP] — so repeated app launches don't reset
 * the schedule.
 */
class PhotoBackupApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        schedulePhotoSync()
    }

    private fun schedulePhotoSync() {
        Log.i(TAG, "Scheduling periodic photo sync (interval: ${Config.SYNC_INTERVAL_HOURS}h)")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<PhotoSyncWorker>(
            Config.SYNC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )

        Log.i(TAG, "Photo sync work scheduled")
    }

    companion object {
        private const val TAG = "PhotoBackupApplication"
        const val SYNC_WORK_NAME = "photo_sync"
    }
}
