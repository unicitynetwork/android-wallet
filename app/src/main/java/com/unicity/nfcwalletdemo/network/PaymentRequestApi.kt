package com.unicity.nfcwalletdemo.network

import retrofit2.http.*

interface PaymentRequestApi {
    @POST("payment-requests")
    suspend fun createPaymentRequest(@Body request: CreatePaymentRequestDto): PaymentRequestResponse

    @GET("payment-requests/{id}")
    suspend fun getPaymentRequest(@Path("id") id: String): PaymentRequest

    @PUT("payment-requests/{id}/complete")
    suspend fun completePaymentRequest(
        @Path("id") id: String,
        @Body request: CompletePaymentRequestDto
    ): PaymentRequest

    @GET("payment-requests/{id}")
    suspend fun pollPaymentRequest(@Path("id") id: String): PaymentStatusResponse
}

data class CreatePaymentRequestDto(
    val recipientAddress: String,
    val currencySymbol: String? = null,
    val amount: String? = null
)

data class CompletePaymentRequestDto(
    val senderAddress: String,
    val currencySymbol: String,
    val amount: String,
    val transactionId: String? = null
)

data class PaymentRequest(
    val requestId: String,
    val recipientAddress: String,
    val currencySymbol: String? = null,
    val amount: String? = null,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val completedAt: String? = null,
    val paymentDetails: PaymentDetails? = null
)

data class PaymentDetails(
    val senderAddress: String,
    val currencySymbol: String,
    val amount: String,
    val transactionId: String? = null
)

data class PaymentRequestResponse(
    val requestId: String,
    val recipientAddress: String,
    val currencySymbol: String? = null,
    val amount: String? = null,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val qrData: String
)

// PaymentStatusResponse is the same as PaymentRequest for polling
typealias PaymentStatusResponse = PaymentRequest