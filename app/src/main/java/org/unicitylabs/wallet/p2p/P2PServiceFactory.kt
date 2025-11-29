package org.unicitylabs.wallet.p2p

import android.content.Context
import android.util.Log
import org.unicitylabs.wallet.nostr.NostrSdkService

/**
 * Factory to create and manage P2P service instances.
 * Uses Nostr NIP-17 for secure, private messaging with gift-wrapping.
 */
object P2PServiceFactory {
    private const val TAG = "P2PServiceFactory"

    private var currentInstance: IP2PService? = null

    /**
     * Get or create a P2P service instance.
     * Uses NostrSdkService which implements NIP-17 private messaging.
     *
     * @param context Optional context - if null, returns existing instance or null
     * @param userTag Not used (kept for API compatibility)
     * @param userPublicKey Not used (kept for API compatibility)
     * @return P2P service instance or null if not initialized and context is null
     */
    @JvmStatic
    fun getInstance(
        context: Context? = null,
        userTag: String? = null,
        userPublicKey: String? = null
    ): IP2PService? {
        // If we already have an instance, return it
        currentInstance?.let { return it }

        // No instance exists - need context to create one
        if (context == null) {
            Log.d(TAG, "No existing P2P service instance and no context provided")
            return null
        }

        Log.d(TAG, "Creating NostrSdkService instance with NIP-17 messaging")

        val nostrService = NostrSdkService.getInstance(context)
        if (nostrService != null) {
            Log.d(TAG, "NostrSdkService created successfully")
            // Start the service if not already running
            if (!nostrService.isRunning()) {
                nostrService.start()
                Log.d(TAG, "NostrSdkService started")
            }
            currentInstance = nostrService
        } else {
            Log.e(TAG, "Failed to create NostrSdkService")
        }

        return currentInstance
    }

    /**
     * Reset the current instance (useful for testing or service restart)
     */
    fun reset() {
        val instance = currentInstance
        if (instance != null) {
            try {
                if (instance.isRunning()) {
                    instance.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping P2P service", e)
            }
        }
        currentInstance = null
        Log.d(TAG, "P2P service instance reset")
    }

    /**
     * Check if a service instance exists
     */
    fun hasInstance(): Boolean = currentInstance != null
}