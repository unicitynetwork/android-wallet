package org.unicitylabs.wallet.data.model

data class TransferResponse(
    val status: String,
    val address: String? = null,
    val error: String? = null
)

data class TransferCompleteResponse(
    val status: String = "transfer_complete"
)