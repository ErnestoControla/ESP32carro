package com.esp32carro.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// La ESP32-CAM crea su propia red WiFi sin internet. Sin bindProcessToNetwork,
// Android puede enrutar nuestras peticiones por datos moviles u otra red en
// vez de por esta, aunque el celular "se vea" conectado al AP del carro.
private const val CAR_BASE_URL = "http://192.168.4.1"
private const val CONTROL_SEND_INTERVAL_MS = 150L
private const val SERVO_CENTER = 90

private enum class CarConnection { BUSCANDO, CONECTADO, DESCONECTADO }

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
private fun rememberCarConnection(): CarConnection {
    val context = LocalContext.current
    var state by remember { mutableStateOf(CarConnection.BUSCANDO) }

    DisposableEffect(Unit) {
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)

        // Esta app solo sirve para controlar el carro, asi que asumimos que
        // cualquier WiFi activa es la del ESP32CAR y fijamos todo el trafico
        // del proceso a esa red. NET_CAPABILITY_INTERNET no sirve para
        // distinguirla: Android la marca en casi cualquier WiFi por defecto
        // (es una declaracion, no una confirmacion) — la señal real de
        // "esta red sí tiene internet" seria NET_CAPABILITY_VALIDATED, que
        // esta red nunca va a tener, pero no hace falta revisarlo: si nos
        // equivocamos de red, las llamadas HTTP simplemente fallan por timeout.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                state = CarConnection.CONECTADO
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
                state = CarConnection.DESCONECTADO
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    return state
}

private suspend fun sendControl(steer: Int, throttle: Int) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("$CAR_BASE_URL/control?steer=$steer&throttle=$throttle")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 300
            connection.readTimeout = 300
            connection.requestMethod = "GET"
            connection.responseCode
            connection.disconnect()
        } catch (_: Exception) {
            // Se ignora: si un comando se pierde, el siguiente tick lo reintenta.
            // El watchdog del firmware se encarga de frenar si esto se prolonga.
        }
    }
}

@Composable
private fun CarScreen() {
    val context = LocalContext.current
    val connection = rememberCarConnection()

    var steer by remember { mutableFloatStateOf(SERVO_CENTER.toFloat()) }
    var throttle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(connection) {
        while (connection == CarConnection.CONECTADO) {
            sendControl(steer.toInt(), throttle.toInt())
            delay(CONTROL_SEND_INTERVAL_MS)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = when (connection) {
                CarConnection.BUSCANDO -> "Buscando red ESP32CAR..."
                CarConnection.CONECTADO -> "Conectado a ESP32CAR"
                CarConnection.DESCONECTADO -> "Sin conexion con el carro"
            }
        )

        if (connection != CarConnection.CONECTADO) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }) {
                Text("Abrir ajustes WiFi")
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = false
                }
            },
            update = { webView ->
                if (connection == CarConnection.CONECTADO && webView.url == null) {
                    webView.loadUrl("$CAR_BASE_URL/")
                }
            }
        )

        Text("Direccion: ${steer.toInt()}")
        Slider(
            value = steer,
            onValueChange = { steer = it },
            valueRange = 0f..180f,
            enabled = connection == CarConnection.CONECTADO
        )

        Text("Velocidad: ${throttle.toInt()}")
        Slider(
            value = throttle,
            onValueChange = { throttle = it },
            onValueChangeFinished = { throttle = 0f },
            valueRange = -255f..255f,
            enabled = connection == CarConnection.CONECTADO
        )
    }
}
