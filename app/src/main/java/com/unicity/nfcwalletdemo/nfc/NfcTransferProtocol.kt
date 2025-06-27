package com.unicity.nfcwalletdemo.nfc

/**
 * Data classes for the NFC transfer protocol
 * These ensure type safety and consistency between sender and receiver
 */

/**
 * Token transfer request sent during handshake phase
 * Sender -> Receiver (CMD_REQUEST_RECEIVER_ADDRESS)
 */
data class TokenTransferRequest(
    val token_id: String,
    val token_type: String,
    val token_name: String
)

/**
 * Receiver address response sent back during handshake
 * Receiver -> Sender (CMD_GET_RECEIVER_ADDRESS)
 */
data class ReceiverAddressResponse(
    val status: String, // "success", "not_ready", "error"
    val receiver_address: String? = null,
    val error: String? = null
)

/**
 * Offline transaction package sent after handshake
 * Sender -> Receiver (CMD_SEND_OFFLINE_TRANSACTION)
 * This wraps the actual offline transaction with metadata
 */
data class OfflineTransactionPackage(
    val token_name: String,
    val offline_transaction: String // The actual Unicity SDK offline transaction JSON
)

/**
 * Test mode ping message
 * Sender -> Receiver (CMD_TEST_PING)
 */
data class TestPingMessage(
    val message: String,
    val timestamp: Long
)

/**
 * Test mode pong response
 * Receiver -> Sender (CMD_TEST_PONG)
 */
data class TestPongResponse(
    val original_message: String,
    val response_message: String,
    val timestamp: Long
)