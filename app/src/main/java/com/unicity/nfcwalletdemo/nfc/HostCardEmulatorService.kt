package com.unicity.nfcwalletdemo.nfc

import android.bluetooth.BluetoothAdapter
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
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
        private const val CMD_GET_BT_ADDRESS: Byte = 0x01
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_ERROR
        }
        
        Log.d(TAG, "Received APDU: ${commandApdu.toHexString()}")
        
        // Check if this is a SELECT AID command
        if (isSelectAidCommand(commandApdu)) {
            Log.d(TAG, "SELECT AID command received")
            return SW_OK
        }
        
        // Check if this is a GET_BT_ADDRESS command
        if (commandApdu[1] == CMD_GET_BT_ADDRESS) {
            Log.d(TAG, "GET_BT_ADDRESS command received")
            return getBluetoothAddressResponse()
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
    private fun getBluetoothAddressResponse(): ByteArray {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val address = bluetoothAdapter?.address ?: "00:00:00:00:00:00"
        
        Log.d(TAG, "Sending Bluetooth address: $address")
        
        val addressBytes = address.toByteArray(StandardCharsets.UTF_8)
        return addressBytes + SW_OK
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}