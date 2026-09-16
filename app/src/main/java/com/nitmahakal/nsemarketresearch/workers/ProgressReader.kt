package com.nitmahakal.nsemarketresearch.workers

import android.content.Context
import org.json.JSONObject
import java.io.File

fun readProgress(context: Context, name: String): JSONObject? = try {
    val f = context.filesDir.resolve("market_data").resolve(name)
    if (!f.exists()) null else JSONObject(f.readText())
} catch (_: Exception) { null }
