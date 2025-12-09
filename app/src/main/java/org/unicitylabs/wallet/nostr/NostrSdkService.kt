package org.unicitylabs.wallet.nostr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.nostr.client.NostrClient
import org.unicitylabs.nostr.client.NostrEventListener
import org.unicitylabs.nostr.crypto.NostrKeyManager
import org.unicitylabs.nostr.messaging.NIP17Protocol
import org.unicitylabs.nostr.messaging.PrivateMessage
import org.unicitylabs.nostr.nametag.NametagBinding
import org.unicitylabs.nostr.protocol.Event as SdkEvent
import org.unicitylabs.nostr.protocol.EventKinds
import org.unicitylabs.nostr.protocol.Filter
import org.unicitylabs.nostr.token.TokenTransferProtocol
import org.unicitylabs.nostr.payment.PaymentRequestProtocol
import org.unicitylabs.wallet.data.chat.ChatDatabase
import org.unicitylabs.wallet.data.chat.ChatConversation
import org.unicitylabs.wallet.data.chat.ChatMessage
import org.unicitylabs.wallet.data.chat.DismissedItem
import org.unicitylabs.wallet.data.chat.DismissedItemType
import org.unicitylabs.wallet.data.chat.MessageStatus
import org.unicitylabs.wallet.data.chat.MessageType
import java.util.UUID
import org.unicitylabs.wallet.data.repository.WalletRepository
import org.unicitylabs.wallet.p2p.IP2PService
import org.unicitylabs.wallet.token.UnicityTokenRegistry
import org.unicitylabs.wallet.util.HexUtils

/**
 * Nostr service implementation using unicity-nostr-sdk.
 * Replaces the legacy NostrP2PService with proper SDK usage.
 *
 * This service wraps NostrClient and provides wallet-specific features:
 * - Token transfers with automatic finalization
 * - Nametag binding and queries
 * - P2P messaging
 * - Agent discovery and location broadcasting
 */
