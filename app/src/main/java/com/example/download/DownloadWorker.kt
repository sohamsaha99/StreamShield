package com.example.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.data.StreamShieldDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Downloads one item (a manual download) in the background, with a progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ITEM_ID) ?: return@withContext Result.failure()
        val dao = StreamShieldDatabase.getInstance(applicationContext).offlineDao()
        val item = dao.getItem(id) ?: return@withContext Result.success()
        showProgress(id, item.title, 0f)
        val outcome = OfflineDownloader(applicationContext, dao).run(id, { isStopped }) { title, fraction ->
            showProgress(id, title, fraction)
        }
        when (outcome) {
            DownloadOutcome.DONE, DownloadOutcome.SKIPPED -> Result.success()
            DownloadOutcome.RETRY -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            DownloadOutcome.FAILED -> Result.failure()
        }
    }

    // Same notification id as the progress updates, so only one notification shows.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        DownloadNotifications.foregroundInfo(
            applicationContext,
            inputData.getString(KEY_ITEM_ID)?.hashCode() ?: NOTIFICATION_ID_GENERIC,
            "Downloading",
            0f
        )

    private var lastNotified = -1

    private suspend fun showProgress(id: String, title: String, fraction: Float) {
        val percent = (fraction * 100).toInt()
        if (percent == lastNotified) return
        lastNotified = percent
        try {
            setForeground(DownloadNotifications.foregroundInfo(applicationContext, id.hashCode(), title, fraction))
        } catch (_: Exception) {
            // Not allowed to show a foreground notification right now (app in the background on
            // newer Android); the download continues as normal background work.
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val NOTIFICATION_ID_GENERIC = 7301

        fun workName(itemId: String) = "download_$itemId"

        /** [restart]: start over a download that is queued or running (Retry); otherwise keep it. */
        fun enqueue(context: Context, itemId: String, restart: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ITEM_ID to itemId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(itemId),
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, itemId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
        }
    }
}

/** Notifications for downloads and smart-download refreshes. */
object DownloadNotifications {
    private const val CHANNEL_ID = "downloads"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of downloads and smart downloads"
                setShowBadge(false)
            }
        )
    }

    fun foregroundInfo(context: Context, notificationId: Int, title: String, fraction: Float): ForegroundInfo {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { "Downloading" })
            .setContentText(if (percent > 0) "$percent %" else "Starting…")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
