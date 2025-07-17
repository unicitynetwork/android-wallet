package com.unicity.nfcwalletdemo.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TransferRequest(
    val requestId: String,
    val senderTag: String,
    val recipientTag: String,
    val assetType: String,
    val assetName: String,
    val amount: String,
    val message: String?,
    val status: String,
    val createdAt: String,
    val expiresAt: String
)

data class CreateTransferRequest(
    val senderTag: String?,
    val recipientTag: String,
    val assetType: String,
    val assetName: String,
    val amount: String,
    val message: String?
)

class TransferApiService {
    companion object {
        private const val TAG = "TransferApiService"
        // Backend URL - update this based on your deployment
        private const val LAMBDA_URL = "https://7jmtgxyogc.execute-api.me-central-1.amazonaws.com/Prod"
        private const val LOCAL_URL = "http://10.0.2.2:3001" // For Android emulator
        
        // Use LOCAL_URL for testing, LAMBDA_URL for production
        private val API_URL = LAMBDA_URL
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    
    suspend fun createTransferRequest(
        senderTag: String?,
        recipientTag: String,
        assetType: String,
        assetName: String,
        amount: String,
        message: String?
    ): Result<TransferRequest> = withContext(Dispatchers.IO) {
        try {
            val requestBody = CreateTransferRequest(
                senderTag = senderTag,
                recipientTag = recipientTag,
                assetType = assetType,
                assetName = assetName,
                amount = amount,
                message = message
            )
            
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$API_URL/transfer-requests")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val transferRequest = gson.fromJson(responseBody, TransferRequest::class.java)
                    Log.d(TAG, "Transfer request created: ${transferRequest.requestId}")
                    Result.success(transferRequest)
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to create transfer request: $error")
                Result.failure(Exception("Failed to create transfer request: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating transfer request", e)
            Result.failure(e)
        }
    }
    
    suspend fun getPendingTransfers(recipientTag: String): Result<List<TransferRequest>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/transfer-requests/pending/$recipientTag")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val transfers = gson.fromJson(responseBody, Array<TransferRequest>::class.java).toList()
                    Log.d(TAG, "Found ${transfers.size} pending transfers for $recipientTag")
                    Result.success(transfers)
                } else {
                    Result.success(emptyList())
                }
            } else {
                Log.e(TAG, "Failed to get pending transfers: ${response.code}")
                Result.success(emptyList()) // Return empty list on error to continue polling
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending transfers", e)
            Result.success(emptyList()) // Return empty list on error to continue polling
        }
    }
    
    suspend fun acceptTransfer(requestId: String): Result<TransferRequest> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/transfer-requests/$requestId/accept")
                .put("{}".toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val transferRequest = gson.fromJson(responseBody, TransferRequest::class.java)
                    Log.d(TAG, "Transfer request accepted: $requestId")
                    Result.success(transferRequest)
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to accept transfer request: $error")
                Result.failure(Exception("Failed to accept transfer: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting transfer request", e)
            Result.failure(e)
        }
    }
    
    suspend fun rejectTransfer(requestId: String): Result<TransferRequest> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_URL/transfer-requests/$requestId/reject")
                .put("{}".toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val transferRequest = gson.fromJson(responseBody, TransferRequest::class.java)
                    Log.d(TAG, "Transfer request rejected: $requestId")
                    Result.success(transferRequest)
                } else {
                    Result.failure(Exception("Empty response"))
                }
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to reject transfer request: $error")
                Result.failure(Exception("Failed to reject transfer: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting transfer request", e)
            Result.failure(e)
        }
    }
}