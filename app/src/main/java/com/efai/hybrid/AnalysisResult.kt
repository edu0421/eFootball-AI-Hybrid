package com.efai.hybrid

import org.json.JSONObject

data class AnalysisResult(
    val ok: Boolean,
    val situation: String,
    val confidence: Double,
    val passing: String,
    val attacking: String,
    val defensive: String,
    val players: Int,
    val ballFound: Boolean,
    val processingMs: Int,
    val notes: String,
    val error: String?,
    val recId: Long = -1
) {
    companion object {
        fun fromJson(json: String): AnalysisResult {
            val o = JSONObject(json)
            if (!o.optBoolean("ok", false)) {
                return AnalysisResult(false, "error", 0.0, "", "", "", 0, false,
                    o.optInt("processing_ms"), "", o.optString("error", "unknown error"))
            }
            val notes = o.optJSONArray("notes")?.let { a ->
                (0 until a.length()).joinToString(" ") { a.getString(it) }
            } ?: ""
            return AnalysisResult(
                true, o.optString("situation"), o.optDouble("confidence", 0.0),
                o.optString("passing"), o.optString("attacking"), o.optString("defensive"),
                o.optInt("players_detected"), !o.isNull("ball"), o.optInt("processing_ms"),
                notes, null, o.optLong("rec_id", -1)
            )
        }
    }
}
