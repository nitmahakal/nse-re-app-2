package com.nitmahakal.nsemarketresearch.pythonbridge

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

object PythonBridge {
    fun init(context: Context) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }

    fun readBundledSymbols(context: Context): List<String> {
        return context.assets.open("symbols.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim().uppercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { if (it.endsWith(".NS")) it else "$it.NS" }
                .distinct()
                .toList()
        }
    }

    fun update(context: Context, progress: (JSONObject) -> Unit): JSONObject {
        init(context)
        val symbols = readBundledSymbols(context)
        val dataDir = context.filesDir.resolve("market_data").absolutePath
        val bridge = Python.getInstance().getModule("android_bridge")
        val finalResult = JSONObject(bridge.callAttr("update_market_data", symbols.toTypedArray(), dataDir).toString())
        progress(finalResult)
        return finalResult
    }

    fun scan(
        context: Context,
        timeframe: String,
        conditionsJson: String,
        progress: (JSONObject) -> Unit
    ): JSONObject {
        init(context)
        val dataDir = context.filesDir.resolve("market_data").absolutePath
        val bridge = Python.getInstance().getModule("android_bridge")
        val result = JSONObject(bridge.callAttr("run_scan", dataDir, timeframe, conditionsJson).toString())
        progress(result)
        return result
    }
}
