package com.nitmahakal.nsemarketresearch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nitmahakal.nsemarketresearch.workers.MarketUpdateWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit
import android.content.SharedPreferences

object ScheduleManager {
    private const val WORK_NAME = "daily-market-update"
    private const val PREFS = "schedule_state"

    fun schedule(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putInt("hour", hour).putInt("minute", minute).apply()
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val delay = (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<MarketUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun rescheduleNextDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        schedule(context, prefs.getInt("hour", 18), prefs.getInt("minute", 30))
    }
}
