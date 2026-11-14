package em10.camera

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * [CameraHttp] on HttpURLConnection. Its read timeout applies to each wait for data, which suits the
 * camera's slow transfers (java.net.http only offers a timeout for the whole request).
 */
class JvmCameraHttp(host: String = WifiCamera.DEFAULT_HOST) : CameraHttp {
    private val base = "http://$host/"

    override fun get(path: String, timeoutMs: Long, sink: ChunkSink?): CameraReply =
        exchange(path, null, timeoutMs, sink)

    override fun post(path: String, body: ByteArray, timeoutMs: Long): CameraReply =
        exchange(path, body, timeoutMs, null)

    private fun exchange(path: String, body: ByteArray?, timeoutMs: Long, sink: ChunkSink?): CameraReply {
        val c = try {
            URI.create(base + path.trimStart('/')).toURL().openConnection() as HttpURLConnection
        } catch (e: IllegalArgumentException) {
            throw CameraException("bad request path $path")
        }
        c.connectTimeout = timeoutMs.toInt()
        c.readTimeout = timeoutMs.toInt()
        c.setRequestProperty("User-Agent", "OI.Share v2")   // the camera's own app sends this
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream: InputStream? = if (status >= 400) c.errorStream else c.inputStream
            if (status != 200 || sink == null) {
                return CameraReply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
            }
            val total = c.contentLengthLong
            val buffer = ByteArray(1 shl 16)
            var received = 0L
            stream!!.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    received += n
                    sink.write(buffer, n, received, total)
                }
            }
            return CameraReply(status, ByteArray(0), received)
        } catch (e: IOException) {
            throw CameraException("cannot reach the camera at $base (${e.message ?: e::class.simpleName}). Is this computer on the camera's Wi-Fi?")
        } finally {
            c.disconnect()
        }
    }
}

/** [PictureStore] writing into a folder; a file is written as NAME.part and renamed when complete. */
class FolderPictureStore(private val dir: File) : PictureStore {
    override fun create(name: String): PictureFile {
        require('/' !in name && '\\' !in name && name != "." && name != "..") { "bad file name $name" }
        dir.mkdirs()
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val out = part.outputStream().buffered()
        return object : PictureFile {
            override fun write(buffer: ByteArray, length: Int, received: Long, total: Long) = out.write(buffer, 0, length)
            override fun commit() {
                out.close()
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("cannot rename ${part.path} to ${target.path}")
                }
            }
            override fun abort() {
                out.close()
                part.delete()
            }
        }
    }
}
