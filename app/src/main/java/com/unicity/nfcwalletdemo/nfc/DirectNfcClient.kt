package com.unicity.nfcwalletdemo.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class DirectNfcClient(
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
        private const val MAX_COMMAND_DATA_SIZE = 240
    }
    
    private var tokenToSend: Token? = null
    private val gson = Gson()
    
    fun setTokenToSend(token: Token) {
        tokenToSend = token
        Log.d(TAG, "Token set for NFC transfer: ${token.name}")
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
                isoDep.timeout = 10000 // 10 seconds
                
                Log.d(TAG, "IsoDep connected successfully with timeout: ${isoDep.timeout}ms")
                
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
            // Convert token to JSON bytes
            val tokenJson = gson.toJson(token)
            val tokenBytes = tokenJson.toByteArray(StandardCharsets.UTF_8)
            
            Log.d(TAG, "Sending token: ${token.name}, JSON size: ${tokenBytes.size} bytes")
            
            // Split into chunks if necessary
            val chunks = tokenBytes.toList().chunked(MAX_COMMAND_DATA_SIZE)
            Log.d(TAG, "Token split into ${chunks.size} chunks")
            
            // Send each chunk
            for (i in chunks.indices) {
                val chunk = chunks[i].toByteArray()
                
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
                
                val response = isoDep.transceive(command)
                if (!isResponseOK(response)) {
                    Log.e(TAG, "Failed to send chunk ${i + 1}, response: ${response.toHexString()}")
                    withContext(Dispatchers.Main) {
                        onError("Failed to send data chunk ${i + 1}")
                    }
                    return
                }
                
                Log.d(TAG, "Chunk ${i + 1} sent successfully")
            }
            
            // Complete transfer
            val completeCommand = byteArrayOf(0x00.toByte(), CMD_TRANSFER_COMPLETE, 0x00.toByte(), 0x00.toByte())
            val completeResponse = isoDep.transceive(completeCommand)
            
            if (isResponseOK(completeResponse)) {
                Log.d(TAG, "✅ NFC transfer completed successfully!")
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
}