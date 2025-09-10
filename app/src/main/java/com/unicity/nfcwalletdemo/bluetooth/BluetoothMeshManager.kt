package com.unicity.nfcwalletdemo.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Singleton manager for Bluetooth mesh operations that runs throughout the app lifecycle
 */
object BluetoothMeshManager {
    private const val TAG = "BluetoothMeshManager"
    private const val DEBUG = false // Set to true to enable verbose BT logging
    
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
    
    // Active GATT connections we've initiated (as client)
    private val activeGattConnections = mutableMapOf<String, BluetoothGatt>()
    
    // Buffer for prepared writes (for messages larger than MTU)
    private val preparedWriteBuffers = mutableMapOf<String, MutableList<PreparedWriteData>>()
    
    data class PreparedWriteData(
        val offset: Int,
        val data: ByteArray
    )
    
    // Track pending writes during MTU negotiation
    private val pendingWrites = mutableMapOf<BluetoothGatt, PendingWrite>()
    private var currentMtu = 23 // Default BLE MTU
    
    data class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val result: CompletableDeferred<Boolean>
    )
    
    sealed class MeshEvent {
        object Idle : MeshEvent()
        object Initialized : MeshEvent()
        data class MessageReceived(val message: String, val fromDevice: String) : MeshEvent()
        data class DeviceDiscovered(val address: String, val name: String?) : MeshEvent()
        data class Error(val message: String) : MeshEvent()
    }
    
    private fun logd(message: String) {
        if (DEBUG) logd( message)
    }
    
    /**
     * Initialize the mesh manager with application context
     */
    fun initialize(appContext: Context) {
        if (isInitialized) {
            logd( "Already initialized")
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
            logd( "Started advertising after delay")
            
            // Log advertising state periodically for debugging
            while (isActive) {
                delay(10000) // Every 10 seconds
                logd( "=== MESH STATUS ===")
                logd( "GATT Server: ${if (gattServer != null) "Running" else "Not running"}")
                logd( "Advertising: Active")
                logd( "Connected devices (as server): ${connectedDevices.size}")
                logd( "Active connections (as client): ${activeGattConnections.size}")
            }
        }
        
        startContinuousScanning()
        
        isInitialized = true
        _meshEvents.tryEmit(MeshEvent.Initialized)
        logd( "Bluetooth mesh manager initialized")
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
            logd( "=== STARTING GATT SERVER ===")
            
            val gattServerCallback = object : BluetoothGattServerCallback() {
                override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                    logd( "onExecuteWrite - device: ${device.address}, execute: $execute")
                    
                    if (execute) {
                        // Combine all prepared writes
                        val deviceId = device.address
                        val writes = preparedWriteBuffers[deviceId]
                        
                        if (writes != null && writes.isNotEmpty()) {
                            // Sort by offset and combine
                            val sortedWrites = writes.sortedBy { it.offset }
                            val totalSize = sortedWrites.sumOf { it.data.size }
                            val combinedData = ByteArray(totalSize)
                            
                            var currentOffset = 0
                            sortedWrites.forEach { write ->
                                write.data.copyInto(combinedData, currentOffset)
                                currentOffset += write.data.size
                            }
                            
                            val message = String(combinedData, StandardCharsets.UTF_8)
                            logd( "Combined prepared write message: length=${message.length}")
                            handleMessage(message, device.address)
                        }
                    }
                    
                    // Clear the buffer
                    preparedWriteBuffers.remove(device.address)
                    
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    logd( "=== GATT CONNECTION STATE CHANGE ===")
                    logd( "Device: ${device.address}")
                    logd( "Status: $status")
                    logd( "New state: $newState (${if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"})")
                    
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevices.add(device)
                        logd( "Device connected. Total connected: ${connectedDevices.size}")
                    } else {
                        connectedDevices.remove(device)
                        logd( "Device disconnected. Total connected: ${connectedDevices.size}")
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
                    logd( "onCharacteristicWriteRequest - device: ${device.address}, characteristic: ${characteristic.uuid}, responseNeeded: $responseNeeded")
                    logd( "Offset: $offset, preparedWrite: $preparedWrite, value size: ${value.size}")
                    
                    if (characteristic.uuid == MESH_MESSAGE_UUID) {
                        // Handle prepared writes for larger messages
                        if (preparedWrite) {
                            logd( "Prepared write received, offset: $offset, size: ${value.size}")
                            handlePreparedWrite(device, requestId, offset, value)
                        } else {
                            val message = String(value, StandardCharsets.UTF_8)
                            logd( "=== BLUETOOTH MESSAGE RECEIVED ===")
                            logd( "From device: ${device.address}")
                            logd( "Message length: ${message.length}")
                            logd( "Message content: $message")
                            
                            // All messages should be handled the same way
                            // The MTU negotiation ensures we can receive the full message
                            handleMessage(message, device.address)
                        }
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        logd( "Response sent to ${device.address}")
                    }
                }
                
                override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                    logd( "Service added - status: $status, service: ${service?.uuid}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        logd( "GATT service successfully added")
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
            
            // Important: Set the characteristic to support variable length
            // This allows messages larger than the default MTU
            messageCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            
            service.addCharacteristic(messageCharacteristic)
            val serviceAdded = gattServer?.addService(service) ?: false
            
            logd( "GATT server started, service added: $serviceAdded, server: $gattServer")
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
                    logd( "Advertising started successfully")
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
                delay(5000) // Restart scanning every 5 seconds if stopped
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
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // More aggressive scanning
                .build()
            
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { scanResult ->
                        val device = scanResult.device
                        val scanRecord = scanResult.scanRecord
                        
                        // Log what we found
                        if (DEBUG) {
                            logd( "BLE scan found device: ${device.address}")
                            logd( "  Name: ${device.name}")
                            logd( "  Service UUIDs: ${scanRecord?.serviceUuids}")
                        }
                        
                        // Only emit if it has our service UUID (double-check the filter)
                        val hasOurService = scanRecord?.serviceUuids?.any { 
                            it.uuid == MESH_SERVICE_UUID 
                        } ?: false
                        
                        if (hasOurService) {
                            logd( "  ✓ Device has our MESH_SERVICE_UUID")
                            _meshEvents.tryEmit(MeshEvent.DeviceDiscovered(
                                address = device.address,
                                name = device.name
                            ))
                        } else {
                            logd( "  ✗ Device does NOT have our service UUID (filter failed?)")
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
            logd( "Started background scanning")
            
            // Stop scanning after 20 seconds to save battery
            scope.launch {
                delay(20000) // Increased from 10 to 20 seconds
                try {
                    scanner.stopScan(scanCallback)
                    isScanning = false
                    logd( "Stopped scanning after 20 seconds")
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
            logd( "=== sendMessage CALLED ===")
            logd( "To device: $deviceAddress")
            logd( "From device: ${bluetoothAdapter?.address ?: "Unknown"}")
            logd( "Message length: ${message.length} chars")
            logd( "Message preview: ${message.take(200)}...")
            
            // Special logging for rejection messages
            if (message.contains("PERMISSION_RESPONSE") || message.contains("REJECT_TEST")) {
                logd( "!!! SENDING REJECTION-RELATED MESSAGE !!!")
            }
            
            // Check message size
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
            logd( "Message size: ${messageBytes.size} bytes")
            
            if (messageBytes.size > 512) {
                Log.w(TAG, "WARNING: Message is large (${messageBytes.size} bytes), may fail!")
            }
            
            var attempt = 0
            var lastError: String? = null
            
            while (attempt < retryCount) {
                attempt++
                logd( "Sending message attempt $attempt of $retryCount to $deviceAddress")
                
                val success = try {
                    sendMessageAttempt(deviceAddress, message)
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(TAG, "Attempt $attempt failed: ${e.message}", e)
                    false
                }
                
                if (success) {
                    logd( "Message sent successfully on attempt $attempt")
                    return@withContext true
                }
                
                // If not the last attempt, wait before retrying
                if (attempt < retryCount) {
                    val delayMs = attempt * 500L // Exponential backoff: 500ms, 1000ms, 1500ms
                    logd( "Waiting ${delayMs}ms before retry...")
                    delay(delayMs)
                }
            }
            
            Log.e(TAG, "Failed to send message after $retryCount attempts. Last error: $lastError")
            return@withContext false
        }
    }
    
    private suspend fun sendMessageAttempt(deviceAddress: String, message: String): Boolean {
        logd( "=== sendMessageAttempt STARTED ===")
        logd( "Target device: $deviceAddress")
        logd( "My device: ${bluetoothAdapter?.address ?: "Unknown"}")
        
        // Check if target device has a GATT server we can see
        logd( "Checking if $deviceAddress is advertising mesh service...")
        
        // First, try to scan for the device to ensure it's advertising
        val deviceFound = scanForDevice(deviceAddress)
        if (!deviceFound) {
            Log.w(TAG, "Device $deviceAddress not found in scan, attempting direct connection anyway...")
        }
        
        val result = CompletableDeferred<Boolean>()
        
        // Check if we already have a connection to this device
        val existingConnection = activeGattConnections[deviceAddress]
        if (existingConnection != null) {
            logd( "Found existing GATT connection to $deviceAddress, closing it")
            // TODO: Send message directly using existing connection
            // For now, close and reconnect
            try {
                existingConnection.disconnect()
                existingConnection.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing existing connection", e)
            }
            activeGattConnections.remove(deviceAddress)
        }
        
        var gattConnection: BluetoothGatt? = null
        
        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                Log.e(TAG, "Device not found: $deviceAddress")
                return false
            }
            
            logd( "Got remote device: ${device.name ?: "Unknown"} (${device.address})")
            
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    logd( "Connection state change - status: $status, newState: $newState")
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            logd( "Connected to $deviceAddress, discovering services...")
                            val discoverResult = gatt.discoverServices()
                            logd( "Service discovery started: $discoverResult")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            logd( "Disconnected from $deviceAddress, status: $status")
                            if (!result.isCompleted) {
                                result.complete(false)
                            }
                        }
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    logd( "onServicesDiscovered - status: $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // List all services
                        val services = gatt.services
                        logd( "Found ${services.size} services:")
                        services.forEach { service ->
                            logd( "  Service: ${service.uuid}")
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
                            logd( "Found message characteristic")
                            
                            // Store the data to send after MTU negotiation
                            gattConnection?.let { connection ->
                                pendingWrites[connection] = PendingWrite(
                                    characteristic = characteristic,
                                    data = message.toByteArray(StandardCharsets.UTF_8),
                                    result = result
                                )
                            }
                            
                            // Request larger MTU
                            val mtuResult = gatt.requestMtu(512)
                            logd( "Requested MTU: 512, result: $mtuResult")
                            
                            if (!mtuResult) {
                                // If MTU request fails, try to send with default MTU
                                Log.w(TAG, "MTU request failed, sending with default MTU")
                                val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
                                sendCharacteristicWrite(gatt, characteristic, messageBytes, result)
                            }
                            // If MTU request succeeds, wait for onMtuChanged callback
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
                    logd( "Characteristic write completed with status: $status")
                    val success = status == BluetoothGatt.GATT_SUCCESS
                    result.complete(success)
                    gatt.disconnect()
                    gatt.close()
                }
                
                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    logd( "MTU changed to: $mtu, status: $status")
                    
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        logd( "New MTU size: $mtu bytes (usable: ${mtu - 3} bytes)")
                        currentMtu = mtu
                        
                        // Now send the pending data with the new MTU
                        val pending = pendingWrites[gatt]
                        if (pending != null) {
                            logd( "Sending pending data with new MTU: ${pending.data.size} bytes")
                            sendCharacteristicWrite(gatt, pending.characteristic, pending.data, pending.result)
                            pendingWrites.remove(gatt)
                        }
                    } else {
                        Log.e(TAG, "MTU change failed, using default MTU")
                        // Send with default MTU
                        val pending = pendingWrites[gatt]
                        if (pending != null) {
                            sendCharacteristicWrite(gatt, pending.characteristic, pending.data, pending.result)
                            pendingWrites.remove(gatt)
                        }
                    }
                }
            }
            
            gattConnection = device.connectGatt(context, false, gattCallback)
            if (gattConnection == null) {
                Log.e(TAG, "Failed to create GATT connection")
                return false
            }
            
            // Store the connection
            activeGattConnections[deviceAddress] = gattConnection
            logd( "Stored GATT connection for $deviceAddress")
            
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
                gattConnection?.let {
                    pendingWrites.remove(it)
                    it.disconnect()
                    it.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
    
    private suspend fun scanForDevice(deviceAddress: String, timeout: Long = 3000): Boolean {
        return withContext(Dispatchers.IO) {
            logd( "Scanning for device $deviceAddress...")
            
            val deviceFound = CompletableDeferred<Boolean>()
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            
            if (scanner == null) {
                Log.e(TAG, "BLE scanner not available")
                return@withContext false
            }
            
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device.address == deviceAddress) {
                        logd( "Found target device $deviceAddress during scan")
                        deviceFound.complete(true)
                    }
                }
                
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    if (!deviceFound.isCompleted) {
                        deviceFound.complete(false)
                    }
                }
            }
            
            try {
                scanner.startScan(scanCallback)
                
                // Wait for device or timeout
                val found = withTimeoutOrNull(timeout) {
                    deviceFound.await()
                } ?: false
                
                scanner.stopScan(scanCallback)
                logd( "Scan complete. Device found: $found")
                
                return@withContext found
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception during scan", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Test if GATT server is working by sending a local message
     */
    fun testLocalMessage() {
        logd( "=== TESTING LOCAL MESSAGE ===")
        
        // Emit a test message event
        val testMessage = "LOCAL_TEST_${System.currentTimeMillis()}"
        val emitted = _meshEvents.tryEmit(MeshEvent.MessageReceived(
            message = testMessage,
            fromDevice = "LOCAL_TEST"
        ))
        
        logd( "Test message emitted: $emitted")
        logd( "Connected devices: ${connectedDevices.size}")
        
        // Log GATT server state
        logd( "GATT server: ${if (gattServer != null) "EXISTS" else "NULL"}")
        logd( "Is initialized: $isInitialized")
    }
    
    private fun sendCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        result: CompletableDeferred<Boolean>
    ) {
        try {
            // Check characteristic properties
            val properties = characteristic.properties
            val writeType = when {
                (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                    logd( "Using WRITE_TYPE_NO_RESPONSE")
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                    logd( "Using WRITE_TYPE_DEFAULT")
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                else -> {
                    Log.e(TAG, "Characteristic doesn't support writing")
                    result.complete(false)
                    gatt.disconnect()
                    return
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeResult = gatt.writeCharacteristic(characteristic, data, writeType)
                logd( "Write initiated (API 33+): $writeResult")
                if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                    result.complete(false)
                    gatt.disconnect()
                }
            } else {
                characteristic.writeType = writeType
                characteristic.value = data
                val writeResult = gatt.writeCharacteristic(characteristic)
                logd( "Write initiated (legacy): $writeResult")
                if (!writeResult) {
                    result.complete(false)
                    gatt.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing characteristic", e)
            result.complete(false)
            gatt.disconnect()
        }
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
     * Get list of currently connected device addresses via GATT server
     */
    fun getConnectedDevices(): Set<String> {
        return connectedDevices.map { it.address }.toSet()
    }
    
    private fun handlePreparedWrite(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        val deviceId = device.address
        val writes = preparedWriteBuffers.getOrPut(deviceId) { mutableListOf() }
        writes.add(PreparedWriteData(offset, value))
        logd( "Stored prepared write for $deviceId: offset=$offset, size=${value.size}")
    }
    
    private fun handleMessage(message: String, fromDevice: String) {
        logd( "=== GATT SERVER RECEIVED MESSAGE ===")
        logd( "From device: $fromDevice")
        logd( "My device: ${bluetoothAdapter?.address ?: "Unknown"}")
        logd( "Message length: ${message.length}")
        logd( "Message preview: ${message.take(100)}...")
        
        // Check if it looks like JSON
        val isJson = message.trim().startsWith("{") && message.trim().endsWith("}")
        logd( "Is JSON message: $isJson")
        
        // Special logging for response messages
        if (message.contains("PERMISSION_RESPONSE")) {
            logd( "!!! RECEIVED PERMISSION RESPONSE ON GATT SERVER !!!")
        }
        
        // Handle ping-pong test messages
        if (message.startsWith("PING:")) {
            logd( "=== RECEIVED PING, SENDING PONG ===")
            val pingId = message.substringAfter("PING:")
            scope.launch {
                // Add delay to ensure the sender's GATT server is ready
                delay(500) // Increased delay
                val pongMessage = "PONG:$pingId"
                logd( "Sending PONG response to $fromDevice")
                
                // For ping-pong, we know the device is connected as a client to our GATT server
                // So we should try to respond through the same connection
                val device = connectedDevices.find { it.address == fromDevice }
                if (device != null) {
                    logd( "Device found in connected list, sending PONG via new client connection")
                }
                
                // Try multiple times with delays
                var success = false
                for (attempt in 1..3) {
                    logd( "PONG send attempt $attempt")
                    success = sendMessage(fromDevice, pongMessage)
                    if (success) {
                        logd( "PONG sent successfully on attempt $attempt")
                        break
                    }
                    delay(1000) // Wait 1 second between attempts
                }
                
                if (!success) {
                    Log.e(TAG, "Failed to send PONG after 3 attempts")
                }
            }
        } else if (message.startsWith("PONG:")) {
            logd( "=== RECEIVED PONG RESPONSE ===")
            logd( "Bi-directional communication confirmed with $fromDevice!")
        }
        
        // Handle rejection messages
        if (message.startsWith("REJECT:")) {
            logd( "=== RECEIVED REJECTION MESSAGE ===")
            val transferId = message.substringAfter("REJECT:")
            logd( "Rejection for transfer: $transferId")
            // Emit this as a special event for the coordinator
            _meshEvents.tryEmit(MeshEvent.MessageReceived(
                message = message,
                fromDevice = fromDevice
            ))
        }
        
        // Emit the message event
        val emitResult = _meshEvents.tryEmit(MeshEvent.MessageReceived(
            message = message,
            fromDevice = fromDevice
        ))
        
        logd( "Message event emit result: $emitResult")
        
        if (!emitResult) {
            Log.e(TAG, "!!! FAILED TO EMIT MESSAGE EVENT !!!")
        }
        
        // Log current connected devices
        logd( "Currently connected devices: ${connectedDevices.map { it.address }.joinToString(", ")}")
    }
    
    /**
     * Get the local Bluetooth adapter address
     */
    fun getBluetoothAddress(): String? {
        return try {
            bluetoothAdapter?.address
        } catch (e: Exception) {
            null
        }
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