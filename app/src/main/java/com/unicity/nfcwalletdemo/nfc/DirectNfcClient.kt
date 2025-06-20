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
                
                // Step 1: Select AID
                val selectResponse = isoDep.transceive(SELECT_AID)
                if (!isResponseOK(selectResponse)) {
                    Log.e(TAG, "SELECT AID failed")
                    withContext(Dispatchers.Main) {
                        onError("Failed to select application")
                    }
                    return@launch
                }
                Log.d(TAG, "SELECT AID successful")
                
                // Step 2: Send token directly
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
            // Check the token type and handle accordingly
            val transferData = when (token.type) {
                "Unicity Token" -> {
                    if (token.jsonData != null) {
                        // Create Unicity transfer for real tokens
                        createUnicityTransfer(token)
                    } else {
                        // Demo Unicity token
                        gson.toJson(token)
                    }
                }
                "Crypto Transfer" -> {
                    // For crypto transfers, send the crypto data directly
                    token.jsonData ?: gson.toJson(token)
                }
                else -> {
                    // For other demo tokens, use existing logic
                    gson.toJson(token)
                }
            }
            
            val tokenBytes = transferData.toByteArray(StandardCharsets.UTF_8)
            
            Log.d(TAG, "Sending token: ${token.name}, JSON size: ${tokenBytes.size} bytes")
            
            // Check if token exceeds NFC transfer limits
            if (tokenBytes.size > MAX_TRANSFER_SIZE) {
                Log.e(TAG, "Token size ${tokenBytes.size} exceeds maximum NFC transfer size $MAX_TRANSFER_SIZE")
                withContext(Dispatchers.Main) {
                    onError("Token too large for NFC transfer (${tokenBytes.size} bytes)")
                }
                return
            }
            
            // Split into chunks - first chunk is smaller to accommodate size bytes
            val chunks = mutableListOf<ByteArray>()
            val firstChunkDataSize = MAX_COMMAND_DATA_SIZE - 2 // Account for 2-byte size header
            
            if (tokenBytes.size <= firstChunkDataSize) {
                // Everything fits in first chunk
                chunks.add(tokenBytes)
            } else {
                // First chunk - exactly firstChunkDataSize bytes
                chunks.add(tokenBytes.sliceArray(0 until firstChunkDataSize))
                var offset = firstChunkDataSize
                
                // Remaining chunks - each exactly MAX_COMMAND_DATA_SIZE bytes (except last)
                while (offset < tokenBytes.size) {
                    val remaining = tokenBytes.size - offset
                    val chunkSize = minOf(remaining, MAX_COMMAND_DATA_SIZE)
                    chunks.add(tokenBytes.sliceArray(offset until offset + chunkSize))
                    offset += chunkSize
                }
            }
            
            // Verify total size matches
            val totalChunkData = chunks.sumOf { it.size }
            if (totalChunkData != tokenBytes.size) {
                Log.e(TAG, "Chunk size mismatch: expected ${tokenBytes.size}, got $totalChunkData")
                withContext(Dispatchers.Main) {
                    onError("Internal chunking error")
                }
                return
            }
            
            Log.d(TAG, "Token split into ${chunks.size} chunks")
            
            // Send each chunk
            for (i in chunks.indices) {
                val chunk = chunks[i]
                
                withContext(Dispatchers.Main) {
                    onProgress(i + 1, chunks.size)
                }
                
                val command = if (i == 0) {
                    // First chunk - build proper APDU with P3 (Lc)
                    val sizeBytes = byteArrayOf(
                        ((tokenBytes.size shr 8) and 0xFF).toByte(),
                        (tokenBytes.size and 0xFF).toByte()
                    )
                    val dataToSend = sizeBytes + chunk
                    val lc = (dataToSend.size and 0xFF).toByte()
                    byteArrayOf(0x00.toByte(), CMD_START_DIRECT_TRANSFER, 0x00.toByte(), 0x00.toByte(), lc) + dataToSend
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
            
            // Complete transfer - wait for receiver to confirm
            Log.d(TAG, "All chunks sent, waiting for receiver confirmation...")
            val completeCommand = byteArrayOf(0x00.toByte(), CMD_TRANSFER_COMPLETE, 0x00.toByte(), 0x00.toByte())
            val completeResponse = isoDep.transceive(completeCommand)
            
            if (isResponseOK(completeResponse)) {
                Log.d(TAG, "✅ Receiver confirmed - NFC transfer completed successfully!")
                withContext(Dispatchers.Main) {
                    onTransferComplete()
                }
            } else {
                Log.e(TAG, "Transfer completion failed - receiver did not confirm")
                withContext(Dispatchers.Main) {
                    onError("Failed to complete transfer")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendTokenDirectly", e)
            withContext(Dispatchers.Main) {
                onError("Transfer error: ${e.message}")
            }
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
    
    private suspend fun createUnicityTransfer(token: Token): String {
        return try {
            // For real Unicity tokens, we need to:
            // 1. Generate a receiver identity
            // 2. Create a transfer transaction using the SDK
            // 3. Return the transfer data as JSON
            
            Log.d(TAG, "Creating Unicity transfer for token: ${token.name}")
            
            // Generate receiver identity (this should ideally come from the receiver)
            // For now, we'll generate it here for demo purposes
            val receiverIdentity = generateReceiverIdentity()
            
            // Extract sender identity and token data from the stored jsonData
            // The jsonData contains the UnicityMintResult with token and identity
            val senderData = extractSenderData(token)
            
            // Create transfer using SDK
            val transferResult = createTransferWithSdk(
                senderData.senderIdentity,
                receiverIdentity,
                senderData.tokenJson
            )
            
            // Create transfer package with all necessary data
            val transferPackage = mapOf(
                "type" to "unicity_transfer",
                "token_name" to token.name,
                "transfer_data" to transferResult,
                "receiver_identity" to receiverIdentity.toJson()
            )
            
            gson.toJson(transferPackage)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Unicity transfer", e)
            // Fallback to demo token transfer
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