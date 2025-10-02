package org.unicitylabs.wallet.nfc
import org.unicitylabs.wallet.util.JsonMapper


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
        // Using shared JsonMapper.mapper
        
        fun fromJson(json: String): BluetoothHandshake {
            return JsonMapper.fromJson(json, BluetoothHandshake::class.java)
        }
    }
    
    fun toJson(): String {
        return JsonMapper.toJson(this)
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
        // Using shared JsonMapper.mapper
        
        fun fromJson(json: String): BluetoothHandshakeResponse {
            return JsonMapper.fromJson(json, BluetoothHandshakeResponse::class.java)
        }
    }
    
    fun toJson(): String {
        return JsonMapper.toJson(this)
    }
    
    fun toByteArray(): ByteArray {
        return toJson().toByteArray()
    }
}