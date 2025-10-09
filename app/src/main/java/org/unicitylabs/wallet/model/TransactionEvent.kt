package org.unicitylabs.wallet.model

import org.unicitylabs.wallet.data.model.Token

/**
 * Represents a transaction event (received or sent)
 */
data class TransactionEvent(
    val token: Token,
    val type: TransactionType,
    val timestamp: Long = token.timestamp
)

enum class TransactionType {
    RECEIVED,  // Token was received
    SENT       // Token was sent
}
