package com.unicity.nfcwalletdemo.bluetooth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicityJavaSdkService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton coordinator for BT mesh transfers between wallets
 * Manages state machine for both sender and recipient flows
 */
object BTMeshTransferCoordinator {
    private const val TAG = "BTMeshTransferCoordinator"
    private const val TRANSFER_TIMEOUT_MS = 60000L // 60 seconds
    private const val APPROVAL_TIMEOUT_MS = 30000L // 30 seconds
    
    private lateinit var context: Context
    private lateinit var bluetoothMeshTransferService: BluetoothMeshTransferService
    private lateinit var sdkService: UnicityJavaSdkService
    private var isInitialized = false

    private val gson = Gson()
    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Track discovered peers
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeer>()
    
    // Observable state for UI
    private val _pendingApprovals = MutableStateFlow<List<TransferApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<TransferApprovalRequest>> = _pendingApprovals
    
    private val _activeTransferStates = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val activeTransferStates: StateFlow<Map<String, TransferState>> = _activeTransferStates

    fun initialize(
        appContext: Context,
        transferService: BluetoothMeshTransferService,
        sdk: UnicityJavaSdkService
    ) {
        if (isInitialized) {
            Log.d(TAG, "BTMeshTransferCoordinator already initialized")
            return
        }
        
        context = appContext
        bluetoothMeshTransferService = transferService
        sdkService = sdk
        isInitialized = true
        
        setupEventHandlers()
        Log.d(TAG, "BTMeshTransferCoordinator initialized")
    }

