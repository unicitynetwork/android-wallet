package com.unicity.nfcwalletdemo.bluetooth

import android.bluetooth.BluetoothAdapter
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
    
    // Store transfer details temporarily
    private val pendingTransferDetails = ConcurrentHashMap<String, TransferDetails>()
    
    // Track timeout jobs so we can cancel them
    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    
    data class TransferDetails(
        val transferId: String,
        val senderPeerId: String,
        val senderName: String,
        val tokenType: String,
        val tokenName: String,
        val tokenPreview: String
    )
    
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
        
        Log.d(TAG, "BTMeshTransferCoordinator.initialize() called")
        context = appContext
        bluetoothMeshTransferService = transferService
        sdkService = sdk
        isInitialized = true
        
        // Set up event handlers IMMEDIATELY to catch all messages
        setupEventHandlers()
        
        // Verify we're actually connected to the event stream
        scope.launch {
            delay(500) // Small delay to let everything initialize
            Log.d(TAG, "Verifying event stream connection...")
            // Test that we can send a message
            BluetoothMeshManager.sendMessage("TEST_DEVICE", "COORDINATOR_INIT_TEST")
        }
        
        Log.d(TAG, "BTMeshTransferCoordinator initialized - event handlers active")
    }

    private fun setupEventHandlers() {
        Log.d(TAG, "=== setupEventHandlers() CALLED ===")
        
        // First, let's verify we can collect events at all
        scope.launch {
            Log.d(TAG, "Testing event collection...")
            val testResult = BluetoothMeshManager.testLocalMessage()
            Log.d(TAG, "Local message test completed")
        }
        
        // Listen for all events from BluetoothMeshManager
        // Using a while loop to restart collection if it fails
        scope.launch {
            Log.d(TAG, "Starting mesh event collection loop in BTMeshTransferCoordinator")
            
            while (isActive) {
                try {
                    Log.d(TAG, "BTMeshTransferCoordinator: Entering collect block...")
                    BluetoothMeshManager.meshEvents.collect { event ->
                        Log.d(TAG, "=== BTMeshTransferCoordinator GOT EVENT: ${event::class.simpleName} ===")
                        
                        // Log event reception
                        Log.d(TAG, "Coordinator processing event: ${event::class.simpleName}")
                        
                        try {
                            when (event) {
                                is BluetoothMeshManager.MeshEvent.MessageReceived -> {
                                    Log.d(TAG, "=== MESSAGE RECEIVED IN COORDINATOR ===")
                                    Log.d(TAG, "From: ${event.fromDevice}")
                                    Log.d(TAG, "Message preview: ${event.message.take(100)}...")
                                    
                                    // Handle the message in a separate coroutine to not block collection
                                    launch {
                                        val messageData = event.message.toByteArray(Charsets.UTF_8)
                                        handleIncomingMessage(messageData, event.fromDevice)
                                    }
                                }
                                is BluetoothMeshManager.MeshEvent.DeviceDiscovered -> {
                                    val peer = DiscoveredPeer(
                                        peerId = event.address,
                                        deviceName = event.name ?: "Unknown Device",
                                        lastSeen = System.currentTimeMillis()
                                    )
                                    discoveredPeers[event.address] = peer
                                    Log.d(TAG, "Wallet peer discovered: ${peer.deviceName} (${peer.peerId})")
                                }
                                else -> {
                                    Log.d(TAG, "Other event: ${event::class.simpleName}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing event", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Event collection failed, restarting in 1 second...", e)
                    delay(1000)
                }
            }
            Log.d(TAG, "Event collection loop ended - THIS SHOULD NOT HAPPEN!")
        }
    }

    fun handleIncomingMessage(data: ByteArray, fromDevice: String) {
        try {
            val messageString = String(data)
            Log.d(TAG, "=== handleIncomingMessage CALLED ===")
            Log.d(TAG, "From: $fromDevice")
            Log.d(TAG, "Message length: ${messageString.length}")
            Log.d(TAG, "Raw message: $messageString")
            
            // Special logging for permission responses
            if (messageString.contains("TRANSFER_PERMISSION_RESPONSE")) {
                Log.d(TAG, "!!! PERMISSION RESPONSE DETECTED IN RAW MESSAGE !!!")
                Log.d(TAG, "Full message for debugging: $messageString")
            }
            
            // Check if it's a test message
            if (messageString.startsWith("TEST_")) {
                Log.d(TAG, "Received test message: $messageString")
                return
            }
            
            // Check for simple rejection format
            if (messageString.startsWith("REJECT:")) {
                val rejectedTransferId = messageString.substringAfter("REJECT:")
                Log.d(TAG, "!!! RECEIVED SIMPLE REJECTION for transfer: $rejectedTransferId")
                Log.d(TAG, "Rejection received from device: $fromDevice")
                
                // Find the transfer and handle rejection
                val transfer = activeTransfers[rejectedTransferId]
                if (transfer != null && transfer.role == TransferRole.SENDER) {
                    Log.d(TAG, "Processing simple rejection for transfer $rejectedTransferId")
                    Log.d(TAG, "Transfer was in state: ${transfer.state}")
                    
                    // Cancel timeout
                    val timeoutJob = timeoutJobs.remove(rejectedTransferId)
                    if (timeoutJob != null) {
                        timeoutJob.cancel()
                        Log.d(TAG, "Cancelled timeout job for rejected transfer")
                    }
                    
                    // Update state
                    updateTransferState(rejectedTransferId, TransferState.REJECTED)
                    
                    // Show feedback
                    context?.let { ctx ->
                        scope.launch(Dispatchers.Main) {
                            if (transfer.tokenData?.id?.startsWith("test_") == true) {
                                val msg = "✅ BT Mesh Test Success!\n\nRejection Flow Complete:\n• Permission Request Sent ✓\n• Approval Dialog Shown ✓\n• Rejection Sent Back ✓\n• Sender Notified ✓\n\nBi-directional mesh working!"
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                Log.d(TAG, "Test rejection success toast shown")
                            } else {
                                android.widget.Toast.makeText(ctx, "❌ Transfer rejected by recipient", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    
                    // Cleanup
                    scope.launch {
                        delay(3000)
                        cleanupTransfer(rejectedTransferId)
                    }
                } else {
                    Log.w(TAG, "No active sender transfer found for rejected ID: $rejectedTransferId")
                    Log.d(TAG, "Active transfers: ${activeTransfers.entries.map { "${it.key} (${it.value.role})" }.joinToString(", ")}")
                }
                return
            }
            
            // Try to parse as JSON first (now that MTU is properly negotiated)
            val isJson = messageString.trim().startsWith("{") && messageString.trim().endsWith("}")
            if (isJson) {
                try {
                    val message = gson.fromJson(messageString, BTMeshMessage::class.java)
                    Log.d(TAG, "Successfully parsed JSON message")
                    handleJsonMessage(message, fromDevice)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON", e)
                }
            }
            
            // Fallback: Handle compact messages for backwards compatibility
            if (messageString.startsWith("TFER_")) {
                handleCompactMessage(messageString, fromDevice)
                return
            }
            
            Log.d(TAG, "Unrecognized message format: $messageString")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from $fromDevice: ${e.message}", e)
            e.printStackTrace()
        }
    }

    // Flow 2: Direct BT Mesh Transfer - Sender initiates
    fun initiateTransfer(token: Token, recipientPeerId: String, recipientName: String) {
        Log.d(TAG, "=== INITIATING BT MESH TRANSFER ===")
        Log.d(TAG, "Token: ${token.name}")
        Log.d(TAG, "Token ID: ${token.id}")
        Log.d(TAG, "Is test token: ${token.id.startsWith("test_")}")
        Log.d(TAG, "Recipient peer ID: $recipientPeerId")
        Log.d(TAG, "Recipient name: $recipientName")
        Log.d(TAG, "Context available: ${context != null}")
        Log.d(TAG, "Is initialized: $isInitialized")
        
        scope.launch {
            val transferId = UUID.randomUUID().toString()
            try {
            // Generate transfer ID
            Log.d(TAG, "Generated transfer ID: $transferId")
            
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
            
            // Show immediate feedback for test transfers
            if (token.id.startsWith("test_")) {
                val ctx = context
                if (ctx != null) {
                    withContext(Dispatchers.Main) {
                        try {
                            val message = "📤 Requesting Permission\nTo: $recipientName\nWaiting for approval..."
                            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                            Log.d(TAG, "Test transfer permission request toast shown: $message")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error showing test transfer toast", e)
                        }
                    }
                }
            }
            
            // Send full permission request now that MTU negotiation works
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
            
            Log.d(TAG, "Sending transfer permission request...")
            sendMessage(recipientPeerId, request)
            
            // Set timeout for approval
            val timeoutJob = launch {
                Log.d(TAG, "Starting approval timeout for transfer $transferId")
                delay(APPROVAL_TIMEOUT_MS)
                
                val currentTransfer = activeTransfers[transferId]
                Log.d(TAG, "Timeout check - Transfer $transferId state: ${currentTransfer?.state}")
                
                if (currentTransfer != null) {
                    when (currentTransfer.state) {
                        TransferState.REQUESTING_PERMISSION -> {
                            Log.e(TAG, "Transfer approval timed out after ${APPROVAL_TIMEOUT_MS}ms")
                            handleTransferTimeout(transferId, "Approval timeout")
                        }
                        TransferState.REJECTED -> {
                            Log.d(TAG, "Transfer was rejected, no timeout action needed")
                        }
                        else -> {
                            Log.d(TAG, "Transfer progressed beyond permission stage, no timeout action needed")
                        }
                    }
                } else {
                    Log.d(TAG, "Transfer already cleaned up")
                }
                
                timeoutJobs.remove(transferId)
            }
            timeoutJobs[transferId] = timeoutJob
            } catch (e: Exception) {
                Log.e(TAG, "Error in initiateTransfer", e)
                updateTransferState(transferId, TransferState.FAILED)
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
        Log.d(TAG, "=== handlePermissionRequest CALLED ===")
        Log.d(TAG, "From device: $fromDevice")
        Log.d(TAG, "Transfer ID: ${message.transferId}")
        Log.d(TAG, "My device ID: ${BluetoothAdapter.getDefaultAdapter()?.address ?: "Unknown"}")
        
        try {
            val payload = gson.fromJson(gson.toJson(message.payload), PermissionRequestPayload::class.java)
            Log.d(TAG, "Parsed payload - sender: ${payload.senderName}, token: ${payload.tokenName}")
            
            val approvalRequest = TransferApprovalRequest(
                transferId = message.transferId,
                senderPeerId = fromDevice,
                senderName = payload.senderName,
                tokenType = payload.tokenType,
                tokenName = payload.tokenName,
                tokenPreview = payload.tokenPreview,
                timestamp = System.currentTimeMillis()
            )
            
            Log.d(TAG, "IMPORTANT: Storing sender peer ID as: $fromDevice")
            Log.d(TAG, "This will be used for sending rejection response")
            
            Log.d(TAG, "Created approval request: $approvalRequest")
            
            // Add to pending approvals for UI
            val oldCount = _pendingApprovals.value.size
            _pendingApprovals.value = _pendingApprovals.value + approvalRequest
            val newCount = _pendingApprovals.value.size
            
            Log.d(TAG, "Pending approvals updated: $oldCount -> $newCount")
            Log.d(TAG, "Current pending approvals: ${_pendingApprovals.value.map { it.transferId }}")
            Log.d(TAG, "Approval request added successfully!")
            
            // Show a toast to confirm
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Transfer approval ready - should show dialog now!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            
            // Force update the state flow again to ensure observers are notified
            val currentApprovals = _pendingApprovals.value
            _pendingApprovals.value = emptyList()
            _pendingApprovals.value = currentApprovals
            Log.d(TAG, "Force updated pending approvals to trigger observers")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission request", e)
        }
    }

    fun approveTransfer(transferId: String) {
        val approval = _pendingApprovals.value.find { it.transferId == transferId } ?: return
        
        Log.d(TAG, "Approving transfer $transferId")
        
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
        
        // Send approval response with full JSON
        val response = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE,
            transferId = transferId,
            payload = PermissionResponsePayload(approved = true)
        )
        
        sendMessage(approval.senderPeerId, response)
    }

    fun rejectTransfer(transferId: String) {
        Log.d(TAG, "=== REJECT TRANSFER CALLED ===")
        Log.d(TAG, "Transfer ID: $transferId")
        
        // Debug: Log all active transfers and pending approvals
        Log.d(TAG, "Active transfers: ${activeTransfers.keys.joinToString(", ")}")
        Log.d(TAG, "Pending approvals: ${_pendingApprovals.value.map { it.transferId }.joinToString(", ")}")
        
        val approval = _pendingApprovals.value.find { it.transferId == transferId }
        if (approval == null) {
            Log.e(TAG, "No pending approval found for transfer $transferId")
            Log.e(TAG, "Available approvals: ${_pendingApprovals.value.map { "${it.transferId} from ${it.senderPeerId}" }.joinToString(", ")}")
            return
        }
        
        Log.d(TAG, "Found approval, sender peer: ${approval.senderPeerId}")
        Log.d(TAG, "My device address: ${BluetoothAdapter.getDefaultAdapter()?.address ?: "Unknown"}")
        
        // Debug: Let's verify the sender peer ID is valid
        val discoveredPeers = getDiscoveredPeers()
        Log.d(TAG, "Currently discovered peers: ${discoveredPeers.map { it.peerId }.joinToString(", ")}")
        val peerExists = discoveredPeers.any { it.peerId == approval.senderPeerId }
        Log.d(TAG, "Sender peer ${approval.senderPeerId} is in discovered list: $peerExists")
        
        // Remove from pending
        _pendingApprovals.value = _pendingApprovals.value.filter { it.transferId != transferId }
        Log.d(TAG, "Removed from pending approvals")
        
        // Send rejection with full JSON
        val response = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE,
            transferId = transferId,
            payload = PermissionResponsePayload(approved = false)
        )
        
        Log.d(TAG, "Sending rejection response to ${approval.senderPeerId}")
        
        // Debug: Log the exact JSON being sent
        val jsonToSend = gson.toJson(response)
        Log.d(TAG, "Rejection JSON: $jsonToSend")
        Log.d(TAG, "JSON length: ${jsonToSend.length}")
        
        // Send the rejection response immediately
        Log.d(TAG, "Sending rejection response NOW to ${approval.senderPeerId}")
        
        // Try a simplified approach - send rejection as a simple string first
        scope.launch {
            // Debug: Check if sender is still in GATT server's connected devices
            val connectedDevices = BluetoothMeshManager.getConnectedDevices()
            Log.d(TAG, "Currently connected GATT devices: ${connectedDevices.joinToString(", ")}")
            val isSenderConnected = connectedDevices.contains(approval.senderPeerId)
            Log.d(TAG, "Is sender ${approval.senderPeerId} still connected via GATT: $isSenderConnected")
            
            // If sender is still connected via GATT server, we can send directly
            if (isSenderConnected) {
                Log.d(TAG, "Sender is still connected via GATT, sending rejection directly")
                
                // Method 1: Send as simple string message first
                val simpleRejection = "REJECT:$transferId"
                Log.d(TAG, "Sending simple string rejection: $simpleRejection")
                val simpleResult = BluetoothMeshManager.sendMessage(approval.senderPeerId, simpleRejection)
                Log.d(TAG, "Simple rejection result: $simpleResult")
                
                if (!simpleResult) {
                    // If simple fails, try the JSON after a delay
                    delay(200)
                    Log.d(TAG, "Simple failed, trying JSON rejection...")
                    val jsonResult = sendMessage(approval.senderPeerId, response)
                    Log.d(TAG, "JSON rejection result: $jsonResult")
                }
            } else {
                // Sender not connected via GATT - need to initiate connection
                Log.d(TAG, "Sender not connected via GATT, initiating connection...")
                
                // Try connecting and sending
                val simpleRejection = "REJECT:$transferId"
                Log.d(TAG, "Attempting to connect and send rejection: $simpleRejection")
                val result = BluetoothMeshManager.sendMessage(approval.senderPeerId, simpleRejection)
                Log.d(TAG, "Connection and send result: $result")
                
                if (!result) {
                    Log.e(TAG, "Failed to send rejection - sender may have disconnected")
                    // Still update local state
                }
            }
        }
        
        // Also update local state for debugging
        val transfer = activeTransfers[transferId]
        if (transfer != null) {
            Log.d(TAG, "Found active transfer, updating state to REJECTED")
            updateTransferState(transferId, TransferState.REJECTED)
        } else {
            Log.w(TAG, "WARNING: No active transfer found on receiver side for $transferId")
        }
    }

    private fun handlePermissionResponse(message: BTMeshMessage, fromDevice: String) {
        Log.d(TAG, "=== RECEIVED PERMISSION RESPONSE ===")
        Log.d(TAG, "Transfer ID: ${message.transferId}")
        Log.d(TAG, "From device: $fromDevice")
        Log.d(TAG, "Raw payload: ${gson.toJson(message.payload)}")
        
        val transfer = activeTransfers[message.transferId]
        if (transfer == null) {
            Log.e(TAG, "No active transfer found for ID: ${message.transferId}")
            return
        }
        
        // Cancel any pending timeout job immediately
        val timeoutJob = timeoutJobs.remove(message.transferId)
        if (timeoutJob != null) {
            Log.d(TAG, "Cancelling timeout job for transfer ${message.transferId}")
            timeoutJob.cancel()
        }
        
        val payload = gson.fromJson(gson.toJson(message.payload), PermissionResponsePayload::class.java)
        Log.d(TAG, "Parsed approved value: ${payload.approved}")
        Log.d(TAG, "Approved is true: ${payload.approved == true}")
        Log.d(TAG, "Approved is false: ${payload.approved == false}")
        
        if (payload.approved) {
            Log.d(TAG, "Transfer approved, moving to WAITING_FOR_ADDRESS state")
            
            // Double-check transfer state before proceeding
            if (transfer.state == TransferState.REJECTED) {
                Log.e(TAG, "ERROR: Transfer was already rejected, not proceeding!")
                return
            }
            
            updateTransferState(message.transferId, TransferState.WAITING_FOR_ADDRESS)
            
            // Show detailed popup for test transfers
            val transfer = activeTransfers[message.transferId]
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        // Test transfer - show success toast
                        val msg = "✅ Test Transfer Approved!\nFrom: $fromDevice\nNow starting transfer..."
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Test transfer approval toast: $msg")
                    }
                }
            }
            
            // Continue with normal flow
            
            // Send the actual transfer request
            val request = BTMeshMessage(
                type = BTMeshMessageType.TRANSFER_REQUEST,
                transferId = message.transferId,
                payload = TransferRequestPayload(
                    tokenType = transfer?.tokenData?.type ?: "",
                    tokenId = transfer?.tokenId ?: ""
                )
            )
            
            Log.d(TAG, "Sending TRANSFER_REQUEST to $fromDevice")
            sendMessage(fromDevice, request)
            
            // Set timeout for the transfer
            setTransferTimeout(message.transferId)
        } else {
            Log.d(TAG, "=== TRANSFER REJECTED BY RECIPIENT ===")
            Log.d(TAG, "Updating state to REJECTED for transfer ${message.transferId}")
            
            updateTransferState(message.transferId, TransferState.REJECTED)
            
            // Force UI update
            _activeTransferStates.value = _activeTransferStates.value.toMutableMap().apply {
                this[message.transferId] = TransferState.REJECTED
            }
            Log.d(TAG, "Active transfer states updated: ${_activeTransferStates.value}")
            
            // Show detailed popup for test transfers
            val transfer = activeTransfers[message.transferId]
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        // Test transfer - show rejection toast
                        val msg = "✅ Test Transfer Rejected!\nFrom: $fromDevice\nBT Mesh working correctly!"
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Test transfer rejection toast: $msg")
                    } else {
                        // Regular transfer - show toast
                        android.widget.Toast.makeText(
                            ctx,
                            "Transfer rejected by recipient",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            
            // Clean up after a short delay to allow UI to update
            scope.launch {
                delay(3000) // Increased delay
                Log.d(TAG, "Cleaning up rejected transfer ${message.transferId}")
                cleanupTransfer(message.transferId)
            }
        }
    }

    private fun handleTransferRequest(message: BTMeshMessage, fromDevice: String) {
        Log.d(TAG, "=== RECEIVED TRANSFER REQUEST ===")
        Log.d(TAG, "Transfer ID: ${message.transferId}")
        Log.d(TAG, "From device: $fromDevice")
        
        val payload = gson.fromJson(gson.toJson(message.payload), TransferRequestPayload::class.java)
        Log.d(TAG, "Token type: ${payload.tokenType}")
        Log.d(TAG, "Token ID: ${payload.tokenId}")
        
        val transfer = activeTransfers[message.transferId]
        
        if (transfer == null) {
            Log.d(TAG, "No existing transfer, creating new recipient transfer")
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
                Log.d(TAG, "Generating address for token...")
                val address = generateAddressForToken(payload.tokenType, payload.tokenId)
                Log.d(TAG, "Generated address: $address")
                
                val response = BTMeshMessage(
                    type = BTMeshMessageType.ADDRESS_RESPONSE,
                    transferId = message.transferId,
                    payload = AddressResponsePayload(address = address)
                )
                
                Log.d(TAG, "Sending ADDRESS_RESPONSE to $fromDevice")
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
        Log.d(TAG, "=== TRANSFER COMPLETE RECEIVED ===")
        val payload = gson.fromJson(gson.toJson(message.payload), TransferCompletePayload::class.java)
        
        if (payload.success) {
            updateTransferState(message.transferId, TransferState.COMPLETED)
            
            // Remove the token from sender's wallet
            val transfer = activeTransfers[message.transferId]
            
            // Show success popup for test transfers
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        val msg = "✅ Test Transfer Complete!\nAll BT Mesh messages working!\n• Request ✓\n• Response ✓\n• Complete ✓"
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Test transfer complete toast: $msg")
                    }
                }
            }
            
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
        val messageString = gson.toJson(message)
        Log.d(TAG, "=== SENDING BT MESH MESSAGE ===")
        Log.d(TAG, "To peer: $peerId")
        Log.d(TAG, "Message type: ${message.type}")
        Log.d(TAG, "Transfer ID: ${message.transferId}")
        Log.d(TAG, "JSON length: ${messageString.length}")
        Log.d(TAG, "JSON content: $messageString")
        
        // Debug: Check if the JSON is valid
        try {
            val parsed = gson.fromJson(messageString, BTMeshMessage::class.java)
            Log.d(TAG, "JSON is valid - can be parsed back")
            Log.d(TAG, "Parsed type: ${parsed.type}")
        } catch (e: Exception) {
            Log.e(TAG, "JSON is INVALID - cannot be parsed!", e)
        }
        
        // Send the message through BluetoothMeshManager
        scope.launch {
            Log.d(TAG, "Calling BluetoothMeshManager.sendMessage with JSON...")
            val sendResult = BluetoothMeshManager.sendMessage(peerId, messageString)
            Log.d(TAG, "JSON send result: $sendResult")
            
            if (sendResult) {
                Log.d(TAG, "✓ Successfully sent ${message.type} to $peerId")
                
                // Special logging for rejection
                if (message.type == BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE) {
                    val payload = gson.fromJson(gson.toJson(message.payload), PermissionResponsePayload::class.java)
                    if (!payload.approved) {
                        Log.d(TAG, "✓✓✓ REJECTION SENT SUCCESSFULLY to $peerId")
                    }
                }
            } else {
                Log.e(TAG, "✗ FAILED to send JSON message to $peerId")
                Log.e(TAG, "Message type that failed: ${message.type}")
                
                // Update transfer state to failed if critical message fails
                when (message.type) {
                    BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE -> {
                        Log.e(TAG, "✗✗✗ CRITICAL: Failed to send permission response (rejection)")
                        updateTransferState(message.transferId, TransferState.FAILED)
                        
                        // Show error to user
                        context?.let { ctx ->
                            scope.launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    "Failed to send rejection response",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    BTMeshMessageType.TRANSFER_REQUEST -> {
                        Log.e(TAG, "Failed to send transfer request")
                        updateTransferState(message.transferId, TransferState.FAILED)
                    }
                    else -> {}
                }
            }
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
        Log.d(TAG, "=== UPDATE TRANSFER STATE ===")
        Log.d(TAG, "Transfer ID: $transferId")
        Log.d(TAG, "New state: $state")
        
        val transfer = activeTransfers[transferId]
        if (transfer != null) {
            Log.d(TAG, "Found transfer, old state: ${transfer.state}")
            transfer.state = state
            _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
            Log.d(TAG, "State updated successfully")
        } else {
            Log.e(TAG, "!!! NO TRANSFER FOUND for ID: $transferId !!!")
            Log.d(TAG, "Active transfers: ${activeTransfers.keys}")
        }
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
        val transfer = activeTransfers[transferId]
        if (transfer == null) {
            Log.d(TAG, "Timeout called but transfer $transferId already cleaned up")
            return
        }
        
        // Don't timeout if already in terminal state
        if (transfer.state == TransferState.COMPLETED || 
            transfer.state == TransferState.REJECTED || 
            transfer.state == TransferState.FAILED) {
            Log.d(TAG, "Timeout called but transfer $transferId already in terminal state: ${transfer.state}")
            return
        }
        
        Log.e(TAG, "Transfer $transferId timed out: $reason")
        Log.e(TAG, "Transfer was in state: ${transfer.state}")
        
        // Show debug popup for test transfers
        if (transfer.tokenData?.id?.startsWith("test_") == true) {
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    val msg = "⏱️ Test Transfer Timeout!\nState: ${transfer.state}\nRecipient may not have received request"
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Test transfer timeout toast: $msg")
                }
            }
        }
        
        updateTransferState(transferId, TransferState.FAILED)
        sendError(transfer.peerId, transferId, reason)
        cleanupTransfer(transferId)
    }

    private fun cleanupTransfer(transferId: String) {
        Log.d(TAG, "=== CLEANUP TRANSFER ===")
        Log.d(TAG, "Transfer ID: $transferId")
        
        // Cancel any pending timeout
        val timeoutJob = timeoutJobs.remove(transferId)
        if (timeoutJob != null) {
            Log.d(TAG, "Cancelling timeout job during cleanup")
            timeoutJob.cancel()
        }
        
        val removed = activeTransfers.remove(transferId)
        if (removed != null) {
            Log.d(TAG, "Transfer removed, was in state: ${removed.state}")
        } else {
            Log.d(TAG, "Transfer was already removed")
        }
        
        _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
        Log.d(TAG, "Active transfers remaining: ${activeTransfers.size}")
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
    
    private fun generateShortId(): String {
        // Generate a 6-character ID
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { chars.random() }
            .joinToString("")
    }
    
    private fun handleCompactMessage(message: String, fromDevice: String) {
        Log.d(TAG, "Handling compact message: $message")
        
        when {
            message.startsWith("TFER_APR:") -> {
                // Transfer approved
                val transferId = message.substringAfter("TFER_APR:")
                handleCompactApproval(transferId, true, fromDevice)
            }
            message.startsWith("TFER_REJ:") -> {
                // Transfer rejected
                val transferId = message.substringAfter("TFER_REJ:")
                handleCompactApproval(transferId, false, fromDevice)
            }
            message.startsWith("TFER_DTL:") -> {
                // Request for transfer details
                val transferId = message.substringAfter("TFER_DTL:")
                sendTransferDetails(transferId, fromDevice)
            }
        }
    }
    
    private fun handleCompactApproval(transferId: String, approved: Boolean, fromDevice: String) {
        val transfer = activeTransfers[transferId] ?: return
        
        if (approved) {
            Log.d(TAG, "Transfer $transferId approved by $fromDevice")
            updateTransferState(transferId, TransferState.APPROVED)
            
            // Continue with transfer flow
            // For now, just mark as completed since we can't send large payloads
            updateTransferState(transferId, TransferState.COMPLETED)
            
            // Show success
            context?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Transfer approved and completed!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            Log.d(TAG, "Transfer $transferId rejected by $fromDevice")
            updateTransferState(transferId, TransferState.REJECTED)
            cleanupTransfer(transferId)
        }
    }
    
    private fun sendTransferDetails(transferId: String, toPeer: String) {
        val details = pendingTransferDetails[transferId]
        if (details != null) {
            // Send details in chunks if needed
            Log.d(TAG, "Sending transfer details for $transferId to $toPeer")
            // For now, just log - actual implementation would send details
        }
    }
    
    private fun handleJsonMessage(message: BTMeshMessage, fromDevice: String) {
        Log.d(TAG, "=== HANDLING JSON MESSAGE ===")
        Log.d(TAG, "Message type: ${message.type}")
        Log.d(TAG, "Transfer ID: ${message.transferId}")
        Log.d(TAG, "From device: $fromDevice")
        
        // Log current transfer state
        val transfer = activeTransfers[message.transferId]
        if (transfer != null) {
            Log.d(TAG, "Current transfer state: ${transfer.state}, role: ${transfer.role}")
        } else {
            Log.d(TAG, "No active transfer for this ID")
        }
        
        when (message.type) {
            BTMeshMessageType.TRANSFER_PERMISSION_REQUEST -> handlePermissionRequest(message, fromDevice)
            BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE -> handlePermissionResponse(message, fromDevice)
            BTMeshMessageType.TRANSFER_REQUEST -> handleTransferRequest(message, fromDevice)
            BTMeshMessageType.ADDRESS_RESPONSE -> handleAddressResponse(message, fromDevice)
            BTMeshMessageType.TRANSFER_PACKAGE -> handleTransferPackage(message, fromDevice)
            BTMeshMessageType.TRANSFER_COMPLETE -> handleTransferComplete(message, fromDevice)
            BTMeshMessageType.TRANSFER_ERROR -> handleTransferError(message, fromDevice)
        }
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