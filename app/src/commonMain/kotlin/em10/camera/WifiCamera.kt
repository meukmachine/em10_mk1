package em10.camera

/**
 * The E-M10 Mark I's Wi-Fi interface (protocol 2.20), as confirmed on the camera: docs/usb-protocol/wifi-api.md
 * and docs/firmware/wifi-cgi.md. Port of `tools/emcam.py`, which stays as the test tool for runs at the camera.
 *
 * Modes: `play` (browse and download), `rec` (live view, properties, remote control) and `shutter`
 * (shutter button only; every other command is refused). Card listing works in play and rec mode.
 *
 * [pause] waits between retries; platforms pass their sleep.
 */
class WifiCamera(
    private val http: CameraHttp,
    private val timeoutMs: Long = 10_000,
    private val pause: (Long) -> Unit = {},
) {
    enum class Mode(val wire: String) { PLAY("play"), REC("rec"), SHUTTER("shutter") }

    /** Live-view sizes; the camera only takes one on a switch from play to rec. */
    enum class LiveViewSize(val wire: String) { SMALL("0320x0240"), LARGE("0640x0480") }

    /** Physical shutter-button steps for [press] (shutter mode). */
    enum class ShutterStep(val wire: String) {
        HALF("1stpush"), RELEASE_HALF("1strelease"), FULL("2ndpush"), RELEASE_FULL("2ndrelease"),
        PRESS("1st2ndpush"), RELEASE_KEEP_HALF("2nd1strelease"),
    }

    // --- raw access ---

    /** GET [path] and return the body; HTTP 520 and other failures become [CameraException]. */
    fun request(path: String, timeoutMs: Long = this.timeoutMs): ByteArray = checked(path, http.get(path, timeoutMs)).body

    fun text(path: String): String = request(path).decodeToString()

    /** Stream [path] into [sink]; returns the number of bytes received. */
    fun stream(path: String, sink: ChunkSink, timeoutMs: Long = this.timeoutMs): Long =
        checked(path, http.get(path, timeoutMs, sink)).received

    private fun checked(path: String, r: CameraReply): CameraReply {
        if (r.status == 200) return r
        val code = CameraXml.errorCode(r.text)
        val what = code?.let { " error $it: ${CameraXml.ERRORS[it] ?: "unknown"}" } ?: ""
        throw CameraException("HTTP ${r.status}$what for $path", r.status, code)
    }

    // --- information ---

    fun connectMode(): String? = CameraXml.tag(text("get_connectmode.cgi"), "connectmode")
    fun model(): String? = CameraXml.tag(text("get_caminfo.cgi"), "model")
    fun commandList(): CommandList = CameraXml.commandList(text("get_commandlist.cgi"))

    /** Free card space in bytes, or null when the camera reports its 32-bit maximum (4 GB or more). */
    fun freeBytes(): Long? {
        val v = CameraXml.tag(text("get_unusedcapacity.cgi"), "unused")?.toLongOrNull()
            ?: throw CameraException("get_unusedcapacity gave no number")
        return if (v >= 0xffffffffL) null else v
    }

    /** Number of pictures (a RAW + JPEG pair counts once). Play mode only. */
    fun pictureCount(): Int? = CameraXml.tag(text("get_dcffilenum.cgi"), "dcffile")?.toIntOrNull()

    // --- card ---

    /** Entries of one card folder. Right after a shot the camera is still writing and answers 520; retried. */
    fun listDir(dir: String): List<CardEntry> {
        val path = "get_imglist.cgi?DIR=" + urlEncode(dir)
        for (attempt in 0 until 7) {
            try {
                return CameraXml.imageList(text(path))
            } catch (e: CameraException) {
                if (e.status != 520 || attempt == 6) throw e
                pause(1000)
            }
        }
        error("unreachable")
    }

    /** All files below [dir]. */
    fun walk(dir: String = "/DCIM"): List<CardEntry> = listDir(dir).flatMap { if (it.isDirectory) walk(it.path) else listOf(it) }

    /** All files on the card by path; an empty card has no /DCIM and gives an empty map. */
    fun cardFiles(): Map<String, CardEntry> = try {
        walk().associateBy { it.path }
    } catch (e: CameraException) {
        if (e.status == 520) emptyMap() else throw e
    }

    fun thumbnail(path: String): ByteArray = request("get_thumbnail.cgi?DIR=" + urlEncode(path))

    /** A resized JPEG ([size] 1024, 1600, 1920 or 2048). Play mode; for other cameras' files the original comes back. */
    fun resized(path: String, size: Int): ByteArray {
        require(size in RESIZE_SIZES) { "size must be one of $RESIZE_SIZES" }
        return request("get_resizeimg.cgi?DIR=${urlEncode(path)}&size=$size")
    }

    fun download(path: String, sink: ChunkSink): Long = stream(path, sink, DOWNLOAD_TIMEOUT_MS)

    // --- modes ---

    fun setMode(mode: Mode, liveView: LiveViewSize? = null) {
        if (mode == Mode.REC && liveView != null) {
            request("switch_cammode.cgi?mode=play")
            request("switch_cammode.cgi?mode=rec&lvqty=${liveView.wire}")
        } else {
            request("switch_cammode.cgi?mode=${mode.wire}")
        }
    }

    // --- properties (rec mode) ---

    fun properties(): List<CameraProperty> = CameraXml.properties(text("get_camprop.cgi?com=desc&propname=desclist"))

    fun property(name: String): CameraProperty =
        CameraXml.properties(text("get_camprop.cgi?com=desc&propname=$name")).firstOrNull()
            ?: throw CameraException("no description for property $name")

    /** Set [name] to [value] and return the value the camera reads back. */
    fun setProperty(name: String, value: String): String? {
        val path = "set_camprop.cgi?com=set&propname=$name"
        checked(path, http.post(path, CameraXml.setPropertyBody(value).encodeToByteArray(), timeoutMs))
        return CameraXml.tag(text("get_camprop.cgi?com=get&propname=$name"), "value")
    }

    /** Lens attached, power zoom (unadvertised command, rec mode, read-only). */
    fun lensState(): LensState = CameraXml.lensState(text("exec_takemisc.cgi?com=checklens"))

    // --- shooting ---

    fun press(step: ShutterStep) {
        request("exec_shutter.cgi?com=${step.wire}")
    }

    /**
     * Take one picture and leave the camera in rec mode with the button released: shutter mode, half
     * press, [focusWaitMs] for focus, full press, release both stages, [saveWaitMs], back to rec mode.
     * The commands only press the button: whether the camera fires is up to its focus and settings.
     *
     * [driveMode] (e.g. "normal" for single, "continuous-L"/"continuous-H" for burst) is set first
     * when given: a continuous drive leaves several frames per shutter action, so pass "normal" for
     * exactly one picture. Null leaves the camera's current drive mode alone.
     */
    fun shoot(focusWaitMs: Long = 2000, saveWaitMs: Long = 1500, driveMode: String? = null) {
        if (driveMode != null) {
            setMode(Mode.REC)
            setProperty("drivemode", driveMode)
        }
        setMode(Mode.SHUTTER)
        val pressed = ArrayList<ShutterStep>()
        var primary: Throwable? = null
        try {
            press(ShutterStep.HALF); pressed += ShutterStep.RELEASE_HALF
            pause(focusWaitMs)
            press(ShutterStep.FULL); pressed += ShutterStep.RELEASE_KEEP_HALF
            pause(500)
        } catch (e: Throwable) {
            primary = e
            throw e
        } finally {
            var failure: CameraException? = null
            for (step in pressed.asReversed()) {
                try {
                    press(step)
                } catch (e: CameraException) {
                    failure = failure ?: e
                }
            }
            pause(saveWaitMs)
            setMode(Mode.REC)
            if (primary == null) failure?.let { throw it }
        }
    }

    /**
     * Take one picture **without leaving rec mode**, the way OI.Share's control (live-view) mode does:
     * arm/focus with `takeready`, then `starttake` and `stoptake` over `exec_takemotion`. Unlike
     * [shoot] (which goes through shutter mode and so kills the live-view stream), this keeps live view
     * running. The camera must already be in rec mode.
     *
     * [driveMode] is set first when given ("normal" for a single frame). [point] "XXXXxYYYY" sets the
     * AF target before the shot (touch AF). [exposureMs] is how long to hold the take between start and
     * stop — short for a normal exposure; a long hold is how bulb / live-time would work.
     *
     * Confirmed on the E-M10 (2026-11-14): one picture, live view stayed on through the shot.
     */
    fun captureInRec(focusWaitMs: Long = 2000, exposureMs: Long = 500, point: String? = null, driveMode: String? = null) {
        if (driveMode != null) setProperty("drivemode", driveMode)
        val pt = point?.let { "&point=$it" } ?: ""
        request("exec_takemotion.cgi?com=takeready$pt")
        pause(focusWaitMs)
        request("exec_takemotion.cgi?com=starttake$pt")
        pause(exposureMs)
        request("exec_takemotion.cgi?com=stoptake")
    }

    /**
     * The full-size JPEG of the last shot (rec mode). About 8 MB, so about 28 s over Wi-Fi; there is no
     * faster way to the full picture. Returns the number of bytes.
     */
    fun lastJpeg(sink: ChunkSink): Long = stream("exec_takemisc.cgi?com=getlastjpg", sink, DOWNLOAD_TIMEOUT_MS)

    // --- live view (rec mode) ---

    /** Start the MJPEG-over-UDP live-view stream to this host's [port] (docs/usb-protocol/liveview-udp.md). */
    fun startLiveView(port: Int, size: LiveViewSize = LiveViewSize.SMALL) {
        request("exec_takemisc.cgi?com=startliveview&port=$port&lvqty=${size.wire}")
    }

    fun stopLiveView() {
        request("exec_takemisc.cgi?com=stopliveview")
    }

    /**
     * The camera's review image of the last shot (rec mode). The camera waits up to 15 s for it to be
     * ready and hands each image out once, so the timeout here is longer. Whether a shot through
     * [shoot] (shutter mode) produces one is not yet known; OI.Share uses it after `starttake`.
     */
    fun reviewImage(): ByteArray = request("exec_takemisc.cgi?com=getrecview", REVIEW_TIMEOUT_MS)

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        val RESIZE_SIZES = listOf(1024, 1600, 1920, 2048)
        const val DOWNLOAD_TIMEOUT_MS = 30_000L
        const val REVIEW_TIMEOUT_MS = 20_000L

        fun urlEncode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                if (c.toChar().let { it.isLetterOrDigit() && c < 0x80 || it in "/-_.~" }) append(c.toChar())
                else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
