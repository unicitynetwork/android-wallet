package org.unicitylabs.wallet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks outgoing token transfers for history and recovery
 * Allows resuming failed transfers and preventing token loss
 */
@Entity(tableName = "transfer_records")
data class TransferRecord(
    @PrimaryKey
    val transferId: String,  // Unique ID for this transfer attempt

    val tokenId: String,  // ID of the token being transferred
    val recipientNametag: String,  // Recipient's nametag
    val recipientPubkey: String,  // Recipient's Nostr pubkey

    val coinId: String,  // Coin ID (e.g., bitcoin, solana)
    val symbol: String,  // Symbol (e.g., BTC, SOL)
    val amount: Long,  // Amount in smallest units
    val decimals: Int,  // Decimals for display

    val status: TransferStatus,  // Current status of the transfer
    val timestamp: Long = System.currentTimeMillis(),  // When transfer was initiated

    // Transfer package data (for retry if needed)
    val sourceTokenJson: String? = null,  // Source token JSON
    val transferTxJson: String? = null,  // Transfer transaction JSON

    val errorMessage: String? = null  // Error message if failed
)

enum class TransferStatus {
    PENDING,           // Creating transfer
    COMMITTED,         // Submitted to blockchain
    CONFIRMED,         // Inclusion proof received
    SENT,              // Sent via Nostr
    COMPLETED,         // Confirmed received by recipient
    FAILED             // Transfer failed
}
