package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.util.Log

/**
 * Factory to create P2P service instances
 * Using Holepunch for true global P2P messaging
 */
object P2PServiceFactory {
    private const val TAG = "P2PServiceFactory"
    
    // ENABLED: Using real Holepunch for production
    private const val USE_HOLEPUNCH = true
    
    fun getInstance(
        context: Context,
        userTag: String,
        userPublicKey: String
    ): IP2PService {
        return if (USE_HOLEPUNCH) {
            Log.d(TAG, "Using Holepunch P2P service for true global connectivity")
            HolepunchP2PService.getInstance(context, userTag, userPublicKey)
        } else {
            Log.d(TAG, "Using NSD P2P service (Holepunch disabled)")
            P2PMessagingService.getInstance(context, userTag, userPublicKey) as IP2PService
        }
    }
    
    fun getExistingInstance(): IP2PService? {
        return if (USE_HOLEPUNCH) {
            HolepunchP2PService.getExistingInstance()
        } else {
            P2PMessagingService.getExistingInstance() as IP2PService?
        }
    }
}
