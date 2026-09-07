package com.esp32carro.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// La ESP32-CAM crea su propia red WiFi sin internet. En uso normal el
// celular esta conectado SOLO a esa red (no hay otra WiFi ni datos moviles
// compitiendo), asi que Android enruta el trafico de cualquier proceso —
// incluido el proceso separado que usa WebView — por ahi sin necesidad de
// amarrarlo explicitamente. bindProcessToNetwork() se deja como respaldo,
// y network.openConnection() se usa para /control porque es mas directo
// y no depende de cual sea la red "por defecto" del sistema en cada momento.
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
                // Amarra el proceso completo a esta red. En uso real el
                // celular no tiene otra red compitiendo, asi que esto es
                // mas que nada un respaldo para el caso normal.
                connectivityManager.bindProcessToNetwork(network)
                state = CarConnectionState(CarConnection.CONECTADO, network)
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
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

// Palanca analogica vertical al estilo de un control fisico: se arrastra
// hacia arriba/abajo y, al soltar, regresa sola al centro (centerValue).
// Se usa tanto para direccion como para velocidad porque en esta app las
// dos deben "auto-centrarse" por seguridad, a diferencia de un volante real.
@Composable
private fun AnalogStick(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    centerValue: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val trackHeight = 220.dp
    val knobSize = 56.dp
    val density = LocalDensity.current
    val travelPx = remember(density) {
        with(density) { (trackHeight - knobSize).toPx() }
    }

    fun valueToOffset(v: Float): Float {
        val fraction = (v - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        return (1f - fraction) * travelPx
    }

    fun offsetToValue(offset: Float): Float {
        val fraction = 1f - (offset / travelPx)
        return valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
    }

    var offsetPx by remember { mutableFloatStateOf(valueToOffset(centerValue)) }
    val scope = rememberCoroutineScope()

    fun springBackToCenter() {
        scope.launch {
            val start = offsetPx
            val end = valueToOffset(centerValue)
            animate(start, end) { current, _ ->
                offsetPx = current
                onValueChange(offsetToValue(current))
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label)
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(trackHeight)
                .background(Color(0xFF2A2A2A), RoundedCornerShape(32.dp))
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { springBackToCenter() },
                                onDragCancel = { springBackToCenter() }
                            ) { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(0f, travelPx)
                                onValueChange(offsetToValue(offsetPx))
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, offsetPx.roundToInt()) }
                    .size(knobSize)
                    .background(Color(0xFF4CAF50), CircleShape)
            )
        }
        Text("${value.toInt()}")
    }
}

@Composable
private fun CarScreen() {
    val context = LocalContext.current
    val connection = rememberCarConnection()

    var steer by remember { mutableFloatStateOf(SERVO_CENTER.toFloat()) }
    var throttle by remember { mutableFloatStateOf(0f) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val network = connection.network

    LaunchedEffect(network) {
        if (network == null) return@LaunchedEffect
        while (isActive) {
            sendControl(network, steer.toInt(), throttle.toInt())
            delay(CONTROL_SEND_INTERVAL_MS)
        }
    }

    LaunchedEffect(network) {
        if (network != null) {
            webViewRef.value?.loadUrl("http://$CAR_HOST/")
        }
    }

    val isConnected = connection.status == CarConnection.CONECTADO

    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnalogStick(
            label = "Direccion",
            value = steer,
            onValueChange = { steer = it },
            valueRange = 0f..180f,
            centerValue = SERVO_CENTER.toFloat(),
            enabled = isConnected
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (connection.status) {
                    CarConnection.BUSCANDO -> "Buscando red ESP32CAR..."
                    CarConnection.CONECTADO -> "Conectado a ESP32CAR"
                    CarConnection.DESCONECTADO -> "Sin conexion con el carro"
                }
            )

            if (!isConnected) {
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
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                Log.e(
                                    "ESP32Carro",
                                    "WebView error url=${request.url} code=${error.errorCode} desc=${error.description}"
                                )
                            }
                        }
                        settings.javaScriptEnabled = false
                        webViewRef.value = this
                        if (network != null) loadUrl("http://$CAR_HOST/")
                    }
                }
            )
        }

        AnalogStick(
            label = "Velocidad",
            value = throttle,
            onValueChange = { throttle = it },
            valueRange = -255f..255f,
            centerValue = 0f,
            enabled = isConnected
        )
    }
}
