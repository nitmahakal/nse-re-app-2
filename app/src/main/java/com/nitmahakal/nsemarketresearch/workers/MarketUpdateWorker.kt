package com.nitmahakal.nsemarketresearch.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.nitmahakal.nsemarketresearch.R
import com.nitmahakal.nsemarketresearch.ScheduleManager
import com.nitmahakal.nsemarketresearch.pythonbridge.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.pm.ServiceInfo

class MarketUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        setForeground(createForegroundInfo("Starting market-data update…"))
        val task = async(Dispatchers.IO) { PythonBridge.update(applicationContext) { } }
        while (!task.isCompleted) {
            val p = readProgress(applicationContext, "update_progress.json")
            if (p != null) updateForeground(p)
            delay(500)
        }
        try {
            val result = task.await()
            updateForeground(result)
            if (result.optBoolean("success", false)) {
                val auto = OneTimeWorkRequestBuilder<AutoScanWorker>().addTag("nse-autoscan").build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("nse-autoscan", ExistingWorkPolicy.REPLACE, auto)
            }
            ScheduleManager.rescheduleNextDay(applicationContext)
            Result.success(androidx.work.workDataOf("result" to result.toString()))
        } catch (e: Throwable) {
            updateForeground(org.json.JSONObject().put("stage", "error").put("error", e.message ?: "Unknown error"))
            Result.failure(androidx.work.workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun updateForeground(p: org.json.JSONObject) {
        val completed = p.optInt("completed", 0)
        val total = p.optInt("total", 0)
        val stage = p.optString("stage", "running")
        val text = if (total > 0) "$stage  $completed / $total" else stage
        setForeground(createForegroundInfo(text))
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        createChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NSE Market Research")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancel)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "NSE background work", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val TAG = "market-update"
        const val CHANNEL_ID = "nse-background"
        const val NOTIFICATION_ID = 4101
    }
}
