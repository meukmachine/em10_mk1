package em10.camera

/** Where [Tethering] puts pictures; the platform decides (a folder on desktop, app storage on a phone). */
fun interface PictureStore {
    fun create(name: String): PictureFile
}

/** A file being written: chunks arrive through [write], then [commit] or [abort]. */
interface PictureFile : ChunkSink {
    fun commit()
    fun abort()
}

/**
 * Take a picture and save it to the computer (issue C5).
 *
 * OI.Share (docs/usb-protocol/wifi-api.md, "After a shot") never downloads the full picture on its own:
 * it shows a review image and fetches the 8 MB JPEG (about 28 s) only when asked. Here the order is:
 * find the new files on the card, save the thumbnail at once (a few KB), then the full JPEG through
 * `getlastjpg`, then optionally the RAW file by its path. The thumbnail lets a UI show the shot while
 * the full picture is still on its way.
 */
class Tethering(private val camera: WifiCamera, private val pause: (Long) -> Unit = {}) {

    data class Options(
        val fullJpeg: Boolean = true,
        val raw: Boolean = false,
        val focusWaitMs: Long = 2000,
        /** How long to wait for the new files to appear on the card. */
        val appearTimeoutMs: Long = 20_000,
    )

    sealed interface Event {
        data class NewFiles(val files: List<CardEntry>) : Event
        data class Progress(val name: String, val received: Long, val total: Long) : Event
        data class Saved(val name: String, val bytes: Long) : Event
        data class Note(val text: String) : Event
    }

    data class Result(val newFiles: List<CardEntry>, val saved: List<String>)

    fun shootAndSave(store: PictureStore, options: Options = Options(), onEvent: (Event) -> Unit = {}): Result {
        camera.setMode(WifiCamera.Mode.REC)          // card listing works in rec mode, not in shutter mode
        val before = camera.cardFiles()
        camera.shoot(options.focusWaitMs)
        val newFiles = waitForNewFiles(before, options.appearTimeoutMs)
        onEvent(Event.NewFiles(newFiles))
        // getlastjpg serves only the newest frame on the card, so the thumbnail and RAW must track that
        // same frame. A burst (sequential drive) leaves several new files; the earlier frames are not
        // fetched here (getlastjpg cannot reach them — they would need a per-path download).
        val jpg = newFiles.lastOrNull { it.name.endsWith(".JPG", ignoreCase = true) }
        val orf = newFiles.lastOrNull { it.name.endsWith(".ORF", ignoreCase = true) }
        val saved = ArrayList<String>()
        val frame = jpg ?: orf ?: newFiles.last()
        val framesPerFile = if (orf != null) 2 else 1
        if (newFiles.size > framesPerFile) {
            onEvent(Event.Note("the shot left ${newFiles.size} new files (a burst); saving only the newest frame ${frame.name}"))
        }

        val thumbName = "thumb_" + frame.name.substringBeforeLast('.') + ".jpg"
        save(store, thumbName, onEvent) { sink -> camera.thumbnail(frame.path).also { sink.write(it, it.size, it.size.toLong(), it.size.toLong()) }.size.toLong() }
        saved += thumbName

        if (options.fullJpeg && jpg != null) {
            val bytes = save(store, jpg.name, onEvent) { sink -> camera.lastJpeg(sink) }
            if (bytes != jpg.size) {
                onEvent(Event.Note("getlastjpg gave $bytes bytes, the card lists ${jpg.size} for ${jpg.path}"))
            }
            saved += jpg.name
        }
        if (options.raw && orf != null) {
            save(store, orf.name, onEvent) { sink -> downloadAnyMode(orf.path, sink, onEvent) }
            saved += orf.name
        }
        return Result(newFiles, saved)
    }

    private fun waitForNewFiles(before: Map<String, CardEntry>, timeoutMs: Long): List<CardEntry> {
        var waited = 0L
        while (true) {
            val new = camera.cardFiles().filterKeys { it !in before }.values.sortedBy { it.path }
            if (new.isNotEmpty()) return new
            if (waited >= timeoutMs) throw CameraException("the shot gave no new file on the card within ${timeoutMs / 1000} s")
            pause(1000)
            waited += 1000
        }
    }

    /** Plain file downloads are known to work in play mode; rec mode is tried first and play mode used if refused. */
    private fun downloadAnyMode(path: String, sink: ChunkSink, onEvent: (Event) -> Unit): Long = try {
        camera.download(path, sink)
    } catch (e: CameraException) {
        if (e.status != 520) throw e
        onEvent(Event.Note("rec mode does not serve $path (${e.message}); fetching it in play mode"))
        camera.setMode(WifiCamera.Mode.PLAY)
        try {
            camera.download(path, sink)
        } finally {
            camera.setMode(WifiCamera.Mode.REC)
        }
    }

    private fun save(store: PictureStore, name: String, onEvent: (Event) -> Unit, fetch: (ChunkSink) -> Long): Long {
        val file = store.create(name)
        val sink = ChunkSink { buffer, length, received, total ->
            file.write(buffer, length, received, total)
            onEvent(Event.Progress(name, received, total))
        }
        val bytes = try {
            fetch(sink)
        } catch (e: Throwable) {
            file.abort()
            throw e
        }
        file.commit()
        onEvent(Event.Saved(name, bytes))
        return bytes
    }
}
