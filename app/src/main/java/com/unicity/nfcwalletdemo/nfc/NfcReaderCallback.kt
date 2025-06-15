package com.unicity.nfcwalletdemo.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets

class NfcReaderCallback(
    private val onBluetoothAddressReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) : NfcAdapter.ReaderCallback {
    
    companion object {
        private const val TAG = "NfcReaderCallback"
        
        // AID for our application
        private val SELECT_AID_COMMAND = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), // Length of AID
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(),
            0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
        
        // Command to get Bluetooth address
        private val GET_BT_ADDRESS_COMMAND = byteArrayOf(
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        
        private const val TIMEOUT_MS = 5000
    }
    
    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "Tag discovered")
        
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "IsoDep not supported")
            onError("NFC tag does not support IsoDep")
            return
        }
        
        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
            
            // Select our application by AID
            val selectResponse = isoDep.transceive(SELECT_AID_COMMAND)
            Log.d(TAG, "SELECT AID response: ${selectResponse.toHexString()}")
            
            if (!isStatusOk(selectResponse)) {
                Log.e(TAG, "Failed to select application")
                onError("Failed to select application")
                return
            }
            
            // Get Bluetooth address
            val btAddressResponse = isoDep.transceive(GET_BT_ADDRESS_COMMAND)
            Log.d(TAG, "BT address response: ${btAddressResponse.toHexString()}")
            
            if (btAddressResponse.size > 2) {
                // Extract Bluetooth address (response minus status bytes)
                val addressBytes = btAddressResponse.sliceArray(0 until btAddressResponse.size - 2)
                val bluetoothAddress = String(addressBytes, StandardCharsets.UTF_8)
                Log.d(TAG, "Received Bluetooth address: $bluetoothAddress")
                onBluetoothAddressReceived(bluetoothAddress)
            } else {
                Log.e(TAG, "Invalid Bluetooth address response")
                onError("Failed to get Bluetooth address")
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