package em10.camera

/**
 * A bound UDP socket for receiving the live-view stream. The platform provides it (JVM: DatagramSocket).
 *
 * [localPort] is the port the camera streams to; the client passes it in `startliveview&port=P`.
 * [receive] fills a reused buffer and returns the datagram length, or -1 on timeout/close. [close]
 * unblocks a pending [receive].
 */
interface UdpSocket {
    val localPort: Int
    fun receive(into: ByteArray): Int
    fun close()
}

/** Opens a UDP socket bound to [port] (0 = any free port) with the given receive timeout. */
fun interface UdpSocketFactory {
    fun open(port: Int, timeoutMs: Int): UdpSocket
}

/**
 * Drives the camera's Wi-Fi live view (docs/usb-protocol/liveview-udp.md): switch to rec mode, open a
 * UDP socket, ask the camera to stream to it, reassemble datagrams into JPEG frames, and stop cleanly.
 *
 * [run] blocks on its own thread, delivering each complete frame to [onFrame], until [stop] is called
 * or [onFrame] returns false. The camera is always told to stop and left in rec mode.
 */
class LiveView(
    private val camera: WifiCamera,
    private val sockets: UdpSocketFactory,
    private val port: Int = 0,
    private val receiveTimeoutMs: Int = 2000,
) {
    @Volatile private var running = false
    @Volatile private var socket: UdpSocket? = null

    fun stop() {
        running = false
        socket?.close()
    }

    /**
     * Stream live view, calling [onFrame] with each JPEG frame (return false to stop) and [onDrop]
     * with each discarded datagram. Blocks until stopped. Returns the number of frames delivered.
     */
    fun run(
        size: WifiCamera.LiveViewSize = WifiCamera.LiveViewSize.SMALL,
        onDrop: (String) -> Unit = {},
        onFrame: (LiveViewFrame) -> Boolean,
    ): Int {
        camera.setMode(WifiCamera.Mode.REC, size)
        val sock = sockets.open(port, receiveTimeoutMs)
        socket = sock
        running = true
        val parser = LiveViewParser(onDrop)
        val buffer = ByteArray(65536)
        var frames = 0
        try {
            camera.startLiveView(sock.localPort, size)
            while (running) {
                val n = sock.receive(buffer)
                if (n < 0) continue                           // timeout: loop and re-check running
                val frame = parser.offer(buffer, n) ?: continue   // reuse the buffer, no per-datagram copy
                frames++
                if (!onFrame(frame)) break
            }
        } finally {
            running = false
            runCatching { camera.stopLiveView() }
            sock.close()
            socket = null
        }
        return frames
    }
}
