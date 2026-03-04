package eu.l42.lucos_photos_android

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Application class responsible for scheduling the periodic background photo sync.
 *
 * WorkManager is initialised here on app start with a custom [WorkerFactory] so that
 * [PhotoSyncWorker] can receive its dependencies via constructor injection (rather than
 * relying on the reflection-based default instantiation, which only calls the two-argument
 * constructor and cannot supply the [PhotoUploader] and [SyncPreferences] defaults).
 *
 * The periodic sync is enqueued using [ExistingPeriodicWorkPolicy.KEEP] — so repeated
 * app launches don't reset the schedule.
 */
class PhotoBackupApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initWorkManager()
        schedulePhotoSync()
    }

    /**
     * Manually initialise WorkManager with a custom factory.
     *
     * This is required because [PhotoSyncWorker] has constructor parameters beyond the standard
     * (Context, WorkerParameters) pair. WorkManager's default initialisation (via
     * androidx.startup) uses reflection and only knows about the two-argument constructor —
     * it has no way to supply [PhotoUploader] or [SyncPreferences]. Without a custom factory,
     * WorkManager would throw a runtime exception and the background sync would silently fail.
     *
     * The corresponding androidx.startup provider is disabled in AndroidManifest.xml so that
     * WorkManager doesn't initialise itself before we can configure it here.
     */
    private fun initWorkManager() {
        val config = Configuration.Builder()
            .setWorkerFactory(
                PhotoSyncWorkerFactory(
                    uploader = PhotoUploader(),
                    prefs = SyncPreferences(this),
                )
            )
            .build()
        WorkManager.initialize(this, config)
        Log.i(TAG, "WorkManager initialised with custom worker factory")
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
