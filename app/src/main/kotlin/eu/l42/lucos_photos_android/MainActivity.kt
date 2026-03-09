package eu.l42.lucos_photos_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Minimal launcher activity.
 *
 * Responsibilities:
 * - Request the READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE (API <33) permission
 * - Show the current sync status
 * - Provide a "Sync now" button for manual triggering
 *
 * The app is designed to run headlessly in the background — this UI is just for
 * setup and debugging purposes.
 */
class MainActivity : AppCompatActivity() {

    private val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.status_text)
        val syncButton = findViewById<Button>(R.id.sync_now_button)
        val prefs = SyncPreferences(this)

        updateStatusText(statusText, prefs)

        syncButton.setOnClickListener {
            if (hasPermission()) {
                val request = triggerImmediateSync()
                statusText.text = getString(R.string.status_syncing)
                // Observe the work so the UI updates once the sync finishes (or fails).
                WorkManager.getInstance(this)
                    .getWorkInfoByIdLiveData(request.id)
                    .observe(this) { workInfo ->
                        if (workInfo != null && workInfo.state.isFinished) {
                            updateStatusText(statusText, SyncPreferences(this))
                        }
                    }
            } else {
                requestPermission()
            }
        }

        if (!hasPermission()) {
            requestPermission()
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(requiredPermission), REQUEST_CODE_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Photo permission granted")
                updateStatusText(findViewById(R.id.status_text), SyncPreferences(this))
            } else {
                Log.w(TAG, "Photo permission denied")
                findViewById<TextView>(R.id.status_text).text = getString(R.string.status_permission_denied)
            }
        }
    }

    private fun triggerImmediateSync(): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<PhotoSyncWorker>().build()
        // Use enqueueUniqueWork so that if a sync is already running or pending, subsequent
        // taps are ignored (KEEP policy). Without this, each tap enqueues a fresh job that
        // reads the same lastSyncTimestampMs — causing the full library to be re-scanned
        // if no photos have been uploaded yet, or duplicating upload work mid-batch.
        WorkManager.getInstance(this).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "Manual sync triggered")
        return request
    }

    private fun updateStatusText(textView: TextView, prefs: SyncPreferences) {
        val lastSyncCompletedAt = prefs.lastSyncCompletedAtMs
        textView.text = if (lastSyncCompletedAt == 0L) {
            getString(R.string.status_never_synced)
        } else {
            getString(R.string.status_last_synced, java.util.Date(lastSyncCompletedAt).toString())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSION = 1001
        private const val IMMEDIATE_SYNC_WORK_NAME = "photo_sync_immediate"
    }
}
