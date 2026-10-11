package com.ztec.cplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayNightModeMonitorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { NightModeSettings.preferences(context).edit().clear().commit() }
    @Test fun changesApplyWithoutAnActivityAndStoppingRemovesCallbacks() {
        val sent = mutableListOf<Boolean>()
        NightModeSettings.saveMode(context, CarPlayNightMode.DAY)
        val monitor = CarPlayNightModeMonitor(context) { sent.add(it) }
        try {
            monitor.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false), sent)
            NightModeSettings.saveMode(context, CarPlayNightMode.NIGHT)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false, true), sent)
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
            assertEquals(listOf(false, true), sent)
            monitor.stop()
            shadowOf(Looper.getMainLooper()).idle()
            NightModeSettings.saveMode(context, CarPlayNightMode.DAY)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
            assertEquals(listOf(false, true), sent)
        } finally { monitor.stop(); shadowOf(Looper.getMainLooper()).idle() }
    }
    @Test fun aNewSessionReceivesTheCurrentModeAgain() {
        val sent = mutableListOf<Boolean>()
        val monitor = CarPlayNightModeMonitor(context) { sent.add(it) }
        try {
            NightModeSettings.saveMode(context, CarPlayNightMode.NIGHT)
            monitor.start(); shadowOf(Looper.getMainLooper()).idle()
            monitor.start(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, true), sent)
            monitor.close(); shadowOf(Looper.getMainLooper()).idle()
            monitor.start(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, true), sent)
        } finally { monitor.stop(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
