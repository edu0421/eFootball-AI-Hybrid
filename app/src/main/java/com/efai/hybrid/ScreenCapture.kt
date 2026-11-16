package com.efai.hybrid

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.Surface

/**
 * Owns the MediaProjection, VirtualDisplay and ImageReader.
 * Frames are captured directly at reduced resolution and delivered (throttled) on [handler]'s thread.
 * Call [start] only after the foreground service is running with type mediaProjection.
 */
class ScreenCapture(
    private val ctx: Context,
    private val handler: Handler,
    private val targetWidth: Int,
    private val onFrame: (rgba: ByteArray, w: Int, h: Int) -> Unit,
    private val onScreenSize: (screenW: Int, screenH: Int) -> Unit,
    private val onStopped: () -> Unit
) {
    @Volatile var intervalMs: Long = 1000L
    @Volatile var active: Boolean = true
    @Volatile var screenW = 0
        private set
    @Volatile var screenH = 0
        private set

    private val dm = ctx.getSystemService(DisplayManager::class.java)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var lastFrameAt = 0L
    private var released = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) rebuildIfSizeChanged()
        }
    }

    fun start(resultCode: Int, data: Intent) {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data)
        projection = p
        // Android 14+: a callback MUST be registered before creating the VirtualDisplay.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                onStopped()
            }
        }, handler)
        computeSize()
        val r = newReader()
        reader = r
        display = p.createVirtualDisplay(
            "efai-capture", capW, capH, ctx.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        dm.registerDisplayListener(displayListener, handler)
        onScreenSize(screenW, screenH)
    }

    private fun computeSize() {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        var w = mode.physicalWidth
        var h = mode.physicalHeight
        if (d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270) {
            val t = w; w = h; h = t
        }
        screenW = w
        screenH = h
        val scale = if (w > targetWidth) targetWidth.toFloat() / w else 1f
        capW = (w * scale).toInt().coerceAtLeast(64)
        capH = (h * scale).toInt().coerceAtLeast(64)
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> handleImage(rd) }, handler)
        return r
    }

    private fun rebuildIfSizeChanged() {
        if (released) return
        val oldW = capW; val oldH = capH
        computeSize()
        if (capW == oldW && capH == oldH) return
        val oldReader = reader
        val newReader = newReader()
        reader = newReader
        display?.resize(capW, capH, ctx.resources.configuration.densityDpi)
        display?.surface = newReader.surface
        oldReader?.close()
        onScreenSize(screenW, screenH)
    }

    private fun handleImage(rd: ImageReader) {
        val img: Image = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!active || now - lastFrameAt < intervalMs) return
            lastFrameAt = now
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val out = ByteArray(w * h * 4)
            if (rowStride == w * 4) {
                buf.get(out, 0, out.size)
            } else {   // row padding: copy row by row
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(out, row * w * 4, w * 4)
                }
            }
            onFrame(out, w, h)
        } catch (e: Exception) {
            // drop the frame; next one will retry
        } finally {
            img.close()
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
    }
}
