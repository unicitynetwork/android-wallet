package com.unicity.nfcwalletdemo.nfc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import com.unicity.nfcwalletdemo.nfc.TokenTransferRequest
import com.unicity.nfcwalletdemo.nfc.ReceiverAddressResponse
import com.unicity.nfcwalletdemo.nfc.OfflineTransactionPackage
import com.unicity.nfcwalletdemo.nfc.TestPingMessage
import com.unicity.nfcwalletdemo.nfc.TestPongResponse

class HostCardEmulatorLogic(
    private val context: Context,
    private val sdkService: UnicitySdkService
) {

    // Transfer state
    private var pendingToken: Token? = null
    private var tokenChunks: List<ByteArray> = emptyList()
    private var currentChunkIndex = 0
    private var transferMode = "BLE_READY"
    private val gson = Gson()

    // Storage for generated receiver addresses
    private var generatedReceiverAddress: String? = null
    private var generatedReceiverIdentity: Map<String, String>? = null
    
    // Offline transaction chunked receiving state
    private var offlineTransactionBuffer = ByteArrayOutputStream()
    private var isReceivingOfflineTransaction = false

    companion object {
        private const val TAG = "HostCardEmulatorLogic"

        // Static variable to receive token from sender
        var tokenToReceive: Token? = null
        // Static variable to control transfer mode
        var currentTransferMode: String = "BLE_READY"

        // Static storage for generated receiver identity during handshake
        private var generatedReceiverIdentityStatic: Map<String, String>? = null

        /**
         * Get the receiver identity that was generated during the handshake
         */
        fun getGeneratedReceiverIdentity(): Map<String, String>? {
            return generatedReceiverIdentityStatic
        }

        // AID for our application
        private val SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        // Commands for offline transfer handshake protocol
        private const val CMD_REQUEST_RECEIVER_ADDRESS: Byte = 0x05
        private const val CMD_GET_RECEIVER_ADDRESS: Byte = 0x06
        private const val CMD_SEND_OFFLINE_TRANSACTION: Byte = 0x07
        
        // Commands for test mode
        private const val CMD_TEST_PING: Byte = 0x08
        private const val CMD_TEST_PONG: Byte = 0x09

        // Transfer modes
        const val TRANSFER_MODE_DIRECT = "DIRECT_READY"

        // Maximum APDU response size (minus status word)
        private const val MAX_APDU_SIZE = 240

        // Android HCE seems to have an internal buffer limit around 37KB
        private const val MAX_TRANSFER_SIZE = 36000 // Stay below 37KB limit
    }

    fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_ERROR
        }

        Log.d(TAG, "Received APDU: ${commandApdu.toHexString()}")

        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            return SW_OK
        }

        // Check command type - only proper offline handshake protocol supported
        when (commandApdu[1]) {
            CMD_REQUEST_RECEIVER_ADDRESS -> {
                Log.d(TAG, "REQUEST_RECEIVER_ADDRESS command received")
                // Start ReceiveActivity for offline handshake protocol
                startReceiveActivity()
                return handleReceiverAddressRequest(commandApdu)
            }
            CMD_GET_RECEIVER_ADDRESS -> {
                Log.d(TAG, "GET_RECEIVER_ADDRESS command received")
                return sendReceiverAddress()
            }
            CMD_SEND_OFFLINE_TRANSACTION -> {
                Log.d(TAG, "SEND_OFFLINE_TRANSACTION command received")
                return startOfflineTransactionReceive(commandApdu)
            }
            CMD_TEST_PING -> {
                Log.d(TAG, "TEST_PING command received")
                // Start ReceiveActivity to show test is in progress
                startReceiveActivity()
                return handleTestPing(commandApdu)
            }
        }

        return SW_ERROR
    }

    private fun isSelectAidCommand(apdu: ByteArray): Boolean {
        if (apdu.size < SELECT_AID.size) return false

        for (i in SELECT_AID.indices) {
            if (apdu[i] != SELECT_AID[i]) return false
        }
        return true
    }


    private var directTransferBuffer = ByteArrayOutputStream()
    private var expectedTotalSize = 0
    private var isProcessingToken = false

    private fun startDirectTransfer(commandApdu: ByteArray): ByteArray {
        try {
            if (commandApdu.size < 7) {
                Log.e(TAG, "Invalid START_DIRECT_TRANSFER command size: ${commandApdu.size}")
                return SW_ERROR
            }

            // APDU format: CLA INS P1 P2 Lc Data
            // Skip first 5 bytes (CLA, INS, P1, P2, Lc) to get to data
            val dataStart = 5

            // Extract token data length (first 2 bytes of data)
            expectedTotalSize = ((commandApdu[dataStart].toInt() and 0xFF) shl 8) or
                               (commandApdu[dataStart + 1].toInt() and 0xFF)
            Log.d(TAG, "Starting direct transfer, expecting total size: $expectedTotalSize bytes")

            // Check if transfer size exceeds Android HCE limits
            if (expectedTotalSize > MAX_TRANSFER_SIZE) {
                Log.e(TAG, "Transfer size $expectedTotalSize exceeds maximum allowed $MAX_TRANSFER_SIZE")
                return SW_ERROR
            }

            // Reset buffer for new transfer
            directTransferBuffer = ByteArrayOutputStream()

            // Extract first chunk data (everything after the 2 size bytes)
            val firstChunkDataStart = dataStart + 2
            if (commandApdu.size > firstChunkDataStart) {
                val firstChunkData = commandApdu.sliceArray(firstChunkDataStart until commandApdu.size)

                // Only accept data up to expected size
                val dataToWrite = if (firstChunkData.size > expectedTotalSize) {
                    Log.w(TAG, "First chunk size ${firstChunkData.size} exceeds total size $expectedTotalSize, truncating")
                    firstChunkData.sliceArray(0 until expectedTotalSize)
                } else {
                    firstChunkData
                }

                directTransferBuffer.write(dataToWrite)
                Log.d(TAG, "Received first chunk: ${dataToWrite.size} bytes, total buffered: ${directTransferBuffer.size()}")

                // Broadcast progress update
                broadcastProgress(directTransferBuffer.size(), expectedTotalSize)
            }

            // Check if this is a single-chunk transfer
            if (directTransferBuffer.size() == expectedTotalSize) {
                return processCompleteToken()
            }

            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error in startDirectTransfer", e)
            return SW_ERROR
        }
    }

    private fun getNextChunk(commandApdu: ByteArray): ByteArray {
        try {
            // APDU format: CLA INS P1 P2 Lc Data
            // Skip first 5 bytes to get to data
            if (commandApdu.size <= 5) {
                Log.e(TAG, "No chunk data in GET_CHUNK command")
                return SW_ERROR
            }

            val chunkData = commandApdu.sliceArray(5 until commandApdu.size)

            // Calculate how much data we still need
            val remainingBytes = expectedTotalSize - directTransferBuffer.size()

            if (remainingBytes <= 0) {
                Log.e(TAG, "Received extra data after transfer should be complete")
                return SW_ERROR
            }

            // Only accept the data we need
            val dataToWrite = if (chunkData.size > remainingBytes) {
                Log.w(TAG, "Chunk size ${chunkData.size} exceeds remaining bytes $remainingBytes, truncating")
                chunkData.sliceArray(0 until remainingBytes)
            } else {
                chunkData
            }

            directTransferBuffer.write(dataToWrite)

            Log.d(TAG, "Received chunk: ${dataToWrite.size} bytes, total buffered: ${directTransferBuffer.size()}/$expectedTotalSize")

            // Broadcast progress update
            broadcastProgress(directTransferBuffer.size(), expectedTotalSize)

            // Check if we have received exactly all data
            if (directTransferBuffer.size() == expectedTotalSize) {
                return processCompleteToken()
            }

            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chunk", e)
            return SW_ERROR
        }
    }

    private fun processCompleteToken(): ByteArray {
        if (isProcessingToken) {
            Log.w(TAG, "Already processing token, ignoring duplicate call")
            return SW_OK
        }

        isProcessingToken = true
        try {
            val receivedData = String(directTransferBuffer.toByteArray(), StandardCharsets.UTF_8)
            Log.d(TAG, "Processing complete data, JSON size: ${receivedData.length}")

            // Check if this is crypto transfer data or token data
            val transferType = try {
                val dataMap = gson.fromJson(receivedData, Map::class.java)
                dataMap["type"] as? String
            } catch (e: Exception) {
                null
            }

            when (transferType) {
                "crypto_transfer" -> {
                    Log.d(TAG, "Processing crypto transfer")
                    processCryptoTransfer(receivedData)
                }
                else -> {
                    Log.d(TAG, "Processing token transfer")
                    processTokenTransfer(receivedData)
                }
            }

            // Reset state for next transfer
            directTransferBuffer = ByteArrayOutputStream()
            expectedTotalSize = 0

            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error processing complete data", e)
            // Reset state on error
            directTransferBuffer = ByteArrayOutputStream()
            expectedTotalSize = 0
            return SW_ERROR
        } finally {
            isProcessingToken = false
        }
    }

    private fun processTokenTransfer(tokenJson: String) {
        try {
            // Parse the token
            val receivedToken = gson.fromJson(tokenJson, Token::class.java)
            tokenToReceive = receivedToken

            Log.d(TAG, "Token successfully received via direct NFC: ${receivedToken.name}")

            // Save token transfer to SharedPreferences for persistence
            val timestamp = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("nfc_token_transfers", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_token_json", tokenJson)
                putLong("last_token_time", timestamp)
                putString("last_token_name", receivedToken.name)
                apply()
            }
            Log.d(TAG, "Saved token transfer to SharedPreferences")

            // Notify ReceiveActivity with local broadcast for safety
            val intent = Intent("com.unicity.nfcwalletdemo.TOKEN_RECEIVED").apply {
                putExtra("token_json", tokenJson)
                setPackage(context.packageName) // Restrict to our app only
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent for received token")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing token transfer", e)
        }
    }

    private fun processCryptoTransfer(cryptoJson: String) {
        try {
            Log.d(TAG, "Crypto transfer successfully received via direct NFC")

            // Notify ReceiveActivity with crypto transfer broadcast
            val intent = Intent("com.unicity.nfcwalletdemo.CRYPTO_RECEIVED").apply {
                putExtra("crypto_json", cryptoJson)
                setPackage(context.packageName) // Restrict to our app only
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent for received crypto")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing crypto transfer", e)
        }
    }

    private fun completeTransfer(): ByteArray {
        try {
            Log.d(TAG, "Transfer completion request received")

            // Make sure we have received all the data
            if (directTransferBuffer.size() < expectedTotalSize) {
                Log.e(TAG, "Transfer incomplete: received ${directTransferBuffer.size()} of $expectedTotalSize bytes")
                return SW_ERROR
            }

            // Process the token if not already done
            if (directTransferBuffer.size() > 0) {
                val result = processCompleteToken()
                if (result.contentEquals(SW_ERROR)) {
                    return SW_ERROR
                }
            }

            Log.d(TAG, "Transfer completed and token processed successfully")

            // Reset any remaining state
            pendingToken = null
            tokenChunks = emptyList()
            currentChunkIndex = 0
            directTransferBuffer = ByteArrayOutputStream()
            expectedTotalSize = 0

            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error completing transfer", e)
            return SW_ERROR
        }
    }

    fun setDirectTransferMode(token: Token) {
        transferMode = TRANSFER_MODE_DIRECT
        pendingToken = token

        // Create chunks for sending
        val tokenJson = gson.toJson(token)
        val tokenBytes = tokenJson.toByteArray(StandardCharsets.UTF_8)

        Log.d(TAG, "Preparing direct transfer for token: ${token.name}, size: ${tokenBytes.size} bytes")

        tokenChunks = tokenBytes.toList().chunked(MAX_APDU_SIZE).map { it.toByteArray() }
        currentChunkIndex = 0

        Log.d(TAG, "Token split into ${tokenChunks.size} chunks")
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun broadcastProgress(currentBytes: Int, totalBytes: Int) {
        try {
            val intent = Intent("com.unicity.nfcwalletdemo.TRANSFER_PROGRESS").apply {
                putExtra("current_bytes", currentBytes)
                putExtra("total_bytes", totalBytes)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting progress", e)
        }
    }

    private fun startReceiveActivity() {
        try {
            val intent = Intent(context, ReceiveActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_started", true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ReceiveActivity", e)
        }
    }

    // NEW OFFLINE TRANSFER HANDSHAKE HANDLERS

    /**
     * Sender requests receiver address for a specific token
     * Handles CMD_REQUEST_RECEIVER_ADDRESS
     */
    private fun handleReceiverAddressRequest(commandApdu: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Processing receiver address request")

            // Extract token request data from APDU
            val dataStart = 5 // Skip CLA, INS, P1, P2, Lc
            if (commandApdu.size <= dataStart) {
                Log.e(TAG, "Invalid receiver address request - no data")
                return SW_ERROR
            }

            val requestData = String(commandApdu.sliceArray(dataStart until commandApdu.size), StandardCharsets.UTF_8)
            Log.d(TAG, "Received token request: $requestData")

            // Parse the token request to get token ID and type
            val tokenRequest = try {
                gson.fromJson(requestData, TokenTransferRequest::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse token transfer request", e)
                return SW_ERROR
            }

            Log.d(TAG, "Token request for: ${tokenRequest.token_name} (ID: ${tokenRequest.token_id}, Type: ${tokenRequest.token_type})")

            // Generate receiver identity and address for this specific token
            // This should be done by the receiver (this device)
            generateReceiverAddressForToken(tokenRequest.token_id, tokenRequest.token_type, tokenRequest.token_name)

            // Return OK to indicate we're processing the request
            // The actual address will be sent in response to a subsequent query
            SW_OK

        } catch (e: Exception) {
            Log.e(TAG, "Error handling receiver address request", e)
            SW_ERROR
        }
    }

    /**
     * Generate receiver address and prepare to send it back
     * This is called internally after receiving the token request
     */
    private fun generateReceiverAddressForToken(tokenId: String, tokenType: String, tokenName: String) {
        try {
            Log.d(TAG, "Generating receiver address for token: $tokenName")

            val tokenIdString = tokenId
            val tokenTypeString = tokenType

            Log.d(TAG, "Starting receiver address generation for token ID: $tokenIdString, Type: $tokenTypeString")

            // Generate receiver identity for this device
            sdkService.generateIdentity { identityResult ->
                identityResult.onSuccess { identityJson ->
                    try {
                        val receiverIdentity = gson.fromJson(identityJson, Map::class.java) as Map<String, String>
                        generatedReceiverIdentity = receiverIdentity
                        generatedReceiverIdentityStatic = receiverIdentity // Store in static for ReceiveActivity

                        Log.d(TAG, "Receiver identity generated successfully")

                        // Generate receiving address based on the receiver identity
                        // For the demo, we'll use a simple format based on the identity secret
                        val secret = receiverIdentity["secret"] as? String
                        if (secret != null) {
                            // Create a simple address format for the demo
                            generatedReceiverAddress = "oddity_${secret.take(16)}"
                            Log.d(TAG, "Receiver address generated successfully: $generatedReceiverAddress")
                            Log.d(TAG, "Receiver address ready for sender retrieval")
                        } else {
                            Log.e(TAG, "No secret found in receiver identity")
                            generatedReceiverAddress = null
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse receiver identity", e)
                        generatedReceiverIdentity = null
                        generatedReceiverAddress = null
                    }
                }
                identityResult.onFailure { error ->
                    Log.e(TAG, "Failed to generate receiver identity", error)
                    generatedReceiverIdentity = null
                    generatedReceiverAddress = null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in generateReceiverAddressForToken", e)
            generatedReceiverAddress = null
            generatedReceiverIdentity = null
        }
    }

    /**
     * Send the generated receiver address back to sender
     * Handles CMD_GET_RECEIVER_ADDRESS
     */
    private fun sendReceiverAddress(): ByteArray {
        return try {
            Log.d(TAG, "Sending receiver address to sender")

            val address = generatedReceiverAddress
            if (address != null) {
                Log.d(TAG, "Returning generated receiver address: $address")

                // Create response with the receiver address only
                // Do NOT include receiver_identity as it makes the response too large for NFC
                val response = ReceiverAddressResponse(
                    status = "success",
                    receiver_address = address
                )

                val responseJson = gson.toJson(response)
                val responseBytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                
                // Check response size to ensure it fits in APDU
                Log.d(TAG, "Response size: ${responseBytes.size} bytes")
                if (responseBytes.size > 250) {
                    Log.e(TAG, "Response too large: ${responseBytes.size} bytes, using minimal format")
                    // If still too large, just send the address
                    val minimalResponse = mapOf("address" to address)
                    val minimalJson = gson.toJson(minimalResponse)
                    minimalJson.toByteArray(StandardCharsets.UTF_8) + SW_OK
                } else {
                    responseBytes + SW_OK
                }

            } else {
                Log.e(TAG, "No receiver address available - still generating or failed")

                // Return "not ready" response
                val response = ReceiverAddressResponse(
                    status = "not_ready",
                    error = "Receiver address still being generated"
                )

                val responseJson = gson.toJson(response)
                val responseBytes = responseJson.toByteArray(StandardCharsets.UTF_8)

                // Return the not ready response + SW_OK (sender should retry)
                responseBytes + SW_OK
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending receiver address", e)
            SW_ERROR
        }
    }

    /**
     * Handle offline transaction package from sender
     * Handles CMD_SEND_OFFLINE_TRANSACTION with chunked data support
     */
    private fun startOfflineTransactionReceive(commandApdu: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Receiving offline transaction chunk")

            if (commandApdu.size < 5) {
                Log.e(TAG, "Invalid offline transaction APDU - too short")
                return SW_ERROR
            }

            val p1 = commandApdu[2] // First chunk indicator
            val p2 = commandApdu[3] // Last chunk indicator
            val lc = commandApdu[4].toInt() and 0xFF

            // Extract chunk data from APDU
            val dataStart = 5
            if (commandApdu.size < dataStart + lc) {
                Log.e(TAG, "Invalid offline transaction APDU - data length mismatch")
                return SW_ERROR
            }

            val chunkData = commandApdu.sliceArray(dataStart until dataStart + lc)
            
            // Handle first chunk
            if (p1 == 0x00.toByte()) {
                Log.d(TAG, "Starting offline transaction receive, first chunk size: ${chunkData.size}")
                offlineTransactionBuffer = ByteArrayOutputStream()
                isReceivingOfflineTransaction = true
            }
            
            if (!isReceivingOfflineTransaction) {
                Log.e(TAG, "Received continuation chunk without starting transaction")
                return SW_ERROR
            }

            // Append chunk data
            offlineTransactionBuffer.write(chunkData)
            Log.d(TAG, "Received chunk: ${chunkData.size} bytes, total buffered: ${offlineTransactionBuffer.size()}, p1=${p1.toInt() and 0xFF}, p2=${p2.toInt() and 0xFF}")

            // Handle last chunk
            if (p2 == 0x01.toByte()) {
                Log.d(TAG, "Received final chunk, processing complete offline transaction")
                
                val completeOfflineTransactionData = String(offlineTransactionBuffer.toByteArray(), StandardCharsets.UTF_8)
                Log.d(TAG, "Complete offline transaction data size: ${completeOfflineTransactionData.length} characters")

                try {
                    // Parse the complete offline transaction package
                    val transferPackage = gson.fromJson(completeOfflineTransactionData, Map::class.java)
                    Log.d(TAG, "Successfully parsed offline transaction package")
                    
                    // Log the structure to understand what we received
                    Log.d(TAG, "Transfer package keys: ${transferPackage.keys}")
                    Log.d(TAG, "First 500 chars of data: ${completeOfflineTransactionData.take(500)}")
                    
                    // Process the offline Unicity transfer package
                    processOfflineUnicityTransfer(completeOfflineTransactionData)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse offline transaction data", e)
                    Log.e(TAG, "Data snippet: ${completeOfflineTransactionData.take(200)}...")
                }

                // Reset state
                offlineTransactionBuffer = ByteArrayOutputStream()
                isReceivingOfflineTransaction = false
            }

            SW_OK

        } catch (e: Exception) {
            Log.e(TAG, "Error receiving offline transaction chunk", e)
            // Reset state on error
            offlineTransactionBuffer = ByteArrayOutputStream()
            isReceivingOfflineTransaction = false
            SW_ERROR
        }
    }

    /**
     * Process complete offline transaction package
     */
    private fun processCompleteOfflineTransaction(): ByteArray {
        return try {
            Log.d(TAG, "Processing complete offline transaction")

            val receivedData = directTransferBuffer.toString(StandardCharsets.UTF_8.name())
            Log.d(TAG, "Received offline transaction data size: ${receivedData.length} characters")

            // Try to parse as OfflineTransactionPackage first
            try {
                val transferPackage = gson.fromJson(receivedData, OfflineTransactionPackage::class.java)
                Log.d(TAG, "Successfully parsed as OfflineTransactionPackage, processing...")
                processOfflineUnicityTransfer(receivedData)
            } catch (e: Exception) {
                // Fall back to old format with type field
                Log.w(TAG, "Failed to parse as OfflineTransactionPackage, trying legacy format", e)
                val transferPackage = gson.fromJson(receivedData, Map::class.java)
                val transferType = transferPackage["type"] as? String
                
                if (transferType == "unicity_offline_transfer") {
                    Log.d(TAG, "Processing legacy Unicity offline transfer")
                    // For legacy format, we need to extract the data differently
                    val tokenName = transferPackage["token_name"] as? String ?: "Unknown Token"
                    val offlineTransaction = transferPackage["offline_transaction"] as? String ?: ""
                    val newPackage = OfflineTransactionPackage(tokenName, offlineTransaction)
                    processOfflineUnicityTransfer(gson.toJson(newPackage))
                } else {
                    Log.w(TAG, "Unknown offline transfer type: $transferType")
                }
            }

            // Reset state
            directTransferBuffer = ByteArrayOutputStream()
            expectedTotalSize = 0

            SW_OK

        } catch (e: Exception) {
            Log.e(TAG, "Error processing complete offline transaction", e)
            directTransferBuffer = ByteArrayOutputStream()
            expectedTotalSize = 0
            SW_ERROR
        }
    }

    /**
     * Process the offline Unicity transfer package
     */
    private fun processOfflineUnicityTransfer(transferPackageJson: String) {
        try {
            // Parse the transfer package
            val transferPackage = try {
                gson.fromJson(transferPackageJson, OfflineTransactionPackage::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse offline transaction package", e)
                return
            }

            Log.d(TAG, "Processing offline Unicity transfer for token: ${transferPackage.token_name}")

            // Save offline transfer to SharedPreferences for persistence
            val timestamp = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("nfc_token_transfers", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_offline_transaction", transferPackage.offline_transaction)
                putLong("last_offline_time", timestamp)
                putString("last_offline_token_name", transferPackage.token_name)
                putString("last_transfer_type", "unicity_offline_transfer")
                apply()
            }
            Log.d(TAG, "Saved offline transfer to SharedPreferences")

            // Broadcast the offline transfer to ReceiveActivity for processing
            val intent = Intent("com.unicity.nfcwalletdemo.TOKEN_RECEIVED").apply {
                putExtra("transfer_type", "unicity_offline_transfer")
                putExtra("token_name", transferPackage.token_name)
                putExtra("offline_transaction", transferPackage.offline_transaction)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            Log.d(TAG, "Broadcast sent for offline Unicity transfer")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing offline Unicity transfer", e)
        }
    }
    
    /**
     * Process offline transaction package using SDK
     */
    private fun processOfflineTransactionWithSdk(offlineTransactionData: String) {
        try {
            Log.d(TAG, "Processing offline transaction with SDK")
            
            val receiverIdentity = generatedReceiverIdentity
            if (receiverIdentity == null) {
                Log.e(TAG, "No receiver identity available for processing offline transaction")
                return
            }
            
            Log.d(TAG, "Using receiver identity for offline transaction processing")
            
            // Extract the actual transaction data from the SDK response format
            val actualTransactionData = try {
                // First parse: Extract from SDK response wrapper {"status":"success","data":"..."}
                val responseJson = gson.fromJson(offlineTransactionData, Map::class.java)
                val status = responseJson["status"] as? String
                
                if (status == "success") {
                    val dataString = responseJson["data"] as? String
                    if (dataString != null) {
                        Log.d(TAG, "Extracted transaction data from SDK response wrapper")
                        // The data field contains the actual offline transaction JSON as a string
                        dataString
                    } else {
                        Log.w(TAG, "No 'data' field in success response, using full response")
                        offlineTransactionData
                    }
                } else {
                    Log.w(TAG, "Response status is not success: $status, using original data")
                    offlineTransactionData
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse as SDK response format, using original data", e)
                offlineTransactionData
            }
            
            Log.d(TAG, "Transaction data length: ${actualTransactionData.length}")
            Log.d(TAG, "Transaction data preview: ${actualTransactionData.take(500)}...")
            
            // Check if the transaction data is double-encoded JSON
            val finalTransactionData = try {
                // Try to parse it as JSON to see if it's a stringified JSON
                val parsed = gson.fromJson(actualTransactionData, Map::class.java)
                Log.d(TAG, "Transaction data appears to be valid JSON, using as-is")
                actualTransactionData
            } catch (e: Exception) {
                // Not valid JSON, might be double-encoded
                Log.w(TAG, "Transaction data is not valid JSON, checking if it's escaped")
                actualTransactionData
            }
            
            // Complete the offline transaction using the SDK
            // receiverIdentity is already a Map<String, String>, convert it to JSON
            val receiverIdentityJson = gson.toJson(receiverIdentity)
            Log.d(TAG, "Receiver identity JSON: ${receiverIdentityJson.take(100)}...")
            
            // Log the full transaction data to understand its structure
            try {
                val transactionObj = gson.fromJson(actualTransactionData, Map::class.java)
                Log.d(TAG, "Transaction object keys: ${transactionObj.keys}")
                if (transactionObj.containsKey("commitment")) {
                    Log.d(TAG, "Found commitment in transaction data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse transaction data for inspection", e)
            }
            
            sdkService.completeOfflineTransfer(
                receiverIdentityJson,
                finalTransactionData
            ) { result: Result<String> ->
                result.onSuccess { processedTokenJson: String ->
                    Log.d(TAG, "Offline transaction processed successfully by SDK")
                    
                    // Broadcast the processed token to ReceiveActivity
                    val intent = Intent("com.unicity.nfcwalletdemo.TOKEN_RECEIVED").apply {
                        putExtra("transfer_type", "unicity_offline_processed")
                        putExtra("processed_token", processedTokenJson)
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                    
                    Log.d(TAG, "Broadcast sent for processed offline transaction")
                }
                result.onFailure { error: Throwable ->
                    Log.e(TAG, "Failed to process offline transaction with SDK", error)
                    
                    // Broadcast error to ReceiveActivity
                    val intent = Intent("com.unicity.nfcwalletdemo.TOKEN_RECEIVED").apply {
                        putExtra("transfer_type", "unicity_offline_error")
                        putExtra("error_message", error.message ?: "Unknown error")
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing offline transaction with SDK", e)
        }
    }
    
    /**
     * Handle test PING command - simple echo back with timestamp
     */
    private fun handleTestPing(commandApdu: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Processing test PING command")
            
            // Extract ping data from APDU
            val dataStart = 5 // Skip CLA, INS, P1, P2, Lc
            if (commandApdu.size <= dataStart) {
                Log.e(TAG, "Invalid PING command - no data")
                return SW_ERROR
            }
            
            val pingData = commandApdu.sliceArray(dataStart until commandApdu.size)
            val pingJson = String(pingData, StandardCharsets.UTF_8)
            Log.d(TAG, "Received PING JSON: $pingJson")
            
            // Parse the ping message
            val pingMessage = try {
                gson.fromJson(pingJson, TestPingMessage::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ping message", e)
                return SW_ERROR
            }
            
            Log.d(TAG, "Parsed PING: ${pingMessage.message} at ${pingMessage.timestamp}")
            
            // Save test result to SharedPreferences for persistence
            val timestamp = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("nfc_test_results", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("last_test_ping", pingMessage.message)
                putLong("last_test_time", timestamp)
                apply()
            }
            Log.d(TAG, "Saved test result to SharedPreferences")
            
            // Notify UI about test transfer
            val testIntent = Intent("com.unicity.nfcwalletdemo.NFC_TEST_RECEIVED").apply {
                putExtra("ping_message", pingMessage.message)
                putExtra("timestamp", pingMessage.timestamp)
                setPackage(context.packageName) // Restrict to our app only
            }
            context.sendBroadcast(testIntent)
            Log.d(TAG, "Broadcast sent for NFC test received")
            
            // Create PONG response
            val pongResponse = TestPongResponse(
                original_message = pingMessage.message,
                response_message = "PONG received",
                timestamp = System.currentTimeMillis()
            )
            val responseBytes = gson.toJson(pongResponse).toByteArray(StandardCharsets.UTF_8)
            
            Log.d(TAG, "Sending PONG: $pongResponse")
            
            // Return response data + SW_OK
            responseBytes + SW_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling test PING", e)
            SW_ERROR
        }
    }
}