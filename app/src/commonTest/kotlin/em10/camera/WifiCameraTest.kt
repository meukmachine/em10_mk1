package em10.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fake camera with the behaviour confirmed on the E-M10 (docs/usb-protocol/wifi-api.md): modes,
 * mode-dependent commands answering 520/1001, a card that gains a RAW + JPEG pair per shot.
 */
class FakeCamera : CameraHttp {
    var mode = "play"
    val log = ArrayList<String>()
    val files = linkedMapOf<String, ByteArray>()
    var shots = 0
    /** Number of listings to answer with 520 (camera still writing). */
    var busyListings = 0
    var serveFilesInRec = true
    /** A request containing this text fails as if the connection dropped. */
    var failOn: String? = null
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(300_000) { (it % 251).toByte() } +
        byteArrayOf(0xff.toByte(), 0xd9.toByte())

    private fun error(code: String) = CameraReply(520, "<?xml version=\"1.0\"?><error><errorcode>$code</errorcode></error>".encodeToByteArray())
    private fun ok(body: String = "") = CameraReply(200, body.encodeToByteArray())

    override fun post(path: String, body: ByteArray, timeoutMs: Long): CameraReply {
        log += "POST $path ${body.decodeToString().replace("\r\n", "")}"
        if (mode != "rec") return error("1001")
        return ok()
    }

    override fun get(path: String, timeoutMs: Long, sink: ChunkSink?): CameraReply {
        log += path
        failOn?.let { if (it in path) throw CameraException("connection lost") }
        val reply = answer(path)
        if (sink == null || reply.status != 200) return reply
        var sent = 0
        while (sent < reply.body.size) {
            val n = minOf(65536, reply.body.size - sent)
            sink.write(reply.body.copyOfRange(sent, sent + n), n, (sent + n).toLong(), reply.body.size.toLong())
            sent += n
        }
        return CameraReply(200, ByteArray(0), sent.toLong())
    }

    private fun answer(path: String): CameraReply {
        val name = path.substringBefore('?').removeSuffix(".cgi")
        val q = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
        if (path.startsWith("/DCIM/")) return files[path]?.let { if (mode == "play" || serveFilesInRec) CameraReply(200, it) else error("1001") }
            ?: CameraReply(404, ByteArray(0))
        if (mode == "shutter" && name !in setOf("switch_cammode", "exec_shutter", "get_connectmode", "get_caminfo")) return error("1001")
        return when (name) {
            "switch_cammode" -> { mode = q["mode"]!!; ok() }
            "get_caminfo" -> ok("<caminfo><model>E-M10</model></caminfo>")
            "get_connectmode" -> ok("<connectmode>private</connectmode>")
            "get_dcffilenum" -> if (mode == "play") ok("<dcffile>${shots}</dcffile>") else error("1001")
            "get_imglist" -> {
                if (busyListings > 0) { busyListings--; return error("1005") }
                val dir = q["DIR"]!!.trimEnd('/')
                val entries = files.keys.filter { it.substringBeforeLast('/') == dir }.map { "$dir,${it.substringAfterLast('/')},${files[it]!!.size},0,23406,39872" } +
                    files.keys.map { it.substringBeforeLast('/') }.distinct().filter { it.substringBeforeLast('/') == dir }
                        .map { "$dir,${it.substringAfterLast('/')},0,16,23406,39872" }
                ok("VER_100\r\n" + entries.joinToString("\r\n"))
            }
            "get_thumbnail" -> files[q["DIR"]]?.let { CameraReply(200, it.copyOf(minOf(it.size, 9000))) } ?: error("1004")
            "get_camprop" -> if (mode != "rec") error("1001") else if (q["propname"] == "desclist") ok(
                "<desclist><desc><propname>isospeedvalue</propname><attribute>getset</attribute><value>Auto</value>" +
                    "<enum>Auto Low 200 400</enum></desc><desc><propname>noisereduction</propname><attribute>get</attribute>" +
                    "<value>off</value><enum>off on auto</enum></desc></desclist>"
            ) else ok("<get><value>Auto</value></get>")
            "exec_takemisc" -> when {
                mode != "rec" -> error("1001")
                q["com"] == "checklens" -> ok("<response><lensmount>ok</lensmount><electrically-driven>ng</electrically-driven></response>")
                q["com"] == "getlastjpg" -> files.keys.lastOrNull { it.endsWith(".JPG") }?.let { CameraReply(200, files[it]!!) } ?: error("1005")
                else -> error("1004")
            }
            "exec_shutter" -> {
                if (q["com"] == "2ndpush") {
                    shots++
                    val base = "/DCIM/100OLYMP/PB14" + shots.toString().padStart(4, '0')
                    files["$base.JPG"] = jpeg
                    files["$base.ORF"] = ByteArray(150_000) { 7 }
                    busyListings = 2
                }
                ok()
            }
            else -> error("1001")
        }
    }

    private fun decode(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') { append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3 } else append(s[i++])
        }
    }
}

class WifiCameraTest {
    @Test
    fun parsesImageList() {
        val list = CameraXml.imageList("VER_100\r\n/DCIM,100OLYMP,0,16,23406,39872\r\n/DCIM/100OLYMP,PB140001.JPG,7988289,0,23406,39872\r\n")
        assertEquals(2, list.size)
        assertTrue(list[0].isDirectory)
        assertEquals("/DCIM/100OLYMP/PB140001.JPG", list[1].path)
        assertEquals(7988289, list[1].size)
        assertEquals("2025-11-14 19:30:00", list[1].timestamp)
    }