class NostrSdkService(
    private val context: Context
) : IP2PService {

    companion object {
        private const val TAG = "NostrSdkService"

        // Default public relays
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.info",
            "wss://nostr-pub.wellorder.net"
        )

        // Unicity relays - multiple for redundancy (app deduplicates via message ID)
        val UNICITY_RELAYS = listOf(
            "wss://nostr-relay.testnet.unicity.network",
            "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        )

        @Volatile
        private var instance: NostrSdkService? = null

        @JvmStatic
        fun getInstance(context: Context?): NostrSdkService? {
            if (context == null && instance == null) {
                return null
            }
            return instance ?: synchronized(this) {
                instance ?: NostrSdkService(context!!).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyManager: NostrKeyManagerAdapter
    private val nostrClient: NostrClient
    private val chatDatabase: ChatDatabase
    private var isRunning = false

    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus

    // Payment request flow - UI can observe this to display incoming payment requests
    private val _paymentRequests = MutableStateFlow<List<IncomingPaymentRequest>>(emptyList())
    val paymentRequests: StateFlow<List<IncomingPaymentRequest>> = _paymentRequests

    /**
     * Data class representing an incoming payment request.
     */
    data class IncomingPaymentRequest(
        val id: String,
        val senderPubkey: String,
        val amount: java.math.BigInteger,
        val coinId: String,
        val symbol: String,
        val message: String?,
        val recipientNametag: String,
        val requestId: String,
        val timestamp: Long,
        val deadline: Long?, // Deadline timestamp in milliseconds, null means no deadline
        var status: PaymentRequestStatus = PaymentRequestStatus.PENDING
    ) {
        /**
         * Check if the payment request has expired.
         */
        fun isExpired(): Boolean = deadline != null && System.currentTimeMillis() > deadline

        /**
         * Get remaining time until deadline in milliseconds.
         * Returns null if no deadline, 0 if expired.
         */
        fun getRemainingTimeMs(): Long? {
            if (deadline == null) return null
            val remaining = deadline - System.currentTimeMillis()
            return if (remaining > 0) remaining else 0L
        }
    }

    enum class PaymentRequestStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        PAID,
        EXPIRED
    }

    /**
     * Data class representing an outgoing payment request (one we sent).
     */
    data class OutgoingPaymentRequest(
        val eventId: String,
        val targetPubkey: String,
        val amount: java.math.BigInteger,
        val coinId: String,
        val symbol: String,
        val message: String?,
        val recipientNametag: String,
        val requestId: String,
        val timestamp: Long,
        val deadline: Long?,
        var status: OutgoingPaymentRequestStatus = OutgoingPaymentRequestStatus.PENDING
    )

    enum class OutgoingPaymentRequestStatus {
        PENDING,    // Waiting for response
        PAID,       // Recipient paid (token transfer received with matching replyToEventId)
        DECLINED,   // Recipient declined
        EXPIRED     // Deadline passed without response
    }

    // Outgoing payment requests flow - UI can observe this to track sent requests
    private val _outgoingPaymentRequests = MutableStateFlow<List<OutgoingPaymentRequest>>(emptyList())
    val outgoingPaymentRequests: StateFlow<List<OutgoingPaymentRequest>> = _outgoingPaymentRequests

    init {
        keyManager = NostrKeyManagerAdapter(context)
        keyManager.initializeKeys()
        nostrClient = NostrClient(keyManager.getSdkKeyManager())
        chatDatabase = ChatDatabase.getDatabase(context)
    }

    override fun start() {
        if (isRunning) return

        isRunning = true
        scope.launch {
            ensureConnected()
        }
        startExpirationChecker()
    }

    override fun stop() {
        isRunning = false
    }

    override fun shutdown() {
        isRunning = false
        nostrClient.disconnect()
        scope.cancel()
    }

    override fun isRunning(): Boolean = isRunning

    /**
     * Ensures NostrClient is connected to relays.
     * Called once at start and automatically reconnects if needed.
     */
    private suspend fun ensureConnected() {
        if (nostrClient.isConnected) return

        try {
            nostrClient.connect(*UNICITY_RELAYS.toTypedArray()).await()
            subscribeToEvents()
            Log.d(TAG, "Connected to Nostr relays")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to relays", e)
        }
    }

    private fun subscribeToEvents() {
        val myPubkey = keyManager.getPublicKey()
        Log.d(TAG, "Subscribing to events for pubkey: $myPubkey")

        // Subscribe to token transfers, payment requests, payment request responses, and encrypted messages
        val personalFilter = Filter().apply {
            kinds = listOf(
                EventKinds.ENCRYPTED_DM,
                EventKinds.GIFT_WRAP,
                EventKinds.TOKEN_TRANSFER,
                EventKinds.PAYMENT_REQUEST,
                EventKinds.PAYMENT_REQUEST_RESPONSE
            )
            pTags = listOf(myPubkey)
        }

        nostrClient.subscribe(personalFilter, object : NostrEventListener {
            override fun onEvent(event: SdkEvent) {
                scope.launch {
                    handleIncomingEvent(event)
                }
            }

            override fun onEndOfStoredEvents(subscriptionId: String) {
                Log.d(TAG, "End of stored events for subscription: $subscriptionId")
            }
        })

        // Subscribe to agent locations and profiles (for P2P discovery)
        val agentFilter = Filter().apply {
            kinds = listOf(EventKinds.AGENT_LOCATION, EventKinds.AGENT_PROFILE)
        }

        nostrClient.subscribe(agentFilter, object : NostrEventListener {
            override fun onEvent(event: SdkEvent) {
                scope.launch {
                    handleAgentEvent(event)
                }
            }

            override fun onEndOfStoredEvents(subscriptionId: String) {
                // No action needed
            }
        })

        Log.d(TAG, "Subscriptions created successfully")
    }

    private suspend fun handleIncomingEvent(event: SdkEvent) {
        Log.d(TAG, "Received event kind=${event.kind} from=${event.pubkey.take(16)}...")

        when (event.kind) {
            EventKinds.TOKEN_TRANSFER -> handleTokenTransfer(event)
            EventKinds.PAYMENT_REQUEST -> handlePaymentRequest(event)
            EventKinds.PAYMENT_REQUEST_RESPONSE -> handlePaymentRequestResponse(event)
            EventKinds.ENCRYPTED_DM -> handleEncryptedMessage(event)
            EventKinds.GIFT_WRAP -> handleGiftWrappedMessage(event)
            else -> Log.d(TAG, "Unhandled event kind: ${event.kind}")
        }
    }

    private suspend fun handleAgentEvent(event: SdkEvent) {
        when (event.kind) {
            EventKinds.AGENT_LOCATION -> handleAgentLocation(event)
            EventKinds.AGENT_PROFILE -> handleAgentProfile(event)
        }
    }

    // ==================== Token Transfer Methods ====================

    /**
     * Send a token transfer using SDK TokenTransferProtocol
     */
    suspend fun sendTokenTransfer(recipientPubkey: String, transferPackage: String): Boolean {
        return sendTokenTransfer(recipientPubkey, transferPackage, null, null, null)
    }

    /**
     * Send a token transfer in response to a payment request.
     *
     * @param recipientPubkey Recipient's Nostr public key (hex)
     * @param transferPackage Token transfer package JSON
     * @param amount Optional amount for metadata
     * @param symbol Optional symbol for metadata
     * @param replyToEventId Optional event ID this transfer is responding to (e.g., payment request)
     */
    suspend fun sendTokenTransfer(
        recipientPubkey: String,
        transferPackage: String,
        amount: java.math.BigInteger?,
        symbol: String?,
        replyToEventId: String?
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending token transfer to $recipientPubkey using SDK")
                if (replyToEventId != null) {
                    Log.d(TAG, "  Reply to payment request: ${replyToEventId.take(16)}...")
                }

                // Use SDK's sendTokenTransfer method with optional replyToEventId
                nostrClient.sendTokenTransfer(recipientPubkey, transferPackage, amount, symbol, replyToEventId).await()

                Log.d(TAG, "Token transfer sent successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send token transfer", e)
                false
            }
        }
    }

    /**
     * Send a token transfer by nametag (with metadata)
     * This is used for demo crypto transfers
     */
    suspend fun sendTokenTransfer(
        recipientNametag: String,
        tokenJson: String,
        amount: java.math.BigInteger? = null,
        symbol: String? = null
    ): Result<String> {
        return try {
            // Resolve recipient's Nostr public key from their nametag
            val recipientPubkey = queryPubkeyByNametag(recipientNametag)
                ?: return Result.failure(Exception("Could not find Nostr public key for nametag: $recipientNametag"))

            Log.d(TAG, "Sending token transfer to $recipientNametag (pubkey: ${recipientPubkey.take(16)}...)")
            Log.d(TAG, "Token size: ${tokenJson.length} bytes, amount: $amount $symbol")

            // Send the token transfer using SDK
            val success = sendTokenTransfer(recipientPubkey, tokenJson)

            if (success) {
                Result.success("Token sent to $recipientNametag")
            } else {
                Result.failure(Exception("Failed to send token transfer"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token transfer", e)
            Result.failure(e)
        }
    }

    /**
     * Handle incoming token transfer event
     */
    private suspend fun handleTokenTransfer(event: SdkEvent) {
        try {
            Log.d(TAG, "Processing token transfer from ${event.pubkey}")

            // Decrypt and parse using SDK TokenTransferProtocol
            val tokenJson = TokenTransferProtocol.parseTokenTransfer(event, keyManager.getSdkKeyManager())

            Log.d(TAG, "Token transfer decrypted successfully (${tokenJson.length} chars)")

            // Check if it's the new format: token_transfer:{json with sourceToken and transferTx}
            if (tokenJson.startsWith("{") && tokenJson.contains("sourceToken") && tokenJson.contains("transferTx")) {
                Log.d(TAG, "Processing proper token transfer with finalization...")

                val payloadObj = com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    tokenJson,
                    Map::class.java
                ) as Map<*, *>

                handleProperTokenTransfer(payloadObj)
            } else {
                Log.w(TAG, "Unknown token transfer format")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process token transfer", e)
        }
    }

    /**
     * Handle incoming payment request event.
     * Decrypts the request, stores it, and emits to the UI.
     */
    private suspend fun handlePaymentRequest(event: SdkEvent) {
        try {
            Log.d(TAG, "Processing payment request from ${event.pubkey.take(16)}...")

            // Check if this payment request was already dismissed
            val dismissedDao = chatDatabase.dismissedItemDao()
            if (dismissedDao.isDismissed(event.id, DismissedItemType.PAYMENT_REQUEST)) {
                Log.d(TAG, "Payment request was dismissed, skipping: ${event.id.take(16)}...")
                return
            }

            // Decrypt and parse using SDK PaymentRequestProtocol
            val request = PaymentRequestProtocol.parsePaymentRequest(event, keyManager.getSdkKeyManager())

            // Check if already expired before displaying
            if (request.isExpired) {
                Log.d(TAG, "Payment request already expired, skipping: ${event.id.take(16)}...")
                return
            }

            // Look up symbol from coin registry based on coinId
            val registry = UnicityTokenRegistry.getInstance(context)
            val coinDef = registry.getCoinDefinition(request.coinId)
            val symbol = coinDef?.symbol ?: "UNKNOWN"

            Log.d(TAG, "Payment request parsed:")
            Log.d(TAG, "  Amount: ${request.amount} $symbol")
            Log.d(TAG, "  Coin ID: ${request.coinId}")
            Log.d(TAG, "  Message: ${request.message}")
            Log.d(TAG, "  Recipient nametag: ${request.recipientNametag}")
            Log.d(TAG, "  Request ID: ${request.requestId}")
            Log.d(TAG, "  Deadline: ${request.deadline}")

            // Create incoming payment request model
            val incomingRequest = IncomingPaymentRequest(
                id = event.id,
                senderPubkey = event.pubkey,
                amount = request.amount,
                coinId = request.coinId,
                symbol = symbol,
                message = request.message,
                recipientNametag = request.recipientNametag,
                requestId = request.requestId,
                timestamp = event.createdAt * 1000,
                deadline = request.deadline
            )

            // Add to list and emit to UI
            val currentList = _paymentRequests.value.toMutableList()

            // Check if we already have this request (by event ID)
            if (currentList.none { it.id == incomingRequest.id }) {
                currentList.add(0, incomingRequest)  // Add to front (newest first)
                _paymentRequests.value = currentList

                Log.i(TAG, "📬 Payment request received: ${request.amount} $symbol from ${event.pubkey.take(16)}...")
                if (request.deadline != null) {
                    val remainingMs = request.remainingTimeMs ?: 0
                    Log.i(TAG, "   Deadline in ${remainingMs / 1000} seconds")
                }
                showPaymentRequestNotification(incomingRequest)
            } else {
                Log.d(TAG, "Payment request already exists, skipping: ${incomingRequest.id}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process payment request", e)
        }
    }

    private fun showPaymentRequestNotification(request: IncomingPaymentRequest) {
        // TODO: Implement notification
        Log.d(TAG, "Payment request notification: ${request.amount} ${request.symbol}")
    }

    /**
     * Handle incoming payment request response event (decline/expiration notification).
     * This is received by the original requester when the recipient declines or the request expires.
     */
    private suspend fun handlePaymentRequestResponse(event: SdkEvent) {
        try {
            Log.d(TAG, "Processing payment request response from ${event.pubkey.take(16)}...")

            // Decrypt and parse using SDK PaymentRequestProtocol
            val response = PaymentRequestProtocol.parsePaymentRequestResponse(event, keyManager.getSdkKeyManager())

            Log.d(TAG, "Payment request response parsed:")
            Log.d(TAG, "  Original Event ID: ${response.originalEventId}")
            Log.d(TAG, "  Request ID: ${response.requestId}")
            Log.d(TAG, "  Status: ${response.status}")
            Log.d(TAG, "  Reason: ${response.reason}")

            // Update the outgoing payment request status
            val currentList = _outgoingPaymentRequests.value.toMutableList()
            val index = currentList.indexOfFirst {
                it.eventId == response.originalEventId || it.requestId == response.requestId
            }

            if (index >= 0) {
                val newStatus = when (response.status) {
                    PaymentRequestProtocol.ResponseStatus.DECLINED -> OutgoingPaymentRequestStatus.DECLINED
                    PaymentRequestProtocol.ResponseStatus.EXPIRED -> OutgoingPaymentRequestStatus.EXPIRED
                    else -> OutgoingPaymentRequestStatus.DECLINED  // Default fallback
                }
                currentList[index] = currentList[index].copy(status = newStatus)
                _outgoingPaymentRequests.value = currentList

                val statusStr = if (response.status == PaymentRequestProtocol.ResponseStatus.DECLINED) "declined" else "expired"
                Log.i(TAG, "📭 Payment request $statusStr: ${currentList[index].amount} ${currentList[index].symbol}")
                if (response.reason != null) {
                    Log.i(TAG, "   Reason: ${response.reason}")
                }
            } else {
                Log.w(TAG, "Received response for unknown payment request: ${response.requestId}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process payment request response", e)
        }
    }

    /**
     * Accept a payment request and initiate token transfer.
     * Returns success or failure.
     *
     * Will fail if the payment request has expired.
     */
    suspend fun acceptPaymentRequest(request: IncomingPaymentRequest): Result<String> {
        return try {
            Log.d(TAG, "Accepting payment request: ${request.requestId}")

            // Check if the request has expired
            if (request.isExpired()) {
                Log.w(TAG, "Cannot accept expired payment request: ${request.requestId}")
                updatePaymentRequestStatus(request.id, PaymentRequestStatus.EXPIRED)
                return Result.failure(IllegalStateException("Payment request has expired"))
            }

            // Update status to ACCEPTED
            updatePaymentRequestStatus(request.id, PaymentRequestStatus.ACCEPTED)

            // TODO: Initiate actual token transfer
            // For now, just mark as accepted - the actual payment flow will be implemented separately

            Result.success("Payment request accepted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept payment request", e)
            Result.failure(e)
        }
    }

    /**
     * Reject a payment request and send decline notification to the requester.
     *
     * @param request The incoming payment request to reject
     * @param reason Optional reason for declining (visible to the requester)
     */
    fun rejectPaymentRequest(request: IncomingPaymentRequest, reason: String? = null) {
        Log.d(TAG, "Rejecting payment request: ${request.requestId}")
        updatePaymentRequestStatus(request.id, PaymentRequestStatus.REJECTED)

        // Send decline notification to the original requester via Nostr
        scope.launch {
            try {
                nostrClient.sendPaymentRequestDecline(
                    request.senderPubkey,
                    request.id,
                    request.requestId,
                    reason
                ).await()
                Log.d(TAG, "Decline notification sent to ${request.senderPubkey.take(16)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send decline notification", e)
            }

            // Persist dismissal so it doesn't reappear on app restart
            chatDatabase.dismissedItemDao().insertDismissedItem(
                DismissedItem(request.id, DismissedItemType.PAYMENT_REQUEST)
            )
        }
    }

    /**
     * Clear a payment request from the list.
     */
    fun clearPaymentRequest(requestId: String) {
        val currentList = _paymentRequests.value.toMutableList()
        currentList.removeAll { it.id == requestId }
        _paymentRequests.value = currentList
        // Persist dismissal so it doesn't reappear on app restart
        scope.launch {
            chatDatabase.dismissedItemDao().insertDismissedItem(
                DismissedItem(requestId, DismissedItemType.PAYMENT_REQUEST)
            )
        }
    }

    /**
     * Reject all pending payment requests at once.
     */
    fun rejectAllPendingPaymentRequests() {
        Log.d(TAG, "Rejecting all pending payment requests")
        val pendingRequests = _paymentRequests.value.filter { it.status == PaymentRequestStatus.PENDING }
        pendingRequests.forEach { request ->
            rejectPaymentRequest(request)
        }
    }

    /**
     * Check and update expired payment requests.
     * Called periodically to handle deadline expiration.
     */
    fun checkExpiredPaymentRequests() {
        val currentList = _paymentRequests.value.toMutableList()
        var hasChanges = false

        for (i in currentList.indices) {
            val request = currentList[i]
            if (request.status == PaymentRequestStatus.PENDING && request.isExpired()) {
                Log.d(TAG, "Payment request expired: ${request.requestId}")
                currentList[i] = request.copy(status = PaymentRequestStatus.EXPIRED)
                hasChanges = true

                // Persist dismissal so it doesn't reappear on app restart
                scope.launch {
                    chatDatabase.dismissedItemDao().insertDismissedItem(
                        DismissedItem(request.id, DismissedItemType.PAYMENT_REQUEST)
                    )
                }
            }
        }

        if (hasChanges) {
            _paymentRequests.value = currentList
        }
    }

    /**
     * Start periodic expiration checking for payment requests.
     * Should be called when the service starts.
     */
    private fun startExpirationChecker() {
        scope.launch {
            while (isRunning) {
                checkExpiredPaymentRequests()
                kotlinx.coroutines.delay(5000) // Check every 5 seconds
            }
        }
    }

    /**
     * Clear all processed (non-PENDING) payment requests from the list.
     */
    fun clearProcessedPaymentRequests() {
        val currentList = _paymentRequests.value.toMutableList()
        currentList.removeAll { it.status != PaymentRequestStatus.PENDING }
        _paymentRequests.value = currentList
        Log.d(TAG, "Cleared processed payment requests, ${currentList.size} pending remain")
    }

    private fun updatePaymentRequestStatus(eventId: String, status: PaymentRequestStatus) {
        val currentList = _paymentRequests.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == eventId }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(status = status)
            _paymentRequests.value = currentList
        }
    }

    /**
     * Handle proper token transfer (sourceToken + transferTx format)
     * This is the format used by wallet-to-wallet and faucet transfers
     */
    private suspend fun handleProperTokenTransfer(payloadObj: Map<*, *>) {
        try {
            val sourceTokenJson = payloadObj["sourceToken"] as? String
            val transferTxJson = payloadObj["transferTx"] as? String

            if (sourceTokenJson == null || transferTxJson == null) {
                Log.e(TAG, "Missing sourceToken or transferTx in payload")
                return
            }

            Log.d(TAG, "Payload keys: ${payloadObj.keys}")
            Log.d(TAG, "sourceTokenJson length: ${sourceTokenJson.length}")
            Log.d(TAG, "transferTxJson length: ${transferTxJson.length}")

            // Parse using Unicity SDK
            Log.d(TAG, "Parsing source token...")
            val sourceToken = org.unicitylabs.sdk.token.Token.fromJson(sourceTokenJson)

            Log.d(TAG, "Parsing transfer transaction...")
            val transferTx = org.unicitylabs.sdk.transaction.TransferTransaction.fromJson(transferTxJson)

            Log.d(TAG, "✅ Parsed successfully!")
            Log.d(TAG, "Source token type: ${sourceToken.type}")
            Log.d(TAG, "Transfer recipient: ${transferTx.getData().recipient}")

            // Finalize the transfer
            finalizeTransfer(sourceToken, transferTx)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling proper token transfer", e)
        }
    }

    /**
     * Finalize a token transfer for the recipient
     */
    private suspend fun finalizeTransfer(
        sourceToken: org.unicitylabs.sdk.token.Token<*>,
        transferTx: org.unicitylabs.sdk.transaction.TransferTransaction
    ) {
        try {
            Log.d(TAG, "Starting finalization...")

            val recipientAddress = transferTx.getData().recipient
            Log.d(TAG, "Recipient address: ${recipientAddress.address}")

            val addressScheme = recipientAddress.scheme
            Log.d(TAG, "Address scheme: $addressScheme")

            // Check if transfer is to a PROXY address (nametag-based)
            if (addressScheme == org.unicitylabs.sdk.address.AddressScheme.PROXY) {
                Log.d(TAG, "Transfer is to PROXY address - finalization required")

                // Load ALL user's nametag tokens and find which one matches
                val nametagService = org.unicitylabs.wallet.nametag.NametagService(context)
                val allNametags = nametagService.getAllNametagTokens()

                if (allNametags.isEmpty()) {
                    Log.e(TAG, "No nametags configured for this wallet")
                    return
                }

                // Find which nametag this transfer is for by checking all proxy addresses
                var matchedNametag: String? = null
                var myNametagToken: org.unicitylabs.sdk.token.Token<*>? = null

                for ((nametagString, nametagToken) in allNametags) {
                    val proxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametagToken.id)
                    if (proxyAddress.address == recipientAddress.address) {
                        matchedNametag = nametagString
                        myNametagToken = nametagToken
                        break
                    }
                }

                if (myNametagToken == null || matchedNametag == null) {
                    Log.e(TAG, "Transfer is not for any of my nametags!")
                    Log.e(TAG, "Got: ${recipientAddress.address}")
                    Log.e(TAG, "My nametags: ${allNametags.keys.joinToString(", ")}")
                    return
                }

                Log.d(TAG, "✅ Transfer is for my nametag: $matchedNametag")

                // Get wallet identity (BIP-39)
                val walletRepository = WalletRepository.getInstance(context)
                val identityManager = walletRepository.getIdentityManager()
                val identity = identityManager.getCurrentIdentity()

                if (identity == null) {
                    Log.e(TAG, "No wallet identity found, cannot finalize transfer")
                    return
                }

                // Create signing service from wallet identity (using createFromSecret, not createFromMaskedSecret)
                val secret = HexUtils.decodeHex(identity.privateKey)
                val signingService = org.unicitylabs.sdk.signing.SigningService.createFromSecret(secret)

                // Create recipient predicate
                val transferSalt = transferTx.getData().salt

                Log.d(TAG, "Creating recipient predicate:")
                Log.d(TAG, "  Identity pubkey: ${identity.publicKey}")
                Log.d(TAG, "  Source TokenId: ${HexUtils.encodeHexString(sourceToken.id.bytes).take(16)}...")
                Log.d(TAG, "  TokenType: ${sourceToken.type}")
                Log.d(TAG, "  Transfer Salt: ${HexUtils.encodeHexString(transferSalt).take(16)}...")

                // Create UnmaskedPredicate using the same parameters as sender
                val recipientPredicate = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate.create(
                    sourceToken.id,
                    sourceToken.type,
                    signingService,
                    org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                    transferSalt
                )

                Log.d(TAG, "✅ Predicate created - PublicKey: ${HexUtils.encodeHexString(recipientPredicate.publicKey).take(32)}...")

                val recipientState = org.unicitylabs.sdk.token.TokenState(recipientPredicate, null)

                Log.d(TAG, "Finalizing transfer with nametag token...")
                Log.d(TAG, "  Transfer Recipient: ${transferTx.getData().recipient.address}")
                Log.d(TAG, "  My Nametag ProxyAddress: ${org.unicitylabs.sdk.address.ProxyAddress.create(myNametagToken.id).address}")

                // Get StateTransitionClient and trustBase
                val client = org.unicitylabs.wallet.di.ServiceProvider.stateTransitionClient
                val trustBase = org.unicitylabs.wallet.di.ServiceProvider.getRootTrustBase()

                // Finalize the transaction with nametag for proxy resolution
                val finalizedToken = try {
                    withContext(Dispatchers.IO) {
                        client.finalizeTransaction(
                            trustBase,
                            sourceToken,
                            recipientState,
                            transferTx,
                            listOf(myNametagToken)
                        )
                    }
                } catch (ve: org.unicitylabs.sdk.verification.VerificationException) {
                    Log.e(TAG, "❌ VERIFICATION FAILED")
                    Log.e(TAG, "Verification Result: ${ve.verificationResult}")
                    throw ve
                }

                Log.i(TAG, "✅ Token finalized successfully!")

                // Save the finalized token
                saveReceivedToken(finalizedToken)

            } else {
                // DIRECT address transfer - no finalization needed
                Log.d(TAG, "Transfer is to DIRECT address - saving without finalization")
                saveReceivedToken(sourceToken)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize transfer", e)
        }
    }

    /**
     * Save a received token to the wallet
     */
    private suspend fun saveReceivedToken(token: org.unicitylabs.sdk.token.Token<*>) {
        try {
            // Convert SDK token to JSON for storage
            val tokenJson = token.toJson()

            Log.d(TAG, "Token has coinData: ${token.getCoins().isPresent}")

            // Extract coin metadata if available
            val coinId: String?
            val amount: String?
            val symbol: String?
            val iconUrl: String?

            val coinsOpt = token.getCoins()
            if (coinsOpt.isPresent) {
                val coinData = coinsOpt.get()
                Log.d(TAG, "Coins map from SDK: ${coinData.coins}")

                val firstCoin = coinData.coins.entries.firstOrNull()
                if (firstCoin != null) {
                    coinId = firstCoin.key.bytes.joinToString("") { "%02x".format(it) }
                    amount = firstCoin.value.toString()

                    Log.d(TAG, "Extracted from SDK: coinId=$coinId, amount=$amount")

                    // Look up symbol from registry
                    val registry = UnicityTokenRegistry.getInstance(context)
                    val coinDef = registry.getCoinDefinition(coinId)
                    symbol = coinDef?.symbol ?: "UNKNOWN"
                    iconUrl = coinDef?.getIconUrl()

                    Log.d(TAG, "Found coin definition: ${coinDef?.name} ($symbol)")
                    Log.d(TAG, "Final metadata: symbol=$symbol, amount=$amount, coinId=$coinId")

                    // Show notification
                    showTokenReceivedNotification(amount, symbol)
                } else {
                    Log.w(TAG, "Token has coinData but no entries")
                    coinId = null
                    amount = null
                    symbol = null
                    iconUrl = null
                }
            } else {
                Log.w(TAG, "Token has no coinData (NFT or other type)")
                coinId = null
                amount = null
                symbol = null
                iconUrl = null
            }

            // Create Token model for wallet storage
            val tokenIdHex = token.id.bytes.joinToString("") { "%02x".format(it) }
            val walletToken = org.unicitylabs.wallet.data.model.Token(
                id = java.util.UUID.randomUUID().toString(),
                name = symbol ?: "Unicity Token",
                type = token.type.toString(),
                jsonData = tokenJson,
                sizeBytes = tokenJson.length,
                status = org.unicitylabs.wallet.data.model.TokenStatus.CONFIRMED,
                amount = amount,
                coinId = coinId,
                symbol = symbol,
                iconUrl = iconUrl
            )

            // Add to wallet repository
            val walletRepository = WalletRepository.getInstance(context)
            walletRepository.addToken(walletToken)

            Log.i(TAG, "✅ Finalized token saved: $amount $symbol")
            Log.i(TAG, "📬 New token received: $amount $symbol")
            Log.i(TAG, "✅ Token transfer completed and finalized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save received token", e)
        }
    }

    private fun showTokenReceivedNotification(amount: String?, symbol: String) {
        // TODO: Implement notification if needed
        Log.d(TAG, "Token received: $amount $symbol")
    }

    // ==================== Nametag Methods ====================

    /**
     * Publish a nametag binding using SDK
     */
    suspend fun publishNametagBinding(nametagId: String, unicityAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                nostrClient.publishNametagBinding(nametagId, unicityAddress).await()
                Log.d(TAG, "Nametag binding published: $nametagId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish nametag binding", e)
                false
            }
        }
    }

    /**
     * Query Nostr pubkey by nametag using SDK
     */
    suspend fun queryPubkeyByNametag(nametagId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                nostrClient.queryPubkeyByNametag(nametagId).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query nametag", e)
                null
            }
        }
    }

    // ==================== NIP-17 Private Messaging Methods ====================

    override fun sendMessage(toTag: String, content: String) {
        scope.launch {
            try {
                // Check if toTag is already a hex pubkey (64 chars) or a nametag
                val isHexPubkey = toTag.length == 64 && toTag.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

                // Get sender's nametag for identification
                val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                val senderNametag = sharedPrefs.getString("unicity_tag", null)

                val eventId: String
                val conversationId: String
                val recipientPubkey: String

                if (isHexPubkey) {
                    // Already a pubkey - use sendPrivateMessage directly
                    recipientPubkey = toTag
                    conversationId = toTag
                    Log.d(TAG, "Sending to pubkey directly: ${toTag.take(16)}...")
                    eventId = nostrClient.sendPrivateMessage(toTag, content, null, senderNametag).await()
                } else {
                    // It's a nametag - use sendPrivateMessageToNametag (auto-resolves)
                    conversationId = toTag
                    Log.d(TAG, "Sending to nametag: $toTag")
                    eventId = nostrClient.sendPrivateMessageToNametag(toTag, content, senderNametag).await()
                    // Get the resolved pubkey for database storage
                    recipientPubkey = queryPubkeyByNametag(toTag) ?: toTag
                }

                Log.d(TAG, "Private message sent to $conversationId from $senderNametag (event: ${eventId.take(16)}...)")

                // Save message to local database
                saveOutgoingMessage(conversationId, recipientPubkey, content, eventId)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    /**
     * Send a private message to a specific pubkey using NIP-17.
     * Returns the gift wrap event ID.
     */
    suspend fun sendPrivateMessage(recipientPubkey: String, message: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                val eventId = nostrClient.sendPrivateMessage(recipientPubkey, message).await()
                Log.d(TAG, "Private message sent (event: ${eventId.take(16)}...)")
                eventId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message", e)
                null
            }
        }
    }

    /**
     * Send a read receipt for a message using NIP-17.
     */
    suspend fun sendReadReceipt(senderPubkey: String, messageEventId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                nostrClient.sendReadReceipt(senderPubkey, messageEventId).await()
                Log.d(TAG, "Read receipt sent for message: ${messageEventId.take(16)}...")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt", e)
                false
            }
        }
    }

    /**
     * Save an outgoing message to the local database.
     */
    private suspend fun saveOutgoingMessage(
        recipientTag: String,
        recipientPubkey: String,
        content: String,
        eventId: String
    ) {
        withContext(Dispatchers.IO) {
            val messageDao = chatDatabase.messageDao()
            val conversationDao = chatDatabase.conversationDao()

            // Ensure conversation exists
            val existingConv = conversationDao.getConversation(recipientTag)
            if (existingConv == null) {
                val newConv = ChatConversation(
                    conversationId = recipientTag,
                    agentTag = recipientTag,
                    agentPublicKey = recipientPubkey,
                    lastMessageTime = System.currentTimeMillis(),
                    lastMessageText = content,
                    isApproved = true  // No handshake needed with NIP-17
                )
                conversationDao.insertConversation(newConv)
            } else {
                conversationDao.updateLastMessage(recipientTag, System.currentTimeMillis(), content)
            }

            // Save message
            val chatMessage = ChatMessage(
                messageId = eventId,
                conversationId = recipientTag,
                content = content,
                timestamp = System.currentTimeMillis(),
                isFromMe = true,
                status = MessageStatus.SENT,
                type = MessageType.TEXT
            )
            messageDao.insertMessage(chatMessage)
        }
    }

    override fun initiateHandshake(agentTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "initiateHandshake not yet implemented in SDK service")
    }

    override fun acceptHandshake(fromTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "acceptHandshake not yet implemented in SDK service")
    }

    override fun rejectHandshake(fromTag: String) {
        // TODO: Implement using SDK publishEvent
        Log.w(TAG, "rejectHandshake not yet implemented in SDK service")
    }

    override fun broadcastLocation(latitude: Double, longitude: Double) {
        // TODO: Implement using SDK publishEvent with EventKinds.AGENT_LOCATION
        Log.w(TAG, "broadcastLocation not yet implemented in SDK service")
    }

    override fun updateAvailability(isAvailable: Boolean) {
        // TODO: Implement using SDK publishEvent with EventKinds.AGENT_PROFILE
        Log.w(TAG, "updateAvailability not yet implemented in SDK service")
    }

    // ==================== Event Handlers (Stubs for now) ====================

    private fun handleEncryptedMessage(event: SdkEvent) {
        try {
            val senderPubkeyBytes = HexUtils.decodeHex(event.pubkey)
            val decryptedContent = keyManager.getSdkKeyManager().decrypt(event.content, senderPubkeyBytes)
            Log.d(TAG, "Received encrypted message: $decryptedContent")
            // TODO: Handle P2P messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message", e)
        }
    }

    /**
     * Handle incoming NIP-17 gift-wrapped message.
     * Unwraps the message and saves it to the local database.
     */
    private suspend fun handleGiftWrappedMessage(event: SdkEvent) {
        try {
            Log.d(TAG, "Processing gift-wrapped message from ${event.pubkey.take(16)}...")

            // Unwrap the gift wrap using NIP-17 protocol
            val privateMessage = nostrClient.unwrapPrivateMessage(event)

            Log.d(TAG, "Message unwrapped successfully:")
            Log.d(TAG, "  Sender: ${privateMessage.senderPubkey.take(16)}...")
            Log.d(TAG, "  Kind: ${privateMessage.kind}")
            Log.d(TAG, "  Content length: ${privateMessage.content.length}")

            when {
                privateMessage.isChatMessage -> {
                    // Regular chat message (kind 14)
                    handleIncomingChatMessage(privateMessage)
                }
                privateMessage.isReadReceipt -> {
                    // Read receipt (kind 15)
                    handleIncomingReadReceipt(privateMessage)
                }
                else -> {
                    Log.w(TAG, "Unknown private message kind: ${privateMessage.kind}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process gift-wrapped message", e)
        }
    }

    /**
     * Handle incoming chat message (NIP-17 kind 14).
     */
    private suspend fun handleIncomingChatMessage(message: PrivateMessage) {
        withContext(Dispatchers.IO) {
            val messageDao = chatDatabase.messageDao()
            val conversationDao = chatDatabase.conversationDao()
            val dismissedDao = chatDatabase.dismissedItemDao()

            // Check if we already processed this message (by event ID)
            val existing = messageDao.getMessage(message.eventId)
            if (existing != null) {
                Log.d(TAG, "Message already exists, skipping: ${message.eventId.take(16)}...")
                return@withContext
            }

            // Use sender's nametag if available, otherwise fall back to pubkey
            val senderNametag = message.senderNametag
            val conversationId = if (!senderNametag.isNullOrEmpty()) {
                Log.d(TAG, "Using sender nametag as conversation ID: $senderNametag")
                senderNametag
            } else {
                Log.d(TAG, "No sender nametag, using pubkey as conversation ID: ${message.senderPubkey.take(16)}...")
                message.senderPubkey
            }

            // Check if conversation was deleted (dismissed)
            if (dismissedDao.isDismissed(conversationId, DismissedItemType.CONVERSATION)) {
                Log.d(TAG, "Conversation was deleted, skipping message: $conversationId")
                return@withContext
            }

            val displayName = senderNametag ?: message.senderPubkey.take(16) + "..."

            // Ensure conversation exists
            val existingConv = conversationDao.getConversation(conversationId)
            if (existingConv == null) {
                val newConv = ChatConversation(
                    conversationId = conversationId,
                    agentTag = conversationId,  // Could be resolved to nametag later
                    agentPublicKey = message.senderPubkey,
                    lastMessageTime = message.timestamp * 1000,
                    lastMessageText = message.content,
                    unreadCount = 1,
                    isApproved = true  // No handshake needed with NIP-17
                )
                conversationDao.insertConversation(newConv)
            } else {
                conversationDao.updateLastMessage(
                    conversationId,
                    message.timestamp * 1000,
                    message.content
                )
                conversationDao.incrementUnreadCount(conversationId)
            }

            // Save message
            val chatMessage = ChatMessage(
                messageId = message.eventId,
                conversationId = conversationId,
                content = message.content,
                timestamp = message.timestamp * 1000,
                isFromMe = false,
                status = MessageStatus.DELIVERED,
                type = MessageType.TEXT
            )
            messageDao.insertMessage(chatMessage)

            Log.i(TAG, "📬 New message received from $displayName")
        }
    }

    /**
     * Handle incoming read receipt (NIP-17 kind 15).
     */
    private suspend fun handleIncomingReadReceipt(message: PrivateMessage) {
        val originalEventId = message.replyToEventId
        if (originalEventId == null) {
            Log.w(TAG, "Read receipt missing original event ID")
            return
        }

        withContext(Dispatchers.IO) {
            val messageDao = chatDatabase.messageDao()
            val existing = messageDao.getMessage(originalEventId)
            if (existing != null && existing.isFromMe) {
                messageDao.updateMessageStatus(originalEventId, MessageStatus.READ)
                Log.d(TAG, "Message marked as read: ${originalEventId.take(16)}...")
            }
        }
    }

    private fun handleAgentLocation(event: SdkEvent) {
        Log.d(TAG, "Agent location received (not yet implemented)")
        // TODO: Implement agent location handling
    }

    private fun handleAgentProfile(event: SdkEvent) {
        Log.d(TAG, "Agent profile received (not yet implemented)")
        // TODO: Implement agent profile handling
    }

    // ==================== Helper Methods ====================

    fun getPublicKeyHex(): String {
        return keyManager.getPublicKey()
    }
}
