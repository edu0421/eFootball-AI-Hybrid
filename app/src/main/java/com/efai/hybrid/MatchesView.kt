package com.efai.hybrid

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Log results and see history. Reads/writes via Python (SQLite) on the shared executor. */
class MatchesView(private val act: Activity, private val ui: Ui) {
    val view: View
    private val summaryTv = ui.text("", 15f, true, ui.accent)
    private val historyTv = ui.text("")
    private val msgTv = ui.text("", 13f, false, ui.muted)

    init {
        fun field(hint: String, number: Boolean) = EditText(act).apply {
            this.hint = hint; setTextColor(ui.fg); setHintTextColor(ui.muted)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        val opp = field("Opponent", false)
        val gf = field("Goals for", true)
        val ga = field("Goals against", true)
        val shots = field("Shots (optional)", true)
        val col = ui.column()
        col.addView(ui.text("Match log", 22f, true))
        col.addView(summaryTv)
        listOf(opp, gf, ga, shots).forEach { col.addView(it) }
        col.addView(ui.button("Save match") {
            val o = opp.text.toString().trim()
            val g1 = gf.text.toString().toIntOrNull()
            val g2 = ga.text.toString().toIntOrNull()
            if (o.isEmpty() || g1 == null || g2 == null) { msgTv.text = "Enter opponent and both scores."; return@button }
            val s = shots.text.toString().toIntOrNull() ?: -1
            PythonBridge.executor.execute {
                val err = try { PythonBridge.init(act); PythonBridge.addMatch(o, g1, g2, s); null }
                          catch (t: Throwable) { t.message ?: "error" }
                act.runOnUiThread {
                    msgTv.text = err?.let { "Could not save: $it" } ?: "Saved."
                    if (err == null) listOf(opp, gf, ga, shots).forEach { it.text.clear() }
                    refresh()
                }
            }
        })
        col.addView(msgTv)
        col.addView(historyTv)
        view = ScrollView(act).apply { addView(col); setBackgroundColor(ui.bg) }
    }

    fun refresh() {
        PythonBridge.executor.execute {
            try {
                PythonBridge.init(act)
                val s = PythonBridge.summary()
                val rows = PythonBridge.recentMatches(10)
                act.runOnUiThread {
                    summaryTv.text = "${s.n} matches · W${s.w} D${s.d} L${s.l} · goals ${s.gf}-${s.ga}"
                    historyTv.text = if (rows.isEmpty()) "No matches yet." else rows.joinToString("\n") {
                        val r = if (it.goalsFor > it.goalsAgainst) "W" else if (it.goalsFor == it.goalsAgainst) "D" else "L"
                        "$r  ${it.goalsFor}-${it.goalsAgainst}  vs ${it.opponent}"
                    }
                }
            } catch (t: Throwable) {
                act.runOnUiThread { msgTv.text = "Could not load: ${t.message}" }
            }
        }
    }
}