    private fun setupEventHandlers() {
        // Listen for all events from BluetoothMeshManager
        scope.launch {
            BluetoothMeshManager.meshEvents.collect { event ->
                when (event) {
                    is BluetoothMeshManager.MeshEvent.MessageReceived -> {
                        Log.d(TAG, "=== BTMeshTransferCoordinator INTERCEPTED MESSAGE ===")
                        Log.d(TAG, "From: ${event.fromDevice}")
                        Log.d(TAG, "Message: ${event.message}")
                        
                        // Convert message string to byte array
                        val messageData = event.message.toByteArray(Charsets.UTF_8)
                        handleIncomingMessage(messageData, event.fromDevice)
                    }
                    is BluetoothMeshManager.MeshEvent.DeviceDiscovered -> {
                        val peer = DiscoveredPeer(
                            peerId = event.address,
                            deviceName = event.name ?: "Unknown Device",
                            lastSeen = System.currentTimeMillis()
                        )
                        discoveredPeers[event.address] = peer
                        Log.d(TAG, "=== WALLET PEER DISCOVERED ===")
                        Log.d(TAG, "Name: ${peer.deviceName}")
                        Log.d(TAG, "Address: ${peer.peerId}")
                        Log.d(TAG, "Total discovered peers: ${discoveredPeers.size}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleIncomingMessage(data: ByteArray, fromDevice: String) {
        try {
            val messageString = String(data)
            Log.d(TAG, "Raw message received: $messageString")
            
            val message = gson.fromJson(messageString, BTMeshMessage::class.java)
            Log.d(TAG, "Parsed message type: ${message.type} from $fromDevice")
            
            when (message.type) {
                BTMeshMessageType.TRANSFER_PERMISSION_REQUEST -> {
                    Log.d(TAG, "Handling TRANSFER_PERMISSION_REQUEST")
                    handlePermissionRequest(message, fromDevice)
                }
                BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE -> handlePermissionResponse(message, fromDevice)
                BTMeshMessageType.TRANSFER_REQUEST -> handleTransferRequest(message, fromDevice)
                BTMeshMessageType.ADDRESS_RESPONSE -> handleAddressResponse(message, fromDevice)
                BTMeshMessageType.TRANSFER_PACKAGE -> handleTransferPackage(message, fromDevice)
                BTMeshMessageType.TRANSFER_COMPLETE -> handleTransferComplete(message, fromDevice)
                BTMeshMessageType.TRANSFER_ERROR -> handleTransferError(message, fromDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from $fromDevice: ${e.message}", e)
            e.printStackTrace()
        }
    }

    // Flow 2: Direct BT Mesh Transfer - Sender initiates
    fun initiateTransfer(token: Token, recipientPeerId: String, recipientName: String) {
        val transferId = UUID.randomUUID().toString()
        val transfer = ActiveTransfer(
            transferId = transferId,
            peerId = recipientPeerId,
            tokenId = token.id,
            tokenData = token,
            role = TransferRole.SENDER,
            state = TransferState.REQUESTING_PERMISSION,
            timestamp = System.currentTimeMillis()
        )
        
        activeTransfers[transferId] = transfer
        updateTransferState(transferId, TransferState.REQUESTING_PERMISSION)
        
        // Send permission request
        val request = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_REQUEST,
            transferId = transferId,
            payload = PermissionRequestPayload(
                senderName = getDeviceName(),
                tokenType = token.type,
                tokenName = token.name,
                tokenPreview = createTokenPreview(token)
            )
        )
        
        sendMessage(recipientPeerId, request)
        
        // Set timeout for approval
        scope.launch {
            delay(APPROVAL_TIMEOUT_MS)
            val currentTransfer = activeTransfers[transferId]
            if (currentTransfer?.state == TransferState.REQUESTING_PERMISSION) {
                handleTransferTimeout(transferId, "Approval timeout")
            }
        }
    }

    // Flow 1 support: Initiate transfer after NFC handshake
    fun initiateNfcTriggeredTransfer(token: Token, recipientPeerId: String) {
        val transferId = UUID.randomUUID().toString()
        val transfer = ActiveTransfer(
            transferId = transferId,
            peerId = recipientPeerId,
            tokenId = token.id,
            tokenData = token,
            role = TransferRole.SENDER,
            state = TransferState.WAITING_FOR_ADDRESS, // Skip permission, go straight to transfer
            timestamp = System.currentTimeMillis()
        )
        
        activeTransfers[transferId] = transfer
        updateTransferState(transferId, TransferState.WAITING_FOR_ADDRESS)
        
        // Send transfer request directly (no permission needed after NFC tap)
        val request = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_REQUEST,
            transferId = transferId,
            payload = TransferRequestPayload(
                tokenType = token.type,
                tokenId = token.id
            )
        )
        
        sendMessage(recipientPeerId, request)
        setTransferTimeout(transferId)
    }

    private fun handlePermissionRequest(message: BTMeshMessage, fromDevice: String) {
        Log.d(TAG, "Handling permission request from $fromDevice")
        val payload = gson.fromJson(gson.toJson(message.payload), PermissionRequestPayload::class.java)
        
        val approvalRequest = TransferApprovalRequest(
            transferId = message.transferId,
            senderPeerId = fromDevice,
            senderName = payload.senderName,
            tokenType = payload.tokenType,
            tokenName = payload.tokenName,
            tokenPreview = payload.tokenPreview,
            timestamp = System.currentTimeMillis()
        )
        
        Log.d(TAG, "Created approval request: $approvalRequest")
        
        // Add to pending approvals for UI
        _pendingApprovals.value = _pendingApprovals.value + approvalRequest
        Log.d(TAG, "Pending approvals count: ${_pendingApprovals.value.size}")
    }

    fun approveTransfer(transferId: String) {
        val approval = _pendingApprovals.value.find { it.transferId == transferId } ?: return
        
        // Remove from pending
        _pendingApprovals.value = _pendingApprovals.value.filter { it.transferId != transferId }
        
        // Create recipient transfer record
        val transfer = ActiveTransfer(
            transferId = transferId,
            peerId = approval.senderPeerId,
            tokenId = "", // Will be filled when we receive the transfer request
            tokenData = null,
            role = TransferRole.RECIPIENT,
            state = TransferState.APPROVED,
            timestamp = System.currentTimeMillis()
        )
        
        activeTransfers[transferId] = transfer
        updateTransferState(transferId, TransferState.APPROVED)
        
        // Send approval response
        val response = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE,
            transferId = transferId,
            payload = PermissionResponsePayload(approved = true)
        )
        
        sendMessage(approval.senderPeerId, response)
    }

    fun rejectTransfer(transferId: String) {
        val approval = _pendingApprovals.value.find { it.transferId == transferId } ?: return
        
        // Remove from pending
        _pendingApprovals.value = _pendingApprovals.value.filter { it.transferId != transferId }
        
        // Send rejection
        val response = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE,
            transferId = transferId,
            payload = PermissionResponsePayload(approved = false)
        )
        
        sendMessage(approval.senderPeerId, response)
    }

    private fun handlePermissionResponse(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), PermissionResponsePayload::class.java)
        val transfer = activeTransfers[message.transferId] ?: return
        
        if (payload.approved) {
            updateTransferState(message.transferId, TransferState.WAITING_FOR_ADDRESS)
            
            // Send the actual transfer request
            val request = BTMeshMessage(
                type = BTMeshMessageType.TRANSFER_REQUEST,
                transferId = message.transferId,
                payload = TransferRequestPayload(
                    tokenType = transfer.tokenData?.type ?: "",
                    tokenId = transfer.tokenId
                )
            )
            
            sendMessage(fromDevice, request)
        } else {
            updateTransferState(message.transferId, TransferState.REJECTED)
            cleanupTransfer(message.transferId)
        }
    }

    private fun handleTransferRequest(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), TransferRequestPayload::class.java)
        val transfer = activeTransfers[message.transferId]
        
