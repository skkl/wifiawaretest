package com.example.wifiawaretest

import android.annotation.SuppressLint
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiAwareCouroutines {
}

class WifiAwareAttachFailedException(message: String = "Wi-Fi Aware attach failed") : Exception(message)
class WifiAwareSessionConfigFailedException(message: String = "Wi-Fi Aware session configuration failed") : Exception(message)
// We might add more as needed, e.g., for session termination if we wrap that.

@SuppressLint("MissingPermission") // Permissions should be checked before calling
suspend fun WifiAwareManager.attachSuspending(handler: Handler? = null): WifiAwareSession =
    suspendCancellableCoroutine { continuation ->
        val attachCallback = object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.d("WifiAwareCoroutine", "onAttached: $session")
                continuation.resume(session)
            }

            override fun onAttachFailed() {
                Log.e("WifiAwareCoroutine", "onAttachFailed")
                continuation.resumeWithException(WifiAwareAttachFailedException())
            }
        }
        this.attach(attachCallback, handler)

        continuation.invokeOnCancellation {
            // The WifiAwareManager.attach() API doesn't have a direct "cancel" method.
            // If cancellation occurs after onAttached, the session might be closed by higher-level logic
            // managing the WifiAwareSession. If it happens before, the callback will eventually fire.
            Log.d("WifiAwareCoroutine", "attachSuspending cancelled")
        }
    }

@SuppressLint("MissingPermission") // Permissions should be checked before calling
suspend fun WifiAwareSession.publishSuspending(
    config: PublishConfig,
    handler: Handler? = null,
    // We pass the full callback because onMessageReceived and onSessionTerminated are ongoing.
    // The suspend function is mainly for the initial setup (started/failed).
    // Alternatively, one could return a Flow for messages. For now, let's keep it simpler.
    callback: DiscoverySessionCallback
): PublishDiscoverySession =
    suspendCancellableCoroutine { continuation ->
        // We need a way to link the provided callback's onPublishStarted and onSessionConfigFailed
        // to the coroutine's continuation. We can create a wrapper callback.

        val coroutineAwareCallback = object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d("WifiAwareCoroutine", "onPublishStarted: $session")
                callback.onPublishStarted(session) // Call original callback
                if (continuation.isActive) {
                    continuation.resume(session)
                }
            }

            override fun onSessionConfigFailed() {
                Log.e("WifiAwareCoroutine", "onSessionConfigFailed (publish)")
                callback.onSessionConfigFailed() // Call original callback
                if (continuation.isActive) {
                    continuation.resumeWithException(WifiAwareSessionConfigFailedException("Publish configuration failed"))
                }
            }

            // Delegate other essential callback methods to the original callback
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                callback.onMessageReceived(peerHandle, message)
            }

            override fun onSessionTerminated() {
                callback.onSessionTerminated()
                // If the session terminates *before* onPublishStarted (e.g. config was so bad it killed session),
                // this might be a failure path for the coroutine.
                // However, typically onSessionConfigFailed handles setup errors.
                // If onPublishStarted was already called, this is a post-setup event.
                if (!continuation.isCompleted && continuation.isActive) {
                    // This could indicate a problem if it happens before publishStarted
                    // For now, we assume onSessionConfigFailed is the primary failure for setup.
                }
            }
        }

        val discoverySession = this.publish(config, coroutineAwareCallback, handler)
        // Note: publish() can return null if parameters are immediately invalid.
        // However, this usually throws IllegalArgumentException before even getting here with coroutines.
        // The callback approach is more for async failures.

        continuation.invokeOnCancellation {
            Log.d("WifiAwareCoroutine", "publishSuspending cancelled")
            // Attempt to close the discovery session if it was started and then cancelled
            // The actual session object might not be available here if onPublishStarted hasn't fired.
            // If `discoverySession` variable (if publish returned it synchronously) is available, use it.
            // But usually, the session is obtained in onPublishStarted.
            // This relies on the user of PublishDiscoverySession to close it if the coroutine is cancelled after resumption.
        }
    }

@SuppressLint("MissingPermission") // Permissions should be checked before calling
suspend fun WifiAwareSession.subscribeSuspending(
    config: SubscribeConfig,
    handler: Handler? = null,
    callback: DiscoverySessionCallback
): SubscribeDiscoverySession =
    suspendCancellableCoroutine { continuation ->
        val coroutineAwareCallback = object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.d("WifiAwareCoroutine", "onSubscribeStarted: $session")
                callback.onSubscribeStarted(session)
                if (continuation.isActive) {
                    continuation.resume(session)
                }
            }

            override fun onSessionConfigFailed() {
                Log.e("WifiAwareCoroutine", "onSessionConfigFailed (subscribe)")
                callback.onSessionConfigFailed()
                if (continuation.isActive) {
                    continuation.resumeWithException(WifiAwareSessionConfigFailedException("Subscribe configuration failed"))
                }
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                callback.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                callback.onMessageReceived(peerHandle, message)
            }

            override fun onSessionTerminated() {
                callback.onSessionTerminated()
                if (!continuation.isCompleted && continuation.isActive) {
                    // Similar to publish, handle if necessary
                }
            }
        }
        this.subscribe(config, coroutineAwareCallback, handler)

        continuation.invokeOnCancellation {
            Log.d("WifiAwareCoroutine", "subscribeSuspending cancelled")
            // Similar cancellation considerations as publishSuspending
        }
    }