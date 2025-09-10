package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Service for transferring Unicity tokens over Bluetooth LE mesh network
 * Inspired by bitchat's approach but optimized for token transfers
 */
class BluetoothMeshTransferService(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothMeshTransfer"
        
        // Service UUID for Unicity transfers
        private val SERVICE_UUID = UUID.fromString("00001830-0000-1000-8000-00805f9b34fb")
        
        // Characteristic UUIDs
        private val TRANSFER_DATA_UUID = UUID.fromString("00002a90-0000-1000-8000-00805f9b34fb")
        private val TRANSFER_CONTROL_UUID = UUID.fromString("00002a91-0000-1000-8000-00805f9b34fb")
        private val TRANSFER_STATUS_UUID = UUID.fromString("00002a92-0000-1000-8000-00805f9b34fb")
        
        // Transfer constants
        private const val CHUNK_SIZE = 512 // BLE MTU safe size
        private const val TRANSFER_TIMEOUT_MS = 30_000L
        private const val MAX_RETRIES = 3
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    
    private var gattServer: BluetoothGattServer? = null
    private val activeTransfers = ConcurrentHashMap<String, TransferSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState
    
    sealed class TransferState {
        object Idle : TransferState()
        data class Discovering(val targetMAC: String) : TransferState()
        data class Connected(val device: BluetoothDevice) : TransferState()
        data class Transferring(val progress: Float) : TransferState()
        data class Completed(val tokenId: String) : TransferState()
        data class Error(val message: String) : TransferState()
    }
    
    data class TransferSession(
        val transferId: String,
        val role: TransferRole,
        val tokenData: ByteArray? = null,
        var chunksReceived: MutableMap<Int, ByteArray> = mutableMapOf(), // Changed to Map for proper ordering
        var totalChunks: Int = 0,
        var device: BluetoothDevice? = null,
        var gatt: BluetoothGatt? = null,
        val startTime: Long = System.currentTimeMillis(),
        var lastActivityTime: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )
    
    enum class TransferRole {
        SENDER, RECEIVER
    }
    
    /**
     * Start as sender after NFC handshake
     */
    suspend fun startSenderMode(
        transferId: String,
        targetMAC: String,
        tokenData: ByteArray
    ) = suspendCoroutine<Unit> { continuation ->
        Log.d(TAG, "Starting sender mode for transfer $transferId to $targetMAC")
        
        val session = TransferSession(
            transferId = transferId,
            role = TransferRole.SENDER,
            tokenData = tokenData
        )
        activeTransfers[transferId] = session
        
        // Start advertising as peripheral
        startAdvertising(transferId)
        
        // Start scanning for target device
        startScanning(targetMAC, transferId) { device ->
            scope.launch {
                try {
                    connectAndTransfer(device, session)
                    continuation.resumeWith(Result.success(Unit))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
        
        // Set timeout
        scope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            if (activeTransfers.containsKey(transferId)) {
                stopTransfer(transferId)
                continuation.resumeWithException(Exception("Transfer timeout"))
            }
        }
    }
    
    /**
     * Start as receiver after NFC handshake
     */
    suspend fun startReceiverMode(
        transferId: String,
        targetMAC: String
    ): ByteArray = suspendCoroutine { continuation ->
        Log.d(TAG, "Starting receiver mode for transfer $transferId from $targetMAC")
        
        val session = TransferSession(
            transferId = transferId,
            role = TransferRole.RECEIVER
        )
        activeTransfers[transferId] = session
        
        // Start GATT server to receive data
        startGattServer(transferId) { receivedData ->
            continuation.resumeWith(Result.success(receivedData))
        }
        
        // Start advertising so sender can find us
        startAdvertising(transferId)
        
        // Also scan for sender
        startScanning(targetMAC, transferId) { device ->
            session.device = device
            _transferState.value = TransferState.Connected(device)
        }
        
        // Set timeout
        scope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            if (activeTransfers.containsKey(transferId)) {
                stopTransfer(transferId)
                continuation.resumeWithException(Exception("Transfer timeout"))
            }
        }
    }
    
    private fun startAdvertising(transferId: String) {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(
                ParcelUuid(SERVICE_UUID),
                transferId.take(8).toByteArray() // First 8 bytes of transfer ID
            )
            .build()
        
        advertiser.startAdvertising(settings, data, advertiseCallback)
        Log.d(TAG, "Started advertising for transfer $transferId")
    }
    
    private fun startScanning(targetMAC: String, transferId: String, onDeviceFound: (BluetoothDevice) -> Unit) {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            return
        }
        
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.address.equals(targetMAC, ignoreCase = true)) {
                    Log.d(TAG, "Found target device: $targetMAC")
                    scanner.stopScan(this)
                    onDeviceFound(device)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: $errorCode")
                _transferState.value = TransferState.Error("Scan failed: $errorCode")
            }
        }
        
        scanner.startScan(filters, settings, callback)
        _transferState.value = TransferState.Discovering(targetMAC)
        Log.d(TAG, "Started scanning for $targetMAC")
    }
    
    private suspend fun connectAndTransfer(device: BluetoothDevice, session: TransferSession) {
        _transferState.value = TransferState.Connected(device)
        
        val connectionResult = CompletableDeferred<Boolean>()
        val servicesDiscovered = CompletableDeferred<Boolean>()
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to ${device.address}, discovering services...")
                        connectionResult.complete(true)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.e(TAG, "Disconnected from ${device.address}")
                        if (!connectionResult.isCompleted) {
                            connectionResult.complete(false)
                        }
                        if (!servicesDiscovered.isCompleted) {
                            servicesDiscovered.complete(false)
                        }
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "Services discovered: status=$status")
                servicesDiscovered.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
            
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.d(TAG, "Characteristic write completed: status=$status")
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU changed to $mtu")
                }
            }
        }
        
        try {
            val gatt = device.connectGatt(context, false, gattCallback)
            session.gatt = gatt
            
            // Wait for connection with timeout
            val connected = withTimeout(5000L) {
                connectionResult.await()
            }
            
            if (!connected) {
                throw Exception("Failed to connect to device")
            }
            
            // Wait for service discovery with timeout
            val discovered = withTimeout(5000L) {
                servicesDiscovered.await()
            }
            
            if (!discovered) {
                throw Exception("Failed to discover services")
            }
            
            // Request larger MTU for faster transfers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt.requestMtu(512)
                delay(500) // Give time for MTU negotiation
            }
            
            // Send data in chunks
            session.tokenData?.let { data ->
                sendDataInChunks(gatt, data, session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            session.gatt?.close()
            _transferState.value = TransferState.Error(e.message ?: "Connection failed")
            throw e
        }
    }
    
    private fun sendDataInChunks(gatt: BluetoothGatt, data: ByteArray, session: TransferSession) {
        val chunks = data.toList().chunked(CHUNK_SIZE)
        val totalChunks = chunks.size
        
        Log.d(TAG, "Sending ${data.size} bytes in $totalChunks chunks")
        
        scope.launch {
            chunks.forEachIndexed { index: Int, chunk: List<Byte> ->
                var retries = 0
                var success = false
                
                // Retry logic for each chunk
                while (retries < MAX_RETRIES && !success) {
                    success = writeCharacteristic(gatt, chunk.toByteArray(), index, totalChunks)
                    
                    if (!success) {
                        retries++
                        Log.w(TAG, "Failed to send chunk $index, retry $retries/$MAX_RETRIES")
                        
                        if (retries < MAX_RETRIES) {
                            delay(100L * retries) // Exponential backoff
                        } else {
                            _transferState.value = TransferState.Error("Failed to send chunk $index after $MAX_RETRIES retries")
                            stopTransfer(session.transferId)
                            return@launch
                        }
                    }
                }
                
                val progress = (index + 1).toFloat() / totalChunks
                _transferState.value = TransferState.Transferring(progress)
                
                // Update activity time
                session.lastActivityTime = System.currentTimeMillis()
                
                // Small delay between chunks to avoid overwhelming the receiver
                delay(20)
            }
            
            Log.d(TAG, "All chunks sent successfully")
            _transferState.value = TransferState.Completed(session.transferId)
            
            // Keep connection open briefly to ensure last chunk is received
            delay(500)
            stopTransfer(session.transferId)
        }
    }
    
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        data: ByteArray,
        chunkIndex: Int,
        totalChunks: Int
    ): Boolean {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Service not found: $SERVICE_UUID")
            return false
        }
        
        val characteristic = service.getCharacteristic(TRANSFER_DATA_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found: $TRANSFER_DATA_UUID")
            return false
        }
        
        // Add chunk metadata
        val packet = ByteArray(data.size + 8)
        packet[0] = (chunkIndex shr 8).toByte()
        packet[1] = chunkIndex.toByte()
        packet[2] = (totalChunks shr 8).toByte()
        packet[3] = totalChunks.toByte()
        packet[4] = (data.size shr 8).toByte()
        packet[5] = data.size.toByte()
        packet[6] = 0 // Reserved
        packet[7] = 0 // Reserved
        System.arraycopy(data, 0, packet, 8, data.size)
        
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ API
                val result = gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                result == BluetoothStatusCodes.SUCCESS
            } else {
                // Legacy API
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception writing characteristic", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error writing characteristic", e)
            false
        }
    }
    
    private fun startGattServer(transferId: String, onDataReceived: (ByteArray) -> Unit) {
        val gattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                Log.d(TAG, "GATT server connection state changed: $newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _transferState.value = TransferState.Connected(device)
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
                if (characteristic.uuid == TRANSFER_DATA_UUID) {
                    handleDataChunk(transferId, value, onDataReceived)
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        // Add service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        val dataCharacteristic = BluetoothGattCharacteristic(
            TRANSFER_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        service.addCharacteristic(dataCharacteristic)
        gattServer?.addService(service)
    }
    
    private fun handleDataChunk(transferId: String, packet: ByteArray, onComplete: (ByteArray) -> Unit) {
        val session = activeTransfers[transferId] ?: return
        
        if (packet.size < 8) {
            Log.e(TAG, "Invalid packet size: ${packet.size}")
            return
        }
        
        // Parse chunk metadata
        val chunkIndex = (packet[0].toInt() and 0xFF shl 8) or (packet[1].toInt() and 0xFF)
        val totalChunks = (packet[2].toInt() and 0xFF shl 8) or (packet[3].toInt() and 0xFF)
        val dataSize = (packet[4].toInt() and 0xFF shl 8) or (packet[5].toInt() and 0xFF)
        
        if (packet.size < 8 + dataSize) {
            Log.e(TAG, "Packet size mismatch: expected ${8 + dataSize}, got ${packet.size}")
            return
        }
        
        val data = packet.sliceArray(8 until (8 + dataSize))
        
        // Update session
        session.totalChunks = totalChunks
        session.chunksReceived[chunkIndex] = data
        session.lastActivityTime = System.currentTimeMillis()
        
        Log.d(TAG, "Received chunk $chunkIndex/$totalChunks (${data.size} bytes)")
        
        val progress = session.chunksReceived.size.toFloat() / totalChunks
        _transferState.value = TransferState.Transferring(progress)
        
        // Check if transfer complete
        if (session.chunksReceived.size == totalChunks) {
            // Verify we have all chunks
            val missingChunks = (0 until totalChunks).filter { !session.chunksReceived.containsKey(it) }
            if (missingChunks.isNotEmpty()) {
                Log.e(TAG, "Missing chunks: $missingChunks")
                _transferState.value = TransferState.Error("Missing chunks: ${missingChunks.size}")
                return
            }
            
            // Reassemble data in correct order
            val completeData = ByteArray(session.chunksReceived.values.sumOf { it.size })
            var offset = 0
            for (i in 0 until totalChunks) {
                val chunk = session.chunksReceived[i]!!
                System.arraycopy(chunk, 0, completeData, offset, chunk.size)
                offset += chunk.size
            }
            
            Log.d(TAG, "Transfer complete: ${completeData.size} bytes")
            _transferState.value = TransferState.Completed(transferId)
            onComplete(completeData)
            stopTransfer(transferId)
        }
    }
    
    private fun createGattCallback(session: TransferSession) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT connection state changed: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "GATT services discovered: $status")
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "Characteristic write status: $status")
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            _transferState.value = TransferState.Error("Advertising failed: $errorCode")
        }
    }
    
    private fun stopTransfer(transferId: String) {
        activeTransfers.remove(transferId)?.let { session ->
            session.gatt?.close()
        }
    }
    
    fun cleanup() {
        scope.cancel()
        gattServer?.close()
        activeTransfers.values.forEach { session ->
            session.gatt?.close()
        }
        activeTransfers.clear()
    }
    
    /**
     * Get currently discovered Bluetooth devices
     */
    fun getDiscoveredDevices(): List<BluetoothDevice> {
        // Get bonded devices as a starting point
        val devices = mutableListOf<BluetoothDevice>()
        
        try {
            // Add bonded devices
            bluetoothAdapter.bondedDevices?.let { bonded ->
                devices.addAll(bonded)
            }
            
            // In a real implementation, we would track devices discovered via scanning
            // For now, return bonded devices which are likely to be nearby
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot access Bluetooth devices", e)
        }
        
        return devices
    }
    
    /**
     * Get device's Bluetooth MAC address (note: may be randomized on newer Android)
     */
    fun getBluetoothMAC(): String {
        return try {
            bluetoothAdapter.address ?: generateRandomMAC()
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access Bluetooth address, using random MAC")
            generateRandomMAC()
        }
    }
    
    private fun generateRandomMAC(): String {
        // Generate a locally administered MAC address
        val random = Random()
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        
        // Set locally administered bit and unicast bit
        bytes[0] = (bytes[0].toInt() and 0xFC or 0x02).toByte()
        
        return bytes.joinToString(":") { "%02X".format(it) }
    }
    
    /**
     * Get current transfer progress for a specific transfer ID
     */
    fun getTransferProgress(transferId: String): Float? {
        val session = activeTransfers[transferId] ?: return null
        return when (session.role) {
            TransferRole.SENDER -> {
                // For sender, progress is tracked by the state flow
                when (val state = _transferState.value) {
                    is TransferState.Transferring -> state.progress
                    is TransferState.Completed -> 1.0f
                    else -> 0.0f
                }
            }
            TransferRole.RECEIVER -> {
                // For receiver, calculate based on chunks received
                if (session.totalChunks > 0) {
                    session.chunksReceived.size.toFloat() / session.totalChunks
                } else {
                    0.0f
                }
            }
        }
    }
    
    /**
     * Check if a transfer is still active
     */
    fun isTransferActive(transferId: String): Boolean {
        return activeTransfers.containsKey(transferId)
    }
    
    /**
     * Cancel a specific transfer
     */
    fun cancelTransfer(transferId: String) {
        Log.d(TAG, "Cancelling transfer: $transferId")
        stopTransfer(transferId)
        _transferState.value = TransferState.Error("Transfer cancelled")
    }
}