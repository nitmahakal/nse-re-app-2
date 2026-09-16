package com.nitmahakal.nsemarketresearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nitmahakal.nsemarketresearch.data.AppStore
import com.nitmahakal.nsemarketresearch.workers.MarketUpdateWorker
import com.nitmahakal.nsemarketresearch.workers.ScanWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val wm = WorkManager.getInstance(app)
    private val _updateText = MutableStateFlow("Ready")
    val updateText = _updateText.asStateFlow()
    private val _updateProgress = MutableStateFlow(0f)
    val updateProgress = _updateProgress.asStateFlow()
    private val _updateRunning = MutableStateFlow(false)
    val updateRunning = _updateRunning.asStateFlow()
    private val _scanText = MutableStateFlow("Ready")
    val scanText = _scanText.asStateFlow()
    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress = _scanProgress.asStateFlow()
    private val _scanRunning = MutableStateFlow(false)
    val scanRunning = _scanRunning.asStateFlow()
    private val _lastUpdated = MutableStateFlow("")
    val lastUpdated = _lastUpdated.asStateFlow()
    private val _resultJson = MutableStateFlow("")
    val resultJson = _resultJson.asStateFlow()
    private val _scanResultJson = MutableStateFlow("")
    val scanResultJson = _scanResultJson.asStateFlow()

    init {
        viewModelScope.launch { store.lastUpdated.collect { _lastUpdated.value = it } }
        observeWorkers()
    }

    private fun observeWorkers() {
        wm.getWorkInfosByTagLiveData(MarketUpdateWorker.TAG).observeForever { infos ->
            val info = infos.firstOrNull()
            if (info != null) {
                _updateRunning.value = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                if (info.state.isFinished) {
                    val result = info.outputData.getString("result")
                    if (!result.isNullOrBlank()) {
                        _resultJson.value = result
                        try {
                            val o = JSONObject(result)
                            val through = o.optString("market_data_through", "")
                            if (through.isNotBlank()) viewModelScope.launch { store.setLastUpdated(through) }
                            _updateText.value = if (o.optBoolean("success", false)) "Updated data upto $through" else "Update completed with ${o.optInt("failed", 0)} failed symbols (one retry used)"
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        wm.getWorkInfosByTagLiveData("nse-scan").observeForever { infos ->
            val info = infos.firstOrNull()
            if (info != null) {
                _scanRunning.value = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                if (info.state.isFinished) {
                    info.outputData.getString("result")?.let { _scanResultJson.value = it; _scanText.value = "Scan complete" }
                }
            }
        }
    }

    fun startUpdate() {
        if (_updateRunning.value) return
        _updateText.value = "Update in progress…"
        _updateProgress.value = 0f
        val req = OneTimeWorkRequestBuilder<MarketUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(MarketUpdateWorker.TAG)
            .build()
        wm.enqueueUniqueWork(MarketUpdateWorker.TAG, ExistingWorkPolicy.KEEP, req)
        viewModelScope.launch { store.setLastUpdated("") }
    }

    fun startScan(timeframe: String, conditionsJson: String) {
        if (_scanRunning.value) return
        _scanText.value = "Scanner is running…"
        _scanProgress.value = 0f
        _scanResultJson.value = ""
        val req = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setInputData(androidx.work.workDataOf("timeframe" to timeframe, "conditions" to conditionsJson))
            .addTag("nse-scan")
            .build()
        wm.enqueueUniqueWork("nse-scan", ExistingWorkPolicy.KEEP, req)
    }
}
