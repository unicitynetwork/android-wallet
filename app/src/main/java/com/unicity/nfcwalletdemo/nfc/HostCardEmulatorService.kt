package com.unicity.nfcwalletdemo.nfc

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

class HostCardEmulatorService : HostApduService() {
    
    companion object {
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
        private const val CMD_SEND_TOKEN: Byte = 0x02
        
        // Callback to notify when token is received
        var onTokenReceived: ((Token) -> Unit)? = null
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
        
        // Check if this is a SEND_TOKEN command
        if (commandApdu[1] == CMD_SEND_TOKEN) {
            Log.d(TAG, "SEND_TOKEN command received")
            return handleTokenReceived(commandApdu)
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
    
    private fun handleTokenReceived(commandApdu: ByteArray): ByteArray {
        try {
            // Extract token data from command (skip the first 4 command bytes)
            if (commandApdu.size <= 4) {
                Log.e(TAG, "No token data in command")
                return SW_ERROR
            }
            
            val tokenBytes = commandApdu.sliceArray(4 until commandApdu.size)
            val tokenJson = String(tokenBytes, StandardCharsets.UTF_8)
            Log.d(TAG, "Received token JSON: ${tokenJson.take(100)}...")
            
            val token = Json.decodeFromString(Token.serializer(), tokenJson)
            Log.d(TAG, "Successfully parsed token: ${token.name}")
            
            // Notify the receiver activity
            onTokenReceived?.invoke(token)
            
            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error handling received token", e)
            return SW_ERROR
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
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