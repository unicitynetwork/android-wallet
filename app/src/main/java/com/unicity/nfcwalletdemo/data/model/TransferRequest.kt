package com.unicity.nfcwalletdemo.data.model

data class TransferRequest(
    val action: String = "request_unicity_address",
    val tokenType: String,
    val timestamp: String = System.currentTimeMillis().toString()
)