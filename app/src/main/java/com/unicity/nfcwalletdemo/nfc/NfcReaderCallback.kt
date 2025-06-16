package com.unicity.nfcwalletdemo.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets

class NfcReaderCallback(
    private val onTokenSent: () -> Unit,
    private val onError: (String) -> Unit,
    private val tokenToSend: Token? = null
) : NfcAdapter.ReaderCallback {
    
    init {
        Log.d(TAG, "NfcReaderCallback created")
    }
    
    companion object {
        private const val TAG = "NfcReaderCallback"
        
        // AID for our application
        private val SELECT_AID_COMMAND = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), // Length of AID
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(),
            0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
        
        // Command to send token
        private val SEND_TOKEN_COMMAND = byteArrayOf(
            0x00.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        
        private const val TIMEOUT_MS = 5000
    }
    
    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "✅ NFC TAG DISCOVERED!")
        Log.d(TAG, "Tag info: ${tag?.toString()}")
        Log.d(TAG, "Tag ID: ${tag?.id?.contentToString()}")
        Log.d(TAG, "Tag tech list: ${tag?.techList?.contentToString()}")
        
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "IsoDep not supported by this tag")
            Log.e(TAG, "Available techs: ${tag?.techList?.contentToString()}")
            onError("NFC tag does not support IsoDep")
            return
        }
        
        Log.d(TAG, "IsoDep supported, proceeding with communication")
        
        try {
            Log.d(TAG, "Connecting to IsoDep...")
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
            Log.d(TAG, "IsoDep connected successfully")
            
            // Select our application by AID
            val selectResponse = isoDep.transceive(SELECT_AID_COMMAND)
            Log.d(TAG, "SELECT AID response: ${selectResponse.toHexString()}")
            
            if (!isStatusOk(selectResponse)) {
                Log.e(TAG, "Failed to select application")
                onError("Failed to select application")
                return
            }
            
            // Send token data to receiver
            if (tokenToSend != null) {
                try {
                    val tokenJson = Json.encodeToString(Token.serializer(), tokenToSend)
                    val tokenBytes = tokenJson.toByteArray(StandardCharsets.UTF_8)
                    
                    // Create command with token data
                    val sendCommand = SEND_TOKEN_COMMAND + tokenBytes
                    Log.d(TAG, "Sending token data: ${tokenJson.take(100)}...")
                    
                    val response = isoDep.transceive(sendCommand)
                    Log.d(TAG, "Send token response: ${response.toHexString()}")
                    
                    if (isStatusOk(response)) {
                        Log.d(TAG, "✅ SUCCESS: Token sent successfully")
                        onTokenSent()
                    } else {
                        Log.e(TAG, "Failed to send token - receiver returned error")
                        onError("Failed to send token to receiver")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending token data", e)
                    onError("Failed to send token: ${e.message}")
                }
            } else {
                Log.e(TAG, "No token to send")
                onError("No token selected for transfer")
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error communicating with tag", e)
            onError("Error communicating with NFC tag: ${e.message}")
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing connection", e)
            }
        }
    }
    
    private fun isStatusOk(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}