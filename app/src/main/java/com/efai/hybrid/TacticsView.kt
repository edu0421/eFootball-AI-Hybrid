package com.efai.hybrid

import android.app.Activity
import android.view.View
import android.widget.ScrollView

/** Formation reference from Python. Personalised recommendations are a later stage. */
class TacticsView(private val act: Activity, private val ui: Ui) {
    private val col = ui.column()
    val view: View = ScrollView(act).apply { addView(col); setBackgroundColor(ui.bg) }
    private var loaded = false

    init {
        col.addView(ui.text("Formations", 22f, true))
        col.addView(ui.text("Reference guide only.", 13f, false, ui.muted))
    }

    fun refresh() {
        if (loaded) return
        PythonBridge.executor.execute {
            try {
                PythonBridge.init(act)
                val f = PythonBridge.formations()
                act.runOnUiThread {
                    loaded = true
                    f.forEach { (n, d) -> col.addView(ui.text(n, 17f, true, ui.accent)); col.addView(ui.text(d)) }
                }
            } catch (t: Throwable) {
                act.runOnUiThread { col.addView(ui.text("Could not load: ${t.message}", 13f, false, ui.muted)) }
            }
        }
    }
}
