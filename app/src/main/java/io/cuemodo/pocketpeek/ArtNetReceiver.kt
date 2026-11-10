package io.cuemodo.pocketpeek

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Latest levels for one Art-Net universe (15-bit port address). Written by the rx thread only. */
class UniverseState(val universe: Int) {
    /** Lock on this array when reading or writing it. */
    val data = ByteArray(512)
    @Volatile var packets = 0L
    @Volatile var lastSeenMs = 0L
    @Volatile var length = 0
    @Volatile var source: InetAddress? = null
}

/**
 * Listens on UDP 6454 for ArtDmx (OpCode 0x5000) and keeps the last frame per universe.
 * Also answers ArtPoll (0x2000) with an ArtPollReply announcing one DMX output port on
 * [selectedUniverse], so consoles that only unicast to discovered nodes will send to us.
 */
class ArtNetReceiver {
    val universes = ConcurrentHashMap<Int, UniverseState>()
    @Volatile var selectedUniverse = 0
    @Volatile var lastError: String? = null
    @Volatile var polls = 0L

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "artnet-rx").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        socket?.close()  // unblocks receive()
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                socket = s
                lastError = null
                while (running) {
                    pkt.length = buf.size
                    s.receive(pkt)
                    handle(buf, pkt.length, pkt.address)
                }
            } catch (e: Exception) {
                if (!running) break
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "rx error", e)
                try { Thread.sleep(1000) } catch (_: InterruptedException) { }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    internal fun handle(buf: ByteArray, len: Int, from: InetAddress) {
        if (len < 10) return
        for (i in HEADER.indices) if (buf[i] != HEADER[i]) return
        when (u16le(buf, 8)) {
            OP_DMX -> handleDmx(buf, len, from)
            OP_POLL -> { polls++; sendPollReply(from) }
        }
    }

    private fun handleDmx(buf: ByteArray, len: Int, from: InetAddress) {
        if (len < 18) return
        // SubUni (byte 14) holds Sub-Net/Universe nibbles, Net (byte 15) the top 7 bits.
        val uni = ((buf[15].toInt() and 0x7f) shl 8) or (buf[14].toInt() and 0xff)
        val n = minOf(((buf[16].toInt() and 0xff) shl 8) or (buf[17].toInt() and 0xff), len - 18, 512)
        if (n <= 0) return
        val st = universes.getOrPut(uni) { UniverseState(uni) }  // single writer thread
        synchronized(st.data) { System.arraycopy(buf, 18, st.data, 0, n) }
        st.length = n
        st.source = from
        st.lastSeenMs = SystemClock.elapsedRealtime()
        st.packets++
    }

    /** Unsolicited reply, e.g. after the selected universe changed. Safe to call from the UI thread. */
    fun announce() {
        Thread({ sendPollReply(null) }, "artnet-announce").start()
    }

    private fun sendPollReply(target: InetAddress?) {
        val s = socket ?: return
        val (ip, bcast) = localIpv4() ?: return
        val uni = selectedUniverse
        val active = universes[uni]?.let { SystemClock.elapsedRealtime() - it.lastSeenMs < 3000 } == true
        val r = buildPollReply(ip, uni, active, polls)
        val dest = target ?: bcast ?: InetAddress.getByName("255.255.255.255")
        try {
            s.send(DatagramPacket(r, r.size, dest, PORT))
        } catch (e: Exception) {
            Log.w(TAG, "poll reply failed", e)
        }
    }

    companion object {
        const val PORT = 6454

        /** ArtPollReply announcing one DMX output port on universe [uni]. */
        internal fun buildPollReply(ip: Inet4Address, uni: Int, active: Boolean, polls: Long): ByteArray {
            val r = ByteArray(239)
            HEADER.copyInto(r)
            r[8] = 0x00; r[9] = 0x21                         // OpPollReply 0x2100, little endian
            ip.address.copyInto(r, 10)
            r[14] = 0x36; r[15] = 0x19                       // port 6454, little endian
            r[17] = 1                                        // firmware version
            r[18] = ((uni shr 8) and 0x7f).toByte()          // NetSwitch
            r[19] = ((uni shr 4) and 0x0f).toByte()          // SubSwitch
            r[20] = 0x00; r[21] = 0xff.toByte()              // OEM unknown
            r[23] = 0xd0.toByte()                            // Status1: indicators normal, front-panel addressing
            ascii("PocketPeek", r, 26, 18)
            ascii("PocketPeek DMX monitor", r, 44, 64)
            ascii("#0001 [${polls % 10000}] Monitoring universe $uni", r, 108, 64)
            r[173] = 1                                       // NumPorts
            r[174] = 0x80.toByte()                           // port 0: output, DMX512
            r[182] = if (active) 0x80.toByte() else 0        // GoodOutput: data being received
            r[190] = (uni and 0x0f).toByte()                 // SwOut port 0
            r[200] = 0x00                                    // StNode
            ip.address.copyInto(r, 207)                      // BindIp
            r[211] = 1                                       // BindIndex
            r[212] = 0x08                                    // Status2: 15-bit port address
            return r
        }
        private const val TAG = "PocketPeek"
        private const val OP_POLL = 0x2000
        private const val OP_DMX = 0x5000
        private val HEADER = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)

        private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

        private fun ascii(s: String, dst: ByteArray, off: Int, max: Int) {
            val b = s.toByteArray(Charsets.US_ASCII)
            b.copyInto(dst, off, 0, minOf(b.size, max - 1))  // keep a NUL terminator
        }

        /** First up, non-loopback IPv4 address (Wi-Fi preferred) and its broadcast address. */
        fun localIpv4(): Pair<Inet4Address, InetAddress?>? {
            val candidates = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (_: Exception) { return null }
            val sorted = candidates.filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (ni in sorted) for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address) return a to ia.broadcast
            }
            return null
        }
    }
}
