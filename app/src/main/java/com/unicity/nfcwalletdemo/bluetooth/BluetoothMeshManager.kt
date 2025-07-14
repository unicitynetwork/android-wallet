package com.unicity.nfcwalletdemo.bluetooth

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Singleton manager for Bluetooth mesh operations that runs throughout the app lifecycle
 */
object BluetoothMeshManager {
    private const val TAG = "BluetoothMeshManager"
    
    // Service and characteristic UUIDs
    private val MESH_SERVICE_UUID = UUID.fromString("00001830-0000-1000-8000-00805f9b34fb")
    private val MESH_MESSAGE_UUID = UUID.fromString("00002a90-0000-1000-8000-00805f9b34fb")
    
    private var context: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var isInitialized = false
    private var isScanning = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // SharedFlow for mesh events - allows multiple collectors
    // Increased buffer capacity and added replay to ensure no messages are lost
    private val _meshEvents = MutableSharedFlow<MeshEvent>(
        replay = 10,  // Keep last 10 events for late subscribers
        extraBufferCapacity = 100  // Large buffer to prevent dropping
    )
    val meshEvents: SharedFlow<MeshEvent> = _meshEvents
    
    // Connected devices
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    
    sealed class MeshEvent {
        object Idle : MeshEvent()
        object Initialized : MeshEvent()
        data class MessageReceived(val message: String, val fromDevice: String) : MeshEvent()
        data class DeviceDiscovered(val address: String, val name: String?) : MeshEvent()
        data class Error(val message: String) : MeshEvent()
    }
    
    /**
     * Initialize the mesh manager with application context
     */
    fun initialize(appContext: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        context = appContext.applicationContext
        bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            _meshEvents.tryEmit(MeshEvent.Error("Bluetooth not supported"))
            return
        }
        
        if (!checkPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            _meshEvents.tryEmit(MeshEvent.Error("Missing Bluetooth permissions"))
            return
        }
        
        startGattServer()
        
        // Add a small delay to ensure GATT server is ready
        scope.launch {
            delay(500)
            startAdvertising()
            Log.d(TAG, "Started advertising after delay")
        }
        
        startContinuousScanning()
        
