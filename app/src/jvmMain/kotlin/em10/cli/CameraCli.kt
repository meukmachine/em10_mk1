package em10.cli

import em10.camera.CameraException
import em10.camera.ChunkSink
import em10.camera.FolderPictureStore
import em10.camera.JvmCameraHttp
import em10.camera.Tethering
import em10.camera.WifiCamera
import java.io.File

const val CAMERA_USAGE = """em10 cam - the camera's Wi-Fi interface (join the camera's network first)

Read-only:
  em10 cam info                 connect mode, model, free space, pictures, API version and commands
  em10 cam ls [DIR] [-r]        list a card folder (default /DCIM); -r includes subfolders
  em10 cam get PATH [DEST]      download one file, e.g. /DCIM/100OLYMP/PB140001.JPG
  em10 cam thumb PATH [DEST]    download a picture's thumbnail
  em10 cam props                remote-control properties with value and allowed values (rec mode)
  em10 cam lens                 lens attached, power zoom (rec mode)

Changing the camera's state:
  em10 cam mode play|rec|shutter [--lv 0320x0240|0640x0480]
  em10 cam set NAME VALUE       set a property (rec mode)
  em10 cam shoot [--save DIR] [--raw] [--no-full] [--focus-wait MS]
                                take one picture; --save finds it on the card and saves the thumbnail,
                                then the full JPEG (about 28 s over Wi-Fi), with --raw also the ORF

Option: --host ADDRESS (default 192.168.0.10)
"""

/** `em10 cam ...`; [args] starts after "cam". */
fun cameraCommand(args: List<String>) {
    val rest = args.toMutableList()
    val host = option(rest, "--host") ?: WifiCamera.DEFAULT_HOST
    val camera = WifiCamera(JvmCameraHttp(host), pause = { Thread.sleep(it) })
    val cmd = rest.removeFirstOrNull() ?: usage(CAMERA_USAGE)
    when (cmd) {
        "info" -> info(camera)
        "ls" -> {
            val recursive = rest.remove("-r")
            val dir = rest.firstOrNull() ?: "/DCIM"
            for (e in if (recursive) camera.walk(dir) else camera.listDir(dir)) {
                println("${e.timestamp} ${(if (e.isDirectory) "<dir>" else e.size.toString()).padStart(10)}  ${e.path}")
            }
        }
        "get" -> {
            val path = rest.getOrNull(0) ?: usage(CAMERA_USAGE)
            val dest = File(rest.getOrNull(1) ?: path.substringAfterLast('/'))
            dest.absoluteFile.parentFile?.mkdirs()
            val bytes = dest.outputStream().buffered().use { out ->
                camera.download(path, progressSink(dest.name) { b, n -> out.write(b, 0, n) })
            }
            System.err.println()
            println("saved ${dest.path} ($bytes bytes)")
        }
        "thumb" -> {
            val path = rest.getOrNull(0) ?: usage(CAMERA_USAGE)
            val dest = File(rest.getOrNull(1) ?: "thumb_" + path.substringAfterLast('/').substringBeforeLast('.') + ".jpg")
            dest.writeBytes(camera.thumbnail(path))
            println("saved ${dest.path} (${dest.length()} bytes)")
        }
        "props" -> for (p in camera.properties()) {
            println("${p.name.padEnd(18)} ${p.attribute.padEnd(6)} ${p.value.padEnd(22)} ${p.allowed.joinToString(" ")}")
        }
        "lens" -> camera.lensState().let { println("lens attached: ${it.attached}, power zoom: ${it.powerZoom}") }
        "mode" -> {
            val lv = option(rest, "--lv")?.let { v -> WifiCamera.LiveViewSize.entries.firstOrNull { it.wire == v } ?: usage(CAMERA_USAGE) }
            val mode = WifiCamera.Mode.entries.firstOrNull { it.wire == rest.firstOrNull() } ?: usage(CAMERA_USAGE)
            camera.setMode(mode, lv)
            println("camera mode set to ${mode.wire}")
        }
        "set" -> {
            if (rest.size != 2) usage(CAMERA_USAGE)
            println("${rest[0]} set to ${rest[1]}; now reads ${camera.setProperty(rest[0], rest[1])}")
        }
        "shoot" -> shoot(camera, rest)
        else -> usage(CAMERA_USAGE)
    }
}

private fun info(camera: WifiCamera) {
    println("connect mode : ${camera.connectMode()}")
    println("model        : ${camera.model()}")
    println("free space   : " + runCatching { camera.freeBytes() }.fold(
        { it?.let { b -> "%.1f MB".format(b / 1e6) } ?: "4 GB or more (the camera's counter stops at 4294967295 bytes)" },
        { modeRestricted(it) }))
    println("pictures     : " + runCatching { camera.pictureCount() }.fold({ "$it" }, { modeRestricted(it) }))
    val commands = camera.commandList()
    println("api version  : ${commands.version}")
    println("commands     : ${commands.commands.sorted().joinToString(" ")}")
}

/** Free space and picture count come only in play mode; soften the wrong-mode refusal (error 1001). */
private fun modeRestricted(e: Throwable): String =
    if (e is CameraException && e.wrongMode) "play mode only (the camera is not in play mode)"
    else "(${e.message})"

private fun shoot(camera: WifiCamera, rest: MutableList<String>) {
    val focusWait = option(rest, "--focus-wait")?.toLongOrNull() ?: 2000
    val saveDir = option(rest, "--save")
    if (saveDir == null) {
        camera.shoot(focusWait)
        println("picture taken; the camera is back in rec mode")
        return
    }
    val options = Tethering.Options(fullJpeg = !rest.remove("--no-full"), raw = rest.remove("--raw"), focusWaitMs = focusWait)
    var progressShown = false
    Tethering(camera) { Thread.sleep(it) }.shootAndSave(FolderPictureStore(File(saveDir)), options) { e ->
        when (e) {
            is Tethering.Event.NewFiles -> e.files.forEach { println("new on card: ${it.path} (${it.size} bytes)") }
            is Tethering.Event.Progress -> if (e.total > 100_000 || e.received > 100_000) {
                System.err.print("\r  ${e.name}: ${e.received / 1024}${if (e.total > 0) " of ${e.total / 1024}" else ""} KB ")
                progressShown = true
            }
            is Tethering.Event.Saved -> {
                if (progressShown) System.err.println()
                progressShown = false
                println("saved ${File(saveDir, e.name).path} (${e.bytes} bytes)")
            }
            is Tethering.Event.Note -> println("note: ${e.text}")
        }
    }
    println("done; the camera is in rec mode")
}

private fun progressSink(label: String, write: (ByteArray, Int) -> Unit): ChunkSink {
    val start = System.nanoTime()
    return ChunkSink { buffer, length, received, total ->
        write(buffer, length)
        val rate = received / 1024.0 / ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.001)
        System.err.print("\r  $label: ${received / 1024}${if (total > 0) " of ${total / 1024}" else ""} KB, %.0f KB/s ".format(rate))
    }
}

/** Remove `NAME VALUE` from [args] and return VALUE. */
private fun option(args: MutableList<String>, name: String): String? {
    val i = args.indexOf(name)
    if (i < 0) return null
    if (i + 1 >= args.size) usage(CAMERA_USAGE)
    args.removeAt(i)
    return args.removeAt(i)
}
