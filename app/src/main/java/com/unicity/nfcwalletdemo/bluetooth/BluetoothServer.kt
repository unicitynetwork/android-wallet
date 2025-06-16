package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothServer(
    private val context: Context,
    private val onConnectionRequest: (String) -> Unit,
    private val onGenerateAddress: () -> String,
    private val onTokenReceived: (Token) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothServer"
        private const val SERVICE_NAME = "UnicityWalletService"
        private val SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
        private const val BUFFER_SIZE = 1024 * 8
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private val gson = Gson()
    
    private var isRunning = false
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun start() = coroutineScope {
        withContext(Dispatchers.IO) {
            if (bluetoothAdapter == null) {
                withContext(Dispatchers.Main) {
                    onError("Bluetooth is not available")
                }
                return@withContext
            }
            
            if (!bluetoothAdapter.isEnabled) {
                withContext(Dispatchers.Main) {
                    onError("Bluetooth is not enabled")
                }
                return@withContext
            }
            
            try {
                // Use insecure RFCOMM to avoid pairing issues
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    SERVICE_NAME,
                    SERVICE_UUID
                )
                
                isRunning = true
                Log.d(TAG, "Bluetooth server started, waiting for connections...")
                
                // Accept connection
                while (isRunning && isActive) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            Log.d(TAG, "Connection accepted from ${socket.remoteDevice.address}")
                            connectedSocket = socket
                            handleConnection(socket)
                            break // Exit after handling one connection
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                        break
                    }
                }
                
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception - missing Bluetooth permission", e)
                withContext(Dispatchers.Main) {
                    onError("Missing Bluetooth permission")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting Bluetooth server", e)
                withContext(Dispatchers.Main) {
                    onError("Failed to start Bluetooth server: ${e.message}")
                }
            } finally {
                if (!isRunning) {
                    cleanup()
                }
            }
        }
    }
    
    private suspend fun handleConnection(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Connected to ${socket.remoteDevice.name ?: socket.remoteDevice.address}")
        
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            
            withContext(Dispatchers.Main) {
                onConnectionRequest(socket.remoteDevice.name ?: "Unknown Device")
            }
            
            // Read transfer request
            val requestJson = readMessage(inputStream)
            val request = gson.fromJson(requestJson, TransferRequest::class.java)
            Log.d(TAG, "Received transfer request: $request")
            
            // Generate new address
            val address = withContext(Dispatchers.Main) {
                onGenerateAddress()
            }
            
            // Send address response
            val response = TransferResponse(
                status = "sending_address",
                address = address
            )
            sendMessage(outputStream, gson.toJson(response))
            
            // Receive token JSON
            val tokenJson = readMessage(inputStream)
            Log.d(TAG, "Received token data")
            
            // Parse and save token
            val token = gson.fromJson(tokenJson, Token::class.java)
            withContext(Dispatchers.Main) {
                onTokenReceived(token)
            }
            
            // Send confirmation
            val confirmation = TransferCompleteResponse()
            sendMessage(outputStream, gson.toJson(confirmation))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection", e)
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
    
    fun stop() {
        isRunning = false
        cleanup()
    }
    
    private fun cleanup() {
        try {
            connectedSocket?.close()
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error during cleanup", e)
        }
        connectedSocket = null
        serverSocket = null
    }
}