package eu.l42.lucos_photos_android

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Custom [WorkerFactory] that injects [PhotoUploader], [SyncPreferences], [TelemetryReporter],
 * [VersionChecker], and [UpdateNotifier] into [PhotoSyncWorker] at construction time.
 *
 * WorkManager's default reflection-based factory only knows about the standard two-argument
 * (Context, WorkerParameters) constructor. By registering this factory we can supply the
 * additional dependencies and keep the worker testable via constructor injection.
 */
class PhotoSyncWorkerFactory(
    private val uploader: PhotoUploader,
    private val prefs: SyncPreferences,
    private val telemetry: TelemetryReporter = TelemetryReporter(),
    private val versionChecker: VersionChecker = VersionChecker(),
    private val updateNotifierFactory: (Context) -> UpdateNotifier = { ctx -> UpdateNotifier(ctx) },
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return if (workerClassName == PhotoSyncWorker::class.java.name) {
            PhotoSyncWorker(
                appContext,
                workerParameters,
                uploader,
                prefs,
                telemetry,
                versionChecker,
                updateNotifierFactory(appContext),
            )
        } else {
            // Return null to let the default factory handle unknown worker classes
            null
        }
    }
}
