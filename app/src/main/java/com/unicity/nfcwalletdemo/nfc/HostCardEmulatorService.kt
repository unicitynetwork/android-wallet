package com.unicity.nfcwalletdemo.nfc

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import java.nio.charset.StandardCharsets
import com.unicity.nfcwalletdemo.data.model.Token
import com.google.gson.Gson
import java.io.ByteArrayOutputStream

class HostCardEmulatorService : HostApduService() {
    
    // Transfer state
    private var pendingToken: Token? = null
    private var tokenChunks: List<ByteArray> = emptyList()
    private var currentChunkIndex = 0
    private var transferMode = "BLE_READY"
    private val gson = Gson()
    
    companion object {
        // Static variable to receive token from sender
        var tokenToReceive: Token? = null
        // Static variable to control transfer mode
        var currentTransferMode: String = "BLE_READY"
        private const val TAG = "HostCardEmulatorService"
        
        // AID for our application
        private val SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
        
        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        
        // Commands
        private const val CMD_START_DIRECT_TRANSFER: Byte = 0x02
        private const val CMD_GET_CHUNK: Byte = 0x03
        private const val CMD_TRANSFER_COMPLETE: Byte = 0x04
        
        // Transfer modes
        const val TRANSFER_MODE_DIRECT = "DIRECT_READY"
        
        // Maximum APDU response size (minus status word)
        private const val MAX_APDU_SIZE = 240
        
        // Android HCE seems to have an internal buffer limit around 37KB
        private const val MAX_TRANSFER_SIZE = 36000 // Stay below 37KB limit
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_ERROR
        }
        
        Log.d(TAG, "Received APDU: ${commandApdu.toHexString()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            // Start ReceiveActivity when sender taps
            startReceiveActivity()
            return SW_OK
        }
        
        // Check command type
        when (commandApdu[1]) {
            CMD_START_DIRECT_TRANSFER -> {
                Log.d(TAG, "START_DIRECT_TRANSFER command received")
                return startDirectTransfer(commandApdu)
            }
            CMD_GET_CHUNK -> {
                Log.d(TAG, "GET_CHUNK command received")
                return getNextChunk(commandApdu)
            }
            CMD_TRANSFER_COMPLETE -> {
                Log.d(TAG, "TRANSFER_COMPLETE command received")
                return completeTransfer()
            }
        }
        
        return SW_ERROR
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
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
                setPackage(packageName) // Restrict to our app only
            }
            sendBroadcast(intent)
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
                setPackage(packageName) // Restrict to our app only
            }
            sendBroadcast(intent)
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
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting progress", e)
        }
    }
    
    private fun startReceiveActivity() {
        try {
            val intent = Intent(this, ReceiveActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_started", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ReceiveActivity", e)
        }
    }
    
}