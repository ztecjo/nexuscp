package com.ztec.cplay.network

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Passive mDNS observer for the wireless bring-up path.
 *
 * The 42 build proved both listener families accept connections (self-test reachable=true on
 * IPv4 and IPv6) and both Bonjour families publish — yet the phone never dials port 7000 and
 * JmDNS logs zero discovery events. What we cannot see today is whether the phone sends ANY
 * mDNS traffic: JmDNS only fires events for service records, not for received queries, so
 * "silence" is ambiguous between "the phone is not discovering" and "its queries never reach
 * us / our answers never reach it" (multicast on a P2P group owner is notoriously lossy).
 *
 * This sniffer joins the two mDNS multicast groups on the hotspot interface and logs a
 * report-safe summary of every packet: query vs response, question count, and the service
 * TYPE of the first question (instance labels can contain the owner's name, so everything
 * before the first underscore is stripped). It never sends, and any failure disables the
 * sniffer with a single log line rather than touching bring-up.
 */
object MdnsSniffer {
    private const val MDNS_PORT = 5353
    private const val RECEIVE_BUFFER_BYTES = 1536

    /** Log at most this many packets in full, then fall back to periodic counts. */
    private const val FULL_LOG_LIMIT = 60
    private const val COUNT_INTERVAL = 50

    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()
    private val sockets = mutableListOf<MulticastSocket>()

    fun start(interfaceName: String?, log: (String) -> Unit) {
        if (running) return
        val iface = interfaceName
            ?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
        if (iface == null || !iface.inetAddresses.hasMoreElements()) {
            log("wireless mdns sniff unavailable: iface=$interfaceName not usable")
            return
        }
        running = true
        val ipv4Group = runCatching { InetAddress.getByName("224.0.0.251") }.getOrNull()
        val ipv6Group = runCatching { InetAddress.getByName("ff02::fb") }.getOrNull()
        if (ipv4Group != null) spawn("mdns-sniff-4", ipv4Group, iface, log)
        if (ipv6Group != null) spawn("mdns-sniff-6", ipv6Group, iface, log)
        log(
            "wireless mdns sniff started iface=$interfaceName " +
                "groups=${buildList {
                    if (ipv4Group != null) add("IPv4")
                    if (ipv6Group != null) add("IPv6")
                }}",
        )
    }

    fun stop() {
        running = false
        // receive() does not react to thread interrupts — closing the sockets is what
        // actually unblocks the sniff loops.
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
        }
        synchronized(threads) {
            threads.forEach { it.join(500) }
            threads.clear()
        }
        synchronized(sockets) { sockets.clear() }
    }

    private fun spawn(name: String, group: InetAddress, iface: NetworkInterface, log: (String) -> Unit) {
        val thread = Thread({ runCatching { sniff(group, iface, log) } }, name).apply {
            isDaemon = true
            start()
        }
        synchronized(threads) { threads.add(thread) }
    }

    private fun sniff(group: InetAddress, iface: NetworkInterface, log: (String) -> Unit) {
        val socket = MulticastSocket(MDNS_PORT)
        socket.reuseAddress = true
        // A failed join must be visible: "sniff started, zero packets" is ambiguous between
        // "no traffic" and "never joined the group" otherwise.
        runCatching { socket.joinGroup(InetSocketAddress(group, MDNS_PORT), iface) }
            .onFailure {
                log(
                    "wireless mdns sniff join failed family=" +
                        "${if (group is Inet4Address) "IPv4" else "IPv6"} " +
                        "reason=${it.javaClass.simpleName}",
                )
                socket.close()
                return
            }
        synchronized(sockets) { sockets.add(socket) }
        val ownAddresses = java.util.Collections.list(iface.inetAddresses).toSet()
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        var seen = 0
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (running) continue else return
            }
            seen += 1
            val source = packet.address
            val ours = source in ownAddresses
            if (seen <= FULL_LOG_LIMIT) {
                val summary = describe(buffer, packet.length)
                log(
                    "wireless mdns sniff family=${if (group is Inet4Address) "IPv4" else "IPv6"} " +
                        "from=${if (ours) "ours" else "peer"} $summary",
                )
            } else if (seen % COUNT_INTERVAL == 0) {
                log(
                    "wireless mdns sniff family=${if (group is Inet4Address) "IPv4" else "IPv6"} " +
                        "packets=$seen (full log capped at $FULL_LOG_LIMIT)",
                )
            }
        }
        socket.close()
    }

    /** Minimal DNS header + first-question parse, collapsing the instance label. */
    private fun describe(bytes: ByteArray, length: Int): String {
        if (length < 12) return "malformed=short"
        val qr = if ((bytes[2].toInt() and 0x80) != 0) "response" else "query"
        val qdcount = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (qdcount == 0) return "qr=$qr qd=0"
        var offset = 12
        val name = StringBuilder()
        while (offset < length) {
            val labelLength = bytes[offset].toInt() and 0xFF
            if (labelLength == 0) {
                offset += 1
                break
            }
            if (offset + 1 + labelLength > length) return "qr=$qr malformed=name"
            if (name.isNotEmpty()) name.append('.')
            for (i in 1..labelLength) {
                val c = bytes[offset + i].toInt()
                if (c < ' '.code || c > '~'.code) return "qr=$qr opaque-name"
                name.append(c.toChar())
            }
            offset += 1 + labelLength
        }
        val raw = name.toString()
        // Strip the instance label: it frequently contains the owner's device name (personal
        // data). Everything from the first "_" onward is just the service type.
        val type = raw.indexOf('_').takeIf { it >= 0 }?.let { raw.substring(it) } ?: "<opaque>"
        return "qr=$qr qd=$qdcount qname=$type"
    }
}
