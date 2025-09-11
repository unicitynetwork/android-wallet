package org.unicitylabs.wallet.nfc

import com.google.gson.Gson

/**
 * Data class for NFC handshake that exchanges Bluetooth connection info
 */
data class BluetoothHandshake(
    val senderId: String,
    val bluetoothMAC: String,
    val transferId: String,
    val tokenPreview: TokenPreview,
    val protocolVersion: Int = 1  // Version for compatibility checking
) {
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): BluetoothHandshake {
            return gson.fromJson(json, BluetoothHandshake::class.java)
        }
    }
    
    fun toJson(): String {
        return gson.toJson(this)
    }
    
    fun toByteArray(): ByteArray {
        return toJson().toByteArray()
    }
}

/**
 * Token preview for handshake - just metadata, not actual token data
 */
data class TokenPreview(
    val tokenId: String,
    val name: String,
    val type: String,
    val amount: Long? = null
)

/**
 * Response to Bluetooth handshake
 */
data class BluetoothHandshakeResponse(
    val receiverId: String,
    val bluetoothMAC: String,
    val transferId: String,
    val accepted: Boolean
) {
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): BluetoothHandshakeResponse {
            return gson.fromJson(json, BluetoothHandshakeResponse::class.java)
        }
    }
    
    fun toJson(): String {
        return gson.toJson(this)
    }
    
    fun toByteArray(): ByteArray {
        return toJson().toByteArray()
    }
}