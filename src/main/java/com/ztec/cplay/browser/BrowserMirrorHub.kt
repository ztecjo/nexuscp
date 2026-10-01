package com.ztec.cplay.browser

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.ztec.cplay.LogCarDiagnose
import com.ztec.cplay.airplay.AirPlayContact
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN browser mirror for CarPlay video + touch.
 * Serves a small static page and one WebSocket viewer on [PORT].
 */
object BrowserMirrorHub {
    const val PORT = 8765

    @Volatile
    var pin: String = "0000"
        private set

    @Volatile
    var frameWidth: Int = 1280
        private set

    @Volatile
    var frameHeight: Int = 720
        private set

    @Volatile
    var live: Boolean = false

    @Volatile
    var phase: String = "idle"

    private val touchSink = AtomicReference<((List<AirPlayContact>) -> Unit)?>(null)
    private var server: MirrorHttpServer? = null

    @Volatile
    var hasViewer: Boolean = false
        private set

    fun start(context: Context, accessPin: String) {
        stop()
        pin = accessPin.filter { it.isDigit() }.padStart(4, '0').takeLast(4)
        val assets = context.applicationContext.assets
        server = MirrorHttpServer(
            port = PORT,
            pin = pin,
            assetReader = { path -> assets.open("browser/$path").use { it.readBytes() } },
            stateProvider = {
                MirrorState(frameWidth, frameHeight, live, phase, hasViewer)
            },
            onViewerChanged = { connected ->
                hasViewer = connected
                if (!connected) touchSink.get()?.invoke(emptyList())
                Log.d(LogCarDiagnose.TAG, "browser mirror viewer=${if (connected) "on" else "off"}")
            },
            onPointer = { points ->
                val contacts = points.map {
                    AirPlayContact(
                        id = it.slot.coerceIn(0, 1),
                        x = it.nx.coerceIn(0.0, 1.0),
                        y = it.ny.coerceIn(0.0, 1.0),
                        down = it.pressed,
                    )
                }
                touchSink.get()?.invoke(contacts)
            },
        ).also { it.start() }
        Log.d(LogCarDiagnose.TAG, "browser mirror listening :$PORT pin=$pin")
    }

    fun stop() {
        server?.close()
        server = null
        hasViewer = false
        touchSink.get()?.invoke(emptyList())
    }

    fun setTouchSink(handler: ((List<AirPlayContact>) -> Unit)?) {
        touchSink.set(handler)
    }

    fun updateGeometry(width: Int, height: Int) {
        if (width > 0) frameWidth = width
        if (height > 0) frameHeight = height
    }

    fun publishJpeg(jpeg: ByteArray) {
        server?.offerFrame(jpeg)
    }

    fun lanUrls(context: Context): List<String> {
        val ips = linkedSetOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.forEach { network ->
                cm.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                    val addr = link.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips += addr.hostAddress ?: return@forEach
                    }
                }
            }
        }
        if (ips.isEmpty()) {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.inetAddresses.toList().forEach { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            ips += addr.hostAddress ?: return@forEach
                        }
                    }
                }
            }
        }
        return ips.map { "http://$it:$PORT/?pin=$pin" }
    }
}

data class MirrorState(
    val width: Int,
    val height: Int,
    val live: Boolean,
    val phase: String,
    val occupied: Boolean,
)

data class MirrorPointer(
    val slot: Int,
    val nx: Double,
    val ny: Double,
    val pressed: Boolean,
)
