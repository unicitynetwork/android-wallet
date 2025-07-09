package com.unicity.nfcwalletdemo.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a user's cryptographic identity
 * @property secret The secret key in hex format
 * @property nonce The nonce in hex format
 */
@Serializable
data class UserIdentity(
    val secret: String,
    val nonce: String
) {
    /**
     * Returns a JSON representation of this identity
     */
    fun toJson(): String {
        return """{"secret":"$secret","nonce":"$nonce"}"""
    }
}