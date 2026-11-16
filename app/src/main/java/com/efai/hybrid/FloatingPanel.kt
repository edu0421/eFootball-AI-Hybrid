package com.efai.hybrid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draggable, collapsible overlay panel. All methods must be called on the main thread
 * (except [ignoreRect], which is a volatile snapshot safe to read from any thread).
 */
class FloatingPanel(
    private val ctx: Context,
    private val prefs: Prefs,
    private val listener: Listener
) {
    interface Listener {
        fun onModeToggled(manual: Boolean)
        fun onPauseToggled(paused: Boolean)
        fun onClose()
        fun onFeedback(recId: Long, helpful: Boolean)
    }

    private class Colors(val bg: Int, val card: Int, val text: Int, val muted: Int, val accent: Int)

    private val darkColors = Colors(0xF00B1410.toInt(), 0xFF14231C.toInt(), 0xFFEAF4EE.toInt(), 0xFF8FA79A.toInt(), 0xFF2ECC71.toInt())
    private val lightColors = Colors(0xF0F2F7F4.toInt(), 0xFFFFFFFF.toInt(), 0xFF13231B.toInt(), 0xFF5E7468.toInt(), 0xFF1E9E57.toInt())

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val params = WindowManager.LayoutParams(
        dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(60) }

    private val root = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val body = LinearLayout(ctx)
    private val dot = View(ctx)
    private val title = TextView(ctx)
    private val modeBtn = chip("ASSIST")
    private val pauseBtn = chip("❚❚")
    private val minBtn = chip("–")
    private val themeBtn = chip("◐")
    private val closeBtn = chip("✕")
    private val statusTv = label(12f, false)
    private val situationTv = label(15f, true)
    private val confTv = label(12f, false)
    private val passTv = label(13f, false)
    private val attackTv = label(13f, false)
    private val defTv = label(13f, false)
    private val footTv = label(11f, false)
    private val chips = listOf(modeBtn, pauseBtn, minBtn, themeBtn, closeBtn)
    private val labels = listOf(statusTv, situationTv, confTv, passTv, attackTv, defTv, footTv)
    private val fbHelpful = chip("Helpful")
    private val fbNot = chip("Not helpful")
    private val fbRow = LinearLayout(ctx)
    private var lastRecId = -1L

    private var attached = false
    private var collapsed = false
    private var paused = false
    private var manual = false
    private var screenW = 0
    private var screenH = 0

    /** Normalised [x0,y0,x1,y1] of the panel on screen, so analysis can ignore our own pixels. */
    @Volatile var ignoreRect: FloatArray? = null
        private set

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(8), dp(10), dp(10))
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        dot.layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        title.text = "eFootball AI"
        title.textSize = 14f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(dot)
        header.addView(title)
        chips.forEach { header.addView(it) }
        body.orientation = LinearLayout.VERTICAL
        labels.forEach { body.addView(it) }
        fbRow.orientation = LinearLayout.HORIZONTAL
        fbRow.visibility = View.GONE
        fbRow.addView(fbHelpful); fbRow.addView(fbNot)
        body.addView(fbRow)
        root.addView(header)
        root.addView(body)

        modeBtn.setOnClickListener {
            manual = !manual
            refreshChips()
            listener.onModeToggled(manual)
        }
        pauseBtn.setOnClickListener {
            paused = !paused
            refreshChips()
            listener.onPauseToggled(paused)
        }
        minBtn.setOnClickListener {
            collapsed = !collapsed
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
            minBtn.text = if (collapsed) "+" else "–"
            root.post { updateBounds() }
        }
        themeBtn.setOnClickListener {
            prefs.dark = !prefs.dark
            applyTheme()
        }
        closeBtn.setOnClickListener { listener.onClose() }
        fbHelpful.setOnClickListener { sendFeedback(true) }
        fbNot.setOnClickListener { sendFeedback(false) }
        installDrag(title)
        installDrag(dot)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        applyTheme()
        refreshChips()
        setStatus("Starting…", Color.parseColor("#F5B041"))
        showIdle()
    }

    private fun label(size: Float, bold: Boolean) = TextView(ctx).apply {
        textSize = size
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun chip(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        minWidth = dp(34)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(v: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (if (screenW > 0) screenW else 2000) - dp(60)
                    val maxY = (if (screenH > 0) screenH else 2000) - dp(60)
                    params.x = (startX + (e.rawX - touchX).toInt()).coerceIn(0, maxX)
                    params.y = (startY + (e.rawY - touchY).toInt()).coerceIn(0, maxY)
                    if (attached) wm.updateViewLayout(root, params)
                    updateBounds()
                    true
                }
                else -> true
            }
        }
    }

    fun show() {
        if (attached) return
        wm.addView(root, params)
        attached = true
    }

    fun remove() {
        if (!attached) return
        try { wm.removeView(root) } catch (_: Exception) {}
        attached = false
        ignoreRect = null
    }

    fun setScreenSize(w: Int, h: Int) {
        screenW = w; screenH = h
        updateBounds()
    }

    private fun updateBounds() {
        if (!attached || screenW <= 0 || screenH <= 0 || root.width == 0) return
        ignoreRect = floatArrayOf(
            (params.x.toFloat() / screenW).coerceIn(0f, 1f),
            (params.y.toFloat() / screenH).coerceIn(0f, 1f),
            ((params.x + root.width).toFloat() / screenW).coerceIn(0f, 1f),
            ((params.y + root.height).toFloat() / screenH).coerceIn(0f, 1f)
        )
    }

    fun setStatus(text: String, color: Int) {
        statusTv.text = text
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun sendFeedback(helpful: Boolean) {
        if (lastRecId >= 0) listener.onFeedback(lastRecId, helpful)
        lastRecId = -1
        fbRow.visibility = View.GONE
    }

    private fun showIdle() {
        fbRow.visibility = View.GONE
        situationTv.text = "Waiting for first frame…"
        confTv.text = ""; passTv.text = ""; attackTv.text = ""; defTv.text = ""; footTv.text = ""
    }

    fun showManual() {
        fbRow.visibility = View.GONE
        situationTv.text = "Manual mode"
        confTv.text = "Analysis is off. Switch to ASSIST to resume."
        passTv.text = ""; attackTv.text = ""; defTv.text = ""; footTv.text = ""
    }

    fun showResult(r: AnalysisResult) {
        if (manual || paused) return
        if (!r.ok) { showError(r.error ?: "analysis failed"); return }
        setStatus("Connected · Analysing", accent())
        situationTv.text = prettySituation(r.situation)
        confTv.text = "Confidence ${(r.confidence * 100).toInt()}% · players ${r.players} · ball ${if (r.ballFound) "seen" else "not seen"}"
        passTv.text = if (r.passing.isNotEmpty()) "Pass: ${r.passing}" else ""
        attackTv.text = if (r.attacking.isNotEmpty()) "Attack: ${r.attacking}" else ""
        defTv.text = if (r.defensive.isNotEmpty()) "Defend: ${r.defensive}" else ""
        footTv.text = "${r.notes}  (${r.processingMs} ms)".trim()
        lastRecId = r.recId
        fbRow.visibility = if (r.recId >= 0) View.VISIBLE else View.GONE
    }

    fun showError(msg: String) {
        fbRow.visibility = View.GONE
        setStatus("Error", Color.parseColor("#E74C3C"))
        situationTv.text = "Analysis error"
        confTv.text = msg.take(160)
        passTv.text = ""; attackTv.text = ""; defTv.text = ""; footTv.text = ""
    }

    private fun prettySituation(s: String) = when (s) {
        "not_in_match" -> "No match view detected"
        "pitch_visible_ball_unknown" -> "Pitch visible · ball not found"
        "own_third" -> "Ball in your defensive third"
        "midfield" -> "Ball in midfield"
        "final_third" -> "Ball in attacking third"
        else -> s
    }

    private fun accent() = if (prefs.dark) darkColors.accent else lightColors.accent

    private fun refreshChips() {
        modeBtn.text = if (manual) "MANUAL" else "ASSIST"
        pauseBtn.text = if (paused) "▶" else "❚❚"
    }

    private fun applyTheme() {
        val c = if (prefs.dark) darkColors else lightColors
        root.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(c.bg)
            setStroke(dp(1), c.accent)
        }
        title.setTextColor(c.text)
        labels.forEach { it.setTextColor(c.text) }
        statusTv.setTextColor(c.muted); confTv.setTextColor(c.muted); footTv.setTextColor(c.muted)
        (chips + listOf(fbHelpful, fbNot)).forEach {
            it.setTextColor(c.text)
            it.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c.card) }
        }
        modeBtn.setTextColor(c.accent)
        // statusTv sits in the body so connection state is visible when expanded
        if (statusTv.parent == null) body.addView(statusTv, 0)
    }
}
