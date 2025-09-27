package org.unicitylabs.wallet.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import org.unicitylabs.wallet.utils.WalletConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton coordinator for BT mesh transfers between wallets
 * Manages state machine for both sender and recipient flows
 */
object BTMeshTransferCoordinator {
    private const val TAG = "BTMeshTransferCoordinator"
    private const val DEBUG = false // Set to true to enable verbose BT logging
    private const val TRANSFER_TIMEOUT_MS = 60000L // 60 seconds
    private const val APPROVAL_TIMEOUT_MS = 30000L // 30 seconds
    
    private var applicationContext: Context? = null
    private lateinit var bluetoothMeshTransferService: BluetoothMeshTransferService
    private lateinit var sdkService: UnicityJavaSdkService
    private var isInitialized = false

    private val gson = Gson()
    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()
    
    private fun logd(message: String) {
        if (DEBUG) logd( message)
    }
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
            logd( "BTMeshTransferCoordinator already initialized")
            return
        }
        
        logd( "BTMeshTransferCoordinator.initialize() called")
        applicationContext = appContext.applicationContext
        bluetoothMeshTransferService = transferService
        sdkService = sdk
        isInitialized = true
        
        // Set up event handlers IMMEDIATELY to catch all messages
        setupEventHandlers()
        
        // Verify we're actually connected to the event stream
        scope.launch {
            delay(500) // Small delay to let everything initialize
            logd( "Verifying event stream connection...")
            // Test that we can send a message
            BluetoothMeshManager.sendMessage("TEST_DEVICE", "COORDINATOR_INIT_TEST")
        }
        
        logd( "BTMeshTransferCoordinator initialized - event handlers active")
    }

    private fun setupEventHandlers() {
        logd( "=== setupEventHandlers() CALLED ===")
        
        // First, let's verify we can collect events at all
        scope.launch {
            logd( "Testing event collection...")
            val testResult = BluetoothMeshManager.testLocalMessage()
            logd( "Local message test completed")
        }
        
        // Listen for all events from BluetoothMeshManager
        // Using a while loop to restart collection if it fails
        scope.launch {
            logd( "Starting mesh event collection loop in BTMeshTransferCoordinator")
            
            while (isActive) {
                try {
                    logd( "BTMeshTransferCoordinator: Entering collect block...")
                    BluetoothMeshManager.meshEvents.collect { event ->
                        logd( "=== BTMeshTransferCoordinator GOT EVENT: ${event::class.simpleName} ===")
                        
                        // Log event reception
                        logd( "Coordinator processing event: ${event::class.simpleName}")
                        
                        try {
                            when (event) {
                                is BluetoothMeshManager.MeshEvent.MessageReceived -> {
                                    logd( "=== MESSAGE RECEIVED IN COORDINATOR ===")
                                    logd( "From: ${event.fromDevice}")
                                    logd( "Message length: ${event.message.length}")
                                    logd( "Message preview: ${event.message.take(100)}...")
                                    
                                    // Special logging for rejection messages
                                    if (event.message.contains("REJECT:") || event.message.contains("PERMISSION_RESPONSE")) {
                                        logd( "!!! CRITICAL MESSAGE DETECTED !!!")
                                        logd( "Full message: ${event.message}")
                                    }
                                    
                                    // Handle the message in a separate coroutine to not block collection
                                    launch {
                                        try {
                                            val messageData = event.message.toByteArray(Charsets.UTF_8)
                                            handleIncomingMessage(messageData, event.fromDevice)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error handling message in coroutine", e)
                                        }
                                    }
                                }
                                is BluetoothMeshManager.MeshEvent.DeviceDiscovered -> {
                                    val peer = DiscoveredPeer(
                                        peerId = event.address,
                                        deviceName = event.name ?: "Unknown Device",
                                        lastSeen = System.currentTimeMillis()
                                    )
                                    discoveredPeers[event.address] = peer
                                    logd( "Wallet peer discovered: ${peer.deviceName} (${peer.peerId})")
                                }
                                else -> {
                                    logd( "Other event: ${event::class.simpleName}")
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
            logd( "Event collection loop ended - THIS SHOULD NOT HAPPEN!")
        }
    }

    fun handleIncomingMessage(data: ByteArray, fromDevice: String) {
        try {
            val messageString = String(data)
            logd( "=== handleIncomingMessage CALLED ===")
            logd( "From: $fromDevice")
            logd( "Message length: ${messageString.length}")
            logd( "Raw message: $messageString")
            
            // Special logging for permission responses
            if (messageString.contains("TRANSFER_PERMISSION_RESPONSE")) {
                logd( "!!! PERMISSION RESPONSE DETECTED IN RAW MESSAGE !!!")
                logd( "Full message for debugging: $messageString")
            }
            
            // Check if it's a test message
            if (messageString.startsWith("TEST_")) {
                logd( "Received test message: $messageString")
                return
            }
            
            // Check for simple rejection format
            if (messageString.startsWith("REJECT:")) {
                val rejectedTransferId = messageString.substringAfter("REJECT:")
                logd( "=== [REJECTION RECEIVED] SIMPLE REJECTION ===")
                logd( "[REJECTION] Transfer ID: $rejectedTransferId")
                logd( "[REJECTION] From device: $fromDevice")
                logd( "[REJECTION] My device: ${BluetoothMeshManager.getBluetoothAddress() ?: "Unknown"}")
                logd( "[REJECTION] Active transfers count: ${activeTransfers.size}")
                
                // Log all active transfers for debugging
                activeTransfers.forEach { (id, transfer) ->
                    logd( "[REJECTION] Transfer $id: role=${transfer.role}, state=${transfer.state}")
                }
                
                // Find the transfer and handle rejection
                val transfer = activeTransfers[rejectedTransferId]
                if (transfer != null && transfer.role == TransferRole.SENDER) {
                    logd( "[REJECTION] ✓ Found matching SENDER transfer")
                    logd( "[REJECTION] Transfer was in state: ${transfer.state}")
                    logd( "[REJECTION] Token: ${transfer.tokenData?.name}")
                    
                    // Cancel timeout
                    val timeoutJob = timeoutJobs.remove(rejectedTransferId)
                    if (timeoutJob != null) {
                        timeoutJob.cancel()
                        logd( "[REJECTION] Cancelled timeout job")
                    }
                    
                    // Update state
                    updateTransferState(rejectedTransferId, TransferState.REJECTED)
                    logd( "[REJECTION] State updated to REJECTED")
                    
                    // Show feedback
                    applicationContext?.let { ctx ->
                        scope.launch(Dispatchers.Main) {
                            if (transfer.tokenData?.id?.startsWith("test_") == true) {
                                val msg = "✅ BT Mesh Test Success!\n\nRejection Flow Complete:\n• Permission Request Sent ✓\n• Approval Dialog Shown ✓\n• Rejection Sent Back ✓\n• Sender Notified ✓\n\nBi-directional mesh working!"
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                logd( "[REJECTION] Test success toast shown")
                            } else {
                                android.widget.Toast.makeText(ctx, "❌ Transfer rejected by recipient", android.widget.Toast.LENGTH_LONG).show()
                                logd( "[REJECTION] Regular rejection toast shown")
                            }
                        }
                    }
                    
                    // Cleanup
                    scope.launch {
                        delay(3000)
                        logd( "[REJECTION] Cleaning up transfer")
                        cleanupTransfer(rejectedTransferId)
                    }
                } else {
                    Log.e(TAG, "[REJECTION] ✗ No active SENDER transfer found!")
                    Log.e(TAG, "[REJECTION] Looking for ID: $rejectedTransferId")
                    Log.e(TAG, "[REJECTION] Transfer exists: ${transfer != null}")
                    Log.e(TAG, "[REJECTION] Transfer role: ${transfer?.role}")
                    Log.e(TAG, "[REJECTION] Active transfers: ${activeTransfers.entries.map { "${it.key} (${it.value.role})" }.joinToString(", ")}")
                }
                return
            }
            
            // Try to parse as JSON first (now that MTU is properly negotiated)
            val isJson = messageString.trim().startsWith("{") && messageString.trim().endsWith("}")
            if (isJson) {
                try {
                    val message = gson.fromJson(messageString, BTMeshMessage::class.java)
                    logd( "Successfully parsed JSON message")
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
            
            logd( "Unrecognized message format: $messageString")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from $fromDevice: ${e.message}", e)
            e.printStackTrace()
        }
    }

    // Flow 2: Direct BT Mesh Transfer - Sender initiates
    fun initiateTransfer(token: Token, recipientPeerId: String, recipientName: String) {
        logd( "=== INITIATING BT MESH TRANSFER ===")
        logd( "Token: ${token.name}")
        logd( "Token ID: ${token.id}")
        logd( "Is test token: ${token.id.startsWith("test_")}")
        logd( "Recipient peer ID: $recipientPeerId")
        logd( "Recipient name: $recipientName")
        logd( "Context available: ${applicationContext != null}")
        logd( "Is initialized: $isInitialized")
        
        scope.launch {
            val transferId = UUID.randomUUID().toString()
            try {
            // Generate transfer ID
            logd( "Generated transfer ID: $transferId")
            
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
            logd( "[TRANSFER INIT] Stored transfer in activeTransfers map")
            logd( "[TRANSFER INIT] Transfer ID: $transferId")
            logd( "[TRANSFER INIT] Role: SENDER")
            logd( "[TRANSFER INIT] Active transfers count: ${activeTransfers.size}")
            updateTransferState(transferId, TransferState.REQUESTING_PERMISSION)
            
            // Show immediate feedback for test transfers
            if (token.id.startsWith("test_")) {
                val ctx = applicationContext
                if (ctx != null) {
                    withContext(Dispatchers.Main) {
                        try {
                            val message = "📤 Requesting Permission\nTo: $recipientName\nWaiting for approval..."
                            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                            logd( "Test transfer permission request toast shown: $message")
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
            
            logd( "Sending transfer permission request...")
            sendMessage(recipientPeerId, request)
            
            // Set timeout for approval
            val timeoutJob = launch {
                logd( "Starting approval timeout for transfer $transferId")
                delay(APPROVAL_TIMEOUT_MS)
                
                val currentTransfer = activeTransfers[transferId]
                logd( "Timeout check - Transfer $transferId state: ${currentTransfer?.state}")
                
                if (currentTransfer != null) {
                    when (currentTransfer.state) {
                        TransferState.REQUESTING_PERMISSION -> {
                            Log.e(TAG, "Transfer approval timed out after ${APPROVAL_TIMEOUT_MS}ms")
                            handleTransferTimeout(transferId, "Approval timeout")
                        }
                        TransferState.REJECTED -> {
                            logd( "Transfer was rejected, no timeout action needed")
                        }
                        else -> {
                            logd( "Transfer progressed beyond permission stage, no timeout action needed")
                        }
                    }
                } else {
                    logd( "Transfer already cleaned up")
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
        logd( "=== handlePermissionRequest CALLED ===")
        logd( "From device: $fromDevice")
        logd( "Transfer ID: ${message.transferId}")
        logd( "My device ID: ${BluetoothAdapter.getDefaultAdapter()?.address ?: "Unknown"}")
        
        try {
            val payload = gson.fromJson(gson.toJson(message.payload), PermissionRequestPayload::class.java)
            logd( "Parsed payload - sender: ${payload.senderName}, token: ${payload.tokenName}")
            
            val approvalRequest = TransferApprovalRequest(
                transferId = message.transferId,
                senderPeerId = fromDevice,
                senderName = payload.senderName,
                tokenType = payload.tokenType,
                tokenName = payload.tokenName,
                tokenPreview = payload.tokenPreview,
                timestamp = System.currentTimeMillis()
            )
            
            logd( "IMPORTANT: Storing sender peer ID as: $fromDevice")
            logd( "This will be used for sending rejection response")
            
            logd( "Created approval request: $approvalRequest")
            
            // Add to pending approvals for UI
            val oldCount = _pendingApprovals.value.size
            _pendingApprovals.value = _pendingApprovals.value + approvalRequest
            val newCount = _pendingApprovals.value.size
            
            logd( "Pending approvals updated: $oldCount -> $newCount")
            logd( "Current pending approvals: ${_pendingApprovals.value.map { it.transferId }}")
            logd( "Approval request added successfully!")
            
            // Show a toast to confirm
            applicationContext?.let { ctx ->
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
            logd( "Force updated pending approvals to trigger observers")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission request", e)
        }
    }

    fun approveTransfer(transferId: String) {
        val approval = _pendingApprovals.value.find { it.transferId == transferId } ?: return
        
        logd( "Approving transfer $transferId")
        
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
        logd( "=== REJECT TRANSFER CALLED ===")
        logd( "Transfer ID: $transferId")
        
        // Debug: Log all active transfers and pending approvals
        logd( "Active transfers: ${activeTransfers.keys.joinToString(", ")}")
        logd( "Pending approvals: ${_pendingApprovals.value.map { it.transferId }.joinToString(", ")}")
        
        val approval = _pendingApprovals.value.find { it.transferId == transferId }
        if (approval == null) {
            Log.e(TAG, "No pending approval found for transfer $transferId")
            Log.e(TAG, "Available approvals: ${_pendingApprovals.value.map { "${it.transferId} from ${it.senderPeerId}" }.joinToString(", ")}")
            return
        }
        
        logd( "Found approval, sender peer: ${approval.senderPeerId}")
        logd( "My device address: ${BluetoothAdapter.getDefaultAdapter()?.address ?: "Unknown"}")
        
        // Debug: Let's verify the sender peer ID is valid
        val discoveredPeers = getDiscoveredPeers()
        logd( "Currently discovered peers: ${discoveredPeers.map { it.peerId }.joinToString(", ")}")
        val peerExists = discoveredPeers.any { it.peerId == approval.senderPeerId }
        logd( "Sender peer ${approval.senderPeerId} is in discovered list: $peerExists")
        
        // Remove from pending
        _pendingApprovals.value = _pendingApprovals.value.filter { it.transferId != transferId }
        logd( "Removed from pending approvals")
        
        // Send rejection with full JSON
        val response = BTMeshMessage(
            type = BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE,
            transferId = transferId,
            payload = PermissionResponsePayload(approved = false)
        )
        
        logd( "Sending rejection response to ${approval.senderPeerId}")
        
        // Debug: Log the exact JSON being sent
        val jsonToSend = gson.toJson(response)
        logd( "Rejection JSON: $jsonToSend")
        logd( "JSON length: ${jsonToSend.length}")
        
        // Send the rejection response immediately
        logd( "Sending rejection response NOW to ${approval.senderPeerId}")
        
        // Try a simplified approach - send rejection as a simple string first
        scope.launch {
            // Debug: Check if sender is still in GATT server's connected devices
            val connectedDevices = BluetoothMeshManager.getConnectedDevices()
            logd( "Currently connected GATT devices: ${connectedDevices.joinToString(", ")}")
            val isSenderConnected = connectedDevices.contains(approval.senderPeerId)
            logd( "Is sender ${approval.senderPeerId} still connected via GATT: $isSenderConnected")
            
            // Always try multiple methods to ensure rejection is delivered
            logd( "=== SENDING REJECTION TO SENDER ===")
            logd( "Is sender connected: $isSenderConnected")
            logd( "Sender peer ID: ${approval.senderPeerId}")
            logd( "Transfer ID: $transferId")
            
            // Method 1: Send simple rejection first (most reliable)
            val simpleRejection = "REJECT:$transferId"
            logd( "[REJECTION] Sending simple string: $simpleRejection")
            val simpleResult = BluetoothMeshManager.sendMessage(approval.senderPeerId, simpleRejection, retryCount = 5)
            logd( "[REJECTION] Simple rejection send result: $simpleResult")
            
            // Method 2: Send JSON rejection after a small delay
            delay(500)
            logd( "[REJECTION] Sending JSON rejection message")
            sendMessage(approval.senderPeerId, response)
            // Note: sendMessage is async and doesn't return a result
            val jsonResult = true // Assume it will be sent
            
            // Method 3: Try once more with simple rejection after another delay
            if (!simpleResult && !jsonResult) {
                delay(1000)
                logd( "[REJECTION] Both methods failed, trying simple rejection again")
                val retryResult = BluetoothMeshManager.sendMessage(approval.senderPeerId, simpleRejection, retryCount = 3)
                logd( "[REJECTION] Retry rejection send result: $retryResult")
            }
            
            // Log final status
            if (simpleResult || jsonResult) {
                logd( "[REJECTION] ✓ Rejection sent successfully")
            } else {
                Log.e(TAG, "[REJECTION] ✗ Failed to send rejection after all attempts")
            }
        }
        
        // Also update local state for debugging
        val transfer = activeTransfers[transferId]
        if (transfer != null) {
            logd( "Found active transfer, updating state to REJECTED")
            updateTransferState(transferId, TransferState.REJECTED)
        } else {
            Log.w(TAG, "WARNING: No active transfer found on receiver side for $transferId")
        }
    }

    private fun handlePermissionResponse(message: BTMeshMessage, fromDevice: String) {
        logd( "=== RECEIVED PERMISSION RESPONSE ===")
        logd( "Transfer ID: ${message.transferId}")
        logd( "From device: $fromDevice")
        logd( "Raw payload: ${gson.toJson(message.payload)}")
        
        val transfer = activeTransfers[message.transferId]
        if (transfer == null) {
            Log.e(TAG, "No active transfer found for ID: ${message.transferId}")
            return
        }
        
        // Cancel any pending timeout job immediately
        val timeoutJob = timeoutJobs.remove(message.transferId)
        if (timeoutJob != null) {
            logd( "Cancelling timeout job for transfer ${message.transferId}")
            timeoutJob.cancel()
        }
        
        val payload = gson.fromJson(gson.toJson(message.payload), PermissionResponsePayload::class.java)
        logd( "Parsed approved value: ${payload.approved}")
        logd( "Approved is true: ${payload.approved == true}")
        logd( "Approved is false: ${payload.approved == false}")
        
        if (payload.approved) {
            logd( "Transfer approved, moving to WAITING_FOR_ADDRESS state")
            
            // Double-check transfer state before proceeding
            if (transfer.state == TransferState.REJECTED) {
                Log.e(TAG, "ERROR: Transfer was already rejected, not proceeding!")
                return
            }
            
            updateTransferState(message.transferId, TransferState.WAITING_FOR_ADDRESS)
            
            // Show detailed popup for test transfers
            val transfer = activeTransfers[message.transferId]
            applicationContext?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        // Test transfer - show success toast
                        val msg = "✅ Test Transfer Approved!\nFrom: $fromDevice\nNow starting transfer..."
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        logd( "Test transfer approval toast: $msg")
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
            
            logd( "Sending TRANSFER_REQUEST to $fromDevice")
            sendMessage(fromDevice, request)
            
            // Set timeout for the transfer
            setTransferTimeout(message.transferId)
        } else {
            logd( "=== TRANSFER REJECTED BY RECIPIENT ===")
            logd( "Updating state to REJECTED for transfer ${message.transferId}")
            
            updateTransferState(message.transferId, TransferState.REJECTED)
            
            // Force UI update
            _activeTransferStates.value = _activeTransferStates.value.toMutableMap().apply {
                this[message.transferId] = TransferState.REJECTED
            }
            logd( "Active transfer states updated: ${_activeTransferStates.value}")
            
            // Show detailed popup for test transfers
            val transfer = activeTransfers[message.transferId]
            applicationContext?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        // Test transfer - show rejection toast
                        val msg = "✅ Test Transfer Rejected!\nFrom: $fromDevice\nBT Mesh working correctly!"
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        logd( "Test transfer rejection toast: $msg")
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
                logd( "Cleaning up rejected transfer ${message.transferId}")
                cleanupTransfer(message.transferId)
            }
        }
    }

    private fun handleTransferRequest(message: BTMeshMessage, fromDevice: String) {
        logd( "=== RECEIVED TRANSFER REQUEST ===")
        logd( "Transfer ID: ${message.transferId}")
        logd( "From device: $fromDevice")
        
        val payload = gson.fromJson(gson.toJson(message.payload), TransferRequestPayload::class.java)
        logd( "Token type: ${payload.tokenType}")
        logd( "Token ID: ${payload.tokenId}")
        
        val transfer = activeTransfers[message.transferId]
        
        if (transfer == null) {
            logd( "No existing transfer, creating new recipient transfer")
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
                logd( "Generating address for token...")
                val address = generateAddressForToken(payload.tokenType, payload.tokenId)
                logd( "Generated address: $address")
                
                val response = BTMeshMessage(
                    type = BTMeshMessageType.ADDRESS_RESPONSE,
                    transferId = message.transferId,
                    payload = AddressResponsePayload(address = address)
                )
                
                logd( "Sending ADDRESS_RESPONSE to $fromDevice")
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
                val senderIdentityJson = getSenderIdentity()
                val senderIdentity = com.google.gson.JsonParser.parseString(senderIdentityJson).asJsonObject
                val senderSecret = senderIdentity.get("secret").asString.toByteArray()
                val senderNonce = hexStringToByteArray(senderIdentity.get("nonce").asString)
                
                // Create offline transfer package
                val tokenJsonData = transfer.tokenData?.jsonData
                if (tokenJsonData.isNullOrEmpty()) {
                    throw Exception("Token JSON data is missing")
                }
                
                logd( "Creating offline package with address: ${payload.address}")
                logd( "Token data size: ${tokenJsonData.length} bytes")
                
                val offlinePackage = sdkService.createOfflineTransfer(
                    tokenJsonData,
                    payload.address,
                    null, // Use full token amount
                    senderSecret,
                    senderNonce
                )
                
                if (offlinePackage != null) {
                        val packageMessage = BTMeshMessage(
                            type = BTMeshMessageType.TRANSFER_PACKAGE,
                            transferId = message.transferId,
                            payload = TransferPackagePayload(
                                offlinePackage = offlinePackage
                            )
                        )
                        
                    updateTransferState(message.transferId, TransferState.SENDING_PACKAGE)
                    sendMessage(fromDevice, packageMessage)
                } else {
                    sendError(fromDevice, message.transferId, "Failed to create offline transfer package")
                }
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
                val recipientIdentityJson = getRecipientIdentity()
                val recipientIdentity = com.google.gson.JsonParser.parseString(recipientIdentityJson).asJsonObject
                val recipientSecret = recipientIdentity.get("secret").asString.toByteArray()
                val recipientNonce = hexStringToByteArray(recipientIdentity.get("nonce").asString)
                
                // Complete the transfer
                val receivedToken = sdkService.completeOfflineTransfer(
                    payload.offlinePackage,
                    recipientSecret,
                    recipientNonce
                )
                
                if (receivedToken != null) {
                        val completeMessage = BTMeshMessage(
                            type = BTMeshMessageType.TRANSFER_COMPLETE,
                            transferId = message.transferId,
                            payload = TransferCompletePayload(
                                success = true,
                                tokenJson = gson.toJson(receivedToken)
                            )
                        )
                        
                        sendMessage(fromDevice, completeMessage)
                        updateTransferState(message.transferId, TransferState.COMPLETED)
                        
                        // Notify the app to update token list
                        notifyTokenReceived(gson.toJson(receivedToken))
                        
                        // Cleanup after a delay
                        delay(5000)
                        cleanupTransfer(message.transferId)
                } else {
                    sendError(fromDevice, message.transferId, "Failed to complete offline transfer")
                }
            } catch (e: Exception) {
                sendError(fromDevice, message.transferId, "Error completing transfer: ${e.message}")
            }
        }
    }

    private fun handleTransferComplete(message: BTMeshMessage, fromDevice: String) {
        logd( "=== TRANSFER COMPLETE RECEIVED ===")
        val payload = gson.fromJson(gson.toJson(message.payload), TransferCompletePayload::class.java)
        
        if (payload.success) {
            updateTransferState(message.transferId, TransferState.COMPLETED)
            
            // Remove the token from sender's wallet
            val transfer = activeTransfers[message.transferId]
            
            // Show success popup for test transfers
            applicationContext?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    if (transfer?.tokenData?.id?.startsWith("test_") == true) {
                        val msg = "✅ Test Transfer Complete!\nAll BT Mesh messages working!\n• Request ✓\n• Response ✓\n• Complete ✓"
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        logd( "Test transfer complete toast: $msg")
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
        logd( "=== SENDING BT MESH MESSAGE ===")
        logd( "To peer: $peerId")
        logd( "Message type: ${message.type}")
        logd( "Transfer ID: ${message.transferId}")
        logd( "JSON length: ${messageString.length}")
        logd( "JSON content: $messageString")
        
        // Debug: Check if the JSON is valid
        try {
            val parsed = gson.fromJson(messageString, BTMeshMessage::class.java)
            logd( "JSON is valid - can be parsed back")
            logd( "Parsed type: ${parsed.type}")
        } catch (e: Exception) {
            Log.e(TAG, "JSON is INVALID - cannot be parsed!", e)
        }
        
        // Send the message through BluetoothMeshManager
        scope.launch {
            logd( "Calling BluetoothMeshManager.sendMessage with JSON...")
            val sendResult = BluetoothMeshManager.sendMessage(peerId, messageString)
            logd( "JSON send result: $sendResult")
            
            if (sendResult) {
                logd( "✓ Successfully sent ${message.type} to $peerId")
                
                // Special logging for rejection
                if (message.type == BTMeshMessageType.TRANSFER_PERMISSION_RESPONSE) {
                    val payload = gson.fromJson(gson.toJson(message.payload), PermissionResponsePayload::class.java)
                    if (!payload.approved) {
                        logd( "✓✓✓ REJECTION SENT SUCCESSFULLY to $peerId")
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
                        applicationContext?.let { ctx ->
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
        logd( "=== UPDATE TRANSFER STATE ===")
        logd( "Transfer ID: $transferId")
        logd( "New state: $state")
        
        val transfer = activeTransfers[transferId]
        if (transfer != null) {
            logd( "Found transfer, old state: ${transfer.state}")
            transfer.state = state
            _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
            logd( "State updated successfully")
        } else {
            Log.e(TAG, "!!! NO TRANSFER FOUND for ID: $transferId !!!")
            logd( "Active transfers: ${activeTransfers.keys}")
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
            logd( "Timeout called but transfer $transferId already cleaned up")
            return
        }
        
        // Don't timeout if already in terminal state
        if (transfer.state == TransferState.COMPLETED || 
            transfer.state == TransferState.REJECTED || 
            transfer.state == TransferState.FAILED) {
            logd( "Timeout called but transfer $transferId already in terminal state: ${transfer.state}")
            return
        }
        
        Log.e(TAG, "Transfer $transferId timed out: $reason")
        Log.e(TAG, "Transfer was in state: ${transfer.state}")
        
        // Show debug popup for test transfers
        if (transfer.tokenData?.id?.startsWith("test_") == true) {
            applicationContext?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    val msg = "⏱️ Test Transfer Timeout!\nState: ${transfer.state}\nRecipient may not have received request"
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    logd( "Test transfer timeout toast: $msg")
                }
            }
        }
        
        updateTransferState(transferId, TransferState.FAILED)
        sendError(transfer.peerId, transferId, reason)
        cleanupTransfer(transferId)
    }

    private fun cleanupTransfer(transferId: String) {
        logd( "=== CLEANUP TRANSFER ===")
        logd( "Transfer ID: $transferId")
        
        // Cancel any pending timeout
        val timeoutJob = timeoutJobs.remove(transferId)
        if (timeoutJob != null) {
            logd( "Cancelling timeout job during cleanup")
            timeoutJob.cancel()
        }
        
        val removed = activeTransfers.remove(transferId)
        if (removed != null) {
            logd( "Transfer removed, was in state: ${removed.state}")
        } else {
            logd( "Transfer was already removed")
        }
        
        _activeTransferStates.value = activeTransfers.mapValues { it.value.state }
        logd( "Active transfers remaining: ${activeTransfers.size}")
    }

    private suspend fun generateAddressForToken(tokenType: String, tokenId: String): String {
        return withContext(Dispatchers.IO) {
            // Generate a new identity for the recipient to create a unique address
            val recipientIdentity = generateTestIdentity()
            
            if (recipientIdentity == null) {
                throw Exception("Failed to generate recipient identity")
            }
            
            // Parse identity to get address components
            val identityData = com.google.gson.JsonParser.parseString(recipientIdentity).asJsonObject
            val secret = identityData.get("secret").asString.toByteArray()
            val nonceHex = identityData.get("nonce").asString
            val nonce = hexStringToByteArray(nonceHex)
            
            // Create signing service and predicate
            val signingService = org.unicitylabs.sdk.signing.SigningService.createFromMaskedSecret(secret, nonce)
            val tokenIdBytes = hexStringToByteArray(tokenId)
            val tokenTypeBytes = hexStringToByteArray(WalletConstants.UNICITY_TOKEN_TYPE)

            val tokenId = org.unicitylabs.sdk.token.TokenId(tokenIdBytes)
            val tokenType = org.unicitylabs.sdk.token.TokenType(tokenTypeBytes)

            val predicate = org.unicitylabs.sdk.predicate.embedded.MaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                nonce
            )
            
            predicate.getReference().toAddress().toString()
        }
    }

    private suspend fun getSenderIdentity(): String {
        // In a real app, this would come from the IdentityManager
        // For now, generate a new one
        return withContext(Dispatchers.IO) {
            generateTestIdentity() ?: throw Exception("Failed to generate sender identity")
        }
    }

    private suspend fun getRecipientIdentity(): String {
        // In a real app, this would come from the IdentityManager
        // For now, generate a new one
        return withContext(Dispatchers.IO) {
            generateTestIdentity() ?: throw Exception("Failed to generate recipient identity")
        }
    }
    
    private fun generateTestIdentity(): String? {
        // Generate a test identity
        val secret = "test-identity-${System.currentTimeMillis()}"
        val nonce = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }
        val identity = mapOf(
            "secret" to secret,
            "nonce" to nonce.toHexString()
        )
        return com.google.gson.Gson().toJson(identity)
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
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
        
        logd( "Returning ${peers.size} discovered wallet peers")
        peers.forEach { peer ->
            logd( "  - ${peer.deviceName} (${peer.peerId})")
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
        logd( "Handling compact message: $message")
        
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
            logd( "Transfer $transferId approved by $fromDevice")
            updateTransferState(transferId, TransferState.APPROVED)
            
            // Continue with transfer flow
            // For now, just mark as completed since we can't send large payloads
            updateTransferState(transferId, TransferState.COMPLETED)
            
            // Show success
            applicationContext?.let { ctx ->
                scope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Transfer approved and completed!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            logd( "Transfer $transferId rejected by $fromDevice")
            updateTransferState(transferId, TransferState.REJECTED)
            cleanupTransfer(transferId)
        }
    }
    
    private fun sendTransferDetails(transferId: String, toPeer: String) {
        val details = pendingTransferDetails[transferId]
        if (details != null) {
            // Send details in chunks if needed
            logd( "Sending transfer details for $transferId to $toPeer")
            // For now, just log - actual implementation would send details
        }
    }
    
    private fun handleJsonMessage(message: BTMeshMessage, fromDevice: String) {
        logd( "=== HANDLING JSON MESSAGE ===")
        logd( "Message type: ${message.type}")
        logd( "Transfer ID: ${message.transferId}")
        logd( "From device: $fromDevice")
        
        // Log current transfer state
        val transfer = activeTransfers[message.transferId]
        if (transfer != null) {
            logd( "Current transfer state: ${transfer.state}, role: ${transfer.role}")
        } else {
            logd( "No active transfer for this ID")
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
        applicationContext = null
    }
    
    /**
     * Debug function to simulate receiving a rejection message
     * This helps test if the rejection handling works correctly
     */
    fun debugSimulateRejection(transferId: String) {
        logd( "=== DEBUG: SIMULATING REJECTION ===")
        logd( "Transfer ID: $transferId")
        logd( "Active transfers before: ${activeTransfers.keys.joinToString(", ")}")
        
        // Simulate receiving a rejection message
        val rejectionMessage = "REJECT:$transferId"
        handleIncomingMessage(rejectionMessage.toByteArray(), "DEBUG_DEVICE")
    }
    
    fun getActiveTransferIds(): List<String> {
        return activeTransfers.keys.toList()
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