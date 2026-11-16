package com.efai.hybrid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-configurable settings, shared by the activity, service and Python settings JSON. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("efai_prefs", Context.MODE_PRIVATE)

    var intervalMs: Long
        get() = sp.getLong("interval_ms", 1000L)
        set(v) = sp.edit().putLong("interval_ms", v).apply()

    var attackRight: Boolean
        get() = sp.getBoolean("attack_right", true)
        set(v) = sp.edit().putBoolean("attack_right", v).apply()

    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()

    /** Width (px) the screen is captured at. Smaller = less CPU/battery. */
    var captureWidth: Int
        get() = sp.getInt("capture_width", 640)
        set(v) = sp.edit().putInt("capture_width", v).apply()

    fun toPythonJson(ignoreRect: FloatArray?): String {
        val o = JSONObject()
        o.put("attack_direction", if (attackRight) "right" else "left")
        o.put("analysis_width", 480)
        val rects = JSONArray()
        if (ignoreRect != null) {
            rects.put(JSONArray().apply { ignoreRect.forEach { put(it.toDouble()) } })
        }
        o.put("ignore_rects", rects)
        return o.toString()
    }
}
