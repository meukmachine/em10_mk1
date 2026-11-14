package em10.camera

/**
 * One reassembled live-view frame: a complete JPEG plus the overlay metadata carried in the first
 * datagram's extension block (docs/usb-protocol/liveview-udp.md).
 *
 * [timestampTicks] is the 90 kHz RTP-style clock shared by a frame's datagrams; [sequence] is the
 * counter of the frame's first datagram. [extensions] is the raw TLV block keyed by type, each value
 * the list of big-endian u32 words that followed it. [rects] pulls the AF / face-detection boxes
 * (types 108..115) out of that block.
 */
data class LiveViewFrame(
    val jpeg: ByteArray,
    val sequence: Int,
    val timestampTicks: Long,
    val extensions: Map<Int, List<Long>> = emptyMap(),
) {
    /** Frame presentation time in milliseconds from the 90 kHz clock. */
    val timestampMs: Long get() = timestampTicks * 1000 / 90_000

    /** On-screen overlay rectangles (AF point / face frames), extension types 108..115. */
    fun rects(): List<Rect> = (108..115).mapNotNull { type ->
        val v = extensions[type] ?: return@mapNotNull null
        if (v.size < 2) return@mapNotNull null
        val x = (v[0] shr 16).toInt(); val y = (v[0] and 0xFFFF).toInt()
        Rect(x, y, x + (v[1] shr 16).toInt(), y + (v[1] and 0xFFFF).toInt())
    }

    // Arrays need custom equals/hashCode for a data class.
    override fun equals(other: Any?): Boolean =
        this === other || (other is LiveViewFrame && sequence == other.sequence &&
            timestampTicks == other.timestampTicks && jpeg.contentEquals(other.jpeg) && extensions == other.extensions)

    override fun hashCode(): Int = jpeg.contentHashCode() * 31 + sequence
}

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Reassembles the camera's MJPEG-over-UDP live-view stream (docs/usb-protocol/liveview-udp.md). Pure
 * and platform-free: feed every datagram to [offer]; it returns a [LiveViewFrame] when a frame's last
 * datagram arrives, otherwise null. [onDrop] reports a discarded datagram (out-of-order or malformed).
 *
 * Wire format: 12-byte big-endian base header on every datagram — flags0 (bit 0x10 = first of frame),
 * flags1 (bit 0x80 = last), u16 sequence, u32 timestamp, u32 reserved. The first datagram then has
 * u16 reserved, u16 extCount N, N×4-byte TLV block, then JPEG (FF D8); continuations carry JPEG from
 * offset 12. The last datagram ends FF D9.
 */
class LiveViewParser(private val onDrop: (String) -> Unit = {}) {
    private var buffer: ByteArray? = null          // JPEG bytes accumulated for the frame in progress
    private var length = 0
    private var sequence = 0                        // last accepted datagram's sequence
    private var firstSeq = 0
    private var timestamp = 0L
    private var extensions: Map<Int, List<Long>> = emptyMap()

    val framesInProgress: Boolean get() = buffer != null

    fun offer(datagram: ByteArray): LiveViewFrame? = offer(datagram, datagram.size)

    /**
     * Feed the first [size] bytes of [datagram] (the rest is ignored), so the receive loop can reuse
     * one buffer instead of copying each datagram.
     */
    fun offer(datagram: ByteArray, size: Int): LiveViewFrame? {
        if (size < 12) { onDrop("short datagram ($size bytes)"); return null }
        val flags0 = datagram[0].toInt() and 0xff
        val flags1 = datagram[1].toInt() and 0xff
        val seq = be16(datagram, 2)
        val first = flags0 and 0x10 != 0
        val last = flags1 and 0x80 != 0

        val payloadStart: Int
        if (first) {
            if (size < 16) { onDrop("short first datagram ($size bytes)"); return null }
            val extCount = be16(datagram, 14)
            payloadStart = 16 + extCount * 4
            if (payloadStart > size) { reset(); onDrop("extension block runs past datagram"); return null }
            buffer = ByteArray(INITIAL_CAPACITY)
            length = 0
            firstSeq = seq
            timestamp = be32(datagram, 4)
            extensions = parseExtensions(datagram, extCount)
        } else {
            if (buffer == null) { onDrop("continuation with no frame started"); return null }
            if (seq != ((sequence + 1) and 0xffff)) { reset(); onDrop("out-of-order sequence $seq after $sequence"); return null }
            payloadStart = 12
        }
        sequence = seq
        append(datagram, payloadStart, size)

        if (!last) return null
        val jpeg = buffer!!.copyOf(length)
        val seqOut = firstSeq; val tsOut = timestamp; val extOut = extensions
        reset()
        if (jpeg.size < 4 || jpeg[0].toInt() and 0xff != 0xFF || jpeg[1].toInt() and 0xff != 0xD8 ||
            jpeg[jpeg.size - 2].toInt() and 0xff != 0xFF || jpeg[jpeg.size - 1].toInt() and 0xff != 0xD9) {
            onDrop("reassembled frame is not bounded by FF D8 .. FF D9 (${jpeg.size} bytes)")
            return null
        }
        return LiveViewFrame(jpeg, seqOut, tsOut, extOut)
    }

    private fun append(src: ByteArray, from: Int, end: Int) {
        val add = end - from
        if (add <= 0) return
        var buf = buffer!!
        if (length + add > buf.size) {
            var cap = buf.size
            while (length + add > cap) cap *= 2
            buf = buf.copyOf(cap)
            buffer = buf
        }
        src.copyInto(buf, length, from, end)
        length += add
    }

    private fun reset() { buffer = null; length = 0; extensions = emptyMap() }

    private fun parseExtensions(d: ByteArray, extCount: Int): Map<Int, List<Long>> {
        val end = 16 + extCount * 4
        val map = LinkedHashMap<Int, List<Long>>()
        var i = 16
        while (i + 4 <= end) {
            val type = be16(d, i)
            val lenWords = be16(d, i + 2)
            val start = i + 4
            if (start + lenWords * 4 > end) break            // truncated entry
            map[type] = (0 until lenWords).map { be32(d, start + it * 4) }
            i = start + lenWords * 4
        }
        return map
    }

    companion object {
        private const val INITIAL_CAPACITY = 65536
        fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
        fun be32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
                ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    }
}
