package com.unicity.nfcwalletdemo.nfc

import android.nfc.TagLostException
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicityIdentity
import com.unicity.nfcwalletdemo.sdk.UnicityJavaSdkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

class DirectNfcClient(
    private val sdkService: UnicityJavaSdkService,
    private val apduTransceiver: ApduTransceiver,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: (Int, Int) -> Unit // current chunk, total chunks
) {
    
    companion object {
        private const val TAG = "DirectNfcClient"
        
        // AID for our application
        private val SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
        
        // Commands for offline transfer handshake protocol
        private const val CMD_REQUEST_RECEIVER_ADDRESS: Byte = 0x05
        private const val CMD_GET_RECEIVER_ADDRESS: Byte = 0x06
        private const val CMD_SEND_OFFLINE_TRANSACTION: Byte = 0x07
        
        // Commands for test mode
        private const val CMD_TEST_PING: Byte = 0x08
        private const val CMD_TEST_PONG: Byte = 0x09
        
        // Commands for direct transfer (crypto assets)
        private const val CMD_START_DIRECT_TRANSFER: Byte = 0x20
        private const val CMD_DATA_CHUNK: Byte = 0x21
        private const val CMD_COMPLETE_DIRECT_TRANSFER: Byte = 0x22
        
        // Maximum APDU command size (minus header and Lc)
        // Using smaller chunks for better stability across different devices
        // Some devices have issues with larger APDUs
        private const val MAX_COMMAND_DATA_SIZE = 200
        
        // Android HCE has a transfer size limit around 37KB
        private const val MAX_TRANSFER_SIZE = 36000
    }
    
    private var tokenToSend: Token? = null
    private val gson = Gson()
    private var isTestMode = false
    
    fun setTokenToSend(token: Token) {
        tokenToSend = token
        Log.d(TAG, "Token set for NFC transfer: ${token.name}")
    }
    
    fun setCryptoToSend(cryptoToken: Token) {
        tokenToSend = cryptoToken
        Log.d(TAG, "Crypto token set for NFC transfer: ${cryptoToken.name}")
    }
    
    fun setTestMode(enabled: Boolean) {
        isTestMode = enabled
        Log.d(TAG, "Test mode set to: $enabled")
    }
    
    fun startNfcTransfer() {
        if (isTestMode) {
            Log.d(TAG, "Starting NFC test transfer")
            startTestTransfer()
            return
        }
        
        val token = tokenToSend
        if (token == null) {
            Log.e(TAG, "No token set for transfer")
            onError("No token set for transfer")
            return
        }
        
        // Launch coroutine to handle the entire transfer process
        CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            val maxRetries = 5
            
            // Notify user to keep phones touching
            withContext(Dispatchers.Main) {
                onProgress(0, 1)
            }
            
            while (retryCount < maxRetries) {
                try {
                    Log.d(TAG, "NFC transfer attempt ${retryCount + 1}/$maxRetries")
                    
                    // Longer delay for first attempt to ensure stable connection
                    if (retryCount == 0) {
                        Log.d(TAG, "Initial stabilization delay...")
                        delay(1000) // 1 second initial delay
                        
                        // Test connection with a simple SELECT AID
                        Log.d(TAG, "Testing NFC connection...")
                        val testResponse = apduTransceiver.transceive(SELECT_AID)
                        if (!isResponseOK(testResponse)) {
                            throw Exception("Connection test failed")
                        }
                        Log.d(TAG, "Connection test successful")
                    } else {
                        // Progressive delay between retries
                        val retryDelay = 500L * retryCount
                        Log.d(TAG, "Retry delay: ${retryDelay}ms")
                        delay(retryDelay)
                    }
                    
                    // Re-select AID for actual transfer
                    Log.d(TAG, "Selecting application...")
                    val selectResponse = apduTransceiver.transceive(SELECT_AID)
                    if (!isResponseOK(selectResponse)) {
                        throw Exception("Failed to select application")
                    }
                    Log.d(TAG, "SELECT AID successful")
                    
                    // Send token directly
                    sendTokenDirectly(token)
                    
                    // If successful, exit the retry loop
                    break
                    
                } catch (e: TagLostException) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        Log.w(TAG, "Tag lost (attempt $retryCount), will retry...")
                        withContext(Dispatchers.Main) {
                            onError("Connection lost - Keep phones firmly touching!")
                        }
                    } else {
                        Log.e(TAG, "Tag lost after $maxRetries attempts", e)
                        withContext(Dispatchers.Main) {
                            onError("Transfer failed. Try holding phones together longer.")
                        }
                    }
                } catch (e: SecurityException) {
                    if (e.message?.contains("out of date") == true) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            Log.w(TAG, "Tag out of date, retrying...")
                        } else {
                            Log.e(TAG, "Tag connection unstable", e)
                            withContext(Dispatchers.Main) {
                                onError("Connection unstable. Try again with phones touching firmly.")
                            }
                        }
                    } else {
                        throw e
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in direct transfer", e)
                    withContext(Dispatchers.Main) {
                        onError("Transfer failed: ${e.message}")
                    }
                    break // Don't retry for other exceptions
                }
            }
        }
    }
    
    private suspend fun sendTokenDirectly(token: Token) {
        try {
            // Check if this is a crypto transfer
            val tokenData = token.jsonData
            if (tokenData != null && tokenData.contains("\"type\":\"crypto_transfer\"")) {
                Log.d(TAG, "Detected crypto transfer, using direct send method")
                sendCryptoTransferDirectly(token)
            } else {
                // Regular Unicity tokens use the proper offline handshake protocol
                sendUnicityTokenWithHandshake(token)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendTokenDirectly", e)
            withContext(Dispatchers.Main) {
                onError("Transfer error: ${e.message}")
            }
        }
    }
    
    private fun startTestTransfer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting simple NFC test transfer...")
                
                // Notify progress
                withContext(Dispatchers.Main) {
                    onProgress(0, 1)
                }
                
                // Wait for connection to stabilize (increased delay)
                Log.d(TAG, "Waiting for NFC connection to stabilize...")
                delay(1000)
                
                // Test 1: Simple SELECT AID
                Log.d(TAG, "Test 1: Sending SELECT AID...")
                val selectResponse = apduTransceiver.transceive(SELECT_AID)
                if (!isResponseOK(selectResponse)) {
                    throw Exception("Failed to select application")
                }
                Log.d(TAG, "✅ SELECT AID successful")
                
                // Small delay between commands
                delay(200)
                
                // Test 2: Send PING command with test data
                Log.d(TAG, "Test 2: Sending PING...")
                val testPing = TestPingMessage(
                    message = "Hello NFC Test",
                    timestamp = System.currentTimeMillis()
                )
                val testData = gson.toJson(testPing).toByteArray(StandardCharsets.UTF_8)
                val pingCommand = byteArrayOf(0x00.toByte(), CMD_TEST_PING, 0x00.toByte(), 0x00.toByte(), testData.size.toByte()) + testData
                
                val pingResponse = apduTransceiver.transceive(pingCommand)
                if (!isResponseOK(pingResponse)) {
                    throw Exception("PING failed")
                }
                
                // Extract response data (remove SW_OK)
                val responseData = pingResponse.sliceArray(0 until pingResponse.size - 2)
                val responseString = String(responseData, StandardCharsets.UTF_8)
                Log.d(TAG, "✅ PING successful, response: $responseString")
                
                // Longer delay before second test
                delay(300)
                
                // Test 3: Send another PING to verify stability
                Log.d(TAG, "Test 3: Sending second PING...")
                val testPing2 = TestPingMessage(
                    message = "Test 2",
                    timestamp = System.currentTimeMillis()
                )
                val testData2 = gson.toJson(testPing2).toByteArray(StandardCharsets.UTF_8)
                val pingCommand2 = byteArrayOf(0x00.toByte(), CMD_TEST_PING, 0x00.toByte(), 0x00.toByte(), testData2.size.toByte()) + testData2
                
                val pingResponse2 = apduTransceiver.transceive(pingCommand2)
                if (!isResponseOK(pingResponse2)) {
                    throw Exception("Second PING failed")
                }
                Log.d(TAG, "✅ Second PING successful")
                
                // All tests passed
                Log.d(TAG, "✅ All NFC tests passed!")
                withContext(Dispatchers.Main) {
                    onTransferComplete()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Test transfer failed", e)
                val errorMessage = when (e) {
                    is android.nfc.TagLostException -> {
                        "NFC connection lost. Please keep phones together and try again."
                    }
                    is java.io.IOException -> {
                        "NFC communication error. Please try again."
                    }
                    else -> {
                        "Test failed: ${e.message}"
                    }
                }
                withContext(Dispatchers.Main) {
                    onError(errorMessage)
                }
            }
        }
    }
    
    /**
     * Implements the offline transfer handshake for Unicity tokens
     */
    private suspend fun sendUnicityTokenWithHandshake(token: Token) {
        try {
            Log.d(TAG, "Starting Unicity token handshake for: ${token.name}")
            
            // Wait for connection to stabilize (same as test transfer)
            Log.d(TAG, "Waiting for NFC connection to stabilize...")
            delay(1000)
            
            // Create a request for the receiver's address
            val tokenRequest = createTokenTransferRequest(token)
            
            // Small delay before first command
            delay(200)
            
            // Request receiver address from the other device
            val receiverAddress = requestReceiverAddress(tokenRequest)
            
            if (receiverAddress.isEmpty()) {
                throw Exception("Failed to get receiver address")
            }
            
            Log.d(TAG, "Received address from receiver: $receiverAddress")
            
            // Delay before creating offline transaction
            delay(300)
            
            // Create offline transaction package with receiver's address
            val offlineTransactionPackage = createOfflineTransferPackageWithReceiverAddress(token, receiverAddress)
            
            // Delay before sending transaction package
            delay(200)
            
            // Send offline transaction package to receiver
            sendOfflineTransactionPackage(offlineTransactionPackage)
            
            Log.d(TAG, "✅ Unicity offline transfer completed successfully!")
            withContext(Dispatchers.Main) {
                onTransferComplete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed Unicity handshake: ${e.message}", e)
            val errorMessage = when (e) {
                is android.nfc.TagLostException -> {
                    "NFC connection lost. Please keep phones together and try again."
                }
                is java.io.IOException -> {
                    "NFC communication error. Please try again."
                }
                else -> {
                    "Offline transfer failed: ${e.message}"
                }
            }
            withContext(Dispatchers.Main) {
                onError(errorMessage)
            }
        }
    }
    
    /**
     * Request receiver address for the token
     */
    private suspend fun requestReceiverAddress(tokenRequest: String): String {
        try {
            Log.d(TAG, "Requesting receiver address...")

            val requestBytes = tokenRequest.toByteArray(StandardCharsets.UTF_8)
            val lc = (requestBytes.size and 0xFF).toByte()
            val command = byteArrayOf(0x00.toByte(), CMD_REQUEST_RECEIVER_ADDRESS, 0x00.toByte(), 0x00.toByte(), lc) + requestBytes

            Log.d(TAG, "Sending address request, size: ${requestBytes.size} bytes")

            val response = apduTransceiver.transceive(command)
            if (!isResponseOK(response)) {
                throw Exception("Receiver address request failed")
            }

            Log.d(TAG, "Address request sent successfully, waiting for receiver to generate address...")

            // Wait for receiver to generate address and then query for it
            var retryCount = 0
            val maxRetries = 30 // Allow 30 retries over 15 seconds

            while (retryCount < maxRetries) {
                delay(500) // Wait 500ms between queries

                Log.d(TAG, "Querying for generated receiver address (attempt ${retryCount + 1})")

                // Query for the generated address using CMD_GET_RECEIVER_ADDRESS
                val queryCommand = byteArrayOf(0x00.toByte(), CMD_GET_RECEIVER_ADDRESS, 0x00.toByte(), 0x00.toByte())
                val queryResponse = apduTransceiver.transceive(queryCommand)

                if (isResponseOK(queryResponse)) {
                    // Remove the SW_OK bytes (last 2 bytes) to get the actual response data
                    val responseData = queryResponse.sliceArray(0 until queryResponse.size - 2)
                    val responseJson = String(responseData, StandardCharsets.UTF_8)

                    Log.d(TAG, "Received address response: $responseJson")

                    try {
                        val response = gson.fromJson(responseJson, ReceiverAddressResponse::class.java)

                        when (response.status) {
                            "success" -> {
                                if (response.receiver_address != null) {
                                    Log.d(TAG, "Receiver address obtained: ${response.receiver_address}")
                                    return response.receiver_address
                                } else {
                                    throw Exception("No receiver address in success response")
                                }
                            }
                            "not_ready" -> {
                                Log.d(TAG, "Receiver address not ready yet, retrying...")
                                retryCount++
                                continue
                            }
                            "error" -> {
                                throw Exception("Receiver reported error: ${response.error}")
                            }
                            else -> {
                                throw Exception("Unknown status in address response: ${response.status}")
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
    private suspend fun sendOfflineTransactionPackage(offlinePackage: String) {
        try {
            Log.d(TAG, "Sending offline transaction package...")
            
            // Wrap the offline transaction in the expected format
            val token = tokenToSend ?: throw Exception("No token set for transfer")
            val transferPackage = OfflineTransactionPackage(
                token_name = token.name,
                offline_transaction = offlinePackage
            )
            val wrappedPackageJson = gson.toJson(transferPackage)
            
            Log.d(TAG, "Wrapped offline package with token name: ${token.name}")
            
            val packageBytes = wrappedPackageJson.toByteArray(StandardCharsets.UTF_8)
            
            // Check size limits
            if (packageBytes.size > MAX_TRANSFER_SIZE) {
                throw Exception("Offline package too large: ${packageBytes.size} bytes")
            }
            
            Log.d(TAG, "Wrapped package size: ${packageBytes.size} bytes, chunking into multiple APDUs")
            
            // Send package in chunks due to APDU size limitations
            var offset = 0
            var chunkIndex = 0
            val totalChunks = (packageBytes.size + MAX_COMMAND_DATA_SIZE - 1) / MAX_COMMAND_DATA_SIZE
            
            while (offset < packageBytes.size) {
                val remainingBytes = packageBytes.size - offset
                val chunkSize = minOf(remainingBytes, MAX_COMMAND_DATA_SIZE)
                val chunk = packageBytes.sliceArray(offset until offset + chunkSize)
                
                Log.d(TAG, "Sending chunk ${chunkIndex + 1}/$totalChunks, size: $chunkSize bytes")
                
                // Create APDU with proper extended length format
                val p1 = if (chunkIndex == 0) 0x00.toByte() else 0x01.toByte() // First chunk vs continuation
                val p2 = if (offset + chunkSize >= packageBytes.size) 0x01.toByte() else 0x00.toByte() // Last chunk indicator
                
                val command = byteArrayOf(0x00.toByte(), CMD_SEND_OFFLINE_TRANSACTION, p1, p2, chunkSize.toByte()) + chunk
                
                val response = apduTransceiver.transceive(command)
                if (!isResponseOK(response)) {
                    throw Exception("Failed to send offline transaction chunk ${chunkIndex + 1}")
                }
                
                offset += chunkSize
                chunkIndex++
                
                // Delay between chunks to avoid overwhelming the connection
                if (chunkIndex < totalChunks) {
                    delay(50) // Increased delay for stability
                }
            }
            
            Log.d(TAG, "Offline transaction package sent successfully in $totalChunks chunks")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send offline transaction package", e)
            throw e
        }
    }
    
    
    private fun isResponseOK(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private suspend fun createTokenTransferRequest(token: Token): String {
        return try {
            // This function will be called by sendTokenDirectly after receiver provides their address
            // It should NOT generate receiver identity - that's the receiver's job!
            
            Log.d(TAG, "Creating token transfer request for token: ${token.name}")
            
            if (token.jsonData.isNullOrEmpty()) {
                Log.e(TAG, "Token has no jsonData - cannot create offline transfer")
                throw Exception("Token jsonData is empty")
            }
            
            // Extract sender data from the token
            val senderData = extractSenderData(token)
            Log.d(TAG, "Extracted sender data successfully")
            
            val parsedTokenData = gson.fromJson(senderData.tokenJson, Map::class.java)
            
            // Extract token ID and type from the nested genesis structure
            val tokenId = try {
                val genesis = parsedTokenData["genesis"] as? Map<*, *>
                val genesisData = genesis?.get("data") as? Map<*, *>
                genesisData?.get("tokenId") ?: parsedTokenData["id"] // Fallback to direct id
            } catch (e: Exception) {
                parsedTokenData["id"] ?: token.id // Fallback to token.id if JSON doesn't have it
            }
            
            val tokenType = try {
                val genesis = parsedTokenData["genesis"] as? Map<*, *>
                val genesisData = genesis?.get("data") as? Map<*, *>
                genesisData?.get("tokenType") ?: parsedTokenData["type"] // Fallback to direct type
            } catch (e: Exception) {
                parsedTokenData["type"] ?: token.type // Fallback to token.type if JSON doesn't have it
            }
            
            Log.d(TAG, "Extracted token ID: $tokenId, token type: $tokenType")

            // Create a minimal request with only the necessary data
            val tokenRequest = TokenTransferRequest(
                token_id = tokenId.toString(),
                token_type = tokenType.toString(),
                token_name = token.name
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
            CoroutineScope(Dispatchers.IO).launch {
                val offlinePackage = sdkService.createOfflineTransfer(
                    gson.toJson(tokenData),
                    receiverAddress,
                    null, // Use full amount
                    senderData.senderIdentity.secret.toByteArray(),
                    senderData.senderIdentity.nonce.toByteArray()
                )
                
                if (offlinePackage != null) {
                    continuation.resume(offlinePackage)
                } else {
                    Log.e(TAG, "Failed to create offline transfer with SDK")
                    // Return fallback offline transfer data
                    val fallbackTransfer = mapOf(
                        "error" to "sdk_offline_transfer_failed",
                        "message" to "Failed to create offline transfer",
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
    
    private suspend fun sendDataInChunks(data: ByteArray) {
        val totalChunks = (data.size + MAX_COMMAND_DATA_SIZE - 1) / MAX_COMMAND_DATA_SIZE
        var chunksSent = 0
        
        Log.d(TAG, "Sending data in $totalChunks chunks (${data.size} bytes total)")
        
        for (offset in data.indices step MAX_COMMAND_DATA_SIZE) {
            val chunkSize = minOf(MAX_COMMAND_DATA_SIZE, data.size - offset)
            val chunk = data.sliceArray(offset until offset + chunkSize)
            
            // Create chunk command
            val chunkCommand = byteArrayOf(
                0x00.toByte(),
                CMD_DATA_CHUNK,
                0x00.toByte(),
                0x00.toByte(),
                chunk.size.toByte()
            ) + chunk
            
            val response = apduTransceiver.transceive(chunkCommand)
            if (!isResponseOK(response)) {
                throw Exception("Failed to send chunk ${chunksSent + 1}/$totalChunks")
            }
            
            chunksSent++
            
            // Update progress
            withContext(Dispatchers.Main) {
                onProgress(chunksSent, totalChunks)
            }
            
            // Small delay between chunks to avoid overwhelming the receiver
            if (chunksSent < totalChunks) {
                delay(50)
            }
        }
        
        Log.d(TAG, "Successfully sent all $totalChunks chunks")
    }
    
    private suspend fun sendCryptoTransferDirectly(token: Token) {
        try {
            Log.d(TAG, "Starting direct crypto transfer for: ${token.name}")
            
            // Wait for connection to stabilize
            delay(500)
            
            // Send the crypto transfer data directly without handshake
            val cryptoData = token.jsonData ?: throw Exception("No crypto data to send")
            Log.d(TAG, "Sending crypto transfer data: ${cryptoData.take(100)}...")
            
            // Send START_DIRECT_TRANSFER command
            val startCommand = byteArrayOf(
                0x00.toByte(), 
                CMD_START_DIRECT_TRANSFER, 
                0x00.toByte(), 
                0x00.toByte(), 
                0x00.toByte()
            )
            
            val startResponse = apduTransceiver.transceive(startCommand)
            if (!isResponseOK(startResponse)) {
                throw Exception("Failed to start crypto transfer")
            }
            
            // Send crypto data in chunks
            val dataBytes = cryptoData.toByteArray(StandardCharsets.UTF_8)
            sendDataInChunks(dataBytes)
            
            // Send COMPLETE_DIRECT_TRANSFER command
            val completeCommand = byteArrayOf(
                0x00.toByte(),
                CMD_COMPLETE_DIRECT_TRANSFER,
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )
            
            val completeResponse = apduTransceiver.transceive(completeCommand)
            if (!isResponseOK(completeResponse)) {
                throw Exception("Failed to complete crypto transfer")
            }
            
            Log.d(TAG, "✅ Crypto transfer completed successfully")
            
            withContext(Dispatchers.Main) {
                onTransferComplete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendCryptoTransferDirectly", e)
            withContext(Dispatchers.Main) {
                onError("Crypto transfer error: ${e.message}")
            }
        }
    }
}
