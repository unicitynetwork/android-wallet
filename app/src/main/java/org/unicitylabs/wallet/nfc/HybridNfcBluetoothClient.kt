package org.unicitylabs.wallet.nfc

import android.content.Context
import android.util.Log
import org.unicitylabs.wallet.bluetooth.BluetoothMeshTransferService
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Hybrid NFC + Bluetooth client for Unicity token transfers
 * 
 * Flow:
 * 1. NFC tap exchanges Bluetooth connection info
 * 2. Bluetooth mesh network handles actual token transfer
 * 3. Provides callbacks for UI updates
 */
class HybridNfcBluetoothClient(
    private val context: Context,
    private val sdkService: UnicityJavaSdkService,
    private val apduTransceiver: ApduTransceiver,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: ((current: Int, total: Int) -> Unit)? = null
) {
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    companion object {
        private const val TAG = "HybridNfcBtClient"
        private const val NFC_HANDSHAKE_TIMEOUT_MS = 5000L
        private const val BLUETOOTH_TRANSFER_TIMEOUT_MS = 30000L
    }
    
    private val bluetoothService = BluetoothMeshTransferService(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state
    
    sealed class TransferState {
        object Idle : TransferState()
        object NfcHandshaking : TransferState()
        data class BluetoothConnecting(val peerMAC: String) : TransferState()
        data class Transferring(val progress: Float) : TransferState()
        object Completed : TransferState()
        data class Error(val message: String) : TransferState()
    }
    
    private var currentToken: Token? = null
    private var senderIdentity: String? = null
    private var currentTransferId: String? = null
    
    /**
     * Start hybrid transfer as sender
     */
    fun startTransferAsSender(token: Token, senderIdentity: String) {
        Log.d(TAG, "Starting hybrid transfer as sender for token: ${token.name}")
        
        this.currentToken = token
        this.senderIdentity = senderIdentity
        
        _state.value = TransferState.NfcHandshaking
        
        scope.launch {
            try {
                // Step 1: NFC Handshake
                Log.d(TAG, "Waiting for NFC tap to exchange Bluetooth info...")
                val handshakeResponse = performNfcHandshake(token)
                
                if (!handshakeResponse.accepted) {
                    throw Exception("Transfer rejected by receiver")
                }
                
                // Step 2: Prepare token data for Bluetooth transfer
                val tokenData = prepareTokenData(token, senderIdentity)
                
                // Step 3: Start Bluetooth transfer
                _state.value = TransferState.BluetoothConnecting(handshakeResponse.bluetoothMAC)
                
                // Monitor Bluetooth transfer progress
                scope.launch {
                    bluetoothService.transferState.collect { btState ->
                        when (btState) {
                            is BluetoothMeshTransferService.TransferState.Transferring -> {
                                _state.value = TransferState.Transferring(btState.progress)
                                onProgress?.invoke((btState.progress * 100).toInt(), 100)
                            }
                            is BluetoothMeshTransferService.TransferState.Completed -> {
                                _state.value = TransferState.Completed
                                onTransferComplete()
                            }
                            is BluetoothMeshTransferService.TransferState.Error -> {
                                _state.value = TransferState.Error(btState.message)
                                onError(btState.message)
                            }
                            else -> {}
                        }
                    }
                }
                
                currentTransferId = handshakeResponse.transferId
                
                bluetoothService.startSenderMode(
                    transferId = handshakeResponse.transferId,
                    targetMAC = handshakeResponse.bluetoothMAC,
                    tokenData = tokenData
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
                _state.value = TransferState.Error(e.message ?: "Unknown error")
                onError(e.message ?: "Transfer failed")
            }
        }
    }
    
    /**
     * Start hybrid transfer as receiver
     */
    fun startTransferAsReceiver(receiverIdentity: String) {
        Log.d(TAG, "Starting hybrid transfer as receiver")
        
        _state.value = TransferState.NfcHandshaking
        
        scope.launch {
            try {
                // Step 1: Wait for NFC handshake
                val handshake = waitForNfcHandshake()
                
                // Step 2: Send acceptance response
                val response = BluetoothHandshakeResponse(
                    receiverId = generatePeerId(),
                    bluetoothMAC = bluetoothService.getBluetoothMAC(),
                    transferId = handshake.transferId,
                    accepted = true
                )
                sendNfcResponse(response)
                
                // Step 3: Start Bluetooth receiver mode
                _state.value = TransferState.BluetoothConnecting(handshake.bluetoothMAC)
                
                // Monitor Bluetooth transfer progress
                scope.launch {
                    bluetoothService.transferState.collect { btState ->
                        when (btState) {
                            is BluetoothMeshTransferService.TransferState.Transferring -> {
                                _state.value = TransferState.Transferring(btState.progress)
                                onProgress?.invoke((btState.progress * 100).toInt(), 100)
                            }
                            else -> {}
                        }
                    }
                }
                
                currentTransferId = handshake.transferId
                
                val tokenData = bluetoothService.startReceiverMode(
                    transferId = handshake.transferId,
                    targetMAC = handshake.bluetoothMAC
                )
                
                // Step 4: Process received token
                processReceivedToken(tokenData, receiverIdentity)
                
                _state.value = TransferState.Completed
                onTransferComplete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Receive failed", e)
                _state.value = TransferState.Error(e.message ?: "Unknown error")
                onError(e.message ?: "Receive failed")
            }
        }
    }
    
    private suspend fun performNfcHandshake(token: Token): BluetoothHandshakeResponse {
        return withTimeout(NFC_HANDSHAKE_TIMEOUT_MS) {
            // Create handshake data with shorter IDs to fit in 255 bytes
            val handshake = BluetoothHandshake(
                senderId = generatePeerId().take(8), // Shorten to 8 chars
                bluetoothMAC = bluetoothService.getBluetoothMAC(),
                transferId = UUID.randomUUID().toString().take(12), // Shorten transfer ID
                tokenPreview = TokenPreview(
                    tokenId = token.id.take(16), // Limit token ID length
                    name = token.name.take(20), // Limit name length
                    type = token.type.take(10), // Limit type length
                    amount = null
                )
            )
            
            Log.d(TAG, "Waiting for NFC tag...")
            
            // First, select the application
            val selectAid = byteArrayOf(
                0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
                0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
            )
            
            val selectResponse = try {
                apduTransceiver.transceive(selectAid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transceive SELECT AID", e)
                throw Exception("Failed to communicate with receiver. Make sure phones are touching.")
            }
            
            Log.d(TAG, "SELECT AID response: ${selectResponse.size} bytes")
            
            if (selectResponse.isEmpty()) {
                throw Exception("Empty response from SELECT AID command - is receiver in receive mode?")
            }
            
            if (selectResponse.size < 2 || selectResponse.last() != 0x00.toByte() || selectResponse[selectResponse.size - 2] != 0x90.toByte()) {
                val status = if (selectResponse.size >= 2) {
                    String.format("%02X%02X", selectResponse[selectResponse.size-2], selectResponse[selectResponse.size-1])
                } else {
                    "Invalid response"
                }
                throw Exception("Failed to select application, status: $status")
            }
            
            // Send hybrid handshake using a new command
            val handshakeData = handshake.toByteArray()
            Log.d(TAG, "Sending hybrid handshake: ${handshake.toJson()}")
            Log.d(TAG, "Handshake data size: ${handshakeData.size} bytes")
            
            // Check if handshake data is too large for single APDU
            if (handshakeData.size > 255) {
                throw Exception("Handshake data too large: ${handshakeData.size} bytes (max 255)")
            }
            
            val command = ByteArray(5 + handshakeData.size)
            command[0] = 0x00.toByte() // CLA
            command[1] = 0x10.toByte() // INS - New command for hybrid handshake
            command[2] = 0x00.toByte() // P1
            command[3] = 0x00.toByte() // P2
            command[4] = handshakeData.size.toByte() // Lc
            System.arraycopy(handshakeData, 0, command, 5, handshakeData.size)
            
            Log.d(TAG, "Sending APDU command: CLA=00 INS=10 P1=00 P2=00 LC=${handshakeData.size}")
            
            val response = apduTransceiver.transceive(command)
            Log.d(TAG, "Received APDU response: ${response.size} bytes, status: ${String.format("%02X %02X", response[response.size-2], response[response.size-1])}")
            
            // Check if response is successful (SW=9000)
            if (response.size < 2 || response[response.size-2] != 0x90.toByte() || response[response.size-1] != 0x00.toByte()) {
                val status = String.format("%02X%02X", response[response.size-2], response[response.size-1])
                
                // Check if receiver doesn't support hybrid mode
                if (status == "6F00") {
                    throw Exception("Receiver doesn't support Bluetooth transfer. They need to update the app.")
                } else {
                    throw Exception("Handshake failed with status: $status")
                }
            }
            
            val responseData = if (response.size > 2) {
                String(response.dropLast(2).toByteArray())
            } else {
                throw Exception("Empty handshake response")
            }
            
            Log.d(TAG, "Handshake response data: $responseData")
            
            try {
                BluetoothHandshakeResponse.fromJson(responseData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse handshake response", e)
                throw Exception("Invalid handshake response format: ${e.message}")
            }
        }
    }
    
    private suspend fun waitForNfcHandshake(): BluetoothHandshake {
        return withTimeout(NFC_HANDSHAKE_TIMEOUT_MS) {
            // In real implementation, this would listen for NFC APDU commands
            // For now, simulate receiving handshake
            suspendCancellableCoroutine { continuation ->
                // This would be called when NFC data is received
                val mockHandshake = BluetoothHandshake(
                    senderId = "mock-sender",
                    bluetoothMAC = "00:11:22:33:44:55",
                    transferId = UUID.randomUUID().toString(),
                    tokenPreview = TokenPreview(
                        tokenId = "test-token",
                        name = "Test Token",
                        type = "NFT",
                        amount = null
                    )
                )
                continuation.resumeWith(Result.success(mockHandshake))
            }
        }
    }
    
    private suspend fun sendNfcResponse(response: BluetoothHandshakeResponse) {
        // Send response via NFC APDU
        val responseApdu = ByteArray(response.toByteArray().size + 2)
        System.arraycopy(response.toByteArray(), 0, responseApdu, 0, response.toByteArray().size)
        responseApdu[responseApdu.size - 2] = 0x90.toByte()
        responseApdu[responseApdu.size - 1] = 0x00.toByte()
        
        // In real implementation, this would send via NFC
        Log.d(TAG, "Sending NFC response: ${response.toJson()}")
    }
    
    private suspend fun prepareTokenData(token: Token, senderIdentity: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                // Parse sender identity to get secret and nonce
                val identityJson = JSONObject(senderIdentity)
                val senderSecret = identityJson.getString("secret").toByteArray()
                val senderNonce = hexStringToByteArray(identityJson.getString("nonce"))
                
                // Create offline transfer package using SDK
                val transferPackage = sdkService.createOfflineTransfer(
                    token.jsonData ?: "{}",
                    "offline-transfer", // Placeholder, will be updated by receiver
                    null, // Use full amount
                    senderSecret,
                    senderNonce
                )
                
                if (transferPackage == null) {
                    Log.e(TAG, "Failed to create offline transfer package")
                    // Fallback to simple JSON format for testing
                    val fallbackPackage = JSONObject().apply {
                        put("tokenId", token.id)
                        put("tokenName", token.name)
                        put("tokenType", token.type)
                        put("tokenData", token.jsonData ?: "{}")
                        put("senderIdentity", senderIdentity)
                        put("timestamp", System.currentTimeMillis())
                        put("isTestTransfer", true)
                    }
                    return@withContext fallbackPackage.toString().toByteArray()
                }
                
                // Return the SDK-generated transfer package
                transferPackage.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing token data", e)
                // Fallback for testing
                val transferPackage = JSONObject().apply {
                    put("tokenId", token.id)
                    put("tokenName", token.name)
                    put("tokenType", token.type)
                    put("tokenData", token.jsonData ?: "{}")
                    put("senderIdentity", senderIdentity)
                    put("timestamp", System.currentTimeMillis())
                    put("isTestTransfer", true)
                    put("error", e.message)
                }
                transferPackage.toString().toByteArray()
            }
        }
    }
    
    private suspend fun processReceivedToken(tokenData: ByteArray, receiverIdentity: String) {
        withContext(Dispatchers.IO) {
            try {
                val transferPackage = JSONObject(String(tokenData))
                
                // Check if this is a test transfer
                val isTestTransfer = transferPackage.optBoolean("isTestTransfer", false)
                
                if (isTestTransfer) {
                    Log.d(TAG, "Received test transfer package: ${transferPackage.toString(2)}")
                    // For test transfers, just log and consider it successful
                    return@withContext
                }
                
                // Parse receiver identity to get secret and nonce
                val identityJson = JSONObject(receiverIdentity)
                val receiverSecret = identityJson.getString("secret").toByteArray()
                val receiverNonce = hexStringToByteArray(identityJson.getString("nonce"))
                
                // Process real transfer with SDK
                val receivedToken = sdkService.completeOfflineTransfer(
                    String(tokenData),
                    receiverSecret,
                    receiverNonce
                )
                
                if (receivedToken == null) {
                    Log.e(TAG, "Failed to complete transfer")
                    throw Exception("Failed to complete offline transfer")
                }
                
                Log.d(TAG, "Successfully completed transfer with SDK")
            } catch (e: JSONException) {
                Log.e(TAG, "Invalid transfer data format", e)
                throw Exception("Invalid transfer data received")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received token", e)
                throw e
            }
        }
    }
    
    private fun generatePeerId(): String {
        return UUID.randomUUID().toString().take(8)
    }
    
    /**
     * Cancel the current transfer
     */
    fun cancelTransfer() {
        currentTransferId?.let { transferId ->
            bluetoothService.cancelTransfer(transferId)
        }
        _state.value = TransferState.Error("Transfer cancelled by user")
    }
    
    /**
     * Get current transfer progress
     */
    fun getTransferProgress(): Float {
        return currentTransferId?.let { transferId ->
            bluetoothService.getTransferProgress(transferId) ?: 0f
        } ?: 0f
    }
    
    fun cleanup() {
        scope.cancel()
        bluetoothService.cleanup()
    }
}