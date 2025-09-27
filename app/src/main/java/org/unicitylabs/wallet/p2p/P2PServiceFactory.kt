package org.unicitylabs.wallet.p2p

import android.content.Context
import android.util.Log
import org.unicitylabs.wallet.nostr.NostrP2PService

/**
 * Factory to create P2P service instances
 * Supports WebSocket-based P2P for local network and Nostr for global messaging
 */
object P2PServiceFactory {
    private const val TAG = "P2PServiceFactory"

    enum class ServiceType {
        WEBSOCKET,
        NOSTR
    }

    private var currentServiceType: ServiceType = ServiceType.NOSTR // Default to Nostr

    fun getInstance(
        context: Context,
        userTag: String,
        userPublicKey: String
    ): IP2PService {
        // Check preferences for service type
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val useNostr = prefs.getBoolean("use_nostr_p2p", true) // Default to Nostr
        currentServiceType = if (useNostr) ServiceType.NOSTR else ServiceType.WEBSOCKET

        Log.d(TAG, "Using ${currentServiceType.name} P2P service")

        return when (currentServiceType) {
            ServiceType.NOSTR -> NostrP2PService.getInstance(context)
            ServiceType.WEBSOCKET -> P2PMessagingService.getInstance(context, userTag, userPublicKey) as IP2PService
        }
    }

    fun getExistingInstance(): IP2PService? {
        return when (currentServiceType) {
            ServiceType.NOSTR -> NostrP2PService.getInstance(null)
            ServiceType.WEBSOCKET -> P2PMessagingService.getExistingInstance() as IP2PService?
        }
    }

    fun setServiceType(context: Context, type: ServiceType) {
        currentServiceType = type
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_nostr_p2p", type == ServiceType.NOSTR).apply()
        Log.d(TAG, "P2P service type set to: ${type.name}")
    }
}