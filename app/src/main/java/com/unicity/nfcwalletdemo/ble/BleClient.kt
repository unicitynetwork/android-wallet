package com.unicity.nfcwalletdemo.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.coroutines.*
import java.util.UUID

class BleClient(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onAddressReceived: (String) -> Unit,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "BleClient"
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val gson = Gson()
    
    private var requestCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private var currentToken: Token? = null
    
    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"])
    suspend fun scanAndConnect(readySignal: String, token: Token) = withContext(Dispatchers.Main) {
        Log.d(TAG, "Starting BLE scan for receiver...")
        currentToken = token
        
        if (!bluetoothAdapter.isEnabled) {
            onError("Bluetooth is not enabled")
            return@withContext
        }
        
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onError("BLE scanning not supported")
            return@withContext
        }
        
        // Create scan filter for our specific service
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        // Set scan timeout
        scanTimeoutRunnable = Runnable {
            stopScan()
            onError("Scan timeout - receiver not found")
        }
        handler.postDelayed(scanTimeoutRunnable!!, BleConstants.SCAN_PERIOD)
        
        // Start scanning
        try {
            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            onError("Failed to start scan: ${e.message}")
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    private fun stopScan() {
        scanner?.stopScan(scanCallback)
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "BLE scan stopped")
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "✅ Found BLE device: ${result.device.name ?: result.device.address}")
            stopScan()
            connectToDevice(result.device)
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ BLE scan failed with error: $errorCode")
            onError("Scan failed: $errorCode")
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to GATT server...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected to GATT server")
                    onConnected()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    cleanup()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service != null) {
                    requestCharacteristic = service.getCharacteristic(BleConstants.CHARACTERISTIC_REQUEST_UUID)
                    responseCharacteristic = service.getCharacteristic(BleConstants.CHARACTERISTIC_RESPONSE_UUID)
                    
                    // Subscribe to notifications
                    responseCharacteristic?.let { characteristic ->
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    
                    Log.d(TAG, "Ready to start data exchange")
                } else {
                    onError("Service not found on device")
                }
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Subscribed to notifications - requesting address")
                requestUnicityAddress()
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BleConstants.CHARACTERISTIC_RESPONSE_UUID) {
                val data = String(characteristic.value)
                Log.d(TAG, "Received response: $data")
                
                try {
                    val response = gson.fromJson(data, Map::class.java) as Map<String, Any>
                    when (response["status"]) {
                        "sending_address" -> {
                            val address = response["address"] as String
                            onAddressReceived(address)
                            // Now send the token with the received address
                            currentToken?.let { token ->
                                val updatedToken = token.copy(unicityAddress = address)
                                sendTokenInternal(updatedToken)
                            }
                        }
                        "transfer_complete" -> {
                            Log.d(TAG, "Transfer completed successfully")
                            onTransferComplete()
                            disconnect()
                        }
                        "error" -> {
                            val message = response["message"] as? String ?: "Unknown error"
                            onError("Server error: $message")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response", e)
                    onError("Invalid response from server")
                }
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Data sent successfully")
            } else {
                Log.e(TAG, "Failed to send data: $status")
                onError("Failed to send data")
            }
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun requestUnicityAddress() {
        val request = mapOf("action" to "request_unicity_address")
        val data = gson.toJson(request).toByteArray()
        
        requestCharacteristic?.let { characteristic ->
            characteristic.value = data
            bluetoothGatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "Sent address request")
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    fun sendToken(token: Token) {
        currentToken = token
        // Token will be sent after address is received
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun sendTokenInternal(token: Token) {
        try {
            val tokenJson = gson.toJson(token)
            val data = tokenJson.toByteArray()
            
            Log.d(TAG, "Sending token data, size: ${data.size} bytes")
            
            requestCharacteristic?.let { characteristic ->
                // Check if data size exceeds BLE MTU limit (typically 20-512 bytes)
                if (data.size > 512) {
                    Log.e(TAG, "Token data too large for BLE: ${data.size} bytes")
                    onError("Token data too large for BLE transfer")
                    return
                }
                
                characteristic.value = data
                val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                if (success) {
                    Log.d(TAG, "Token write initiated")
                } else {
                    Log.e(TAG, "Failed to initiate token write")
                    onError("Failed to send token data")
                }
            } ?: run {
                Log.e(TAG, "Request characteristic is null")
                onError("BLE not ready for transfer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending token", e)
            onError("Failed to send token: ${e.message}")
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }
    
    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        requestCharacteristic = null
        responseCharacteristic = null
    }
}