package em10.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reassembly of the live-view UDP stream (docs/usb-protocol/liveview-udp.md). */
class LiveViewParserTest {

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** A datagram: flags0/flags1, sequence, timestamp, extension entries (first only), JPEG payload. */
    private fun datagram(
        first: Boolean, last: Boolean, seq: Int, timestamp: Long = 90_000,
        ext: List<Pair<Int, List<Long>>> = emptyList(), payload: ByteArray,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { out += (v shr 24).toByte(); out += (v shr 16).toByte(); out += (v shr 8).toByte(); out += v.toByte() }
        out += (if (first) 0x10 else 0).toByte()
        out += (if (last) 0x80 else 0).toByte()
        u16(seq); u32(timestamp); u32(0)
        if (first) {
            val words = ext.sumOf { 1 + it.second.size }     // one header word + value words each
            u16(0); u16(words)
            for ((type, values) in ext) { u16(type); u16(values.size); values.forEach { u32(it) } }
        }
        out += payload.toList()
        return out.toByteArray()
    }

    @Test fun singleDatagramFrame() {
        val parser = LiveViewParser()
        val jpeg = soi + byteArrayOf(1, 2, 3) + eoi
        val frame = parser.offer(datagram(first = true, last = true, seq = 7, ext = listOf(1 to listOf(jpeg.size.toLong())), payload = jpeg))
        assertTrue(frame != null)
        assertContentEquals(jpeg, frame.jpeg)
        assertEquals(7, frame.sequence)
        assertEquals(1000, frame.timestampMs)       // 90000 / 90000 s = 1000 ms
        assertEquals(listOf(jpeg.size.toLong()), frame.extensions[1])
    }

    @Test fun threeDatagramFrame() {
        val parser = LiveViewParser()
        val a = soi + ByteArray(5) { 0xA }
        val b = ByteArray(5) { 0xB }
        val c = ByteArray(5) { 0xC } + eoi
        assertNull(parser.offer(datagram(first = true, last = false, seq = 10, payload = a)))
        assertNull(parser.offer(datagram(first = false, last = false, seq = 11, payload = b)))
        val frame = parser.offer(datagram(first = false, last = true, seq = 12, payload = c))
        assertTrue(frame != null)
        assertContentEquals(a + b + c, frame.jpeg)
        assertEquals(10, frame.sequence)
    }

    @Test fun outOfOrderContinuationDropsFrame() {
        val parser = LiveViewParser()
        val drops = ArrayList<String>()
        val p = LiveViewParser { drops += it }
        assertNull(p.offer(datagram(first = true, last = false, seq = 1, payload = soi + byteArrayOf(9))))
        assertNull(p.offer(datagram(first = false, last = true, seq = 3, payload = byteArrayOf(9) + eoi)))  // skipped 2
        assertTrue(drops.any { "out-of-order" in it })
        assertTrue(!p.framesInProgress)
    }

    @Test fun sequenceWrapsAt65535() {
        val parser = LiveViewParser()
        assertNull(parser.offer(datagram(first = true, last = false, seq = 65535, payload = soi)))
        val frame = parser.offer(datagram(first = false, last = true, seq = 0, payload = eoi))
        assertTrue(frame != null)
        assertContentEquals(soi + eoi, frame.jpeg)
    }

    @Test fun missingEoiIsRejected() {
        val drops = ArrayList<String>()
        val parser = LiveViewParser { drops += it }
        val frame = parser.offer(datagram(first = true, last = true, seq = 1, payload = soi + byteArrayOf(1, 2)))  // no EOI
        assertNull(frame)
        assertTrue(drops.any { "FF D8" in it })
    }

    @Test fun afRectsFromExtensions() {
        val parser = LiveViewParser()
        // type 108: v0 packs (x=100,y=200), v1 packs (w=30,h=40) -> right=130, bottom=240
        val v0 = (100L shl 16) or 200L
        val v1 = (30L shl 16) or 40L
        val jpeg = soi + eoi
        val frame = parser.offer(datagram(first = true, last = true, seq = 1,
            ext = listOf(1 to listOf(jpeg.size.toLong()), 108 to listOf(v0, v1)), payload = jpeg))!!
        val rects = frame.rects()
        assertEquals(1, rects.size)
        assertEquals(Rect(100, 200, 130, 240), rects[0])
    }

    @Test fun firstDatagramResyncsAfterLostFrame() {
        val parser = LiveViewParser()
        parser.offer(datagram(first = true, last = false, seq = 1, payload = soi))   // frame never finished
        // a new first datagram should discard the stale buffer and start clean
        val frame = parser.offer(datagram(first = true, last = true, seq = 99, payload = soi + eoi))
        assertTrue(frame != null)
        assertContentEquals(soi + eoi, frame.jpeg)
        assertEquals(99, frame.sequence)
    }
}
