package em10.camera

/**
 * Plain-HTTP access to the camera's Wi-Fi interface (docs/usb-protocol/wifi-api.md). Each platform
 * supplies one; paths are relative to the camera, e.g. `get_caminfo.cgi`.
 *
 * Unlike [em10.core.HttpTransport], a non-200 status is not an error here: the camera answers HTTP 520
 * with an error code in the body, and the client decides what that means.
 */
interface CameraHttp {
    /**
     * GET [path]. With [sink] the body is streamed to it and the reply's body is empty; without it the
     * body is returned. [timeoutMs] limits each wait for data, not the whole transfer: a full-size
     * picture takes about 28 s over the camera's Wi-Fi.
     */
    fun get(path: String, timeoutMs: Long, sink: ChunkSink? = null): CameraReply

    /** POST [body] to [path] (used by `set_camprop`). */
    fun post(path: String, body: ByteArray, timeoutMs: Long): CameraReply
}

/** Receives a streamed body. [total] is the announced length, or -1. */
fun interface ChunkSink {
    fun write(buffer: ByteArray, length: Int, received: Long, total: Long)
}

class CameraReply(val status: Int, val body: ByteArray, val received: Long = body.size.toLong()) {
    val text: String get() = body.decodeToString()
}

/** The camera could not be reached, or it refused a request. */
class CameraException(message: String, val status: Int = 0, val errorCode: String? = null) : Exception(message) {
    /** HTTP 520 with error 1001: the command is not accepted in the camera's current mode. */
    val wrongMode: Boolean get() = status == 520 && errorCode == "1001"
}
