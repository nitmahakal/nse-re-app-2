package com.nitmahakal.nsemarketresearch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SavedScanRepository(private val context: Context) {
    private val file: File get() = context.filesDir.resolve("saved_scans.json")

    fun list(): List<JSONObject> = try {
        val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    fun save(name: String, timeframe: String, conditions: String, auto: Boolean): JSONObject {
        val arr = JSONArray().apply { list().forEach { put(it) } }
        val obj = JSONObject().apply {
            put("name", name)
            put("timeframe", timeframe)
            put("conditions", JSONArray(conditions))
            put("auto", auto)
            put("saved_at", System.currentTimeMillis())
        }
        arr.put(obj)
        atomicWrite(arr.toString(2))
        return obj
    }

    fun delete(name: String) {
        val arr = JSONArray()
        list().filterNot { it.optString("name") == name }.forEach { arr.put(it) }
        atomicWrite(arr.toString(2))
    }

    fun setAuto(name: String, auto: Boolean) {
        val arr = JSONArray()
        list().forEach { obj ->
            if (obj.optString("name") == name) obj.put("auto", auto)
            arr.put(obj)
        }
        atomicWrite(arr.toString(2))
    }

    fun resultDir(): File = context.filesDir.resolve("saved_results").also { it.mkdirs() }
    fun saveResult(name: String, date: String, json: String) {
        resultDir().resolve("${safeName(name)}_$date.json").writeText(json)
    }
    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "scan" }
    fun trackingDir(): File = context.filesDir.resolve("tracking").also { it.mkdirs() }

    private fun atomicWrite(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }
}
