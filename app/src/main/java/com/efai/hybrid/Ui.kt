package com.efai.hybrid

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small programmatic-UI helpers shared by the app screens. */
class Ui(val ctx: Context, dark: Boolean) {
    val bg = if (dark) 0xFF0B1410.toInt() else 0xFFF2F7F4.toInt()
    val fg = if (dark) 0xFFEAF4EE.toInt() else 0xFF13231B.toInt()
    val muted = if (dark) 0xFF8FA79A.toInt() else 0xFF5E7468.toInt()
    val accent = if (dark) 0xFF2ECC71.toInt() else 0xFF1E9E57.toInt()

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun column() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        setBackgroundColor(bg)
    }

    fun text(t: String, size: Float = 14f, bold: Boolean = false, color: Int = fg) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    fun button(t: String, onClick: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
}
