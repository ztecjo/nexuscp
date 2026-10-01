package com.ztec.cplay.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Answers the one question the 45 logs could not: after the phone joins the hotspot group,
 * did it ever get an IP, and is its mDNS stack alive on this network at all?
 *
 * Run 45 showed the phone joining (group clients 0->1), the re-announce going out, and then
 * TWENTY SECONDS of total mDNS silence from the phone — no queries, no announcements, nothing
 * on either family. Two very different causes fit that: the phone never completed DHCP (no IP,
 * nothing it sends can work), or it has an IP but never enters wireless-CarPlay mode (its mDNS
 * stack is deliberately quiet). They need opposite fixes, and the log had no way to tell them
 * apart. This probe closes that gap in three steps, all report-safe and passive to the phone:
 *
 * 1. Watch /proc/net/arp for an entry on the hotspot interface — proves DHCP completed and
 *    gives the phone's address.
 * 2. TCP-connect to the phone on port 5353. The connection being REFUSED still proves L3
 *    reachability in both directions (a RST is an answer); a timeout means filtered/dead.
 * 3. Send a legacy UNICAST mDNS query (`_airplay._tcp.local` PTR) from an EPHEMERAL port to
 *    the phone's 5353. A live mDNSResponder answers unicast back to that ephemeral port, so
 *    no interaction with JmDNS's 5353 socket and no reportable identifiers. An answer proves
 *    the phone's mDNS is fully functional over this network — which then points the
 *    investigation at the CarPlay session trigger, not at networking.
 */
object PhoneProbe {
    private const val ARP_WATCH_MILLIS = 30_000L
    private const val ARP_POLL_MILLIS = 2_000L
    private const val TCP_PROBE_TIMEOUT_MILLIS = 2_000
    private const val MDNS_ANSWER_TIMEOUT_MILLIS = 3_000

    fun start(interfaceName: String?, log: (String) -> Unit) {
        if (interfaceName.isNullOrEmpty()) {
            log("wireless phone probe unavailable: no interface name")
            return
        }
        Thread({ runCatching { probe(interfaceName, log) } }, "nexuscp-phone-probe")
            .apply { isDaemon = true }
            .start()
    }

    private fun probe(interfaceName: String, log: (String) -> Unit) {
        // 1. DHCP completeness, via the kernel ARP table (readable on this Android version).
        val deadline = System.currentTimeMillis() + ARP_WATCH_MILLIS
        var phone: InetAddress? = null
        while (phone == null && System.currentTimeMillis() < deadline) {
            phone = arpEntryOn(interfaceName)
            if (phone == null) Thread.sleep(ARP_POLL_MILLIS)
        }
        if (phone == null) {
            log(
                "wireless phone probe arp=none in ${ARP_WATCH_MILLIS / 1000}s " +
                    "-> phone associated but has no IP (DHCP incomplete), all subsequent silence originates from this",
            )
            return
        }
        log("wireless phone probe arp=found phone=$phone (DHCP complete)")

        // 2. L3 reachability. Connection refused = the phone's IP stack answered with a RST —
        //    that is a SUCCESS for this check.
        val tcpOutcome = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(phone, 5353), TCP_PROBE_TIMEOUT_MILLIS)
            }
        }
        log(
            "wireless phone probe tcp5353=" + when {
                tcpOutcome.isSuccess -> "connected"
                else -> {
                    val reason = tcpOutcome.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                    // ConnectionReset/ConnectException on a closed port = phone reachable.
                    if (reason == "ConnectException") "refused (phone reachable, port not open, expected)"
                    else "$reason (timeout = path unreachable or filtered)"
                }
            },
        )

        // 3. Is the phone's mDNS stack alive? Legacy unicast query from an ephemeral port.
        val asked = System.currentTimeMillis()
        val answered = runCatching { legacyQuery(phone) }.getOrDefault(false)
        log(
            if (answered) {
                "wireless phone probe mdns=answered in ${System.currentTimeMillis() - asked}ms " +
                    "-> phone mDNS stack alive and unicast bidirectional, issue is not at network layer"
            } else {
                "wireless phone probe mdns=no-answer in ${MDNS_ANSWER_TIMEOUT_MILLIS}ms " +
                    "-> phone mDNS stack silent on this network (not in CarPlay wireless mode)"
            },
        )
    }

    /** First resolved, non-incomplete ARP entry whose device matches [interfaceName]. */
    private fun arpEntryOn(interfaceName: String): InetAddress? {
        runCatching {
            java.io.File("/proc/net/arp").useLines { lines ->
                for (line in lines.drop(1)) { // first line is the header
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size < 6) continue
                    // List destructuring only goes up to component5() — use indexes.
                    val ip = columns[0]
                    val flags = columns[2]
                    val device = columns[5]
                    if (device == interfaceName && flags != "0x0") {
                        return runCatching { InetAddress.getByName(ip) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /** 12-byte DNS header + `_airplay._tcp.local` PTR question, sent unicast. */
    private fun legacyQuery(phone: InetAddress): Boolean {
        val query = byteArrayOf(
            // Header: id=0x1234, flags=0 (standard query), QDCOUNT=1.
            0x12, 0x34, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // QNAME: 8 "_airplay", 4 "_tcp", 5 "local", root.
            0x08, 0x5F, 0x61, 0x69, 0x72, 0x70, 0x6C, 0x61, 0x79,
            0x04, 0x5F, 0x74, 0x63, 0x70,
            0x05, 0x6C, 0x6F, 0x63, 0x61, 0x6C,
            0x00,
            // QTYPE=PTR(12), QCLASS=IN(1).
            0x00, 0x0C, 0x00, 0x01,
        )
        DatagramSocket().use { socket ->
            socket.soTimeout = MDNS_ANSWER_TIMEOUT_MILLIS
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(phone, 5353)))
            val buffer = ByteArray(1500)
            socket.receive(DatagramPacket(buffer, buffer.size))
            return true
        }
    }
}