        if (transfer == null) {
            // This might be an NFC-initiated transfer, create the transfer record
            val newTransfer = ActiveTransfer(
                transferId = message.transferId,
                peerId = fromDevice,
                tokenId = payload.tokenId,
                tokenData = null,
                role = TransferRole.RECIPIENT,
                state = TransferState.GENERATING_ADDRESS,
                timestamp = System.currentTimeMillis()
            )
            activeTransfers[message.transferId] = newTransfer
        }
        
        updateTransferState(message.transferId, TransferState.GENERATING_ADDRESS)
        
        // Generate address for this token type
        scope.launch {
            try {
                val address = generateAddressForToken(payload.tokenType, payload.tokenId)
                
                val response = BTMeshMessage(
                    type = BTMeshMessageType.ADDRESS_RESPONSE,
                    transferId = message.transferId,
                    payload = AddressResponsePayload(address = address)
                )
                
                sendMessage(fromDevice, response)
                updateTransferState(message.transferId, TransferState.WAITING_FOR_PACKAGE)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error generating address", e)
                sendError(fromDevice, message.transferId, "Failed to generate address: ${e.message}")
            }
        }
    }

    private fun handleAddressResponse(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), AddressResponsePayload::class.java)
        val transfer = activeTransfers[message.transferId] ?: return
        
        updateTransferState(message.transferId, TransferState.CREATING_PACKAGE)
        
        scope.launch {
            try {
                // Get sender identity
                val senderIdentity = getSenderIdentity()
                
                // Create offline transfer package
                val tokenJsonData = transfer.tokenData?.jsonData
                if (tokenJsonData.isNullOrEmpty()) {
                    throw Exception("Token JSON data is missing")
                }
                
                Log.d(TAG, "Creating offline package with address: ${payload.address}")
                Log.d(TAG, "Token data size: ${tokenJsonData.length} bytes")
                
                val packageResult = sdkService.createOfflineTransferPackage(
                    senderIdentity,
                    payload.address,
                    tokenJsonData
                )
                
                packageResult.fold(
                    onSuccess = { offlinePackage ->
                        val packageMessage = BTMeshMessage(
                            type = BTMeshMessageType.TRANSFER_PACKAGE,
                            transferId = message.transferId,
                            payload = TransferPackagePayload(
                                offlinePackage = offlinePackage
                            )
                        )
                        
                        updateTransferState(message.transferId, TransferState.SENDING_PACKAGE)
                        sendMessage(fromDevice, packageMessage)
                    },
                    onFailure = { error ->
                        sendError(fromDevice, message.transferId, "Failed to create package: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                sendError(fromDevice, message.transferId, "Error creating package: ${e.message}")
            }
        }
    }

    private fun handleTransferPackage(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), TransferPackagePayload::class.java)
        val transfer = activeTransfers[message.transferId] ?: return
        
        updateTransferState(message.transferId, TransferState.COMPLETING_TRANSFER)
        
        scope.launch {
            try {
                // Get recipient identity
                val recipientIdentity = getRecipientIdentity()
                
                // Complete the transfer
                val result = sdkService.completeOfflineTransfer(
                    recipientIdentity,
                    payload.offlinePackage
                )
                
                result.fold(
                    onSuccess = { receivedToken ->
                        val completeMessage = BTMeshMessage(
                            type = BTMeshMessageType.TRANSFER_COMPLETE,
                            transferId = message.transferId,
                            payload = TransferCompletePayload(
                                success = true,
                                tokenJson = receivedToken
                            )
                        )
                        
                        sendMessage(fromDevice, completeMessage)
                        updateTransferState(message.transferId, TransferState.COMPLETED)
                        
                        // Notify the app to update token list
                        notifyTokenReceived(receivedToken)
                        
                        // Cleanup after a delay
                        delay(5000)
                        cleanupTransfer(message.transferId)
                    },
                    onFailure = { error ->
                        sendError(fromDevice, message.transferId, "Failed to complete transfer: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                sendError(fromDevice, message.transferId, "Error completing transfer: ${e.message}")
            }
        }
    }

    private fun handleTransferComplete(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), TransferCompletePayload::class.java)
        
        if (payload.success) {
            updateTransferState(message.transferId, TransferState.COMPLETED)
            
            // Remove the token from sender's wallet
            val transfer = activeTransfers[message.transferId]
            transfer?.tokenData?.let { token ->
                notifyTokenSent(token.id)
            }
            
            // Cleanup after a delay
            scope.launch {
                delay(5000)
                cleanupTransfer(message.transferId)
            }
        } else {
            updateTransferState(message.transferId, TransferState.FAILED)
        }
    }

    private fun handleTransferError(message: BTMeshMessage, fromDevice: String) {
        val payload = gson.fromJson(gson.toJson(message.payload), TransferErrorPayload::class.java)
        Log.e(TAG, "Transfer error from $fromDevice: ${payload.error}")
        
        updateTransferState(message.transferId, TransferState.FAILED)
        cleanupTransfer(message.transferId)
    }

    private fun sendMessage(peerId: String, message: BTMeshMessage) {
        Log.d(TAG, "Sending message to $peerId: ${gson.toJson(message)}")
        
        // Send the message through BluetoothMeshManager
        val messageString = gson.toJson(message)
        scope.launch {
            BluetoothMeshManager.sendMessage(peerId, messageString)
        }
    }

    private fun sendError(peerId: String, transferId: String, error: String) {
        val errorMessage = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_ERROR,
            transferId = transferId,
            payload = TransferErrorPayload(error = error)
        )
        sendMessage(peerId, errorMessage)
        updateTransferState(transferId, TransferState.FAILED)
    }

    private fun updateTransferState(transferId: String, state: TransferState) {
        activeTransfers[transferId]?.state = state
        _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
    }

    private fun setTransferTimeout(transferId: String) {
        scope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            val transfer = activeTransfers[transferId]
            if (transfer != null && transfer.state != TransferState.COMPLETED) {
                handleTransferTimeout(transferId, "Transfer timeout")
            }
        }
    }

    private fun handleTransferTimeout(transferId: String, reason: String) {
        val transfer = activeTransfers[transferId] ?: return
        Log.e(TAG, "Transfer $transferId timed out: $reason")
        
        updateTransferState(transferId, TransferState.FAILED)
        sendError(transfer.peerId, transferId, reason)
        cleanupTransfer(transferId)
    }

    private fun cleanupTransfer(transferId: String) {
        activeTransfers.remove(transferId)
        _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
    }

    private suspend fun generateAddressForToken(tokenType: String, tokenId: String): String {
        return withContext(Dispatchers.IO) {
            // Generate a new identity for the recipient to create a unique address
            var recipientIdentity: String? = null
            sdkService.generateIdentity { result ->
                result.fold(
                    onSuccess = { identity -> recipientIdentity = identity },
                    onFailure = { error -> throw error }
                )
            }
            
            // Wait for identity generation
            delay(1000)
            
            if (recipientIdentity == null) {
                throw Exception("Failed to generate recipient identity")
            }
            
            // Parse identity to get address components
            val identityData = com.google.gson.JsonParser.parseString(recipientIdentity).asJsonObject
            val secret = identityData.get("secret").asString.toByteArray()
            val nonce = identityData.get("nonce").asString.toByteArray()
            
            // Create signing service and predicate
            val signingService = com.unicity.sdk.shared.signing.SigningService.createFromSecret(secret, nonce).get()
            val tokenIdBytes = hexStringToByteArray(tokenId)
            val tokenTypeBytes = hexStringToByteArray("01") // Default token type
            
            val predicate = com.unicity.sdk.predicate.MaskedPredicate.create(
                com.unicity.sdk.token.TokenId.create(tokenIdBytes),
                com.unicity.sdk.token.TokenType.create(tokenTypeBytes),
                signingService,
                com.unicity.sdk.shared.hash.HashAlgorithm.SHA256,
                nonce
            ).get()
            
            com.unicity.sdk.address.DirectAddress.create(predicate.reference).get().toString()
        }
    }

    private suspend fun getSenderIdentity(): String {
        // In a real app, this would come from the IdentityManager
        // For now, generate a new one
        return withContext(Dispatchers.IO) {
            var identity: String? = null
            sdkService.generateIdentity { result ->
                result.fold(
                    onSuccess = { id -> identity = id },
                    onFailure = { error -> throw error }
                )
            }
            delay(1000)
            identity ?: throw Exception("Failed to generate sender identity")
        }
    }

    private suspend fun getRecipientIdentity(): String {
        // In a real app, this would come from the IdentityManager
        // For now, generate a new one
        return withContext(Dispatchers.IO) {
            var identity: String? = null
            sdkService.generateIdentity { result ->
                result.fold(
                    onSuccess = { id -> identity = id },
                    onFailure = { error -> throw error }
                )
            }
            delay(1000)
            identity ?: throw Exception("Failed to generate recipient identity")
        }
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    private fun createTokenPreview(token: Token): String {
        // Create a preview suitable for showing in approval dialog
        return when (token.type) {
            "Unicity Token" -> "Unicity Token: ${token.name}"
            else -> "${token.type}: ${token.name}"
        }
    }

    private fun notifyTokenReceived(tokenJson: String) {
        // TODO: Notify the wallet repository to add the received token
    }

    private fun notifyTokenSent(tokenId: String) {
        // TODO: Notify the wallet repository to remove the sent token
    }

    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        // Only return peers that we've actually discovered through BluetoothMeshManager events
        // These are devices that are advertising our MESH_SERVICE_UUID and running the wallet app
        val peers = discoveredPeers.values.toList()
        
        Log.d(TAG, "Returning ${peers.size} discovered wallet peers")
        peers.forEach { peer ->
            Log.d(TAG, "  - ${peer.deviceName} (${peer.peerId})")
        }
        
        // Return peers sorted by last seen (most recent first)
        return peers.sortedByDescending { it.lastSeen }
    }
    
    fun getActiveTransfer(transferId: String): ActiveTransfer? {
        return activeTransfers[transferId]
    }

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
    
    fun cleanup() {
        scope.cancel()
        activeTransfers.clear()
    }
}

