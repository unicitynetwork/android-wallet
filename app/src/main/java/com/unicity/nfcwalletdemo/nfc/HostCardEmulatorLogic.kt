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
            val tokenRequest = gson.fromJson(requestData, Map::class.java)
            val tokenName = tokenRequest["token_name"] as? String ?: "Unknown Token"

            val tokenId = tokenRequest["token_id"]
            val tokenType = tokenRequest["token_type"]

            if (tokenId == null || tokenType == null) {
                Log.e(TAG, "Invalid token request - missing token ID or type in genesis data")
                return SW_ERROR
            }

            Log.d(TAG, "Token request for: $tokenName (ID: $tokenId, Type: $tokenType)")

            // Generate receiver identity and address for this specific token
            // This should be done by the receiver (this device)
            generateReceiverAddressForToken(tokenId, tokenType, tokenName)

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
    private fun generateReceiverAddressForToken(tokenId: Any, tokenType: Any, tokenName: String) {
        try {
            Log.d(TAG, "Generating receiver address for token: $tokenName")

            val tokenIdString = tokenId.toString()
            val tokenTypeString = tokenType.toString()

            Log.d(TAG, "Starting receiver address generation for token ID: $tokenIdString, Type: $tokenTypeString")

            // Generate receiver identity for this device
            sdkService.generateIdentity { identityResult ->
                identityResult.onSuccess { identityJson ->
                    try {
                        val receiverIdentity = gson.fromJson(identityJson, Map::class.java) as Map<String, String>
                        generatedReceiverIdentity = receiverIdentity
                        generatedReceiverIdentityStatic = receiverIdentity // Store in static for ReceiveActivity

                        Log.d(TAG, "Receiver identity generated successfully")

                        // Generate receiving address using the specific token ID and type
                        sdkService.generateReceivingAddressForOfflineTransfer(
                            tokenIdString,
                            tokenTypeString,
                            gson.toJson(receiverIdentity)
                        ) { addressResult ->
                            addressResult.onSuccess { addressResponseJson ->
                                try {
                                    val addressResponse = gson.fromJson(addressResponseJson, Map::class.java)
                                    val receiverAddress = addressResponse["address"] as? String

                                    if (receiverAddress != null) {
                                        // Store the generated address for sender to retrieve
                                        generatedReceiverAddress = receiverAddress

                                        Log.d(TAG, "Receiver address generated successfully: $receiverAddress")
                                        Log.d(TAG, "Receiver address ready for sender retrieval")

                                    } else {
                                        Log.e(TAG, "No address found in response: $addressResponseJson")
                                        generatedReceiverAddress = null
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse address response", e)
                                    generatedReceiverAddress = null
                                }
                            }
                            addressResult.onFailure { error ->
                                Log.e(TAG, "Failed to generate receiving address via SDK", error)
                                generatedReceiverAddress = null
                            }
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

                // Create response with the receiver address
                val response = mapOf(
                    "status" to "success",
                    "receiver_address" to address,
                    "receiver_identity" to generatedReceiverIdentity
                )

                val responseJson = gson.toJson(response)
                val responseBytes = responseJson.toByteArray(StandardCharsets.UTF_8)

                // Return the address data + SW_OK
                responseBytes + SW_OK

            } else {
                Log.e(TAG, "No receiver address available - still generating or failed")

                // Return "not ready" response
                val response = mapOf(
                    "status" to "not_ready",
                    "message" to "Receiver address still being generated"
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
     * Handles CMD_SEND_OFFLINE_TRANSACTION
     */
    private fun startOfflineTransactionReceive(commandApdu: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Receiving offline transaction package")

            // Extract offline transaction data from APDU
            val dataStart = 5 // Skip CLA, INS, P1, P2, Lc
            if (commandApdu.size <= dataStart) {
                Log.e(TAG, "Invalid offline transaction - no data")
                return SW_ERROR
            }

            val offlineTransactionData = String(commandApdu.sliceArray(dataStart until commandApdu.size), StandardCharsets.UTF_8)
            Log.d(TAG, "Received offline transaction data size: ${offlineTransactionData.length} characters")

            // Parse the offline transaction package
            val transferPackage = gson.fromJson(offlineTransactionData, Map::class.java)
            val transferType = transferPackage["type"] as? String

            if (transferType == "unicity_offline_transfer") {
                Log.d(TAG, "Processing Unicity offline transfer")
                processOfflineUnicityTransfer(transferPackage)
            } else {
                Log.w(TAG, "Unknown offline transfer type: $transferType")
            }

            SW_OK

        } catch (e: Exception) {
            Log.e(TAG, "Error receiving offline transaction", e)
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

            // Parse the offline transaction package
            val transferPackage = gson.fromJson(receivedData, Map::class.java)
            val transferType = transferPackage["type"] as? String

            if (transferType == "unicity_offline_transfer") {
                Log.d(TAG, "Processing Unicity offline transfer")
                processOfflineUnicityTransfer(transferPackage)
            } else {
                Log.w(TAG, "Unknown offline transfer type: $transferType")
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
    private fun processOfflineUnicityTransfer(transferPackage: Map<*, *>) {
        try {
            val tokenName = transferPackage["token_name"] as? String ?: "Unknown Token"
            val offlineTransaction = transferPackage["offline_transaction"] as? String ?: ""

            Log.d(TAG, "Processing offline Unicity transfer for token: $tokenName")

            // Broadcast the offline transfer to ReceiveActivity for processing
            val intent = Intent("com.unicity.nfcwalletdemo.TOKEN_RECEIVED").apply {
                putExtra("transfer_type", "unicity_offline_transfer")
                putExtra("token_name", tokenName)
                putExtra("offline_transaction", offlineTransaction)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            Log.d(TAG, "Broadcast sent for offline Unicity transfer")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing offline Unicity transfer", e)
        }
    }
}