    @Test
    fun parsesPropertiesLensAndCommands() {
        val props = CameraXml.properties("<desclist><desc><propname>takemode</propname><attribute>getset</attribute><value>P</value><enum>iAuto P A S M ART</enum></desc>" +
            "<desc><propname>touchactiveframe</propname><attribute>get</attribute><value>0108-0080_0422x0318</value></desc></desclist>")
        assertEquals(listOf("iAuto", "P", "A", "S", "M", "ART"), props[0].allowed)
        assertTrue(props[0].settable)
        assertFalse(props[1].settable)
        assertEquals(emptyList(), props[1].allowed)
        assertEquals(LensState(attached = true, powerZoom = false),
            CameraXml.lensState("<?xml version=\"1.0\"?><response><lensmount>ok</lensmount><electrically-driven>ng</electrically-driven></response>"))
        val cl = CameraXml.commandList("<oishare><version>2.20</version><cgi name=\"get_imglist\"/><cgi name=\"exec_shutter\"/></oishare>")
        assertEquals("2.20", cl.version)
        assertEquals(setOf("get_imglist", "exec_shutter"), cl.commands)
    }

    @Test
    fun errorRepliesBecomeExceptionsWithCode() {
        val cam = WifiCamera(FakeCamera())
        val e = assertFailsWith<CameraException> { cam.properties() }
        assertEquals(520, e.status)
        assertEquals("1001", e.errorCode)
        assertTrue(e.wrongMode)
    }

    @Test
    fun urlEncodingKeepsPathsReadable() {
        assertEquals("/DCIM/100OLYMP/P1010001.JPG", WifiCamera.urlEncode("/DCIM/100OLYMP/P1010001.JPG"))
        assertEquals("a%20b%26c", WifiCamera.urlEncode("a b&c"))
    }

    @Test
    fun shootPressesAndReleasesInOrderAndReturnsToRec() {
        val fake = FakeCamera()
        WifiCamera(fake).shoot(focusWaitMs = 0, saveWaitMs = 0)
        assertEquals(
            listOf("switch_cammode.cgi?mode=shutter", "exec_shutter.cgi?com=1stpush", "exec_shutter.cgi?com=2ndpush",
                "exec_shutter.cgi?com=2nd1strelease", "exec_shutter.cgi?com=1strelease", "switch_cammode.cgi?mode=rec"),
            fake.log,
        )
        assertEquals("rec", fake.mode)
    }

    @Test
    fun setPropertyPostsTheBodyAndReadsBack() {
        val fake = FakeCamera().apply { mode = "rec" }
        assertEquals("Auto", WifiCamera(fake).setProperty("isospeedvalue", "Auto"))
        assertTrue(fake.log.any { it == "POST set_camprop.cgi?com=set&propname=isospeedvalue <?xml version=\"1.0\"?><set><value>Auto</value></set>" })
    }

    @Test
    fun emptyCardHasNoFiles() {
        assertEquals(emptyMap(), WifiCamera(FakeCamera()).cardFiles())
    }

    private class MemoryStore : PictureStore {
        val done = linkedMapOf<String, ByteArray>()
        val aborted = ArrayList<String>()
        override fun create(name: String) = object : PictureFile {
            val data = ArrayList<Byte>()
            override fun write(buffer: ByteArray, length: Int, received: Long, total: Long) {
                for (i in 0 until length) data += buffer[i]
            }
            override fun commit() { done[name] = data.toByteArray() }
            override fun abort() { aborted += name }
        }
    }

    @Test
    fun shootAndSaveSavesThumbnailFullJpegAndRaw() {
        val fake = FakeCamera()
        val store = MemoryStore()
        val events = ArrayList<Tethering.Event>()
        val result = Tethering(WifiCamera(fake)).shootAndSave(store, Tethering.Options(raw = true, focusWaitMs = 0)) { events += it }
        assertEquals(listOf("/DCIM/100OLYMP/PB140001.JPG", "/DCIM/100OLYMP/PB140001.ORF"), result.newFiles.map { it.path })
        assertEquals(listOf("thumb_PB140001.jpg", "PB140001.JPG", "PB140001.ORF"), result.saved)
        assertContentEquals(fake.files["/DCIM/100OLYMP/PB140001.JPG"], store.done["PB140001.JPG"])
        assertContentEquals(fake.files["/DCIM/100OLYMP/PB140001.ORF"], store.done["PB140001.ORF"])
        assertTrue(events.any { it is Tethering.Event.Progress && it.name == "PB140001.JPG" && it.received < it.total })
        assertTrue(events.none { it is Tethering.Event.Note })
        assertEquals("rec", fake.mode)
    }

    @Test
    fun rawFallsBackToPlayModeWhenRecModeRefusesFiles() {
        val fake = FakeCamera().apply { serveFilesInRec = false }
        val store = MemoryStore()
        val events = ArrayList<Tethering.Event>()
        Tethering(WifiCamera(fake)).shootAndSave(store, Tethering.Options(fullJpeg = false, raw = true, focusWaitMs = 0)) { events += it }
        assertEquals(setOf("thumb_PB140001.jpg", "PB140001.ORF"), store.done.keys)
        assertTrue(events.any { it is Tethering.Event.Note })
        assertEquals("rec", fake.mode)
        assertTrue(fake.log.contains("switch_cammode.cgi?mode=play"))
    }

    @Test
    fun failedDownloadIsAbortedNotCommitted() {
        val fake = FakeCamera().apply { failOn = "getlastjpg" }
        val store = MemoryStore()
        assertFailsWith<CameraException> { Tethering(WifiCamera(fake)).shootAndSave(store, Tethering.Options(focusWaitMs = 0)) }
        assertEquals(listOf("PB140001.JPG"), store.aborted)
        assertNull(store.done["PB140001.JPG"])
    }
}
