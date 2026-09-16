package com.nitmahakal.nsemarketresearch.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import com.nitmahakal.nsemarketresearch.pythonbridge.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.work.workDataOf
import android.os.Build
import android.content.pm.ServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager

class ScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val timeframe = inputData.getString("timeframe") ?: return@coroutineScope Result.failure()
        val conditions = inputData.getString("conditions") ?: return@coroutineScope Result.failure()
        setForeground(createForegroundInfo("Scanner starting…"))
        val task = async(Dispatchers.IO) { PythonBridge.scan(applicationContext, timeframe, conditions) {} }
        while (!task.isCompleted) {
            readProgress(applicationContext, "scan_progress.json")?.let { updateForeground(it) }
            delay(500)
        }
        try {
            val result = task.await()
            updateForeground(result)
            Result.success(workDataOf("result" to result.toString()))
        } catch (e: Throwable) {
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun updateForeground(p: JSONObject) {
        val total = p.optInt("total", 0)
        val done = p.optInt("completed", 0)
        val text = if (total > 0) "Scanning  $done / $total" else "Scanning…"
        setForeground(createForegroundInfo(text))
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val channelId = "nse-scanner"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "NSE scanner", NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("NSE Scanner")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancel)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(4102, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4102, n)
    }
}
