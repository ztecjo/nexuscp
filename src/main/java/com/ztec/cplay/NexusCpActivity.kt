// SPDX-License-Identifier: GPL-3.0-only
package com.ztec.cplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ztec.cplay.R
import com.ztec.cplay.orchestration.WirelessHotspotMode

/** Main activity: Nexus CP connection home and settings. */
class NexusCpActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices_permission_title), getString(R.string.nearby_devices_permission_message))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { OfflineMfiBootstrap.ensure(this) }.exceptionOrNull()?.let {
            getString(R.string.local_init_failed)
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(tick); handler.post(tick)
        if (!initialLaunch && page == "home") render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                NexusCpPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(28), dp(20), dp(28), dp(28)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        header.addView(label(getString(R.string.app_name), 22, TEXT, true).apply {
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (page == "home") {
            header.addView(button(getString(R.string.settings), false) {
                page = "settings"; render()
            }, LinearLayout.LayoutParams(dp(120), dp(48)))
        } else {
            header.addView(button(getString(R.string.back_button), false) {
                page = "home"; render()
            }, LinearLayout.LayoutParams(dp(100), dp(48)))
        }
        content.addView(header)
        content.addView(hairline().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(8) }
        })
        when (page) {
            "settings" -> settings(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val body = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(space(48))
        status = label(getString(R.string.ready_anytime), 22, TEXT, true).apply {
            gravity = Gravity.CENTER
        }
        body.addView(status)
        body.addView(space(20))
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        body.addView(connectButton, matchButton(0, 60))
        body.addView(label(getString(R.string.pair_iphone_bluetooth_hint), 14, MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(8), dp(12), dp(8), 0)
        })
        if (carHotspotOff()) {
            body.addView(label(getString(R.string.car_hotspot_off_warning, AirPlayPersistence.loadManualHotspotSsid(this)), 14, WARNING).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), 0)
            })
            body.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 52))
        }
        body.addView(space(20))
        val actions = row().apply { gravity = Gravity.CENTER }
        actions.addView(button(getString(R.string.connect_usb), false) { connect(false) },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(space(10).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        actions.addView(button(getString(R.string.select_iphone), false) { choosePhone() },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        body.addView(disconnectButton, matchButton(12, 52))
        setupError?.let {
            body.addView(label(it, 15, WARNING).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }
        body.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        body.addView(label(getString(R.string.public_preview_version, version()), 12, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
        content.addView(body, LinearLayout.LayoutParams(-1, -1))
    }

    private fun settings(content: LinearLayout) {
        content.addView(label(getString(R.string.settings), 26, TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(label(getString(R.string.settings_change_hint), 14, MUTED).apply { setPadding(0, dp(6), 0, dp(16)) })
        section(content, getString(R.string.auto_connect_section)) { panel ->
            toggle(panel, getString(R.string.connect_on_launch), getString(R.string.connect_on_launch_desc), NexusCpPreferences.autoConnect(this)) { NexusCpPreferences.saveAutoConnect(this, it) }
            toggle(panel, getString(R.string.auto_start_on_boot), getString(R.string.auto_start_on_boot_desc), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            panel.addView(button(getString(R.string.select_iphone_button, NexusCpPreferences.phoneName(this)), false) { choosePhone() }, matchButton(12, 56))
        }
        section(content, getString(R.string.wireless_section)) { panel -> wirelessLinkControls(panel) }
        section(content, getString(R.string.display_performance_section)) { panel ->
            carPlaySizeControl(panel)
            choice(panel, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.resolution_80_lighter), getString(R.string.resolution_60_lightest)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.ztec.cplay.media.MediaAudioBuffer.presets
            choice(panel, "Music Buffer", listOf(getString(R.string.buffer_300ms_default), getString(R.string.buffer_500ms), getString(R.string.buffer_1000ms_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(panel, getString(R.string.frame_rate), listOf(getString(R.string.fps_30_lighter), getString(R.string.fps_60_smoother)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(panel, getString(R.string.efficient_video), getString(R.string.efficient_video_desc), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(panel, getString(R.string.right_hand_drive), getString(R.string.right_hand_drive_desc), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(panel, getString(R.string.fullscreen), getString(R.string.fullscreen_desc), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        section(content, getString(R.string.permissions_section)) { panel ->
            panel.addView(label(getString(R.string.permissions_hint), 15, MUTED))
            panel.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 56))
            panel.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 56))
            panel.addView(button(getString(R.string.wireless_help), false) { wirelessHelp() }, matchButton(10, 56))
        }
        section(content, getString(R.string.browser_mirror_section)) { panel ->
            val pin = AirPlayPersistence.ensureBrowserMirrorPin(this)
            toggle(
                panel,
                getString(R.string.browser_mirror_enable),
                getString(R.string.browser_mirror_enable_desc),
                AirPlayPersistence.loadBrowserMirrorEnabled(this),
            ) { enabled ->
                AirPlayPersistence.saveBrowserMirrorEnabled(this, enabled)
                if (enabled) {
                    com.ztec.cplay.browser.BrowserMirrorHub.start(this, AirPlayPersistence.ensureBrowserMirrorPin(this))
                } else {
                    com.ztec.cplay.browser.BrowserMirrorHub.stop()
                }
                render()
            }
            panel.addView(label(getString(R.string.browser_mirror_pin_label, pin), 16, TEXT, true).apply {
                setPadding(0, dp(12), 0, dp(8))
            })
            panel.addView(button(getString(R.string.browser_mirror_regenerate_pin), false) {
                AirPlayPersistence.regenerateBrowserMirrorPin(this)
                if (AirPlayPersistence.loadBrowserMirrorEnabled(this)) {
                    com.ztec.cplay.browser.BrowserMirrorHub.start(this, AirPlayPersistence.ensureBrowserMirrorPin(this))
                }
                render()
            }, matchButton(0, 52))
            panel.addView(label(getString(R.string.browser_mirror_urls_hint), 14, MUTED).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            val urls = com.ztec.cplay.browser.BrowserMirrorHub.lanUrls(this)
            if (urls.isEmpty()) {
                panel.addView(label(getString(R.string.browser_mirror_no_ip), 14, WARNING))
            } else {
                urls.take(4).forEach { url ->
                    panel.addView(label(url, 13, ACCENT).apply { setPadding(0, dp(4), 0, 0) })
                }
            }
            panel.addView(label(getString(R.string.browser_mirror_port_note, com.ztec.cplay.browser.BrowserMirrorHub.PORT), 12, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    // The car hotspot link needs the hotspot on; Nexus CP only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.ztec.cplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_off_title))
            .setMessage(getString(R.string.car_hotspot_off_message, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(hotspot, 0) != null &&
            runCatching { startActivity(hotspot) }.isSuccess
        ) {
            return
        }
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val carHotspot = mode == WirelessHotspotMode.MANUAL || mode == WirelessHotspotMode.EXTERNAL_WIFI
        val options = arrayOf(getString(R.string.wifi_direct_default), getString(R.string.car_hotspot), getString(R.string.external_wifi_same_network))
        val currentIndex = when (mode) {
            WirelessHotspotMode.MANUAL -> 1
            WirelessHotspotMode.EXTERNAL_WIFI -> 2
            else -> 0
        }
        val control = button(getString(R.string.wireless_method_button, options[currentIndex]), false) {}
        control.setOnClickListener {
            var selection = currentIndex
            AlertDialog.Builder(this).setTitle(getString(R.string.wireless_method))
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_reconnect) else getString(R.string.save)) { _, _ ->
                    val target = when (selection) {
                        0 -> WirelessHotspotMode.WIFI_P2P
                        1 -> WirelessHotspotMode.MANUAL
                        else -> WirelessHotspotMode.EXTERNAL_WIFI
                    }
                    when {
                        target == mode -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null ->
                            applyWirelessLink(target)
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(target)
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(control, matchButton(0, 56)); parent.addView(space(12))
        if (!carHotspot) {
            parent.addView(label(getString(R.string.wifi_direct_hint), 13, MUTED).apply {
                setPadding(0, 0, 0, dp(14))
            })
            return
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(button(getString(R.string.hotspot_name_button, ssid), false) {
            textInput(getString(R.string.car_hotspot_name_title), ssid, secret = false) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0, 56))
        parent.addView(space(12))
        parent.addView(button(getString(R.string.hotspot_password_button, if (password.isEmpty()) getString(R.string.none) else "•".repeat(8)), false) {
            textInput(getString(R.string.car_hotspot_password_title), password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0, 56))
        if (mode == WirelessHotspotMode.EXTERNAL_WIFI) {
            parent.addView(label(getString(R.string.external_wifi_hint), 13, MUTED).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
            return
        }
        parent.addView(space(12))
        val channel = AirPlayPersistence.loadManualHotspotChannel(this)
        parent.addView(button(
            getString(R.string.hotspot_channel_button, if (channel == 0) getString(R.string.hotspot_channel_unset) else channel.toString()),
            false,
        ) {
            textInput(getString(R.string.hotspot_channel_title), channel.toString(), secret = false) { value ->
                val parsed = value.trim().toIntOrNull()
                if (parsed == null || parsed !in 0..196) {
                    toast(getString(R.string.channel_range_error))
                    return@textInput
                }
                AirPlayPersistence.saveManualHotspotChannel(this, parsed)
                AirPlayPersistence.saveManualHotspotBand(
                    this,
                    when {
                        parsed == 0 -> com.ztec.cplay.orchestration.ManualHotspotBand.AUTO
                        parsed <= 13 -> com.ztec.cplay.orchestration.ManualHotspotBand.GHZ_2_4
                        else -> com.ztec.cplay.orchestration.ManualHotspotBand.GHZ_5
                    },
                )
                render()
            }
        }, matchButton(0, 56))
        parent.addView(label(getString(R.string.car_hotspot_hint), 13, MUTED).apply {
            setPadding(0, dp(8), 0, dp(14))
        })
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.ztec.cplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.ztec.cplay.orchestration.ManualHotspotValidation.securityFor(password))
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput(getString(R.string.car_hotspot_name_title), storedSsid(), secret = false) { ssid ->
            textInput(getString(R.string.car_hotspot_password_title), storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.ztec.cplay.airplay.CarPlaySize.entries
        val current = com.ztec.cplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size_label), sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.carplay_size_hint), 13, MUTED).apply {
            setPadding(0, 0, 0, dp(14))
        })
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && NexusCpPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("Nexus CP", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth_title))
                .setMessage(getString(R.string.turn_on_bluetooth_message))
                .setPositiveButton(getString(R.string.turn_on_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_iphone_title))
                .setMessage(getString(R.string.pair_iphone_message))
                .setPositiveButton(getString(R.string.turn_on_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.select_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                NexusCpPreferences.savePhone(this, device.address, device.name ?: getString(R.string.your_iphone))
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_help_title))
            .setMessage(getString(R.string.wireless_help_message))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wifi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wifi_title))
            .setMessage(getString(R.string.reset_carplay_wifi_message))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.wifi_direct_not_supported)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wifi_direct_busy))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.cannot_reset_wifi_direct)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.nearby_devices_permission_title), getString(R.string.wireless_permission_hint))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.init_needed)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_iphone)
            NexusCpPreferences.phoneAddress(this) != null -> getString(R.string.ready_phone, NexusCpPreferences.phoneName(this))
            else -> getString(R.string.ready_anytime)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_in_settings)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        parent.addView(hairline().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(14) }
        })
        parent.addView(label(title, 15, ACCENT, true).apply { setPadding(0, 0, 0, dp(10)) })
        val panel = panel()
        build(panel)
        parent.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        val text = column(); text.addView(label(title, 16, TEXT, true)); text.addView(label(description, 13, MUTED).apply { setPadding(0, dp(4), dp(12), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(48); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_reconnect) else getString(R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 56)); parent.addView(space(10))
    }
    private fun panel() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(16), dp(14), dp(16), dp(14)) }
    private fun hairline() = View(this).apply { setBackgroundColor(BORDER) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 16f; setTextColor(if (primary) ON_ACCENT else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x333ECF9A),
            rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER),
            null,
        )
        setPadding(dp(14), 0, dp(14), 0); minHeight = dp(48); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(10).toFloat(); setStroke(dp(1), stroke)
    }
    private fun matchButton(top: Int = 0, height: Int = 56) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(20, 23, 28)
        private val SURFACE = Color.rgb(28, 33, 40)
        private val BORDER = Color.rgb(44, 51, 61)
        private val ACCENT = Color.rgb(62, 207, 154)
        private val ON_ACCENT = Color.rgb(10, 22, 18)
        private val TEXT = Color.rgb(242, 244, 246)
        private val MUTED = Color.rgb(154, 163, 173)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
