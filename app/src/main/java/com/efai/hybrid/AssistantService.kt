package com.efai.hybrid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type mediaProjection):
 *   capture thread -> ScreenCapture -> PythonBridge.executor -> main thread (FloatingPanel).
 */
class AssistantService : Service(), FloatingPanel.Listener {

    companion object {
        const val ACTION_START = "com.efai.hybrid.START"
        const val ACTION_STOP = "com.efai.hybrid.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "efai_assistant"
        private const val NOTIF_ID = 1001
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var capture: ScreenCapture? = null
    private var panel: FloatingPanel? = null
    private lateinit var prefs: Prefs
    private val busy = AtomicBoolean(false)
    @Volatile private var pythonReady = false
    @Volatile private var paused = false
    @Volatile private var manual = false
    @Volatile private var stopped = false
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> start(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY   // a projection token cannot be reused after a restart
    }

    private fun start(intent: Intent) {
        // Must run promptly after startForegroundService() and BEFORE getMediaProjection().
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (started) return
        started = true
        running = true
        prefs = Prefs(this)

        if (!Settings.canDrawOverlays(this)) { toast("Overlay permission missing"); shutdown(); return }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) { toast("Screen-capture permission data missing"); shutdown(); return }

        val thread = HandlerThread("efai-capture").also { it.start() }
        captureThread = thread

        val p = FloatingPanel(this, prefs, this)
        panel = p
        p.show()

        PythonBridge.executor.execute {     // first start can take seconds: keep off main thread
            try {
                PythonBridge.init(this)
                pythonReady = true
                main.post { if (!stopped) updateStatus() }
            } catch (t: Throwable) {
                main.post { if (!stopped) panel?.showError("Python failed to start: ${t.message}") }
            }
        }

        try {
            val cap = ScreenCapture(
                this, Handler(thread.looper), prefs.captureWidth,
                onFrame = { rgba, w, h -> onFrame(rgba, w, h) },
                onScreenSize = { sw, sh -> main.post { panel?.setScreenSize(sw, sh) } },
                onStopped = { main.post { shutdown() } }   // user ended sharing from system UI
            )
            capture = cap
            cap.intervalMs = prefs.intervalMs
            cap.start(resultCode, data)
        } catch (t: Throwable) {
            panel?.showError("Capture failed: ${t.message}")
            main.postDelayed({ shutdown() }, 4000)
        }
    }

    /** Capture thread. Drops the frame if analysis is still running. */
    private fun onFrame(rgba: ByteArray, w: Int, h: Int) {
        if (stopped || !pythonReady || !busy.compareAndSet(false, true)) return
        capture?.intervalMs = prefs.intervalMs
        val settings = prefs.toPythonJson(panel?.ignoreRect)
        try {
            PythonBridge.executor.execute {
                try {
                    val result = PythonBridge.analyze(rgba, w, h, settings)
                    main.post { if (!stopped) panel?.showResult(result) }
                } catch (t: Throwable) {
                    main.post { if (!stopped) panel?.showError(t.message ?: t.javaClass.simpleName) }
                } finally {
                    busy.set(false)
                }
            }
        } catch (_: Exception) {
            busy.set(false)
        }
    }

    // ---- FloatingPanel.Listener (main thread) ----
    override fun onModeToggled(manual: Boolean) { this.manual = manual; updateStatus() }
    override fun onPauseToggled(paused: Boolean) { this.paused = paused; updateStatus() }
    override fun onClose() = shutdown()
    override fun onFeedback(recId: Long, helpful: Boolean) {
        PythonBridge.executor.execute {
            try { PythonBridge.setFeedback(recId, helpful) } catch (_: Throwable) {}
        }
    }

    private fun updateStatus() {
        capture?.active = !paused && !manual
        val p = panel ?: return
        when {
            manual -> { p.setStatus("Manual · analysis off", Color.parseColor("#F5B041")); p.showManual() }
            paused -> p.setStatus("Paused", Color.parseColor("#F5B041"))
            !pythonReady -> p.setStatus("Starting Python…", Color.parseColor("#F5B041"))
            else -> p.setStatus("Connected · Analysing", Color.parseColor("#2ECC71"))
        }
    }

    // ---- lifecycle / cleanup (idempotent) ----
    private fun shutdown() {
        if (stopped) return
        stopped = true
        running = false
        panel?.remove(); panel = null
        capture?.release(); capture = null
        captureThread?.quitSafely(); captureThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("eFootball AI assistant")
            .setContentText("Screen is being analysed on-device")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
