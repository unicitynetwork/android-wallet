package org.unicitylabs.wallet.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.unicitylabs.wallet.util.BigIntegerStringDeserializer
import org.unicitylabs.wallet.util.BigIntegerStringSerializer
import java.math.BigInteger

/**
 * Payment request for Nostr-based token transfers
 * Used to encode payment information in QR codes
 *
 * IMPORTANT: Amount is BigInteger to avoid overflow (encoded as string in JSON)
 */
data class PaymentRequest(
    @JsonProperty("v")
    val version: String = "1.0",

    @JsonProperty("type")
    val type: String = "unicity_payment_request",

    @JsonProperty("nametag")
    val nametag: String,

    @JsonProperty("coinId")
    val coinId: String? = null,  // Hex string of CoinId (optional)

    @JsonProperty("amount")
    @JsonSerialize(using = BigIntegerStringSerializer::class)
    @JsonDeserialize(using = BigIntegerStringDeserializer::class)
    val amount: BigInteger? = null,     // Amount requested as BigInteger (encoded as string in JSON)

    @JsonProperty("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Parse PaymentRequest from JSON string
         */
        fun fromJson(json: String): PaymentRequest {
            return org.unicitylabs.wallet.util.JsonMapper.fromJson(json, PaymentRequest::class.java)
        }

        /**
         * Parse PaymentRequest from URI (e.g., unicity://pay?nametag=alice&coinId=...&amount=1000)
         */
        fun fromUri(uri: String): PaymentRequest? {
            return try {
                val parsedUri = android.net.Uri.parse(uri)

                // Support both unicity://pay and nfcwallet://payment-request schemes
                if ((parsedUri.scheme != "unicity" && parsedUri.scheme != "nfcwallet") ||
                    (parsedUri.host != "pay" && parsedUri.host != "payment-request")) {
                    return null
                }

                val nametag = parsedUri.getQueryParameter("nametag") ?: return null
                val coinId = parsedUri.getQueryParameter("coinId")
                val amountStr = parsedUri.getQueryParameter("amount")
                val amount = if (amountStr != null) {
                    try {
                        BigInteger(amountStr)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                PaymentRequest(
                    nametag = nametag,
                    coinId = coinId,
                    amount = amount
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Convert to JSON string for QR code
     */
    fun toJson(): String {
        return org.unicitylabs.wallet.util.JsonMapper.toJson(this)
    }

    /**
     * Convert to URI format (alternative to JSON)
     */
    fun toUri(): String {
        val builder = StringBuilder("unicity://pay?nametag=$nametag")
        coinId?.let { builder.append("&coinId=$it") }
        amount?.let { builder.append("&amount=$it") }
        return builder.toString()
    }

    /**
     * Check if this is a specific payment request (with coinId and amount)
     */
    fun isSpecific(): Boolean = coinId != null && amount != null

    /**
     * Get a human-readable description of the request
     */
    fun getDescription(): String {
        return when {
            coinId != null && amount != null -> "Request for $amount tokens"
            coinId != null -> "Request for specific token"
            else -> "Open payment request"
        }
    }
}
