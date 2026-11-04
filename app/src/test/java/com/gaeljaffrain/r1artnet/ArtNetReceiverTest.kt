package com.gaeljaffrain.r1artnet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

class ArtNetReceiverTest {
    private val src: InetAddress = InetAddress.getByName("192.168.1.110")

    /** ArtDmx packet as a console sends it. */
    private fun artDmx(net: Int, subUni: Int, data: ByteArray, declaredLength: Int = data.size): ByteArray {
        val p = ByteArray(18 + data.size)
        "Art-Net\u0000".toByteArray(Charsets.US_ASCII).copyInto(p)
        p[8] = 0x00; p[9] = 0x50                       // OpDmx 0x5000, little endian
        p[11] = 14                                     // protocol version
        p[14] = subUni.toByte()
        p[15] = net.toByte()
        p[16] = (declaredLength shr 8).toByte()        // length, big endian
        p[17] = declaredLength.toByte()
        data.copyInto(p, 18)
        return p
    }

    private fun levels(n: Int) = ByteArray(n) { (it + 1).toByte() }

    @Test fun storesDmxFrame() {
        val rx = ArtNetReceiver()
        val pkt = artDmx(0, 0, levels(512))
        rx.handle(pkt, pkt.size, src)

        val st = rx.universes[0]
        assertNotNull(st)
        assertEquals(512, st!!.length)
        assertEquals(1L, st.packets)
        assertEquals(src, st.source)
        assertArrayEquals(levels(512), st.data)
    }

    @Test fun decodesFifteenBitPortAddress() {
        val rx = ArtNetReceiver()
        val pkt = artDmx(net = 1, subUni = 0x02, data = levels(2))
        rx.handle(pkt, pkt.size, src)
        assertNotNull(rx.universes[258])
        assertEquals(setOf(258), rx.universes.keys)

        val top = artDmx(net = 0x7f, subUni = 0xff, data = levels(2))
        rx.handle(top, top.size, src)
        assertNotNull(rx.universes[Universe.MAX])
    }

    @Test fun shortFrameUpdatesOnlyItsChannels() {
        val rx = ArtNetReceiver()
        val full = artDmx(0, 0, ByteArray(512) { 9 })
        rx.handle(full, full.size, src)
        val short = artDmx(0, 0, levels(24))
        rx.handle(short, short.size, src)

        val st = rx.universes[0]!!
        assertEquals(24, st.length)
        assertEquals(2L, st.packets)
        assertArrayEquals(levels(24), st.data.copyOfRange(0, 24))
        assertEquals(9.toByte(), st.data[24])
    }

    @Test fun lengthIsClampedToPacketAndUniverse() {
        val rx = ArtNetReceiver()
        // Header claims 512 channels but only 10 arrived.
        val truncated = artDmx(0, 1, levels(10), declaredLength = 512)
        rx.handle(truncated, truncated.size, src)
        assertEquals(10, rx.universes[1]!!.length)

        // Header claims more than a universe holds.
        val oversized = artDmx(0, 2, ByteArray(600) { 1 }, declaredLength = 600)
        rx.handle(oversized, oversized.size, src)
        assertEquals(512, rx.universes[2]!!.length)
    }

    @Test fun ignoresInvalidPackets() {
        val rx = ArtNetReceiver()
        val good = artDmx(0, 0, levels(8))

        val badHeader = good.copyOf().also { it[0] = 'X'.code.toByte() }
        rx.handle(badHeader, badHeader.size, src)
        rx.handle(good, 9, src)                                  // too short for a header
        rx.handle(good, 17, src)                                 // too short for ArtDmx
        val zeroLength = artDmx(0, 0, ByteArray(0))
        rx.handle(zeroLength, zeroLength.size, src)
        val otherOp = good.copyOf().also { it[9] = 0x60 }        // OpAddress, not handled
        rx.handle(otherOp, otherOp.size, src)

        assertTrue(rx.universes.isEmpty())
    }

    @Test fun countsArtPoll() {
        val rx = ArtNetReceiver()
        val poll = ByteArray(14)
        "Art-Net\u0000".toByteArray(Charsets.US_ASCII).copyInto(poll)
        poll[9] = 0x20                                           // OpPoll 0x2000
        rx.handle(poll, poll.size, src)                          // no socket yet: reply is skipped
        assertEquals(1L, rx.polls)
        assertTrue(rx.universes.isEmpty())
    }

    @Test fun pollReplyAnnouncesSelectedUniverse() {
        val ip = InetAddress.getByName("192.168.1.65") as Inet4Address
        val r = ArtNetReceiver.buildPollReply(ip, uni = 258, active = true, polls = 3)

        assertEquals(239, r.size)
        assertEquals("Art-Net\u0000", String(r, 0, 8, Charsets.US_ASCII))
        assertEquals(0x2100, (r[8].toInt() and 0xff) or ((r[9].toInt() and 0xff) shl 8))
        assertArrayEquals(ip.address, r.copyOfRange(10, 14))
        assertEquals(ArtNetReceiver.PORT, (r[14].toInt() and 0xff) or ((r[15].toInt() and 0xff) shl 8))
        assertEquals(1, r[18].toInt())                          // NetSwitch
        assertEquals(0, r[19].toInt())                          // SubSwitch
        assertEquals(2, r[190].toInt())                         // SwOut port 0
        assertEquals(1, r[173].toInt())                         // NumPorts
        assertEquals(0x80, r[174].toInt() and 0xff)             // output, DMX512
        assertEquals(0x80, r[182].toInt() and 0xff)             // GoodOutput: data received
        assertEquals("R1 Art-Net", cString(r, 26, 18))
        assertEquals("Rabbit R1 Art-Net Monitor", cString(r, 44, 64))
        assertEquals("#0001 [3] Monitoring universe 258", cString(r, 108, 64))
        assertArrayEquals(ip.address, r.copyOfRange(207, 211)) // BindIp
    }

    @Test fun pollReplyWithoutDataClearsGoodOutput() {
        val ip = InetAddress.getByName("10.0.0.2") as Inet4Address
        val r = ArtNetReceiver.buildPollReply(ip, uni = 0, active = false, polls = 0)
        assertEquals(0, r[182].toInt())
    }

    @Test fun pollReplyStringsStayNulTerminated() {
        val ip = InetAddress.getByName("10.0.0.2") as Inet4Address
        val r = ArtNetReceiver.buildPollReply(ip, uni = Universe.MAX, active = false, polls = 123_456_789)
        assertEquals(0, r[107].toInt())                         // end of LongName
        assertEquals(0, r[171].toInt())                         // end of NodeReport
    }

    /** End to end over a real UDP socket on loopback. */
    @Test(timeout = 5000)
    fun receivesOverUdp() {
        val rx = ArtNetReceiver()
        rx.start()
        try {
            val pkt = artDmx(0, 5, levels(16))
            DatagramSocket().use { tx ->
                val dest = InetAddress.getLoopbackAddress()
                // The receiver binds on its own thread; resend until it shows up.
                while (rx.universes[5] == null) {
                    assertNull(rx.lastError)
                    tx.send(DatagramPacket(pkt, pkt.size, dest, ArtNetReceiver.PORT))
                    Thread.sleep(50)
                }
            }
            assertArrayEquals(levels(16), rx.universes[5]!!.data.copyOfRange(0, 16))
        } finally {
            rx.stop()
        }
    }

    private fun cString(b: ByteArray, off: Int, max: Int): String {
        var end = off
        while (end < off + max && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }
}
