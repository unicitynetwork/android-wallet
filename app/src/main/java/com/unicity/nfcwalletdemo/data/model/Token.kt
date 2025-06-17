package com.unicity.nfcwalletdemo.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Token(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val unicityAddress: String? = null,
    val jsonData: String? = null,
    val sizeBytes: Int = 0
) {
    fun getFormattedSize(): String {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }
    }
}