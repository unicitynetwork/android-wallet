package com.unicity.nfcwalletdemo.data.model

data class TransferResponse(
    val status: String,
    val address: String? = null,
    val error: String? = null
)

data class TransferCompleteResponse(
    val status: String = "transfer_complete"
)