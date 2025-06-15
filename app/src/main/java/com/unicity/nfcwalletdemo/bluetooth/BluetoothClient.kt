package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothClient(
    private val onConnected: () -> Unit,
    private val onAddressReceived: (String) -> Unit,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothClient"
        private val SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
        private const val BUFFER_SIZE = 1024 * 8
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private val gson = Gson()
    
    suspend fun connect(address: String, token: Token) = withContext(Dispatchers.IO) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onError("Bluetooth is not available or not enabled")
            return@withContext
        }
        
        try {
            // Cancel discovery to improve connection speed
            bluetoothAdapter.cancelDiscovery()
            
            // Get remote device
            val device = bluetoothAdapter.getRemoteDevice(address)
            
            // Create socket and connect
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket?.connect()
            
            Log.d(TAG, "Connected to ${device.name ?: device.address}")
            withContext(Dispatchers.Main) {
                onConnected()
            }
            
            handleTransfer(socket!!, token)
            
        } catch (e: IOException) {
            Log.e(TAG, "Error connecting to device", e)
            withContext(Dispatchers.Main) {
                onError("Failed to connect: ${e.message}")
            }
            cleanup()
        }
    }
    
    private suspend fun handleTransfer(socket: BluetoothSocket, token: Token) = withContext(Dispatchers.IO) {
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            
            // Send transfer request
            val request = TransferRequest(
                tokenType = token.type
            )
            sendMessage(outputStream, gson.toJson(request))
            Log.d(TAG, "Sent transfer request")
            
            // Read address response
            val responseJson = readMessage(inputStream)
            val response = gson.fromJson(responseJson, TransferResponse::class.java)
            
            if (response.status == "sending_address" && response.address != null) {
                Log.d(TAG, "Received address: ${response.address}")
                withContext(Dispatchers.Main) {
                    onAddressReceived(response.address)
                }
                
                // Create token with receiver address and send it
                val transferToken = token.copy(
                    unicityAddress = response.address,
                    jsonData = generateTokenJson(token, response.address)
                )
                
                sendMessage(outputStream, gson.toJson(transferToken))
                Log.d(TAG, "Sent token data")
                
                // Wait for confirmation
                val confirmationJson = readMessage(inputStream)
                val confirmation = gson.fromJson(confirmationJson, TransferCompleteResponse::class.java)
                
                if (confirmation.status == "transfer_complete") {
                    Log.d(TAG, "Transfer completed successfully")
                    withContext(Dispatchers.Main) {
                        onTransferComplete()
                    }
                } else {
                    throw IOException("Transfer not confirmed")
                }
                
            } else {
                throw IOException("Invalid address response")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during transfer", e)
            withContext(Dispatchers.Main) {
                onError("Transfer failed: ${e.message}")
            }
        } finally {
            cleanup()
        }
    }
    
    private fun readMessage(inputStream: InputStream): String {
        val buffer = ByteArray(BUFFER_SIZE)
        val bytesRead = inputStream.read(buffer)
        return String(buffer, 0, bytesRead)
    }
    
    private fun sendMessage(outputStream: OutputStream, message: String) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
    }
    
    private fun generateTokenJson(token: Token, receiverAddress: String): String {
        // In real implementation, this would use Unicity SDK to create proper token JSON
        return gson.toJson(mapOf(
            "token_id" to token.id,
            "token_name" to token.name,
            "token_type" to token.type,
            "receiver_address" to receiverAddress,
            "timestamp" to System.currentTimeMillis(),
            "signature" to "mock_signature_${UUID.randomUUID()}"
        ))
    }
    
    fun disconnect() {
        cleanup()
    }
    
    private fun cleanup() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
    }
}