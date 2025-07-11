package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
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
        var chunksReceived: MutableList<ByteArray> = mutableListOf(),
        var totalChunks: Int = 0,
        var device: BluetoothDevice? = null,
        var gatt: BluetoothGatt? = null,
        val startTime: Long = System.currentTimeMillis()
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
        
        val gatt = device.connectGatt(context, false, createGattCallback(session))
        session.gatt = gatt
        
        // Wait for connection and discovery
        delay(2000)
        
        // Send data in chunks
        session.tokenData?.let { data ->
            sendDataInChunks(gatt, data, session)
        }
    }
    
    private fun sendDataInChunks(gatt: BluetoothGatt, data: ByteArray, session: TransferSession) {
        val chunks = data.toList().chunked(CHUNK_SIZE)
        val totalChunks = chunks.size
        
        scope.launch {
            chunks.forEachIndexed { index: Int, chunk: List<Byte> ->
                val success = writeCharacteristic(gatt, chunk.toByteArray(), index, totalChunks)
                if (!success) {
                    _transferState.value = TransferState.Error("Failed to send chunk $index")
                    return@launch
                }
                
                val progress = (index + 1).toFloat() / totalChunks
                _transferState.value = TransferState.Transferring(progress)
                
                delay(50) // Small delay between chunks
            }
            
            _transferState.value = TransferState.Completed(session.transferId)
        }
    }
    
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        data: ByteArray,
        chunkIndex: Int,
        totalChunks: Int
    ): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(TRANSFER_DATA_UUID) ?: return false
        
        // Add chunk metadata
        val packet = ByteArray(data.size + 8)
        packet[0] = (chunkIndex shr 8).toByte()
        packet[1] = chunkIndex.toByte()
        packet[2] = (totalChunks shr 8).toByte()
        packet[3] = totalChunks.toByte()
        packet[4] = (data.size shr 8).toByte()
        packet[5] = data.size.toByte()
        System.arraycopy(data, 0, packet, 8, data.size)
        
        characteristic.value = packet
        return gatt.writeCharacteristic(characteristic)
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
        
        // Parse chunk metadata
        val chunkIndex = (packet[0].toInt() shl 8) or (packet[1].toInt() and 0xFF)
        val totalChunks = (packet[2].toInt() shl 8) or (packet[3].toInt() and 0xFF)
        val dataSize = (packet[4].toInt() shl 8) or (packet[5].toInt() and 0xFF)
        
        val data = packet.sliceArray(8 until (8 + dataSize))
        
        session.totalChunks = totalChunks
        session.chunksReceived.add(data)
        
        val progress = session.chunksReceived.size.toFloat() / totalChunks
        _transferState.value = TransferState.Transferring(progress)
        
        // Check if transfer complete
        if (session.chunksReceived.size == totalChunks) {
            val completeData = session.chunksReceived.reduce { acc, bytes -> acc + bytes }
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
     * Get device's Bluetooth MAC address (note: may be randomized on newer Android)
     */
    fun getBluetoothMAC(): String {
        return bluetoothAdapter.address ?: "00:00:00:00:00:00"
    }
}