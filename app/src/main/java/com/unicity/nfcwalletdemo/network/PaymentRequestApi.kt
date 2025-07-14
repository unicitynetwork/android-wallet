package com.unicity.nfcwalletdemo.network

import retrofit2.http.*

interface PaymentRequestApi {
    @POST("api/payment-requests")
    suspend fun createPaymentRequest(@Body request: CreatePaymentRequestDto): PaymentRequestResponse

    @GET("api/payment-requests/{id}")
    suspend fun getPaymentRequest(@Path("id") id: String): PaymentRequest

    @POST("api/payment-requests/{id}/complete")
    suspend fun completePaymentRequest(
        @Path("id") id: String,
        @Body request: CompletePaymentRequestDto
    ): PaymentRequest

    @GET("api/payment-requests/{id}/poll")
    suspend fun pollPaymentRequest(@Path("id") id: String): PaymentStatusResponse
}

data class CreatePaymentRequestDto(
    val recipientAddress: String
)

data class CompletePaymentRequestDto(
    val senderAddress: String,
    val currencySymbol: String,
    val amount: String,
    val transactionId: String? = null
)

data class PaymentRequest(
    val id: String,
    val recipientAddress: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val completedAt: String? = null,
    val payment: Payment? = null
)

data class Payment(
    val senderAddress: String,
    val currencySymbol: String,
    val amount: String,
    val transactionId: String? = null
)

data class PaymentRequestResponse(
    val id: String,
    val recipientAddress: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val qrData: String
)

data class PaymentStatusResponse(
    val status: String,
    val payment: Payment? = null,
    val updatedAt: String
)