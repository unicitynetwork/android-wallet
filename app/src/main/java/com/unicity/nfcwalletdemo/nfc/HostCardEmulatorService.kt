package com.unicity.nfcwalletdemo.nfc

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import java.nio.charset.StandardCharsets
import java.util.UUID

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
        private const val CMD_GET_BT_ADDRESS: Byte = 0x01
        
        // Shared transfer UUID for this session
        var currentTransferUUID: String? = null
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_ERROR
        }
        
        Log.d(TAG, "Received APDU: ${commandApdu.toHexString()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            // Generate a unique transfer UUID for this session
            currentTransferUUID = UUID.randomUUID().toString()
            // Start ReceiveActivity when sender taps
            startReceiveActivity()
            return SW_OK
        }
        
        // Check if this is a GET_BT_ADDRESS command
        if (commandApdu[1] == CMD_GET_BT_ADDRESS) {
            Log.d(TAG, "GET_BT_ADDRESS command received")
            return getTransferUUIDResponse()
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
    
    @RequiresPermission("android.permission.BLUETOOTH")
    private fun getTransferUUIDResponse(): ByteArray {
        val transferUUID = currentTransferUUID ?: UUID.randomUUID().toString()
        
        Log.d(TAG, "Sending transfer UUID: $transferUUID")
        
        val uuidBytes = transferUUID.toByteArray(StandardCharsets.UTF_8)
        return uuidBytes + SW_OK
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private fun startReceiveActivity() {
        try {
            val intent = Intent(this, ReceiveActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_started", true)
                putExtra("transfer_uuid", currentTransferUUID)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ReceiveActivity", e)
        }
    }
}