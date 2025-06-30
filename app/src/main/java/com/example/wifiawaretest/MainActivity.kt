
package com.example.wifiawaretest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wifiawaretest.ui.theme.WifiawaretestTheme
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

const val AWARE_SERVICE_NAME = "foobar"
private const val TAG = "WifiAwareActivity"
private const val AWARE_SOCKET_PORT = 8988
private const val PSK_PASSPHRASE = "someReallyStrongPassword"
private const val MESSAGE_TYPE_IP_ADDRESS = "IP_ADDR:"
private const val MESSAGE_HELLO_SUBSCRIBER = "Hello Publisher! I want to connect."

class MainActivity : ComponentActivity() {

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentStatus by mutableStateOf("App Started")

    private lateinit var connectivityManager: ConnectivityManager
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var publisherClientSocket: Socket? = null // For publisher to talk to connected client

    private var peerHandleForIpResponse: PeerHandle? = null

    private val permissionsToRequest = mutableListOf<String>()

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach { (permission, isGranted) ->
            if (!isGranted) {
                allGranted = false
                Log.d(TAG, "$permission permission denied")
            }
        }

        if (allGranted) {
            Log.d(TAG, "All required permissions granted")
            currentStatus = "All permissions granted, try action again."
        } else {
            Log.d(TAG, "One or more permissions were denied")
            currentStatus = "Permissions denied. Wifi Aware needs these permissions."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
                currentStatus = "Wi-Fi Aware feature not available on this device."
                Log.e(TAG, currentStatus)
                // wifiAwareManager remains null
            } else {
                wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                if (wifiAwareManager == null) {
                    currentStatus = "Failed to get WifiAwareManager service."
                    Log.e(TAG, currentStatus)
                } else {
                    currentStatus = "WifiAwareManager obtained. Ready to attach."
                    Log.i(TAG, currentStatus)
                }
            }
        } else {
            currentStatus = "Wi-Fi Aware requires Android 8.0 (Oreo) or higher."
            Log.e(TAG, currentStatus)
            // wifiAwareManager remains null
        }

        setContent {
            WifiawaretestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WifiAwareScreen(
                        status = currentStatus,
                        onAttach = { attachToWifiAware() },
                        onPublish = { publishService() },
                        onSubscribe = { subscribeToService() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        permissionsToRequest.clear()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    "android.permission.NEARBY_WIFI_DEVICES"
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add("android.permission.NEARBY_WIFI_DEVICES")
            }
        }
        if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.INTERNET
            ) != PackageManager.PERMISSION_GRANTED
        ){
            permissionsToRequest.add(Manifest.permission.INTERNET)
        }
         if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_NETWORK_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ){
            permissionsToRequest.add(Manifest.permission.ACCESS_NETWORK_STATE)
        }

        return if (permissionsToRequest.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            false
        } else {
            true
        }
    }

    private val attachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            Log.d(TAG, "onAttached")
            currentStatus = "Wifi Aware Attached"
            wifiAwareSession = session
        }

        override fun onAttachFailed() {
            Log.e(TAG, "onAttachFailed")
            currentStatus = "Wifi Aware Attach Failed"
            wifiAwareSession = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachToWifiAware() {
        if (wifiAwareManager == null) {
            currentStatus = "WifiAwareManager not initialized or feature not supported."
            Log.e(TAG, currentStatus)
            return
        }
        if (!wifiAwareManager!!.isAvailable) {
            currentStatus = "Wi-Fi Aware not available on this device (e.g., Wi-Fi off or Airplane Mode)."
            Log.e(TAG, currentStatus)
            return
        }
        if (!checkAndRequestPermissions()) {
            currentStatus = "Permissions needed for Wifi Aware."
            return
        }
        Log.d(TAG, "Attaching to Wifi Aware service...")
        currentStatus = "Attaching to Wifi Aware..."
        wifiAwareManager!!.attach(attachCallback, mainHandler)
    }

    @SuppressLint("MissingPermission")
    private fun getAwareIpAddress(network: Network): String? {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        var ipv6Address: String? = null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet6Address) {
                if (!address.isLinkLocalAddress) {
                    return address.hostAddress?.split("%")?.get(0)
                }
                if (ipv6Address == null) { 
                    ipv6Address = address.hostAddress?.split("%")?.get(0)
                }
            }
        }
        return ipv6Address
    }

    @SuppressLint("MissingPermission")
    fun publishService() {
        if (wifiAwareSession == null) {
            currentStatus = "Wifi Aware session not available. Attach first."
            return
        }
        if (!checkAndRequestPermissions()) {
            currentStatus = "Permissions needed to publish."
            return
        }

        val config = PublishConfig.Builder()
            .setServiceName(AWARE_SERVICE_NAME)
            .build()

        currentStatus = "Publishing service: $AWARE_SERVICE_NAME"
        wifiAwareSession!!.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d(TAG, "Publish started")
                currentStatus = "Service Published: $AWARE_SERVICE_NAME"
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val messageStr = message.toString(StandardCharsets.UTF_8)
                Log.d(TAG, "Publisher received message: '$messageStr' from $peerHandle")
                currentStatus = "Publisher: Msg from $peerHandle - $messageStr"

                if (messageStr == MESSAGE_HELLO_SUBSCRIBER) {
                    peerHandleForIpResponse = peerHandle 
                    currentStatus = "Publisher: Received hello, preparing data path with $peerHandle"
                    Log.d(TAG, "Publisher: Setting up network for $peerHandle")

                    val networkRequestBuilder = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    
                    val awareNetworkSpecifier = WifiAwareNetworkSpecifier.Builder(publishDiscoverySession!!, peerHandle)
                        .setPskPassphrase(PSK_PASSPHRASE)
                        .build()
                    networkRequestBuilder.setNetworkSpecifier(awareNetworkSpecifier)
                    
                    val networkRequest = networkRequestBuilder.build()

                    connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
                        @SuppressLint("MissingPermission")
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.d(TAG, "Publisher: Network available for $peerHandle: $network")
                            currentStatus = "Publisher: Network available for $peerHandle"

                            val publisherIp = getAwareIpAddress(network)
                            if (publisherIp != null) {
                                Log.d(TAG, "Publisher: Found IP $publisherIp for network $network")
                                val ipMessage = "$MESSAGE_TYPE_IP_ADDRESS$publisherIp"
                                peerHandleForIpResponse?.let {
                                    publishDiscoverySession?.sendMessage(it, 0, ipMessage.toByteArray(StandardCharsets.UTF_8))
                                    currentStatus = "Publisher: Sent IP $publisherIp to $it"
                                    Log.i(TAG, "Publisher: Sent IP address $publisherIp to $it")
                                } ?: run {
                                    Log.e(TAG, "Publisher: peerHandleForIpResponse was null, cannot send IP.")
                                    mainHandler.post { currentStatus = "Publisher: Error, peerHandle was null." }
                                }

                                thread {
                                    try {
                                        serverSocket?.close() // Close previous if any
                                        serverSocket = ServerSocket()
                                        serverSocket!!.bind(InetSocketAddress(publisherIp, AWARE_SOCKET_PORT))
                                        Log.i(TAG, "Publisher: ServerSocket bound to $publisherIp:$AWARE_SOCKET_PORT")
                                        mainHandler.post { currentStatus = "Publisher: ServerSocket listening on $publisherIp:$AWARE_SOCKET_PORT" }

                                        publisherClientSocket = serverSocket!!.accept() // Blocks until connection
                                        Log.i(TAG, "Publisher: Client connected: ${publisherClientSocket?.remoteSocketAddress}")
                                        mainHandler.post { currentStatus = "Publisher: Client connected from ${publisherClientSocket?.remoteSocketAddress}" }

                                        try {
                                            publisherClientSocket?.outputStream?.use { outputStream ->
                                                val testMessage = "Hello Subscriber! Connection successful."
                                                outputStream.write(testMessage.toByteArray(StandardCharsets.UTF_8))
                                                outputStream.flush()
                                                Log.i(TAG, "Publisher: Sent test message to client.")
                                                mainHandler.post { currentStatus = "Publisher: Sent test message." }
                                            }
                                        } catch (e: IOException) {
                                            Log.e(TAG, "Publisher: Error sending message", e)
                                            mainHandler.post { currentStatus = "Publisher: Error sending message: ${e.message}" }
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "Publisher: ServerSocket IOException", e)
                                        mainHandler.post { currentStatus = "Publisher: ServerSocket Error: ${e.message}" }
                                    } finally {
                                        // serverSocket?.close() // Don't close immediately, keep listening or close on session end
                                    }
                                }
                            } else {
                                Log.e(TAG, "Publisher: Could not obtain IP address for Aware network.")
                                mainHandler.post { currentStatus = "Publisher: Failed to get IP for data path" }
                            }
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.e(TAG, "Publisher: Network lost for $peerHandle: $network")
                            mainHandler.post { currentStatus = "Publisher: Network lost with $peerHandle" }
                            try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing serverSocket onLost", e) }
                            serverSocket = null
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            Log.e(TAG, "Publisher: Network request unavailable for $peerHandle")
                            mainHandler.post { currentStatus = "Publisher: Network unavailable for $peerHandle" }
                        }
                    })
                }
            }

            override fun onSessionTerminated() {
                Log.d(TAG, "Publish session terminated")
                currentStatus = "Publish session terminated"
                publishDiscoverySession = null
                try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing serverSocket onSessionTerminated", e) }
                serverSocket = null
                try { publisherClientSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing publisherClientSocket onSessionTerminated", e) }
                publisherClientSocket = null
            }
        }, mainHandler)
    }

    @SuppressLint("MissingPermission")
    fun subscribeToService() {
        if (wifiAwareSession == null) {
            currentStatus = "Wifi Aware session not available. Attach first."
            return
        }
        if (!checkAndRequestPermissions()) {
            currentStatus = "Permissions needed to subscribe."
            return
        }

        val config = SubscribeConfig.Builder()
            .setServiceName(AWARE_SERVICE_NAME)
            .build()

        currentStatus = "Subscribing to service: $AWARE_SERVICE_NAME"
        wifiAwareSession!!.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.d(TAG, "Subscribe started")
                currentStatus = "Subscription Started for: $AWARE_SERVICE_NAME"
                subscribeDiscoverySession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                Log.d(TAG, "Service discovered from peer: $peerHandle")
                currentStatus = "Service Discovered: $AWARE_SERVICE_NAME from $peerHandle"

                subscribeDiscoverySession?.sendMessage(
                    peerHandle,
                    0, // Message ID, 0 if not used
                    MESSAGE_HELLO_SUBSCRIBER.toByteArray(StandardCharsets.UTF_8)
                )
                currentStatus = "Subscriber: Sent hello to $peerHandle"
                Log.i(TAG, "Subscriber: Sent '$MESSAGE_HELLO_SUBSCRIBER' to $peerHandle")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val messageStr = message.toString(StandardCharsets.UTF_8)
                Log.d(TAG, "Subscriber received message: '$messageStr' from $peerHandle")
                currentStatus = "Subscriber: Msg from $peerHandle - $messageStr"

                if (messageStr.startsWith(MESSAGE_TYPE_IP_ADDRESS)) {
                    val publisherIp = messageStr.substring(MESSAGE_TYPE_IP_ADDRESS.length)
                    Log.i(TAG, "Subscriber: Received IP address $publisherIp from $peerHandle")
                    currentStatus = "Subscriber: Got IP $publisherIp from $peerHandle. Connecting..."

                    val networkRequestBuilder = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)

                    val awareNetworkSpecifier = WifiAwareNetworkSpecifier.Builder(subscribeDiscoverySession!!, peerHandle)
                        .setPskPassphrase(PSK_PASSPHRASE)
                        .build()
                    networkRequestBuilder.setNetworkSpecifier(awareNetworkSpecifier)
                    
                    val networkRequest = networkRequestBuilder.build()

                    connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.d(TAG, "Subscriber: Network available for $peerHandle: $network")
                            currentStatus = "Subscriber: Network available with $peerHandle"
                            thread {
                                try {
                                    clientSocket?.close() // Close previous if any
                                    clientSocket = network.socketFactory.createSocket() // Use network specific factory
                                    clientSocket!!.connect(InetSocketAddress(publisherIp, AWARE_SOCKET_PORT), 5000) // 5s timeout
                                    Log.i(TAG, "Subscriber: Socket connected to publisher $publisherIp:$AWARE_SOCKET_PORT")
                                    mainHandler.post { currentStatus = "Subscriber: Connected to $publisherIp" }

                                    try {
                                        clientSocket?.inputStream?.use { inputStream ->
                                            val buffer = ByteArray(1024)
                                            val bytesRead = inputStream.read(buffer)
                                            if (bytesRead > 0) {
                                                val receivedMessage = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                                                Log.i(TAG, "Subscriber: Received message: '$receivedMessage'")
                                                mainHandler.post { currentStatus = "Subscriber: Received: '$receivedMessage'" }
                                            } else {
                                                Log.i(TAG, "Subscriber: No data received or stream closed.")
                                                mainHandler.post { currentStatus = "Subscriber: No data from publisher." }
                                            }
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "Subscriber: Error receiving message", e)
                                        mainHandler.post { currentStatus = "Subscriber: Error receiving: ${e.message}" }
                                    }
                                } catch (e: IOException) {
                                    Log.e(TAG, "Subscriber: Socket connection IOException", e)
                                    mainHandler.post { currentStatus = "Subscriber: Connection Error: ${e.message}" }
                                } catch (e: IllegalArgumentException) {
                                     Log.e(TAG, "Subscriber: Socket connection IllegalArgumentException (invalid IP/Port?)", e)
                                    mainHandler.post { currentStatus = "Subscriber: Connection Arg Error: ${e.message}" }
                                }
                            }
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.e(TAG, "Subscriber: Network lost for $peerHandle: $network")
                            mainHandler.post { currentStatus = "Subscriber: Network lost with $peerHandle" }
                            try { clientSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing clientSocket onLost", e) }
                            clientSocket = null
                        }
                        override fun onUnavailable() {
                            super.onUnavailable()
                            Log.e(TAG, "Subscriber: Network request unavailable for $peerHandle")
                            mainHandler.post { currentStatus = "Subscriber: Network unavailable for $peerHandle" }
                        }
                    })
                }
            }

            override fun onSessionTerminated() {
                Log.d(TAG, "Subscribe session terminated")
                currentStatus = "Subscribe session terminated"
                subscribeDiscoverySession = null
                try { clientSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing clientSocket onSessionTerminated", e) }
                clientSocket = null
            }
        }, mainHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        wifiAwareSession?.close()
        try {
            serverSocket?.close()
            clientSocket?.close()
            publisherClientSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing sockets in onDestroy", e)
        }
    }
}

@Composable
fun WifiAwareScreen(
    status: String,
    onAttach: () -> Unit,
    onPublish: () -> Unit,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Wi-Fi Aware Status: $status")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAttach) {
            Text("Attach to Wi-Fi Aware")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onPublish) {
            Text("Publish Service")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onSubscribe) {
            Text("Subscribe to Service")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiAwareScreenPreview() {
    WifiawaretestTheme {
        WifiAwareScreen(
            status = "Preview Status: Ready",
            onAttach = { },
            onPublish = { },
            onSubscribe = { }
        )
    }
}
