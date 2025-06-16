package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothClient(
    private val context: Context,
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
    
    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"])
    suspend fun connectViaUUID(transferUUID: String, token: Token) = withContext(Dispatchers.IO) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            withContext(Dispatchers.Main) {
                onError("Bluetooth is not available or not enabled")
            }
            return@withContext
        }
        
        try {
            Log.d(TAG, "Starting Bluetooth client connection process")
            Log.d(TAG, "Transfer UUID: $transferUUID")
            
            // Try to connect to all paired devices that might be running the server
            val pairedDevices = bluetoothAdapter.bondedDevices
            Log.d(TAG, "Found ${pairedDevices.size} paired devices:")
            pairedDevices.forEach { device ->
                Log.d(TAG, "  - ${device.name} (${device.address})")
            }
            
            var connected = false
            
            // First try already paired devices
            for (device in pairedDevices) {
                Log.d(TAG, "\nAttempting connection to: ${device.name} (${device.address})")
                if (tryConnectToDevice(device, token)) {
                    connected = true
                    Log.d(TAG, "✓ Successfully connected to ${device.name}")
                    break
                } else {
                    Log.d(TAG, "✗ Failed to connect to ${device.name}")
                }
            }
            
            if (!connected) {
                // If no paired device worked, try to discover new devices
                Log.d(TAG, "No paired device worked, starting discovery")
                withContext(Dispatchers.Main) {
                    onError("Connecting to receiver... Make sure devices are paired.")
                }
                
                // Try connecting with exponential backoff
                for (attempt in 1..5) {
                    Log.d(TAG, "Connection attempt $attempt")
                    
                    for (device in bluetoothAdapter.bondedDevices) {
                        if (tryConnectToDevice(device, token)) {
                            connected = true
                            break
                        }
                    }
                    
                    if (connected) break
                    
                    // Wait before retry with exponential backoff
                    val delayMs = (500 * Math.pow(2.0, (attempt - 1).toDouble())).toLong()
                    delay(delayMs)
                }
            }
            
            if (!connected) {
                withContext(Dispatchers.Main) {
                    onError("Could not connect to receiver. Please pair devices first.")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectViaUUID", e)
            withContext(Dispatchers.Main) {
                onError("Connection failed: ${e.message}")
            }
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private suspend fun tryConnectToDevice(device: BluetoothDevice, token: Token): Boolean {
        return try {
            Log.d(TAG, "Creating socket for ${device.name}...")
            Log.d(TAG, "  - Using UUID: $SERVICE_UUID")
            
            // Cancel discovery to improve connection speed
            bluetoothAdapter?.cancelDiscovery()
            
            // Create socket and connect using insecure RFCOMM
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            Log.d(TAG, "Socket created, attempting to connect...")
            
            socket?.connect()
            Log.d(TAG, "Socket connected successfully!")
            
            Log.d(TAG, "Connected to ${device.name ?: device.address}")
            withContext(Dispatchers.Main) {
                onConnected()
            }
            
            handleTransfer(socket!!, token)
            true
        } catch (e: IOException) {
            Log.e(TAG, "IOException connecting to ${device.name}: ${e.message}")
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            if (e.message?.contains("read failed") == true) {
                Log.e(TAG, "This usually means the server is not running on the target device")
            }
            socket?.close()
            socket = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception: ${e.message}", e)
            socket?.close()
            socket = null
            false
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
        if (bytesRead == -1) {
            throw IOException("Connection closed by remote device")
        }
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