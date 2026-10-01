package com.ztec.cplay.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Captures CarPlay frames from the host TextureView for LAN JPEG streaming. */
class BrowserFrameCapture(
    private val onJpeg: (ByteArray) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nexus-mirror-capture").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var sourceView: View? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            captureOnce()
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun attach(view: View?) {
        sourceView = view
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        mainHandler.post(tick)
    }

    fun stop() {
        running.set(false)
        mainHandler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        worker.shutdownNow()
        sourceView = null
    }

    private fun captureOnce() {
        if (!BrowserMirrorHub.hasViewer) return
        val view = sourceView as? TextureView ?: return
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return
        if (!busy.compareAndSet(false, true)) return
        val bitmap = runCatching { view.getBitmap(view.width, view.height) }.getOrNull()
        if (bitmap == null) {
            busy.set(false)
            return
        }
        worker.execute {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                onJpeg(out.toByteArray())
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 66L
        private const val JPEG_QUALITY = 70
    }
}
