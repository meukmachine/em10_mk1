package em10.camera

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

/** JVM live-view UDP socket over java.net.DatagramSocket. */
class JvmUdpSocket(port: Int, timeoutMs: Int) : UdpSocket {
    private val socket = DatagramSocket(port).apply { soTimeout = timeoutMs }
    override val localPort: Int get() = socket.localPort

    override fun receive(into: ByteArray): Int {
        val packet = DatagramPacket(into, into.size)
        return try {
            socket.receive(packet)
            packet.length
        } catch (_: SocketTimeoutException) {
            -1
        } catch (_: SocketException) {
            -1                      // socket closed from another thread
        }
    }

    override fun close() = socket.close()

    companion object : UdpSocketFactory {
        override fun open(port: Int, timeoutMs: Int): UdpSocket = JvmUdpSocket(port, timeoutMs)
    }
}
