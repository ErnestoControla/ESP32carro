package com.esp32carro.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// La ESP32-CAM crea su propia red WiFi sin internet. Android no deja que
// apps sin bind explicito (Chrome normal, o el proceso separado que usa
// WebView) enruten trafico por una red sin "internet validado" aunque la
// tabla de rutas se vea correcta — por eso usamos network.openConnection()
// en cada peticion en vez de confiar en bindProcessToNetwork o en un
// WebView, que corre en su propio proceso y no hereda ese bind.
private const val CAR_HOST = "192.168.4.1"
private const val CONTROL_SEND_INTERVAL_MS = 150L
private const val SERVO_CENTER = 90

private enum class CarConnection { BUSCANDO, CONECTADO, DESCONECTADO }

private data class CarConnectionState(
    val status: CarConnection,
    val network: Network? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarScreen()
                }
            }
        }
    }
}

@Composable
private fun rememberCarConnection(): CarConnectionState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(CarConnectionState(CarConnection.BUSCANDO)) }

    DisposableEffect(Unit) {
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)

        // Esta app solo sirve para controlar el carro, asi que asumimos que
        // cualquier WiFi activa es la del ESP32CAR.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                state = CarConnectionState(CarConnection.CONECTADO, network)
            }

            override fun onLost(network: Network) {
                state = CarConnectionState(CarConnection.DESCONECTADO)
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    return state
}

private suspend fun sendControl(network: Network, steer: Int, throttle: Int) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$CAR_HOST/control?steer=$steer&throttle=$throttle")
            val connection = network.openConnection(url) as HttpURLConnection
            connection.connectTimeout = 300
            connection.readTimeout = 300
            connection.requestMethod = "GET"
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("ESP32Carro", "sendControl fallo: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    var readAny = false
    while (true) {
        val b = input.read()
        if (b == -1) return if (readAny) sb.toString() else null
        readAny = true
        if (b == '\n'.code) break
        if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
}

// Parsea a mano el multipart/x-mixed-replace que manda el firmware
// (ver STREAM_BOUNDARY/STREAM_PART en firmware/src/main.cpp): cada parte
// trae "Content-Length" exacto, asi que no hace falta buscar el boundary
// byte a byte, solo saltar las lineas de cabecera hasta la linea en blanco.
private suspend fun streamMjpeg(network: Network, onFrame: (Bitmap) -> Unit) {
    withContext(Dispatchers.IO) {
        val url = URL("http://$CAR_HOST:81/stream")
        val connection = network.openConnection(url) as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        try {
            val input = BufferedInputStream(connection.inputStream)
            while (isActive) {
                var line = readLine(input) ?: break
                while (line.isBlank() || line.startsWith("--")) {
                    line = readLine(input) ?: return@withContext
                }
                var contentLength = -1
                while (line.isNotBlank()) {
                    if (line.startsWith("Content-Length", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    line = readLine(input) ?: return@withContext
                }
                if (contentLength <= 0) break

                val jpeg = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(jpeg, read, contentLength - read)
                    if (n == -1) return@withContext
                    read += n
                }
                BitmapFactory.decodeByteArray(jpeg, 0, contentLength)?.let(onFrame)
            }
        } catch (e: Exception) {
            Log.e("ESP32Carro", "streamMjpeg fallo: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
private fun CarScreen() {
    val context = LocalContext.current
    val connection = rememberCarConnection()

    var steer by remember { mutableFloatStateOf(SERVO_CENTER.toFloat()) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }

    val network = connection.network

    LaunchedEffect(network) {
        if (network == null) return@LaunchedEffect
        while (isActive) {
            sendControl(network, steer.toInt(), throttle.toInt())
            delay(CONTROL_SEND_INTERVAL_MS)
        }
    }

    LaunchedEffect(network) {
        if (network == null) {
            frame = null
            return@LaunchedEffect
        }
        while (isActive) {
            streamMjpeg(network) { frame = it }
            delay(500) // reintento si la conexion del stream se cae
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = when (connection.status) {
                CarConnection.BUSCANDO -> "Buscando red ESP32CAR..."
                CarConnection.CONECTADO -> "Conectado a ESP32CAR"
                CarConnection.DESCONECTADO -> "Sin conexion con el carro"
            }
        )

        if (connection.status != CarConnection.CONECTADO) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }) {
                Text("Abrir ajustes WiFi")
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Video del carro",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text("Direccion: ${steer.toInt()}")
        Slider(
            value = steer,
            onValueChange = { steer = it },
            valueRange = 0f..180f,
            enabled = connection.status == CarConnection.CONECTADO
        )

        Text("Velocidad: ${throttle.toInt()}")
        Slider(
            value = throttle,
            onValueChange = { throttle = it },
            onValueChangeFinished = { throttle = 0f },
            valueRange = -255f..255f,
            enabled = connection.status == CarConnection.CONECTADO
        )
    }
}
