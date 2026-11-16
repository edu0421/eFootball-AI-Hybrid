package com.efai.hybrid

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class MatchRow(val opponent: String, val goalsFor: Int, val goalsAgainst: Int)
data class Summary(val n: Int, val w: Int, val d: Int, val l: Int, val gf: Int, val ga: Int)

/**
 * The only place Kotlin touches Python. All calls must run on [executor] (never the main thread);
 * it is a single daemon thread shared by the service and the activity.
 */
object PythonBridge {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "efai-python").apply { isDaemon = true }
    }

    @Volatile private var api: PyObject? = null

    @Synchronized
    fun init(ctx: Context) {
        if (api != null) return
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        val m = Python.getInstance().getModule("efai.api")
        m.callAttr("init", ctx.filesDir.absolutePath)
        api = m
    }

    private fun a(): PyObject = api ?: throw IllegalStateException("Python not initialised")

    /** [rgba] is tightly packed RGBA_8888 (w*h*4 bytes). */
    fun analyze(rgba: ByteArray, w: Int, h: Int, settingsJson: String): AnalysisResult =
        AnalysisResult.fromJson(a().callAttr("analyze_and_log", rgba, w, h, settingsJson).toString())

    fun setFeedback(recId: Long, helpful: Boolean) {
        a().callAttr("set_feedback", recId, helpful)
    }

    fun addMatch(opponent: String, gf: Int, ga: Int, shots: Int) {
        a().callAttr("add_match", opponent, gf, ga, shots)
    }

    fun recentMatches(limit: Int): List<MatchRow> {
        val arr = JSONArray(a().callAttr("recent_matches", limit).toString())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MatchRow(o.optString("opponent"), o.optInt("goals_for"), o.optInt("goals_against"))
        }
    }

    fun summary(): Summary {
        val o = JSONObject(a().callAttr("summary").toString())
        return Summary(o.optInt("n"), o.optInt("w"), o.optInt("d"), o.optInt("l"),
            o.optInt("gf"), o.optInt("ga"))
    }

    fun formations(): List<Pair<String, String>> {
        val o = JSONObject(a().callAttr("formations").toString())
        return o.keys().asSequence().map { it to o.getString(it) }.toList()
    }
}
