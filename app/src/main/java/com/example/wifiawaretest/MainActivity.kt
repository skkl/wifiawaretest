
package com.example.wifiawaretest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.lifecycle.lifecycleScope
import com.example.wifiawaretest.ui.theme.WifiawaretestTheme
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

const val AWARE_SERVICE_NAME = "foobar"
private const val TAG = "WifiAwareActivity"
private const val AWARE_SOCKET_PORT = 8988 // Make sure this is an Int
private const val PSK_PASSPHRASE = "someReallyStrongPassword"
private const val MESSAGE_TYPE_IP_ADDRESS = "IP_ADDR:"
private const val MESSAGE_HELLO_SUBSCRIBER = "Hello Publisher! I want to connect."

// Custom Exceptions

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
    private var publisherClientSocket: Socket? = null

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
            } else {
                wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                if (wifiAwareManager == null) {
                    currentStatus = "Failed to get WifiAwareManager service."
                    Log.e(TAG, currentStatus)
                } else {
                    currentStatus = "WifiAwareManager obtained. Ready."
                    Log.i(TAG, currentStatus)
                }
            }
        } else {
            currentStatus = "Wi-Fi Aware requires Android 8.0 (Oreo) or higher."
            Log.e(TAG, currentStatus)
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

        Log.d(TAG, "Attaching to Wifi Aware service (coroutine)...")
        currentStatus = "Attaching to Wifi Aware..."

        lifecycleScope.launch {
            try {
                val session = wifiAwareManager!!.attachSuspending(mainHandler)
                this@MainActivity.wifiAwareSession = session
                currentStatus = "Wifi Aware Attached (Coroutine)"
                Log.d(TAG, "onAttached (coroutine): $session")
            } catch (e: WifiAwareAttachFailedException) {
                Log.e(TAG, "onAttachFailed (coroutine)", e)
                currentStatus = "Wifi Aware Attach Failed (Coroutine)"
                this@MainActivity.wifiAwareSession = null
            } catch (e: Exception) {
                Log.e(TAG, "Exception during attach (coroutine)", e)
                currentStatus = "Attach Error: ${e.message}"
                this@MainActivity.wifiAwareSession = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getAwareIpAddress(network: Network): String? {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        var ipv6Address: String? = null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet6Address) {
                if (!address.isLinkLocalAddress) {
                    return address.hostAddress?.split("%")?.get(0) // Return first global IPv6
                }
                // Store the first link-local IPv6 if no global is found yet
                if (ipv6Address == null) { 
                    ipv6Address = address.hostAddress?.split("%")?.get(0)
                }
            }
        }
        return ipv6Address // Return link-local if no global found
    }

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

        currentStatus = "Publishing service (coroutine): $AWARE_SERVICE_NAME"
        Log.d(TAG, "Publishing service (coroutine)...")

        val discoveryCallback = object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                super.onPublishStarted(session)
                Log.d(TAG, "DiscoverySessionCallback: Publish started (from original callback): $session")
                this@MainActivity.publishDiscoverySession = session
                currentStatus = "Service Published (Coroutine): $AWARE_SERVICE_NAME"
            }

            override fun onSessionConfigFailed() {
                super.onSessionConfigFailed()
                Log.e(TAG, "DiscoverySessionCallback: Publish session config failed (from original callback)")
                currentStatus = "Publish Config Failed (Coroutine)"
                this@MainActivity.publishDiscoverySession = null
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val messageStr = message.toString(StandardCharsets.UTF_8)
                Log.d(TAG, "Publisher received message: '$messageStr' from $peerHandle")
                mainHandler.post { currentStatus = "Publisher: Msg from $peerHandle - $messageStr" }

                if (messageStr == MESSAGE_HELLO_SUBSCRIBER) {
                    peerHandleForIpResponse = peerHandle
                    Log.d(TAG, "Publisher: Received hello, preparing data path with $peerHandle")
                    mainHandler.post { currentStatus = "Publisher: Received hello from $peerHandle" }
                    setupNetworkRequestForPublisher(peerHandle)
                }
            }

            override fun onSessionTerminated() {
                super.onSessionTerminated()
                Log.d(TAG, "DiscoverySessionCallback: Publish session terminated (from original callback)")
                currentStatus = "Publish Session Terminated"
                this@MainActivity.publishDiscoverySession = null
                try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing server socket on publish termination", e)}
            }
        }

        lifecycleScope.launch {
            try {
                val session = wifiAwareSession!!.publishSuspending(config, mainHandler, discoveryCallback)
                Log.i(TAG, "Publish successful (coroutine), session: $session")
            } catch (e: WifiAwareSessionConfigFailedException) {
                Log.e(TAG, "Publish failed (coroutine)", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during publish (coroutine)", e)
                currentStatus = "Publish Error: ${e.message}"
                this@MainActivity.publishDiscoverySession = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupNetworkRequestForPublisher(peerHandle: PeerHandle) {
        if (publishDiscoverySession == null) {
            Log.e(TAG, "Publisher: Publish session is null, cannot request network.")
            currentStatus = "Publisher: Error - No publish session"
            return
        }

        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(publishDiscoverySession!!, peerHandle)
            .setPskPassphrase(PSK_PASSPHRASE)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        Log.i(TAG, "Publisher: Requesting network for data path to $peerHandle with specifier: $networkSpecifier")
        currentStatus = "Publisher: Requesting network..."

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "Publisher: Network available for data path: $network")
                mainHandler.post { currentStatus = "Publisher: Network Available!" }

                val publisherIp = getAwareIpAddress(network)
                if (publisherIp != null) {
                    Log.i(TAG, "Publisher: Got Aware IP: $publisherIp. Sending to subscriber.")
                    mainHandler.post { currentStatus = "Publisher: IP $publisherIp. Sending..." }

                    publishDiscoverySession?.sendMessage(
                        peerHandle, // Send to the specific peer that requested connection
                        0, // Message ID (0 for unsolicited)
                        (MESSAGE_TYPE_IP_ADDRESS + publisherIp).toByteArray(StandardCharsets.UTF_8)
                    )

                    thread {
                        try {
                            serverSocket = ServerSocket()
                            serverSocket!!.bind(InetSocketAddress(publisherIp, AWARE_SOCKET_PORT))
                            Log.i(TAG, "Publisher: ServerSocket bound to $publisherIp:$AWARE_SOCKET_PORT. Waiting for client...")
                            mainHandler.post { currentStatus = "Publisher: ServerSocket Ready. Waiting..." }

                            publisherClientSocket = serverSocket!!.accept() // Blocks until a client connects
                            Log.i(TAG, "Publisher: Client connected: ${publisherClientSocket?.inetAddress}")
                            mainHandler.post { currentStatus = "Publisher: Client Connected. Sending data." }
                            try {
                                publisherClientSocket?.outputStream?.use { outputStream ->
                                    val messageToSend = "Hello from Publisher!"
                                    outputStream.write(messageToSend.toByteArray(StandardCharsets.UTF_8))
                                    outputStream.flush()
                                    Log.i(TAG, "Publisher: Sent message: '$messageToSend'")
                                    mainHandler.post { currentStatus = "Publisher: Sent: '$messageToSend'" }
                                }
                            } catch (e: IOException) {
                                Log.e(TAG, "Publisher: Error sending data", e)
                                mainHandler.post { currentStatus = "Publisher: Error sending data: ${e.message}" }
                            } finally {
                                try {
                                    publisherClientSocket?.close()
                                    Log.i(TAG, "Publisher: Closed publisherClientSocket")
                                } catch (e: IOException) {
                                    Log.e(TAG, "Publisher: Error closing publisherClientSocket", e)
                                }
                                // Consider if serverSocket should be closed here or kept for more connections
                                // For this example, closing after one interaction to simplify.
                                // try {
                                //     serverSocket?.close()
                                //     Log.i(TAG, "Publisher: Closed serverSocket after client interaction")
                                // } catch (e: IOException) {
                                //     Log.e(TAG, "Publisher: Error closing serverSocket post-interaction", e)
                                // }
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Publisher: ServerSocket exception", e)
                            mainHandler.post { currentStatus = "Publisher: Server Error: ${e.message}" }
                        } finally {
                             // Ensure server socket is closed if it was opened and not part of the per-client handling above
                             if (serverSocket?.isBound == true && serverSocket?.isClosed == false && publisherClientSocket == null) {
                                try {
                                    serverSocket?.close()
                                    Log.i(TAG, "Publisher: ServerSocket closed in finally block.")
                                } catch (e: IOException) {
                                    Log.e(TAG, "Publisher: Error closing serverSocket in finally", e)
                                }
                             }
                        }
                    }
                } else {
                    Log.e(TAG, "Publisher: Could not get Aware IP address.")
                    mainHandler.post { currentStatus = "Publisher: Failed to get IP." }
                    connectivityManager.unregisterNetworkCallback(this) // Clean up if IP fails
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.e(TAG, "Publisher: Network lost for data path: $network")
                mainHandler.post { currentStatus = "Publisher: Network Lost." }
                try {
                    publisherClientSocket?.close()
                    serverSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Publisher: Error closing sockets on network lost", e)
                }
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e(TAG, "Publisher: Network unavailable for data path.")
                mainHandler.post { currentStatus = "Publisher: Network Unavailable." }
                 try {
                    publisherClientSocket?.close()
                    serverSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Publisher: Error closing sockets on network unavailable", e)
                }
                connectivityManager.unregisterNetworkCallback(this)
            }
             override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                Log.i(TAG, "Publisher: Network capabilities changed: $networkCapabilities")
            }
        }
        connectivityManager.requestNetwork(networkRequest, networkCallback)
    }


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

        currentStatus = "Subscribing to service (coroutine): $AWARE_SERVICE_NAME"
        Log.d(TAG, "Subscribing to service (coroutine)...")

        val discoveryCallback = object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                super.onSubscribeStarted(session)
                Log.d(TAG, "DiscoverySessionCallback: Subscribe started (from original callback): $session")
                this@MainActivity.subscribeDiscoverySession = session
                currentStatus = "Subscribed (Coroutine): $AWARE_SERVICE_NAME. Waiting for services."
            }

            override fun onSessionConfigFailed() {
                super.onSessionConfigFailed()
                Log.e(TAG, "DiscoverySessionCallback: Subscribe session config failed (from original callback)")
                currentStatus = "Subscribe Config Failed (Coroutine)"
                this@MainActivity.subscribeDiscoverySession = null
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                super.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter)
                Log.i(TAG, "Service discovered from $peerHandle! Sending hello message.")
                mainHandler.post { currentStatus = "Service Discovered from $peerHandle. Sending hello." }

                subscribeDiscoverySession?.sendMessage(
                    peerHandle,
                    0, 
                    MESSAGE_HELLO_SUBSCRIBER.toByteArray(StandardCharsets.UTF_8)
                )
                Log.i(TAG, "Subscriber: Sent '$MESSAGE_HELLO_SUBSCRIBER' to $peerHandle")
                mainHandler.post { currentStatus = "Subscriber: Sent hello to $peerHandle" }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                super.onMessageReceived(peerHandle, message)
                val messageStr = message.toString(StandardCharsets.UTF_8)
                Log.d(TAG, "Subscriber received message: '$messageStr' from $peerHandle")
                mainHandler.post { currentStatus = "Subscriber: Msg from $peerHandle - $messageStr" }

                if (messageStr.startsWith(MESSAGE_TYPE_IP_ADDRESS)) {
                    val publisherIp = messageStr.substring(MESSAGE_TYPE_IP_ADDRESS.length)
                    Log.i(TAG, "Subscriber: Received IP address $publisherIp from $peerHandle")
                    mainHandler.post { currentStatus = "Subscriber: Got IP $publisherIp from $peerHandle" }
                    setupNetworkRequestForSubscriber(peerHandle, publisherIp)
                }
            }

            override fun onSessionTerminated() {
                super.onSessionTerminated()
                Log.d(TAG, "DiscoverySessionCallback: Subscribe session terminated (from original callback)")
                currentStatus = "Subscribe Session Terminated"
                this@MainActivity.subscribeDiscoverySession = null
                try { clientSocket?.close() } catch (e: IOException) { Log.e(TAG, "Error closing client socket on subscribe termination", e)}
            }
        }

        lifecycleScope.launch {
            try {
                val session = wifiAwareSession!!.subscribeSuspending(config, mainHandler, discoveryCallback)
                Log.i(TAG, "Subscribe successful (coroutine), session: $session")
            } catch (e: WifiAwareSessionConfigFailedException) {
                Log.e(TAG, "Subscribe failed (coroutine)", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during subscribe (coroutine)", e)
                currentStatus = "Subscribe Error: ${e.message}"
                this@MainActivity.subscribeDiscoverySession = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupNetworkRequestForSubscriber(peerHandle: PeerHandle, publisherIp: String) {
        if (subscribeDiscoverySession == null) {
            Log.e(TAG, "Subscriber: Subscribe session is null, cannot request network.")
            currentStatus = "Subscriber: Error - No subscribe session"
            return
        }

        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(subscribeDiscoverySession!!, peerHandle)
            .setPskPassphrase(PSK_PASSPHRASE)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        Log.i(TAG, "Subscriber: Requesting network for data path to $peerHandle ($publisherIp) with specifier: $networkSpecifier")
        currentStatus = "Subscriber: Requesting network..."

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "Subscriber: Network available for data path: $network")
                mainHandler.post { currentStatus = "Subscriber: Network Available!" }

                thread {
                    try {
                        clientSocket = Socket() // Create a new socket instance
                        // IMPORTANT: Bind the socket to the Wi-Fi Aware network *before* connecting
                        network.bindSocket(clientSocket)

                        val socketAddress = InetSocketAddress(publisherIp, AWARE_SOCKET_PORT)
                        Log.i(TAG, "Subscriber: Connecting to $publisherIp:$AWARE_SOCKET_PORT")
                        clientSocket!!.connect(socketAddress, 5000) // 5 second timeout

                        Log.i(TAG, "Subscriber: Connected to publisher.")
                        mainHandler.post { currentStatus = "Subscriber: Connected. Receiving data." }
                        try {
                            clientSocket?.inputStream?.use { inputStream ->
                                val buffer = ByteArray(1024)
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead > 0) {
                                    val receivedMessage = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                                    Log.i(TAG, "Subscriber: Received message: '$receivedMessage'")
                                    mainHandler.post { currentStatus = "Subscriber: Received: '$receivedMessage'" }
                                } else {
                                    Log.w(TAG, "Subscriber: No data received.")
                                    mainHandler.post { currentStatus = "Subscriber: No data from publisher." }
                                }
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Subscriber: Error receiving data", e)
                            mainHandler.post { currentStatus = "Subscriber: Error receiving: ${e.message}" }
                        } finally {
                            try {
                                clientSocket?.close()
                                Log.i(TAG, "Subscriber: Closed clientSocket")
                            } catch (e: IOException) {
                                Log.e(TAG, "Subscriber: Error closing clientSocket", e)
                            }
                        }

                    } catch (e: SocketTimeoutException) {
                        Log.e(TAG, "Subscriber: Connection timed out", e)
                        mainHandler.post { currentStatus = "Subscriber: Connection Timeout" }
                    } catch (e: IOException) {
                        Log.e(TAG, "Subscriber: Could not connect to publisher", e)
                        mainHandler.post { currentStatus = "Subscriber: Connection Error: ${e.message}" }
                    } finally {
                        // Ensure client socket is closed if it was opened and not handled in the specific try-catch for read
                        if (clientSocket?.isConnected == false && clientSocket?.isClosed == false) {
                            try {
                                clientSocket?.close()
                                Log.i(TAG, "Subscriber: ClientSocket closed in finally block.")
                            } catch (e: IOException) {
                                Log.e(TAG, "Subscriber: Error closing clientSocket in finally", e)
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.e(TAG, "Subscriber: Network lost for data path: $network")
                mainHandler.post { currentStatus = "Subscriber: Network Lost." }
                try {
                    clientSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Subscriber: Error closing client socket on network lost", e)
                }
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.e(TAG, "Subscriber: Network unavailable for data path.")
                mainHandler.post { currentStatus = "Subscriber: Network Unavailable." }
                try {
                    clientSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Subscriber: Error closing client socket on network unavailable", e)
                }
                connectivityManager.unregisterNetworkCallback(this)
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                Log.i(TAG, "Subscriber: Network capabilities changed: $networkCapabilities")
            }
        }
        connectivityManager.requestNetwork(networkRequest, networkCallback)
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
