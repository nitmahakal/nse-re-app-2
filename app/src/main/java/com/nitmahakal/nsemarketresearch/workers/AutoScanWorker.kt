package com.nitmahakal.nsemarketresearch.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.core.app.NotificationCompat
import androidx.work.workDataOf
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.pm.ServiceInfo
import com.nitmahakal.nsemarketresearch.data.SavedScanRepository
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AutoScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(info("Running saved scans…"))
        val repo = SavedScanRepository(applicationContext)
        val scans = repo.list().filter { it.optBoolean("auto", false) }
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val bridge = Python.getInstance().getModule("android_bridge")
        val dataDir = applicationContext.filesDir.resolve("market_data").absolutePath
        val resultDir = repo.resultDir().absolutePath
        val trackingDir = repo.trackingDir().absolutePath
        var done = 0
        for (scan in scans) {
            try {
                bridge.callAttr(
                    "run_saved_and_track",
                    dataDir,
                    scan.optString("name"),
                    scan.optString("timeframe"),
                    scan.optJSONArray("conditions")?.toString() ?: "[]",
                    resultDir,
                    trackingDir
                )
            } catch (_: Throwable) { }
            done++
            setForeground(info("Saved scans  $done / ${scans.size}"))
        }
        Result.success(workDataOf("scans" to done))
    }

    private fun info(text: String): ForegroundInfo {
        val id = "nse-autoscan"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(id, "Saved scans", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, id)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NSE saved scans")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4103, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4103, n)
    }
}
