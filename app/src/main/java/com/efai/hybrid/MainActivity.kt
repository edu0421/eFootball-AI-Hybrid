package com.efai.hybrid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Single app shell: Assistant (setup/start), Matches, Tactics. */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var statusTv: TextView
    private lateinit var intervalBtn: android.widget.Button
    private lateinit var dirBtn: android.widget.Button
    private lateinit var themeBtn: android.widget.Button
    private lateinit var matches: MatchesView
    private lateinit var tactics: TacticsView
    private lateinit var assistantView: View
    private lateinit var content: FrameLayout
    private val intervals = longArrayOf(500, 1000, 2000, 3000)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, AssistantService::class.java)
                        .setAction(AssistantService.ACTION_START)
                        .putExtra(AssistantService.EXTRA_RESULT_CODE, res.resultCode)
                        .putExtra(AssistantService.EXTRA_DATA, data)
                )
                Toast.makeText(this, "Assistant started. Open eFootball.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
            }
            refresh()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this, prefs.dark)
        matches = MatchesView(this, ui)
        tactics = TacticsView(this, ui)
        assistantView = buildAssistant()
        content = FrameLayout(this)
        val nav = LinearLayout(this).apply { setBackgroundColor(ui.bg) }
        fun tab(label: String, show: () -> Unit) = ui.button(label) { show() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nav.addView(tab("Assistant") { show(assistantView) })
        nav.addView(tab("Matches") { show(matches.view); matches.refresh() })
        nav.addView(tab("Tactics") { show(tactics.view); tactics.refresh() })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav)
        }
        setContentView(root)
        show(assistantView)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun show(v: View) { content.removeAllViews(); content.addView(v) }

    private fun buildAssistant(): View {
        val col = ui.column()
        col.addView(ui.text("eFootball AI Hybrid", 24f, true))
        col.addView(ui.text("Floating assistant (AI Assist only). Reads your screen on-device and " +
            "shows suggestions. It never touches the game controls.", 13f, false, ui.muted))
        statusTv = ui.text("")
        col.addView(statusTv)
        col.addView(ui.button("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(ui.button("2. Allow notifications") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
        intervalBtn = ui.button("") {
            prefs.intervalMs = intervals[(intervals.indexOf(prefs.intervalMs) + 1).mod(intervals.size)]; refresh()
        }
        dirBtn = ui.button("") { prefs.attackRight = !prefs.attackRight; refresh() }
        themeBtn = ui.button("") { prefs.dark = !prefs.dark; recreate() }
        col.addView(intervalBtn); col.addView(dirBtn); col.addView(themeBtn)
        col.addView(ui.button("3. Start assistant") { onStartClicked() })
        col.addView(ui.button("Stop assistant") {
            startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
            refresh()
        })
        return ScrollView(this).apply { addView(col); setBackgroundColor(ui.bg) }
    }

    private fun hasNotif() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) { toast("Grant overlay permission first"); return }
        if (!hasNotif()) { toast("Allow notifications first (needed for the service notice)"); return }
        if (AssistantService.running) { toast("Already running"); return }
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun refresh() {
        val ok = "✔"; val no = "✘"
        statusTv.text = "Overlay: ${if (Settings.canDrawOverlays(this)) ok else no}   " +
            "Notifications: ${if (hasNotif()) ok else no}\nAssistant: ${if (AssistantService.running) "running" else "stopped"}"
        intervalBtn.text = "Analysis interval: ${prefs.intervalMs} ms (tap to change)"
        dirBtn.text = "You attack towards: ${if (prefs.attackRight) "RIGHT" else "LEFT"} (tap)"
        themeBtn.text = "Theme: ${if (prefs.dark) "Dark" else "Light"} (tap)"
    }
}
