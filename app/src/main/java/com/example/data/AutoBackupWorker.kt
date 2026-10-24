package com.example.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Daily backup into the folder chosen in Settings (only while automatic backup is on). */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefsRepo = UserPreferencesRepository(applicationContext)
        val prefs = prefsRepo.preferencesFlow.first()
        if (!prefs.autoBackupEnabled || prefs.autoBackupFolder.isBlank()) return Result.success()
        val json = BackupManager(StreamShieldDatabase.getInstance(applicationContext), prefsRepo).export()
        val ok = BackupFolder.writeAutoBackup(applicationContext, prefs.autoBackupFolder, json)
        if (ok) prefsRepo.setLastAutoBackup(System.currentTimeMillis())
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "auto_backup"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