        isInitialized = true
        _meshEvents.tryEmit(MeshEvent.Initialized)
        Log.d(TAG, "Bluetooth mesh manager initialized")
    }
    
    private fun checkPermissions(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun startGattServer() {
        try {
            Log.d(TAG, "=== STARTING GATT SERVER ===")
            
            val gattServerCallback = object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    Log.d(TAG, "=== GATT CONNECTION STATE CHANGE ===")
                    Log.d(TAG, "Device: ${device.address}")
                    Log.d(TAG, "Status: $status")
                    Log.d(TAG, "New state: $newState (${if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"})")
                    
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevices.add(device)
                        Log.d(TAG, "Device connected. Total connected: ${connectedDevices.size}")
                    } else {
                        connectedDevices.remove(device)
                        Log.d(TAG, "Device disconnected. Total connected: ${connectedDevices.size}")
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
                    Log.d(TAG, "onCharacteristicWriteRequest - device: ${device.address}, characteristic: ${characteristic.uuid}, responseNeeded: $responseNeeded")
                    
                    if (characteristic.uuid == MESH_MESSAGE_UUID) {
                        val message = String(value, StandardCharsets.UTF_8)
                        Log.d(TAG, "=== BLUETOOTH MESSAGE RECEIVED ===")
                        Log.d(TAG, "From device: ${device.address}")
                        Log.d(TAG, "Message length: ${message.length}")
                        Log.d(TAG, "Message content: $message")
                        
                        // Log that we received a message
                        Log.d(TAG, "Message received and will be emitted to collectors")
                        
                        // Check if it looks like JSON
                        val isJson = message.trim().startsWith("{") && message.trim().endsWith("}")
                        Log.d(TAG, "Is JSON message: $isJson")
                        
                        // Emit the message event
                        val emitResult = _meshEvents.tryEmit(MeshEvent.MessageReceived(
                            message = message,
                            fromDevice = device.address
                        ))
                        
                        Log.d(TAG, "Message event emit result: $emitResult")
                        
                        if (!emitResult) {
                            Log.e(TAG, "!!! FAILED TO EMIT MESSAGE EVENT !!!")
                        }
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        Log.d(TAG, "Response sent to ${device.address}")
                    }
                }
                
                override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                    Log.d(TAG, "Service added - status: $status, service: ${service?.uuid}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "GATT service successfully added")
                    } else {
                        Log.e(TAG, "Failed to add GATT service, status: $status")
                    }
                }
            }
            
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            
            // Create the mesh service
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            
            // Add message characteristic with more permissive settings
            val messageCharacteristic = BluetoothGattCharacteristic(
                MESH_MESSAGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or 
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or 
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            service.addCharacteristic(messageCharacteristic)
            val serviceAdded = gattServer?.addService(service) ?: false
            
            Log.d(TAG, "GATT server started, service added: $serviceAdded, server: $gattServer")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting GATT server", e)
        }
    }
    
    private fun startAdvertising() {
        try {
            val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BLE advertising not supported")
                return
            }
            
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .build()
            
            advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d(TAG, "Advertising started successfully")
                }
                
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Advertising failed: $errorCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting advertising", e)
        }
    }
    
    private fun startContinuousScanning() {
        scope.launch {
            while (isActive) {
                if (!isScanning && checkPermissions()) {
                    startScanning()
                }
                delay(30000) // Restart scanning every 30 seconds if stopped
            }
        }
    }
    
    private fun startScanning() {
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BLE scanner not available")
                return
            }
            
            val scanFilters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                    .build()
            )
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { scanResult ->
                        val device = scanResult.device
                        val scanRecord = scanResult.scanRecord
                        
                        // Log what we found
                        Log.d(TAG, "BLE scan found device: ${device.address}")
                        Log.d(TAG, "  Name: ${device.name}")
                        Log.d(TAG, "  Service UUIDs: ${scanRecord?.serviceUuids}")
                        
                        // Only emit if it has our service UUID (double-check the filter)
                        val hasOurService = scanRecord?.serviceUuids?.any { 
                            it.uuid == MESH_SERVICE_UUID 
                        } ?: false
                        
                        if (hasOurService) {
                            Log.d(TAG, "  ✓ Device has our MESH_SERVICE_UUID")
                            _meshEvents.tryEmit(MeshEvent.DeviceDiscovered(
                                address = device.address,
                                name = device.name
                            ))
                        } else {
                            Log.d(TAG, "  ✗ Device does NOT have our service UUID (filter failed?)")
                        }
                    }
                }
                
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    isScanning = false
                }
            }
            
            scanner.startScan(scanFilters, scanSettings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started background scanning")
            
            // Stop scanning after 10 seconds to save battery
            scope.launch {
                delay(10000)
                try {
                    scanner.stopScan(scanCallback)
                    isScanning = false
                    Log.d(TAG, "Stopped scanning to save battery")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Error stopping scan", e)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan", e)
        }
    }
    
    /**
     * Send a message to a specific device
     */
    suspend fun sendMessage(deviceAddress: String, message: String, retryCount: Int = 3): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "=== sendMessage CALLED ===")
            Log.d(TAG, "To device: $deviceAddress")
            Log.d(TAG, "Message length: ${message.length} chars")
            Log.d(TAG, "Message preview: ${message.take(200)}...")
            
            // Check message size
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "Message size: ${messageBytes.size} bytes")
            
            if (messageBytes.size > 512) {
                Log.w(TAG, "WARNING: Message is large (${messageBytes.size} bytes), may fail!")
            }
            
            var attempt = 0
            var lastError: String? = null
            
            while (attempt < retryCount) {
                attempt++
                Log.d(TAG, "Sending message attempt $attempt of $retryCount to $deviceAddress")
                
                val success = try {
                    sendMessageAttempt(deviceAddress, message)
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(TAG, "Attempt $attempt failed: ${e.message}", e)
                    false
                }
                
                if (success) {
                    Log.d(TAG, "Message sent successfully on attempt $attempt")
                    return@withContext true
                }
                
                // If not the last attempt, wait before retrying
                if (attempt < retryCount) {
                    val delayMs = attempt * 500L // Exponential backoff: 500ms, 1000ms, 1500ms
                    Log.d(TAG, "Waiting ${delayMs}ms before retry...")
                    delay(delayMs)
                }
            }
            
            Log.e(TAG, "Failed to send message after $retryCount attempts. Last error: $lastError")
            return@withContext false
        }
    }
    
    private suspend fun sendMessageAttempt(deviceAddress: String, message: String): Boolean {
        Log.d(TAG, "=== sendMessageAttempt STARTED ===")
        Log.d(TAG, "Target device: $deviceAddress")
        
        val result = CompletableDeferred<Boolean>()
        var gattConnection: BluetoothGatt? = null
        
        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                Log.e(TAG, "Device not found: $deviceAddress")
                return false
            }
            
            Log.d(TAG, "Got remote device: ${device.name ?: "Unknown"} (${device.address})")
            
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "Connection state change - status: $status, newState: $newState")
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to $deviceAddress, discovering services...")
                            val discoverResult = gatt.discoverServices()
                            Log.d(TAG, "Service discovery started: $discoverResult")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected from $deviceAddress, status: $status")
                            if (!result.isCompleted) {
                                result.complete(false)
                            }
                        }
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Log.d(TAG, "onServicesDiscovered - status: $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // List all services
                        val services = gatt.services
                        Log.d(TAG, "Found ${services.size} services:")
                        services.forEach { service ->
                            Log.d(TAG, "  Service: ${service.uuid}")
                        }
                        
                        val service = gatt.getService(MESH_SERVICE_UUID)
                        if (service == null) {
                            Log.e(TAG, "Mesh service $MESH_SERVICE_UUID not found")
                            result.complete(false)
                            gatt.disconnect()
                            return
                        }
                        
                        val characteristic = service.getCharacteristic(MESH_MESSAGE_UUID)
                        if (characteristic != null) {
                            Log.d(TAG, "Found message characteristic, writing message...")
                            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
                            
                            // Check characteristic properties
                            val properties = characteristic.properties
                            val writeType = when {
                                (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                                    Log.d(TAG, "Using WRITE_TYPE_NO_RESPONSE")
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                }
                                (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                                    Log.d(TAG, "Using WRITE_TYPE_DEFAULT")
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                }
                                else -> {
                                    Log.e(TAG, "Characteristic doesn't support writing")
                                    result.complete(false)
                                    gatt.disconnect()
                                    return
                                }
                            }
                            
                            // Try WRITE_TYPE_NO_RESPONSE first for better compatibility
                            val preferredWriteType = if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            }
                            
                            Log.d(TAG, "Message bytes size: ${messageBytes.size}")
                            Log.d(TAG, "Using write type: $preferredWriteType")
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val writeResult = gatt.writeCharacteristic(characteristic, messageBytes, preferredWriteType)
                                Log.d(TAG, "Write initiated (API 33+): $writeResult with writeType: $preferredWriteType")
                                if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                                    Log.e(TAG, "Write failed with result: $writeResult")
                                    
                                    // If NO_RESPONSE failed, try DEFAULT
                                    if (preferredWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && 
                                        (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                                        Log.d(TAG, "Retrying with WRITE_TYPE_DEFAULT...")
                                        val retryResult = gatt.writeCharacteristic(characteristic, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                        if (retryResult == BluetoothGatt.GATT_SUCCESS) {
                                            Log.d(TAG, "Retry successful")
                                            return
                                        }
                                    }
                                    
                                    result.complete(false)
                                    gatt.disconnect()
                                }
                            } else {
                                characteristic.writeType = preferredWriteType
                                characteristic.value = messageBytes
                                val writeResult = gatt.writeCharacteristic(characteristic)
                                Log.d(TAG, "Write initiated (legacy): $writeResult with writeType: $preferredWriteType")
                                if (!writeResult) {
                                    Log.e(TAG, "Write failed")
                                    
                                    // If NO_RESPONSE failed, try DEFAULT
                                    if (preferredWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && 
                                        (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                                        Log.d(TAG, "Retrying with WRITE_TYPE_DEFAULT...")
                                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                        val retryResult = gatt.writeCharacteristic(characteristic)
                                        if (retryResult) {
                                            Log.d(TAG, "Retry successful")
                                            return
                                        }
                                    }
                                    
                                    result.complete(false)
                                    gatt.disconnect()
                                }
                            }
                        } else {
                            Log.e(TAG, "Message characteristic $MESH_MESSAGE_UUID not found")
                            result.complete(false)
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Service discovery failed with status: $status")
                        result.complete(false)
                        gatt.disconnect()
                    }
                }
                
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    Log.d(TAG, "Characteristic write completed with status: $status")
                    val success = status == BluetoothGatt.GATT_SUCCESS
                    result.complete(success)
                    gatt.disconnect()
                    gatt.close()
                }
            }
            
            gattConnection = device.connectGatt(context, false, gattCallback)
            if (gattConnection == null) {
                Log.e(TAG, "Failed to create GATT connection")
                return false
            }
            
            // Wait for the result with a timeout
            return try {
                withTimeout(10000) {
                    result.await()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Connection timeout after 10 seconds")
                result.complete(false)
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending message", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            return false
        } finally {
            // Ensure cleanup if something went wrong
            if (!result.isCompleted) {
                result.complete(false)
            }
            try {
                gattConnection?.disconnect()
                gattConnection?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
    
    /**
     * Test if GATT server is working by sending a local message
     */
    fun testLocalMessage() {
        Log.d(TAG, "=== TESTING LOCAL MESSAGE ===")
        
        // Emit a test message event
        val testMessage = "LOCAL_TEST_${System.currentTimeMillis()}"
        val emitted = _meshEvents.tryEmit(MeshEvent.MessageReceived(
            message = testMessage,
            fromDevice = "LOCAL_TEST"
        ))
        
        Log.d(TAG, "Test message emitted: $emitted")
        Log.d(TAG, "Connected devices: ${connectedDevices.size}")
        
        // Log GATT server state
        Log.d(TAG, "GATT server: ${if (gattServer != null) "EXISTS" else "NULL"}")
        Log.d(TAG, "Is initialized: $isInitialized")
    }
    
    /**
     * Get diagnostic info
     */
    fun getDiagnostics(): String {
        return """
            BluetoothMeshManager Diagnostics:
            - Initialized: $isInitialized
            - GATT Server: ${if (gattServer != null) "Running" else "Not running"}
            - Connected devices: ${connectedDevices.size}
            - Bluetooth enabled: ${bluetoothAdapter?.isEnabled ?: false}
            - Scanning: $isScanning
        """.trimIndent()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        try {
            gattServer?.close()
            // Stop advertising properly
            try {
                val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
                // We don't have a reference to the original callback, so we can't stop specific advertising
                // This is a limitation of the current implementation
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping advertising", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        isInitialized = false
    }
}