// Data classes
enum class BTMeshMessageType {
    TRANSFER_PERMISSION_REQUEST,
    TRANSFER_PERMISSION_RESPONSE,
    TRANSFER_REQUEST,
    ADDRESS_RESPONSE,
    TRANSFER_PACKAGE,
    TRANSFER_COMPLETE,
    TRANSFER_ERROR
}

data class BTMeshMessage(
    val type: BTMeshMessageType,
    val transferId: String,
    val payload: Any
)

enum class TransferRole {
    SENDER,
    RECIPIENT
}

enum class TransferState {
    REQUESTING_PERMISSION,
    APPROVED,
    REJECTED,
    WAITING_FOR_ADDRESS,
    GENERATING_ADDRESS,
    CREATING_PACKAGE,
    SENDING_PACKAGE,
    WAITING_FOR_PACKAGE,
    COMPLETING_TRANSFER,
    COMPLETED,
    FAILED
}

data class ActiveTransfer(
    val transferId: String,
    val peerId: String,
    val tokenId: String,
    val tokenData: Token?,
    val role: TransferRole,
    var state: TransferState,
    val timestamp: Long
)

data class TransferApprovalRequest(
    val transferId: String,
    val senderPeerId: String,
    val senderName: String,
    val tokenType: String,
    val tokenName: String,
    val tokenPreview: String,
    val timestamp: Long
)

data class DiscoveredPeer(
    val peerId: String,
    val deviceName: String,
    val lastSeen: Long
)

// Payload classes
data class PermissionRequestPayload(
    val senderName: String,
    val tokenType: String,
    val tokenName: String,
    val tokenPreview: String
)

data class PermissionResponsePayload(
    val approved: Boolean
)

data class TransferRequestPayload(
    val tokenType: String,
    val tokenId: String
)

data class AddressResponsePayload(
    val address: String
)

data class TransferPackagePayload(
    val offlinePackage: String
)

data class TransferCompletePayload(
    val success: Boolean,
    val tokenJson: String? = null
)

data class TransferErrorPayload(
    val error: String
)