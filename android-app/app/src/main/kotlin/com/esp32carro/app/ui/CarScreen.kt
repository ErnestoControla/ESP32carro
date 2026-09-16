package com.esp32carro.app.ui

import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.esp32carro.app.network.*
import com.esp32carro.app.ui.components.AnalogStick
import com.esp32carro.app.ui.components.SpeedometerGauge
import com.esp32carro.app.ui.components.SteeringWheel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

// El WebView puede dejar de repintar el stream MJPEG sin avisar (sin
// onReceivedError, sin ningun evento observable) aunque el ESP32 le siga
// mandando frames sin fallar - confirmado con logs del firmware (ver
// docs/notas-tecnicas.md seccion 6.2). No hay forma confiable de detectar
// "dejo de actualizarse" desde Compose/WebView para un stream infinito, asi
// que en vez de eso se recarga el WebView periodicamente como watchdog: si
// esta colgado, esto lo autorepara en como mucho este intervalo.
private const val VIDEO_RELOAD_INTERVAL_MS = 20_000L

@Composable
fun CarScreen() {
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

    // Watchdog de video: recarga el WebView cada VIDEO_RELOAD_INTERVAL_MS
    // mientras haya conexion, para autorepararse si el stream se colgo en
    // silencio (ver comentario junto a la constante).
    LaunchedEffect(network) {
        if (network == null) return@LaunchedEffect
        while (isActive) {
            delay(VIDEO_RELOAD_INTERVAL_MS)
            webViewRef.value?.reload()
        }
    }

    val isConnected = connection.status == CarConnection.CONECTADO

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Video Center (Windshield)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 100.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF333333), RoundedCornerShape(24.dp)) // Added border for visibility
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewRef.value = this
                        if (network != null) loadUrl("http://$CAR_HOST/")
                    }
                }
            )
            
            if (!isConnected) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (connection.status) {
                            CarConnection.BUSCANDO -> "Buscando red ESP32CAR..."
                            CarConnection.DESCONECTADO -> "Sin conexion con el carro"
                            else -> ""
                        },
                        color = Color.White
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text("Abrir ajustes WiFi")
                    }
                }
            }
        }

        // Left Stick (Direction) -> Replaced by SteeringWheel
        SteeringWheel(
            value = steer,
            onValueChange = { steer = it },
            valueRange = 0f..180f,
            centerValue = SERVO_CENTER.toFloat(),
            enabled = isConnected,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
        )

        // Right Stick (Throttle)
        AnalogStick(
            label = "Velocidad",
            value = throttle,
            onValueChange = { throttle = it },
            valueRange = -255f..255f,
            centerValue = 0f,
            enabled = isConnected,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
        )

        // Speedometer HUD
        SpeedometerGauge(
            speedPercent = (abs(throttle) / 255f) * 100f,
            isReverse = throttle < 0,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
        
        // Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isConnected) "SYSTEM ONLINE" else "SYSTEM OFFLINE",
                color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                style = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )
        }
    }
}
