package com.esp32carro.app.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val CAR_HOST = "192.168.4.1"
const val CONTROL_SEND_INTERVAL_MS = 200L
const val SERVO_CENTER = 90

enum class CarConnection { BUSCANDO, CONECTADO, DESCONECTADO }

data class CarConnectionState(
    val status: CarConnection,
    val network: Network? = null
)

@Composable
fun rememberCarConnection(): CarConnectionState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(CarConnectionState(CarConnection.BUSCANDO)) }

    DisposableEffect(Unit) {
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
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

suspend fun sendControl(network: Network, steer: Int, throttle: Int) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$CAR_HOST/control?steer=$steer&throttle=$throttle")
            val connection = network.openConnection(url) as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("ESP32Carro", "sendControl fallo: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
