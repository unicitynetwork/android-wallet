package com.unicity.nfcwalletdemo.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.*
import java.util.UUID

class BleServer(
    private val context: Context,
    private val onConnectionRequest: (String) -> Unit,
    private val onGenerateAddress: () -> String,
    private val onTokenReceived: (Token) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "BleServer"
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private val gson = Gson()
    
    private var requestCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    
    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT"])
    fun start() {
        Log.d(TAG, "Starting BLE server...")
        
        if (!bluetoothAdapter.isEnabled) {
            onError("Bluetooth is not enabled")
            return
        }
        
        setupGattServer()
        startAdvertising()
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun setupGattServer() {
        Log.d(TAG, "Setting up GATT server...")
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        // Create service
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Create request characteristic (Alice writes, Bob reads)
        requestCharacteristic = BluetoothGattCharacteristic(
            BleConstants.CHARACTERISTIC_REQUEST_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // Create response characteristic (Bob writes, Alice reads/subscribes)
        responseCharacteristic = BluetoothGattCharacteristic(
            BleConstants.CHARACTERISTIC_RESPONSE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Add descriptor for notifications
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE
        )
        responseCharacteristic?.addDescriptor(descriptor)
        
        service.addCharacteristic(requestCharacteristic)
        service.addCharacteristic(responseCharacteristic)
        
        gattServer?.addService(service)
        Log.d(TAG, "GATT server setup complete")
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_ADVERTISE")
    private fun startAdvertising() {
        Log.d(TAG, "Starting BLE advertising...")
        
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser is null - advertising not supported on this device")
            onError("BLE advertising not supported on this device")
            return
        }
        
        // Check if multiple advertising is supported
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "Multiple advertisement not supported")
            onError("BLE advertising not supported - device limitations")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Exclude device name to save space
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        
        Log.d(TAG, "Starting advertising with UUID: ${BleConstants.SERVICE_UUID}")
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "✅ BLE advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising not supported on this device"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many BLE advertisers running"
                ADVERTISE_FAILED_ALREADY_STARTED -> "BLE advertising already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement data too large"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal BLE advertising error"
                else -> "Unknown BLE advertising error: $errorCode"
            }
            Log.e(TAG, "❌ BLE advertising failed: $errorMessage")
            onError(errorMessage)
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Device connected: ${device.name ?: device.address}")
                    connectedDevice = device
                    onConnectionRequest(device.name ?: device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected: ${device.name ?: device.address}")
                    connectedDevice = null
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BleConstants.CHARACTERISTIC_REQUEST_UUID -> {
                    val data = String(value)
                    Log.d(TAG, "Received request: $data")
                    
                    try {
                        val request = gson.fromJson(data, Map::class.java)
                        when (request["action"]) {
                            "request_unicity_address" -> {
                                handleAddressRequest()
                            }
                            "send_token" -> {
                                // Should not happen - token should come with the initial write
                                Log.e(TAG, "Unexpected send_token action")
                                sendResponse(mapOf("status" to "error", "message" to "Invalid request"))
                            }
                            else -> {
                                // Try to parse as token directly
                                try {
                                    val token = gson.fromJson(data, Token::class.java)
                                    Log.d(TAG, "Token received: ${token.name}")
                                    onTokenReceived(token)
                                    sendResponse(mapOf("status" to "transfer_complete"))
                                } catch (tokenError: Exception) {
                                    Log.e(TAG, "Unknown request format", tokenError)
                                    sendResponse(mapOf("status" to "error", "message" to "Unknown request"))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing request", e)
                        sendResponse(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun handleAddressRequest() {
        val address = onGenerateAddress()
        val response = mapOf("status" to "sending_address", "address" to address)
        sendResponse(response)
    }
    
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun sendResponse(response: Map<String, Any>) {
        val data = gson.toJson(response).toByteArray()
        responseCharacteristic?.value = data
        
        connectedDevice?.let { device ->
            gattServer?.notifyCharacteristicChanged(device, responseCharacteristic, false)
            Log.d(TAG, "Sent response: ${gson.toJson(response)}")
        }
    }
    
    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT"])
    fun stop() {
        Log.d(TAG, "Stopping BLE server...")
        
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        
        advertiser = null
        gattServer = null
        connectedDevice = null
        
        Log.d(TAG, "BLE server stopped")
    }
}