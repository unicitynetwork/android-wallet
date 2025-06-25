package com.unicity.nfcwalletdemo.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import com.unicity.nfcwalletdemo.sdk.UnicityIdentity
import com.unicity.nfcwalletdemo.sdk.UnicityTransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

class DirectNfcClient(
    private val sdkService: UnicitySdkService,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: (Int, Int) -> Unit // current chunk, total chunks
) : NfcAdapter.ReaderCallback {
    
    companion object {
        private const val TAG = "DirectNfcClient"
        
        // AID for our application
        private val SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
        
        // Commands
        private const val CMD_START_DIRECT_TRANSFER: Byte = 0x02
        private const val CMD_GET_CHUNK: Byte = 0x03
        private const val CMD_TRANSFER_COMPLETE: Byte = 0x04
        
        // New commands for proper offline transfer handshake
        private const val CMD_REQUEST_RECEIVER_ADDRESS: Byte = 0x05
        private const val CMD_GET_RECEIVER_ADDRESS: Byte = 0x06
        private const val CMD_SEND_OFFLINE_TRANSACTION: Byte = 0x07
        
        // Maximum APDU command size (minus header and Lc)
        // Using smaller chunks for better stability across different devices
        // Some devices have issues with larger APDUs
        private const val MAX_COMMAND_DATA_SIZE = 200
        
        // Android HCE has a transfer size limit around 37KB
        private const val MAX_TRANSFER_SIZE = 36000
    }
    
    private var tokenToSend: Token? = null
    private val gson = Gson()
    
    fun setTokenToSend(token: Token) {
        tokenToSend = token
        Log.d(TAG, "Token set for NFC transfer: ${token.name}")
    }
    
    fun setCryptoToSend(cryptoToken: Token) {
        tokenToSend = cryptoToken
        Log.d(TAG, "Crypto token set for NFC transfer: ${cryptoToken.name}")
    }
    
    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "✅ NFC TAG DISCOVERED for transfer!")
        tag?.let { processDirectTransfer(it) }
    }
    
    private fun processDirectTransfer(tag: Tag) {
        val token = tokenToSend
        if (token == null) {
            Log.e(TAG, "No token set for transfer")
            onError("No token set for transfer")
            return
        }
        
        // Launch coroutine to handle the entire transfer process
        CoroutineScope(Dispatchers.IO).launch {
            var isoDep: IsoDep? = null
            try {
                isoDep = IsoDep.get(tag)
                if (isoDep == null) {
                    Log.e(TAG, "IsoDep not supported")
                    withContext(Dispatchers.Main) {
                        onError("NFC card does not support IsoDep")
                    }
                    return@launch
                }
                
                Log.d(TAG, "Connecting to IsoDep...")
                isoDep.connect()
                
                // Set a longer timeout for the connection
                isoDep.timeout = 30000 // 30 seconds - much longer for stability
                
                // Check if extended length APDUs are supported
                val maxTransceiveLength = isoDep.maxTransceiveLength
                Log.d(TAG, "IsoDep connected successfully")
                Log.d(TAG, "Timeout: ${isoDep.timeout}ms, Max transceive length: $maxTransceiveLength")
                
                // If extended APDUs are supported, we could use larger chunks
                // But for compatibility, we'll stick with standard APDU size
                
                // Select AID
                val selectResponse = isoDep.transceive(SELECT_AID)
                if (!isResponseOK(selectResponse)) {
                    Log.e(TAG, "SELECT AID failed")
                    withContext(Dispatchers.Main) {
                        onError("Failed to select application")
                    }
                    return@launch
                }
                Log.d(TAG, "SELECT AID successful")
                
                // Send token directly
                sendTokenDirectly(isoDep, token)
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception in direct transfer", e)
                withContext(Dispatchers.Main) {
                    onError("Transfer failed: ${e.message}")
                }
            } finally {
                try {
                    isoDep?.close()
                    Log.d(TAG, "IsoDep connection closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing IsoDep", e)
                }
            }
        }
    }
    
    private suspend fun sendTokenDirectly(isoDep: IsoDep, token: Token) {
        try {
            // For Unicity tokens, implement the handshake
            // For other tokens, use existing direct transfer
            
            when (token.type) {
                "Unicity Token" -> {
                    if (token.jsonData != null) {
                        // Implement proper offline transfer handshake
                        sendUnicityTokenWithHandshake(isoDep, token)
                    } else {
                        // Demo Unicity token - use direct transfer
                        sendTokenWithDirectTransfer(isoDep, gson.toJson(token))
                    }
                }
                "Crypto Transfer" -> {
                    // For crypto transfers, send the crypto data directly
                    val transferData = token.jsonData ?: gson.toJson(token)
                    sendTokenWithDirectTransfer(isoDep, transferData)
                }
                else -> {
                    // For other demo tokens, use existing logic
                    sendTokenWithDirectTransfer(isoDep, gson.toJson(token))
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendTokenDirectly", e)
            withContext(Dispatchers.Main) {
                onError("Transfer error: ${e.message}")
            }
        }
    }
    
    /**
     * Implements the offline transfer handshake for Unicity tokens
     */
    private suspend fun sendUnicityTokenWithHandshake(isoDep: IsoDep, token: Token) {
        try {
            Log.d(TAG, "Starting Unicity token handshake for: ${token.name}")
            
            // Request receiver address
            val tokenRequest = createUnicityTransfer(token)
            val receiverAddress = requestReceiverAddress(isoDep, tokenRequest)
            
            if (receiverAddress.isEmpty()) {
                throw Exception("Failed to get receiver address")
            }
            
            Log.d(TAG, "Received address from receiver: $receiverAddress")
            
            // Create offline transaction package with receiver's address
            val offlineTransactionPackage = createOfflineTransferPackageWithReceiverAddress(token, receiverAddress)
            
            // Send offline transaction package to receiver
            sendOfflineTransactionPackage(isoDep, offlineTransactionPackage)
            
            Log.d(TAG, "✅ Unicity offline transfer completed successfully!")
            withContext(Dispatchers.Main) {
                onTransferComplete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed Unicity handshake: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError("Offline transfer failed: ${e.message}")
            }
        }
    }
    
    /**
     * Request receiver address for the token
     */
    private suspend fun requestReceiverAddress(isoDep: IsoDep, tokenRequest: String): String {
        return try {
            Log.d(TAG, "Requesting receiver address...")
            
            val requestBytes = tokenRequest.toByteArray(StandardCharsets.UTF_8)
            val lc = (requestBytes.size and 0xFF).toByte()
            val command = byteArrayOf(0x00.toByte(), CMD_REQUEST_RECEIVER_ADDRESS, 0x00.toByte(), 0x00.toByte(), lc) + requestBytes
            
            Log.d(TAG, "Sending address request, size: ${requestBytes.size} bytes")
            
            val response = isoDep.transceive(command)
            if (!isResponseOK(response)) {
                throw Exception("Receiver address request failed")
            }
            
            Log.d(TAG, "Address request sent successfully, waiting for receiver to generate address...")
            
            // Wait for receiver to generate address and then query for it
            var retryCount = 0
            val maxRetries = 10 // Allow 10 retries over 5 seconds
            
            while (retryCount < maxRetries) {
                delay(500) // Wait 500ms between queries
                
                Log.d(TAG, "Querying for generated receiver address (attempt ${retryCount + 1})")
                
                // Query for the generated address using CMD_GET_RECEIVER_ADDRESS
                val queryCommand = byteArrayOf(0x00.toByte(), CMD_GET_RECEIVER_ADDRESS, 0x00.toByte(), 0x00.toByte())
                val queryResponse = isoDep.transceive(queryCommand)
                
                if (isResponseOK(queryResponse)) {
                    // Remove the SW_OK bytes (last 2 bytes) to get the actual response data
                    val responseData = queryResponse.sliceArray(0 until queryResponse.size - 2)
                    val responseJson = String(responseData, StandardCharsets.UTF_8)
                    
                    Log.d(TAG, "Received address response: $responseJson")
                    
                    try {
                        val response = gson.fromJson(responseJson, Map::class.java)
                        val status = response["status"] as? String
                        
                        when (status) {
                            "success" -> {
                                val receiverAddress = response["receiver_address"] as? String
                                if (receiverAddress != null) {
                                    Log.d(TAG, "Receiver address obtained: $receiverAddress")
                                    return receiverAddress
                                } else {
                                    throw Exception("No receiver address in success response")
                                }
                            }
                            "not_ready" -> {
                                Log.d(TAG, "Receiver address not ready yet, retrying...")
                                retryCount++
                                continue
                            }
                            else -> {
                                throw Exception("Unknown status in address response: $status")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse address response: $responseJson", e)
                        throw Exception("Invalid address response format")
                    }
                } else {
                    Log.e(TAG, "Failed to query receiver address")
                    retryCount++
                }
            }
            
            throw Exception("Timeout waiting for receiver address after $maxRetries attempts")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request receiver address", e)
            throw e
        }
    }
    
    /**
     * Send offline transaction package to receiver
     */
    private suspend fun sendOfflineTransactionPackage(isoDep: IsoDep, offlinePackage: String) {
        try {
            Log.d(TAG, "Sending offline transaction package...")
            
            val packageBytes = offlinePackage.toByteArray(StandardCharsets.UTF_8)
            
            // Check size limits
            if (packageBytes.size > MAX_TRANSFER_SIZE) {
                throw Exception("Offline package too large: ${packageBytes.size} bytes")
            }
            
            // Send using chunked transfer with new command
            sendDataInChunks(isoDep, packageBytes, CMD_SEND_OFFLINE_TRANSACTION)
            
            Log.d(TAG, "Offline transaction package sent successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send offline transaction package", e)
            throw e
        }
    }
    
    /**
     * Fallback method for direct token transfer (non-handshake)
     */
    private suspend fun sendTokenWithDirectTransfer(isoDep: IsoDep, transferData: String) {
        try {
            val tokenBytes = transferData.toByteArray(StandardCharsets.UTF_8)
            
            Log.d(TAG, "Sending token via direct transfer, size: ${tokenBytes.size} bytes")
            
            // Check if token exceeds NFC transfer limits
            if (tokenBytes.size > MAX_TRANSFER_SIZE) {
                Log.e(TAG, "Token size ${tokenBytes.size} exceeds maximum NFC transfer size $MAX_TRANSFER_SIZE")
                withContext(Dispatchers.Main) {
                    onError("Token too large for NFC transfer (${tokenBytes.size} bytes)")
                }
                return
            }
            
            // Send using existing chunked transfer
            sendDataInChunks(isoDep, tokenBytes, CMD_START_DIRECT_TRANSFER)
            
            // Complete transfer
            Log.d(TAG, "Direct transfer completed, waiting for receiver confirmation...")
            val completeCommand = byteArrayOf(0x00.toByte(), CMD_TRANSFER_COMPLETE, 0x00.toByte(), 0x00.toByte())
            val completeResponse = isoDep.transceive(completeCommand)
            
            if (isResponseOK(completeResponse)) {
                Log.d(TAG, "✅ Direct transfer completed successfully!")
                withContext(Dispatchers.Main) {
                    onTransferComplete()
                }
            } else {
                Log.e(TAG, "Transfer completion failed")
                withContext(Dispatchers.Main) {
                    onError("Failed to complete transfer")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Direct transfer failed", e)
            withContext(Dispatchers.Main) {
                onError("Transfer error: ${e.message}")
            }
        }
    }
    
    /**
     * Generic method to send data in chunks with specified command
     */
    private suspend fun sendDataInChunks(isoDep: IsoDep, data: ByteArray, startCommand: Byte) {
        // Split into chunks - first chunk is smaller to accommodate size bytes
        val chunks = mutableListOf<ByteArray>()
        val firstChunkDataSize = MAX_COMMAND_DATA_SIZE - 2 // Account for 2-byte size header
        
        if (data.size <= firstChunkDataSize) {
            // Everything fits in first chunk
            chunks.add(data)
        } else {
            // First chunk - exactly firstChunkDataSize bytes
            chunks.add(data.sliceArray(0 until firstChunkDataSize))
            var offset = firstChunkDataSize
            
            // Remaining chunks - each exactly MAX_COMMAND_DATA_SIZE bytes (except last)
            while (offset < data.size) {
                val remaining = data.size - offset
                val chunkSize = minOf(remaining, MAX_COMMAND_DATA_SIZE)
                chunks.add(data.sliceArray(offset until offset + chunkSize))
                offset += chunkSize
            }
        }
        
        // Verify total size matches
        val totalChunkData = chunks.sumOf { it.size }
        if (totalChunkData != data.size) {
            Log.e(TAG, "Chunk size mismatch: expected ${data.size}, got $totalChunkData")
            withContext(Dispatchers.Main) {
                onError("Internal chunking error")
            }
            return
        }
        
        Log.d(TAG, "Data split into ${chunks.size} chunks")
        
        // Send each chunk
        for (i in chunks.indices) {
            val chunk = chunks[i]
            
            withContext(Dispatchers.Main) {
                onProgress(i + 1, chunks.size)
            }
            
            val command = if (i == 0) {
                // First chunk - build proper APDU with P3 (Lc)
                val sizeBytes = byteArrayOf(
                    ((data.size shr 8) and 0xFF).toByte(),
                    (data.size and 0xFF).toByte()
                )
                val dataToSend = sizeBytes + chunk
                val lc = (dataToSend.size and 0xFF).toByte()
                byteArrayOf(0x00.toByte(), startCommand, 0x00.toByte(), 0x00.toByte(), lc) + dataToSend
            } else {
                // Subsequent chunks - build proper APDU with P3 (Lc)
                val lc = (chunk.size and 0xFF).toByte()
                byteArrayOf(0x00.toByte(), CMD_GET_CHUNK, 0x00.toByte(), 0x00.toByte(), lc) + chunk
            }
            
            Log.d(TAG, "Sending chunk ${i + 1}/${chunks.size}, command size: ${command.size}, chunk size: ${chunk.size}")
            
            // Check if still connected
            if (!isoDep.isConnected) {
                Log.e(TAG, "IsoDep connection lost")
                withContext(Dispatchers.Main) {
                    onError("NFC connection lost")
                }
                return
            }
            
            val response = try {
                isoDep.transceive(command)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send chunk ${i + 1}: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Connection lost during transfer")
                }
                return
            }
            
            if (!isResponseOK(response)) {
                Log.e(TAG, "Failed to send chunk ${i + 1}, response: ${response.toHexString()}")
                withContext(Dispatchers.Main) {
                    onError("Failed to send data chunk ${i + 1}")
                }
                return
            }
            
            Log.d(TAG, "Chunk ${i + 1} sent successfully")
            
            // Small delay between chunks to prevent overwhelming the receiver
            if (i < chunks.size - 1) {
                delay(50) // 50ms delay between chunks
            }
        }
        
        Log.d(TAG, "All chunks sent successfully")
    }
    
    private fun isResponseOK(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private suspend fun createUnicityTransfer(token: Token): String {
        return try {
            // This function will be called by sendTokenDirectly after receiver provides their address
            // It should NOT generate receiver identity - that's the receiver's job!
            
            Log.d(TAG, "Creating offline Unicity transfer for token: ${token.name}")
            
            if (token.jsonData.isNullOrEmpty()) {
                Log.e(TAG, "Token has no jsonData - cannot create offline transfer")
                throw Exception("Token jsonData is empty")
            }
            
            // Extract sender data from the token
            val senderData = extractSenderData(token)
            Log.d(TAG, "Extracted sender data successfully")
            
            // Parse the token JSON to get the actual token data structure
            val parsedTokenData = try {
                // The jsonData should contain the complete Token structure from mint result
                val mintResult = gson.fromJson(token.jsonData, Map::class.java)
                val tokenData = mintResult["token"] as? Map<*, *> 
                    ?: throw Exception("Token data not found in mint result")
                
                Log.d(TAG, "Token data keys: ${tokenData.keys}")
                tokenData
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse token data from jsonData", e)
                throw Exception("Invalid token data structure: ${e.message}")
            }
            
            // In the proper implementation, this will receive the receiver's address from NFC handshake
            val tokenRequest = mapOf(
                "type" to "token_transfer_request",
                "token_name" to token.name,
                "token_id" to parsedTokenData["id"],
                "token_type" to parsedTokenData["type"],
                "sender_data" to senderData.senderIdentity.toJson(),
                "token_data" to gson.toJson(parsedTokenData)
            )
            
            Log.d(TAG, "Created token transfer request for handshake")
            gson.toJson(tokenRequest)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create token transfer request: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Token data: name=${token.name}, type=${token.type}, hasJsonData=${!token.jsonData.isNullOrEmpty()}")
            Log.e(TAG, "Stack trace:", e)
            
            // Fallback to demo token transfer
            Log.w(TAG, "Falling back to demo token transfer due to offline transfer failure")
            gson.toJson(token)
        }
    }
    
    private suspend fun generateReceiverIdentity(): UnicityIdentity = 
        suspendCancellableCoroutine { continuation ->
            sdkService.generateIdentity { result ->
                result.onSuccess { identityJson ->
                    try {
                        val identity = UnicityIdentity.fromJson(identityJson)
                        continuation.resume(identity)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse receiver identity", e)
                        continuation.resume(UnicityIdentity("receiver_secret", "receiver_nonce"))
                    }
                }
                result.onFailure { error ->
                    Log.e(TAG, "Failed to generate receiver identity", error)
                    continuation.resume(UnicityIdentity("fallback_receiver_secret", "fallback_receiver_nonce"))
                }
            }
        }
    
    private data class SenderData(
        val senderIdentity: UnicityIdentity,
        val tokenJson: String
    )
    
    private fun extractSenderData(token: Token): SenderData {
        return try {
            // Parse the stored jsonData to extract sender identity and token
            val mintResult = gson.fromJson(token.jsonData, Map::class.java)
            
            // Extract identity from mint result
            val identityData = mintResult["identity"] as? Map<*, *>
            val senderIdentity = if (identityData != null) {
                UnicityIdentity(
                    secret = identityData["secret"] as? String ?: "fallback_secret",
                    nonce = identityData["nonce"] as? String ?: "fallback_nonce"
                )
            } else {
                UnicityIdentity("fallback_secret", "fallback_nonce")
            }
            
            // Extract token data
            val tokenData = mintResult["token"]
            val tokenJson = gson.toJson(tokenData)
            
            SenderData(senderIdentity, tokenJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract sender data", e)
            SenderData(
                UnicityIdentity("error_secret", "error_nonce"),
                "{\"error\": \"failed_to_parse_token\"}"
            )
        }
    }
    
    private suspend fun generateReceivingAddressForOfflineTransfer(
        tokenIdJson: String,
        tokenTypeJson: String,
        receiverIdentity: UnicityIdentity
    ): String = suspendCancellableCoroutine { continuation ->
        sdkService.generateReceivingAddressForOfflineTransfer(
            tokenIdJson,
            tokenTypeJson,
            receiverIdentity.toJson()
        ) { result ->
            result.onSuccess { addressJson ->
                val addressData = gson.fromJson(addressJson, Map::class.java)
                val address = addressData["address"] as? String ?: throw Exception("Address not found")
                continuation.resume(address)
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to generate receiving address for offline transfer", error)
                continuation.resume("fallback_address")
            }
        }
    }

    /**
     * Creates the actual offline transaction package after receiving the receiver's address
     * This is step 3 of the proper offline transfer flow
     */
    private suspend fun createOfflineTransferPackageWithReceiverAddress(
        token: Token,
        receiverAddress: String
    ): String = suspendCancellableCoroutine { continuation ->
        try {
            Log.d(TAG, "Creating offline transfer package with receiver address: $receiverAddress")
            
            // Extract sender data from the token
            val senderData = extractSenderData(token)
            
            // Parse the token JSON to get the actual token data structure
            val mintResult = gson.fromJson(token.jsonData, Map::class.java)
            val tokenData = mintResult["token"] as? Map<*, *> 
                ?: throw Exception("Token data not found in mint result")
            
            // Create offline transfer package using SDK
            sdkService.createOfflineTransferPackage(
                senderData.senderIdentity.toJson(),
                receiverAddress,
                gson.toJson(tokenData)
            ) { result ->
                result.onSuccess { offlineTransactionJson ->
                    // Create the final transfer package for NFC
                    val transferPackage = mapOf(
                        "type" to "unicity_offline_transfer",
                        "token_name" to token.name,
                        "offline_transaction" to offlineTransactionJson
                    )
                    continuation.resume(gson.toJson(transferPackage))
                }
                result.onFailure { error ->
                    Log.e(TAG, "Failed to create offline transfer with SDK", error)
                    // Return fallback offline transfer data
                    val fallbackTransfer = mapOf(
                        "error" to "sdk_offline_transfer_failed",
                        "message" to error.message,
                        "token" to gson.toJson(token)
                    )
                    continuation.resume(gson.toJson(fallbackTransfer))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offline transfer package", e)
            continuation.resume(gson.toJson(mapOf("error" to e.message)))
        }
    }

    private suspend fun createOfflineTransferWithSdk(
        senderIdentity: UnicityIdentity,
        recipientAddress: String,
        tokenJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        sdkService.createOfflineTransferPackage(
            senderIdentity.toJson(),
            recipientAddress,
            tokenJson
        ) { result ->
            result.onSuccess { offlineTransactionJson ->
                continuation.resume(offlineTransactionJson)
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to create offline transfer with SDK", error)
                // Return fallback offline transfer data
                val fallbackTransfer = mapOf(
                    "error" to "sdk_offline_transfer_failed",
                    "message" to error.message,
                    "token" to tokenJson
                )
                continuation.resume(gson.toJson(fallbackTransfer))
            }
        }
    }

    private suspend fun createTransferWithSdk(
        senderIdentity: UnicityIdentity,
        receiverIdentity: UnicityIdentity,
        tokenJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        sdkService.createTransfer(
            senderIdentity.toJson(),
            receiverIdentity.toJson(),
            tokenJson
        ) { result ->
            result.onSuccess { transferJson ->
                continuation.resume(transferJson)
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to create transfer with SDK", error)
                // Return fallback transfer data
                val fallbackTransfer = mapOf(
                    "error" to "sdk_transfer_failed",
                    "message" to error.message,
                    "token" to tokenJson
                )
                continuation.resume(gson.toJson(fallbackTransfer))
            }
        }
    }
}