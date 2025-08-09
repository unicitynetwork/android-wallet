package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.util.Log

/**
 * Factory to create P2P service instances
 * Using WebSocket-based P2P for local network messaging
 */
object P2PServiceFactory {
    private const val TAG = "P2PServiceFactory"
    
    fun getInstance(
        context: Context,
        userTag: String,
        userPublicKey: String
    ): IP2PService {
        Log.d(TAG, "Using WebSocket P2P service for local network connectivity")
        return P2PMessagingService.getInstance(context, userTag, userPublicKey) as IP2PService
    }
    
    fun getExistingInstance(): IP2PService? {
        return P2PMessagingService.getExistingInstance() as IP2PService?
    }
}