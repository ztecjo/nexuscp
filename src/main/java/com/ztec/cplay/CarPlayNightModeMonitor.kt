// SPDX-License-Identifier: GPL-3.0-only
package com.ztec.cplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Runs with the connection, including while the projection Activity is in the background. */
internal class CarPlayNightModeMonitor(context: Context, private val send: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = NightModeSettings.preferences(app)
    private var started = false
    private var closed = false
    private var lastNight: Boolean? = null
    private val tick = Runnable { refresh() }
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { if (started) refresh() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (started) refresh() }
    }

    fun start() { handler.post {
        if (closed) return@post
        if (!started) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") app.registerReceiver(receiver, filter) }
            prefs.registerOnSharedPreferenceChangeListener(changes)
            started = true
        }
        lastNight = null
        refresh()
    } }

    fun stop() { handler.post { stopNow() } }

    fun close() { handler.post { closed = true; stopNow() } }

    private fun stopNow() {
        handler.removeCallbacks(tick)
        if (started) {
            prefs.unregisterOnSharedPreferenceChangeListener(changes)
            app.unregisterReceiver(receiver)
            started = false
        }
        lastNight = null
    }

    private fun refresh() {
        handler.removeCallbacks(tick)
        if (!started) return
        val night = NightModeSettings.current(app)
        if (night != lastNight) { lastNight = night; send(night) }
        handler.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L)
    }
}
