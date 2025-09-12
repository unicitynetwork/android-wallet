package org.unicitylabs.wallet.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a user's cryptographic identity
 * @property privateKey The private key in hex format (32 bytes) - used for secp256k1 signing
 * @property nonce The nonce in hex format (32 bytes) - used as salt for predicates
 * @property publicKey The public key in hex format (33 bytes compressed) - derived from private key
 * @property address The wallet address (unmasked predicate) for the current chain (testnet)
 */
@Serializable
data class UserIdentity(
    val privateKey: String,
    val nonce: String,
    val publicKey: String,
    val address: String
) {
    /**
     * Returns a JSON representation of this identity
     */
    fun toJson(): String {
        return """{"privateKey":"$privateKey","nonce":"$nonce","publicKey":"$publicKey","address":"$address"}"""
    }
}