package em10.gui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import em10.camera.JvmCameraHttp
import em10.camera.JvmUdpSocket
import em10.camera.LiveView
import em10.camera.WifiCamera
import org.jetbrains.skia.Image as SkiaImage

/** Decode a JPEG frame to a Compose bitmap (Skia, bundled with Compose Desktop). */
private fun decode(jpeg: ByteArray): ImageBitmap = SkiaImage.makeFromEncoded(jpeg).toComposeImageBitmap()

/**
 * First desktop window for the E-M10 (issue A4): the live-view feed and the shutter, built on the
 * headless client in `em10.camera`. The camera's state changes (live view, shooting) run off the UI
 * thread; the window only paints frames and drives the buttons.
 */
@Composable
fun App() {
    var host by remember { mutableStateOf(WifiCamera.DEFAULT_HOST) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("not connected") }
    var live by remember { mutableStateOf<LiveView?>(null) }
    var busy by remember { mutableStateOf(false) }
    val camera = remember(host) { WifiCamera(JvmCameraHttp(host), pause = { Thread.sleep(it) }) }

    fun startLive() {
        if (live != null) return
        val lv = LiveView(camera, JvmUdpSocket)
        live = lv
        status = "starting live view…"
        Thread {
            runCatching {
                lv.run(WifiCamera.LiveViewSize.LARGE) { f ->
                    frame = decode(f.jpeg)
                    status = "live ${f.jpeg.size / 1024} KB"
                    live != null       // stop when the flag is cleared
                }
            }.onFailure { status = "live view error: ${it.message}" }
            live = null
        }.apply { isDaemon = true }.start()
    }

    fun stopLive() {
        live?.stop()
        live = null
        status = "live view stopped"
    }

    fun shoot() {
        if (busy) return
        stopLive()
        busy = true
        status = "shooting…"
        Thread {
            runCatching { camera.shoot(driveMode = "normal") }   // single shot, whatever the camera's drive mode
                .onSuccess { status = "picture taken (on the card)" }
                .onFailure { status = "shoot error: ${it.message}" }
            busy = false
        }.apply { isDaemon = true }.start()
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("camera host") }, modifier = Modifier.width(220.dp), singleLine = true)
                if (live == null) Button(::startLive, enabled = !busy) { Text("Start live view") }
                else Button(::stopLive) { Text("Stop") }
                Button(::shoot, enabled = !busy) { Text("Shoot") }
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF101010)), contentAlignment = Alignment.Center) {
                val f = frame
                if (f != null) Image(f, "live view", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(status, color = Color.White)
            }
            Text(status, color = MaterialTheme.colors.onSurface)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "em10 — Olympus OM-D E-M10") {
        App()
    }
}
