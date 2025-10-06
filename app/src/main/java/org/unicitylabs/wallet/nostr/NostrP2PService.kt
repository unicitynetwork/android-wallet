package org.unicitylabs.wallet.nostr

// Nostr protocol implementation without external library dependency
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.spongycastle.util.encoders.Hex
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.wallet.data.repository.WalletRepository
import org.unicitylabs.wallet.p2p.IP2PService
import org.unicitylabs.wallet.token.UnicityTokenRegistry
import org.unicitylabs.wallet.util.JsonMapper
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

// Event data class for Nostr protocol
data class Event(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * Nostr-based P2P service implementation for Unicity Wallet
 * Implements decentralized messaging using the Nostr protocol
 */
class NostrP2PService(
    private val context: Context
) : IP2PService {

    companion object {
        private const val TAG = "NostrP2PService"

        // Event Kinds (NIPs + Custom)
        const val KIND_PROFILE = 0
        const val KIND_TEXT_NOTE = 1
        const val KIND_ENCRYPTED_DM = 4
        const val KIND_GIFT_WRAP = 1059
        const val KIND_RELAY_LIST = 10002
        const val KIND_APP_DATA = 30078

        // Custom Unicity event kinds
        const val KIND_AGENT_PROFILE = 31111
        const val KIND_AGENT_LOCATION = 31112
        const val KIND_TOKEN_TRANSFER = 31113
        const val KIND_FILE_METADATA = 31114

        // Default public relays
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.info",
            "wss://nostr-pub.wellorder.net"
        )

        // Unicity private relay on AWS
        val UNICITY_RELAYS = listOf(
            "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        )

        private var instance: NostrP2PService? = null

        @JvmStatic
        fun getInstance(context: Context?): NostrP2PService? {
            // If context is null and no instance exists, return null instead of throwing
            if (context == null && instance == null) {
                return null
            }

            return instance ?: synchronized(this) {
                instance ?: NostrP2PService(context!!).also { instance = it }
            }
        }
    }

    // Core components
    private val keyManager = NostrKeyManager(context)
    // Using shared JsonMapper
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket connections
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // Send ping every 25 seconds to keep connection alive
        .build()

    // Relay connections
    private val relayConnections = mutableMapOf<String, WebSocket>()
    private val eventListeners = mutableListOf<(Event) -> Unit>()

    // State management
    private var isRunning = false
    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus

    // Message queue for offline delivery
    private val messageQueue = mutableListOf<QueuedMessage>()

    data class QueuedMessage(
        val event: Event,
        val relays: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun start() {
        if (isRunning) return

        isRunning = true
        Log.d(TAG, "Starting Nostr P2P Service")

        scope.launch {
            // Initialize keys
            keyManager.initializeKeys()

            // Connect to relays
            connectToRelays()

            // Publish profile
            publishProfile()

            // Start agent discovery if configured
            if (isAgentMode()) {
                startAgentDiscovery()
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping Nostr P2P Service")
        isRunning = false

        // Close all relay connections
        relayConnections.values.forEach { ws ->
            ws.close(1000, "Service stopped")
        }
        relayConnections.clear()
    }

    override fun shutdown() {
        stop()
        scope.cancel()
    }

    override fun isRunning(): Boolean = isRunning

    /**
     * Connect to Nostr relays
     */
    private suspend fun connectToRelays() {
        val relays = getAllRelays()

        relays.forEach { relayUrl ->
            connectToRelay(relayUrl)
        }
    }

    /**
     * Connect to a single relay
     */
    private fun connectToRelay(url: String) {
        // Check if already connected
        if (relayConnections.containsKey(url)) {
            Log.d(TAG, "Already connected to relay: $url")
            return
        }

        Log.d(TAG, "Connecting to relay: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay: $url")
                relayConnections[url] = webSocket
                updateConnectionStatus(url, true)

                // Send queued messages
                flushMessageQueue(url)

                // Subscribe to relevant events
                subscribeToEvents(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleRelayMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling relay message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closing: $url - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closed: $url")
                relayConnections.remove(url)
                updateConnectionStatus(url, false)

                // Reconnect if service is still running
                if (isRunning) {
                    scope.launch {
                        delay(5000)
                        connectToRelay(url)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay connection failed: $url", t)
                relayConnections.remove(url)
                updateConnectionStatus(url, false)

                // Reconnect if service is still running
                if (isRunning) {
                    scope.launch {
                        delay(10000)
                        connectToRelay(url)
                    }
                }
            }
        })
    }

    /**
     * Handle incoming relay messages
     */
    private fun handleRelayMessage(message: String) {
        try {
            val json = JsonMapper.fromJson(message, List::class.java)

            when (json[0]) {
                "EVENT" -> {
                    // Handle incoming event
                    val subscriptionId = json[1] as String
                    val eventData = json[2] as Map<*, *>
                    handleIncomingEvent(eventData)
                }
                "OK" -> {
                    // Event accepted
                    val eventId = json[1] as String
                    val success = json[2] as Boolean
                    val message = if (json.size > 3) json[3] as String else ""
                    Log.d(TAG, "Event $eventId: success=$success, message=$message")
                }
                "EOSE" -> {
                    // End of stored events
                    val subscriptionId = json[1] as String
                    Log.d(TAG, "End of stored events for subscription: $subscriptionId")
                }
                "NOTICE" -> {
                    // Server notice
                    val notice = json[1] as String
                    Log.i(TAG, "Relay notice: $notice")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing relay message", e)
        }
    }

    /**
     * Handle incoming event
     */
    private fun handleIncomingEvent(eventData: Map<*, *>) {
        val event = parseEvent(eventData)

        // Notify listeners
        eventListeners.forEach { listener ->
            try {
                listener(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }

        // Handle based on event kind
        when (event.kind) {
            KIND_ENCRYPTED_DM -> handleEncryptedMessage(event)
            KIND_GIFT_WRAP -> handleGiftWrappedMessage(event)
            KIND_AGENT_LOCATION -> handleAgentLocation(event)
            KIND_TOKEN_TRANSFER -> handleTokenTransfer(event)
            KIND_FILE_METADATA -> handleFileMetadata(event)
        }
    }

    /**
     * Parse event from JSON data
     */
    private fun parseEvent(data: Map<*, *>): Event {
        return Event(
            id = data["id"] as String,
            pubkey = data["pubkey"] as String,
            created_at = when (val ts = data["created_at"]) {
                is Number -> ts.toLong()
                else -> 0L
            },
            kind = when (val k = data["kind"]) {
                is Number -> k.toInt()
                else -> 0
            },
            tags = (data["tags"] as List<*>).map { it as List<String> },
            content = data["content"] as String,
            sig = data["sig"] as String
        )
    }

    /**
     * Subscribe to relevant events
     */
    private fun subscribeToEvents(webSocket: WebSocket) {
        val publicKey = keyManager.getPublicKey()

        // Subscribe to messages for us
        val filters = listOf(
            mapOf(
                "kinds" to listOf(KIND_ENCRYPTED_DM, KIND_GIFT_WRAP, KIND_TOKEN_TRANSFER),
                "#p" to listOf(publicKey),
                "since" to (System.currentTimeMillis() / 1000) // Only new events from now
            ),
            mapOf(
                "kinds" to listOf(KIND_AGENT_LOCATION, KIND_AGENT_PROFILE),
                "#t" to listOf("unicity-agent"),
                "since" to (System.currentTimeMillis() / 1000 - 3600) // Last hour
            )
        )

        val subscriptionId = UUID.randomUUID().toString().substring(0, 8)
        val request = mutableListOf<Any>("REQ", subscriptionId).apply {
            addAll(filters)
        }

        webSocket.send(JsonMapper.toJson(request))
    }

    /**
     * Publish user profile
     */
    private suspend fun publishProfile() {
        val profile = createProfileEvent()
        publishEvent(profile)
    }

    /**
     * Create profile event
     */
    private fun createProfileEvent(): Event {
        val content = mapOf(
            "name" to getUsername(),
            "about" to "Unicity Wallet user",
            "picture" to "",
            "nip05" to getNip05Identifier(),
            "unicity" to mapOf(
                "wallet_address" to getWalletAddress(),
                "agent_tag" to getAgentTag(),
                "services" to getAgentServices()
            )
        )

        return createEvent(
            kind = KIND_PROFILE,
            content = JsonMapper.toJson(content),
            tags = listOf()
        )
    }

    /**
     * Create and sign an event
     */
    private fun createEvent(kind: Int, content: String, tags: List<List<String>>): Event {
        val publicKey = keyManager.getPublicKey()
        val createdAt = System.currentTimeMillis() / 1000

        val event = Event(
            id = "",
            pubkey = publicKey,
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )

        // Calculate event ID
        val eventId = calculateEventId(event)

        // Sign event
        val signature = signEvent(eventId)

        return event.copy(
            id = eventId,
            sig = signature
        )
    }

    /**
     * Calculate event ID (SHA256 of serialized event)
     */
    private fun calculateEventId(event: Event): String {
        val serialized = listOf(
            0,
            event.pubkey,
            event.created_at,
            event.kind,
            event.tags,
            event.content
        )

        val json = JsonMapper.toJson(serialized)
        val hash = org.spongycastle.crypto.digests.SHA256Digest()
        val bytes = json.toByteArray()
        hash.update(bytes, 0, bytes.size)

        val result = ByteArray(32)
        hash.doFinal(result, 0)

        return Hex.toHexString(result)
    }

    /**
     * Sign event with private key using Schnorr signature
     * Nostr uses Schnorr signatures (BIP-340) for events
     */
    private fun signEvent(eventId: String): String {
        val messageBytes = Hex.decode(eventId)
        val signature = keyManager.sign(messageBytes)
        return Hex.toHexString(signature)
    }

    /**
     * Publish event to relays
     */
    private fun publishEvent(event: Event) {
        val message = listOf("EVENT", event)
        val json = JsonMapper.toJson(message)

        relayConnections.values.forEach { ws ->
            ws.send(json)
        }

        // Queue if no connections
        if (relayConnections.isEmpty()) {
            messageQueue.add(QueuedMessage(event, getAllRelays()))
        }
    }

    // IP2PService implementation

    override fun sendMessage(toTag: String, content: String) {
        scope.launch {
            // Resolve recipient's public key
            val recipientPubkey = resolveTagToPubkey(toTag)

            if (recipientPubkey != null) {
                // Create encrypted message
                val event = createEncryptedMessage(recipientPubkey, content)
                publishEvent(event)

                Log.d(TAG, "Message sent to $toTag")
            } else {
                Log.e(TAG, "Could not resolve tag: $toTag")
            }
        }
    }

    override fun initiateHandshake(agentTag: String) {
        Log.d(TAG, "=== HANDSHAKE DEBUG: initiateHandshake called with agentTag: $agentTag ===")
        // In Nostr, handshakes are implicit through contact lists
        // We can create a contact request event
        scope.launch {
            Log.d(TAG, "=== HANDSHAKE DEBUG: Resolving tag to pubkey for: $agentTag ===")
            val recipientPubkey = resolveTagToPubkey(agentTag)
            Log.d(TAG, "=== HANDSHAKE DEBUG: Resolved pubkey: ${recipientPubkey?.take(20)}... ===")

            if (recipientPubkey != null) {
                val content = mapOf(
                    "type" to "handshake_request",
                    "from" to getAgentTag(),
                    "timestamp" to System.currentTimeMillis()
                )
                Log.d(TAG, "=== HANDSHAKE DEBUG: Creating encrypted message with content: $content ===")

                val event = createEncryptedMessage(recipientPubkey, JsonMapper.toJson(content))
                Log.d(TAG, "=== HANDSHAKE DEBUG: Created event with id: ${event.id}, publishing... ===")

                publishEvent(event)

                Log.d(TAG, "=== HANDSHAKE DEBUG: Handshake initiated with $agentTag ===")
            } else {
                Log.e(TAG, "=== HANDSHAKE DEBUG: Failed to resolve pubkey for $agentTag ===")
            }
        }
    }

    override fun acceptHandshake(fromTag: String) {
        // Accept contact request
        updateConnectionStatus(fromTag, true)
        Log.d(TAG, "Handshake accepted from $fromTag")
    }

    override fun rejectHandshake(fromTag: String) {
        // Reject contact request
        updateConnectionStatus(fromTag, false)
        Log.d(TAG, "Handshake rejected from $fromTag")
    }

    // Helper methods

    private fun getAllRelays(): List<String> {
        // Use ONLY our private AWS relay to ensure it's working
        return UNICITY_RELAYS
    }

    private fun updateConnectionStatus(identifier: String, isConnected: Boolean) {
        _connectionStatus.value = _connectionStatus.value.toMutableMap().apply {
            this[identifier] = IP2PService.ConnectionStatus(
                isConnected = isConnected,
                isAvailable = isConnected,
                lastSeen = System.currentTimeMillis()
            )
        }
    }

    private fun flushMessageQueue(relayUrl: String) {
        val ws = relayConnections[relayUrl] ?: return

        messageQueue.toList().forEach { queued ->
            if (relayUrl in queued.relays) {
                val message = listOf("EVENT", queued.event)
                ws.send(JsonMapper.toJson(message))
            }
        }
    }

    // Placeholder methods - to be implemented

    private fun isAgentMode(): Boolean {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_agent", false)
    }

    private fun startAgentDiscovery() {
        // TODO: Implement agent discovery
    }

    private fun getUsername(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "Anonymous") ?: "Anonymous"
    }

    private fun getNip05Identifier(): String {
        return "${getUsername()}@unicity.network"
    }

    private fun getWalletAddress(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("wallet_address", "") ?: ""
    }

    private fun getAgentTag(): String {
        val prefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        return prefs.getString("unicity_tag", "") ?: ""
    }

    private fun getAgentServices(): List<String> {
        return listOf("cash", "crypto", "transfer")
    }

    private suspend fun resolveTagToPubkey(tag: String): String? {
        Log.d(TAG, "=== RESOLVE DEBUG: Resolving tag '$tag' to pubkey ===")

        // First try to query the nametag binding from Nostr relay
        try {
            val pubkey = queryPubkeyByNametag(tag)
            if (pubkey != null) {
                Log.d(TAG, "=== RESOLVE DEBUG: Found pubkey from Nostr binding: ${pubkey.take(20)}... ===")
                return pubkey
            }
        } catch (e: Exception) {
            Log.w(TAG, "=== RESOLVE DEBUG: Failed to query Nostr binding for $tag: ${e.message} ===")
        }

        // Fallback to deterministic generation for backwards compatibility
        // This ensures both devices can derive the same pubkey for a given tag
        return try {
            val tagBytes = tag.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(tagBytes)
            val pubkey = Hex.toHexString(hash)
            Log.d(TAG, "=== RESOLVE DEBUG: Generated fallback pubkey for $tag: ${pubkey.take(20)}... ===")
            pubkey
        } catch (e: Exception) {
            Log.e(TAG, "=== RESOLVE DEBUG: Failed to generate pubkey for $tag: ${e.message} ===")
            null
        }
    }

    private fun createEncryptedMessage(recipientPubkey: String, content: String): Event {
        // Implement NIP-04 encryption
        val recipientPubkeyBytes = Hex.decode(recipientPubkey)
        val encryptedContent = keyManager.encryptMessage(content, recipientPubkeyBytes)

        return createEvent(
            kind = KIND_ENCRYPTED_DM,
            content = encryptedContent,
            tags = listOf(listOf("p", recipientPubkey))
        )
    }

    private fun handleEncryptedMessage(event: Event) {
        // Decrypt NIP-04 message
        try {
            val senderPubkeyBytes = Hex.decode(event.pubkey)
            val decryptedContent = keyManager.decryptMessage(event.content, senderPubkeyBytes)

            Log.d(TAG, "Received encrypted message from ${event.pubkey}: $decryptedContent")

            // TODO: Handle the decrypted message (e.g., store in database, notify UI)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message from ${event.pubkey}", e)
        }
    }

    private fun handleGiftWrappedMessage(event: Event) {
        // TODO: Unwrap and handle message
        Log.d(TAG, "Received gift-wrapped message")
    }

    private fun handleAgentLocation(event: Event) {
        try {
            // Parse location from content (format: "lat,lon,timestamp,tag")
            val parts = event.content.split(",")
            if (parts.size >= 3) {
                val latitude = parts[0].toDoubleOrNull()
                val longitude = parts[1].toDoubleOrNull()
                val timestamp = parts[2].toLongOrNull()
                val agentTag = if (parts.size > 3) parts[3] else event.pubkey

                if (latitude != null && longitude != null && timestamp != null) {
                    Log.d(TAG, "Received location from $agentTag: ($latitude, $longitude) at $timestamp")

                    // Update connection status with location info
                    _connectionStatus.update { current ->
                        current + (agentTag to IP2PService.ConnectionStatus(
                            isConnected = true,
                            isAvailable = true,
                            lastSeen = timestamp
                        ))
                    }

                    // TODO: Notify UI about new agent location
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing agent location", e)
        }
    }

    private fun handleTokenTransfer(event: Event) {
        scope.launch {
            try {
                Log.d(TAG, "Processing token transfer from ${event.pubkey}")

                // Decrypt the message content
                // Try hex decoding first (faucet uses simple hex), then NIP-04
                val decryptedContent = try {
                    // First try simple hex decoding (faucet format)
                    try {
                        String(Hex.decode(event.content), Charsets.UTF_8)
                    } catch (hexError: Exception) {
                        // Fall back to NIP-04 encryption
                        val senderPubkeyBytes = Hex.decode(event.pubkey)
                        keyManager.decryptMessage(event.content, senderPubkeyBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt token transfer", e)
                    return@launch
                }

                // Check if it's a token_transfer message
                if (!decryptedContent.startsWith("token_transfer:")) {
                    Log.w(TAG, "Not a token_transfer message")
                    return@launch
                }

                // Extract token JSON
                val tokenJson = decryptedContent.substring("token_transfer:".length)
                Log.d(TAG, "Received token JSON (${tokenJson.length} chars)")
                Log.d(TAG, "Token JSON preview: ${tokenJson.take(500)}")

                // Parse to check if it's a demo crypto transfer or real Unicity token
                val tokenJsonObj = try {
                    JsonMapper.fromJson(tokenJson, Map::class.java) as Map<*, *>
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse token JSON", e)
                    return@launch
                }

                val transferType = tokenJsonObj["type"] as? String

                if (transferType == "crypto_transfer") {
                    // Handle demo crypto transfer
                    handleDemoCryptoTransfer(tokenJsonObj)
                    return@launch
                }

                // Parse the Unicity SDK token (for real tokens)
                val unicityToken = try {
                    UnicityObjectMapper.JSON.readValue(tokenJson, org.unicitylabs.sdk.token.Token::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Unicity token", e)
                    return@launch
                }

                // Extract token metadata - using JSON parsing for simplicity
                // The SDK Token is complex, so we parse the JSON to extract metadata
                val registry = UnicityTokenRegistry.getInstance(context)
                var amount: Long? = null
                var coinIdHex: String? = null
                var symbol: String? = null
                var iconUrl: String? = null
                var tokenTypeHex = "unknown"

                try {
                    // Token JSON already parsed above
                    Log.d(TAG, "Token JSON keys: ${tokenJsonObj.keys}")

                    // Extract token type from genesis transaction
                    val genesis = tokenJsonObj["genesis"] as? Map<*, *>
                    Log.d(TAG, "Genesis keys: ${genesis?.keys}")
                    if (genesis != null) {
                        val genesisData = genesis["data"] as? Map<*, *>
                        Log.d(TAG, "Genesis data keys: ${genesisData?.keys}")
                        if (genesisData != null) {
                            // Token type
                            val tokenType = genesisData["tokenType"] as? String
                            if (tokenType != null) {
                                tokenTypeHex = tokenType
                                Log.d(TAG, "Token type: $tokenTypeHex")
                            }

                            // Try to find coins - format is array of [coinId, amount] pairs
                            // Option 1: genesisData.coins (mint transaction)
                            var coinsArray = genesisData["coins"] as? List<*>

                            // Option 2: genesisData.data.coins (nested)
                            if (coinsArray == null || coinsArray.isEmpty()) {
                                val data = genesisData["data"] as? Map<*, *>
                                coinsArray = data?.get("coins") as? List<*>
                            }

                            // Option 3: Current state data (for transferred tokens)
                            if (coinsArray == null || coinsArray.isEmpty()) {
                                val state = tokenJsonObj["state"] as? Map<*, *>
                                val stateData = state?.get("data") as? Map<*, *>
                                coinsArray = stateData?.get("coins") as? List<*>
                            }

                            Log.d(TAG, "Coins array: $coinsArray")
                            if (coinsArray != null && coinsArray.isNotEmpty()) {
                                // Get first coin entry [coinId, amount]
                                val firstCoin = coinsArray[0] as? List<*>
                                if (firstCoin != null && firstCoin.size >= 2) {
                                    coinIdHex = firstCoin[0] as? String
                                    amount = (firstCoin[1] as? String)?.toLongOrNull()
                                        ?: (firstCoin[1] as? Number)?.toLong()

                                    Log.d(TAG, "Coin ID: $coinIdHex, Amount: $amount")

                                    // Look up coin metadata in registry
                                    if (coinIdHex != null) {
                                        val coinDef = registry.getCoinDefinition(coinIdHex)
                                        if (coinDef != null) {
                                            symbol = coinDef.symbol
                                            iconUrl = coinDef.icon
                                            Log.d(TAG, "Found coin: ${coinDef.name} ($symbol) = $amount")
                                        } else {
                                            Log.w(TAG, "Coin $coinIdHex not found in registry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract token metadata from JSON", e)
                    e.printStackTrace()
                }

                // Create wallet Token model
                val walletToken = org.unicitylabs.wallet.data.model.Token(
                    name = symbol ?: "Token",
                    type = tokenTypeHex,
                    jsonData = tokenJson,
                    sizeBytes = tokenJson.length,
                    status = org.unicitylabs.wallet.data.model.TokenStatus.CONFIRMED,
                    amount = amount,
                    coinId = coinIdHex,
                    symbol = symbol,
                    iconUrl = iconUrl
                )

                // Save to wallet repository (use singleton)
                val walletRepository = org.unicitylabs.wallet.data.repository.WalletRepository.getInstance(context)
                walletRepository.addToken(walletToken)

                Log.i(TAG, "✅ Token received and saved: $amount $symbol")

                // Show notification to user
                showTokenReceivedNotification(amount, symbol ?: "tokens")

            } catch (e: Exception) {
                Log.e(TAG, "Error handling token transfer", e)
            }
        }
    }

    private fun showTokenReceivedNotification(amount: Long?, symbol: String) {
        // TODO: Implement notification
        Log.i(TAG, "📬 New token received: $amount $symbol")
    }

    /**
     * Handle receiving a demo crypto transfer (BTC, ETH, etc.)
     * Updates the receiver's cryptocurrency balance
     */
    private fun handleDemoCryptoTransfer(transferData: Map<*, *>) {
        try {
            val cryptoId = transferData["crypto_id"] as? String ?: return
            val cryptoSymbol = transferData["crypto_symbol"] as? String ?: return
            val cryptoName = transferData["crypto_name"] as? String ?: return
            val amount = (transferData["amount"] as? Number)?.toDouble() ?: return
            val isDemo = transferData["is_demo"] as? Boolean ?: true

            Log.d(TAG, "Received demo crypto transfer: $amount $cryptoSymbol (isDemo: $isDemo)")

            // Broadcast intent to MainActivity to update the crypto balance
            val intent = android.content.Intent("org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED")
            intent.putExtra("crypto_id", cryptoId)
            intent.putExtra("crypto_symbol", cryptoSymbol)
            intent.putExtra("crypto_name", cryptoName)
            intent.putExtra("amount", amount)
            intent.putExtra("is_demo", isDemo)

            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                .sendBroadcast(intent)

            Log.i(TAG, "✅ Demo crypto received: $amount $cryptoSymbol")

            // Show notification
            showCryptoReceivedNotification(amount, cryptoSymbol)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling demo crypto transfer", e)
        }
    }

    private fun showCryptoReceivedNotification(amount: Double, symbol: String) {
        // TODO: Implement notification
        Log.i(TAG, "📬 New crypto received: $amount $symbol")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun handleFileMetadata(event: Event) {
        // TODO: Handle file metadata
        Log.d(TAG, "Received file metadata from ${event.pubkey}")
    }

    /**
     * Send a token transfer to a recipient identified by their @unicity nametag
     * This creates an encrypted Nostr event containing the token JSON
     *
     * @param recipientNametag The recipient's @unicity nametag (e.g., "alice@unicity")
     * @param tokenJson The Unicity SDK token JSON to transfer
     * @param amount Optional amount for display purposes
     * @param symbol Optional symbol for display purposes
     * @return Result indicating success or failure
     */
    suspend fun sendTokenTransfer(
        recipientNametag: String,
        tokenJson: String,
        amount: Long? = null,
        symbol: String? = null
    ): Result<String> {
        return try {
            // Resolve recipient's Nostr public key from their nametag
            val recipientPubkey = queryPubkeyByNametag(recipientNametag)
                ?: return Result.failure(Exception("Could not find Nostr public key for nametag: $recipientNametag"))

            Log.d(TAG, "Sending token transfer to $recipientNametag (pubkey: ${recipientPubkey.take(16)}...)")
            Log.d(TAG, "Token size: ${tokenJson.length} bytes, amount: $amount $symbol")

            // Create encrypted token transfer content
            // Format: "token_transfer:<token_json>"
            val content = "token_transfer:$tokenJson"

            // Encrypt the content for the recipient
            val recipientPubkeyBytes = Hex.decode(recipientPubkey)
            val encryptedContent = keyManager.encryptMessage(content, recipientPubkeyBytes)

            // Create token transfer event
            val event = createEvent(
                kind = KIND_TOKEN_TRANSFER,
                content = encryptedContent,
                tags = listOf(
                    listOf("p", recipientPubkey), // Recipient pubkey
                    listOf("nametag", recipientNametag), // Recipient nametag for easier lookup
                    listOf("type", "token_transfer") // Event type
                ).let { tags ->
                    // Add optional amount/symbol tags if provided
                    if (amount != null && symbol != null) {
                        tags + listOf(
                            listOf("amount", amount.toString()),
                            listOf("symbol", symbol)
                        )
                    } else {
                        tags
                    }
                }
            )

            // Publish to relays
            publishEvent(event)

            Log.i(TAG, "✅ Token transfer sent successfully to $recipientNametag (event ID: ${event.id})")
            Result.success(event.id)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token transfer to $recipientNametag", e)
            Result.failure(e)
        }
    }

    override fun broadcastLocation(latitude: Double, longitude: Double) {
        if (!isRunning) {
            Log.w(TAG, "Cannot broadcast location - service not running")
            return
        }

        scope.launch {
            try {
                // Get user info
                val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""

                // Create location content (format: "lat,lon,timestamp,tag")
                val timestamp = System.currentTimeMillis()
                val content = "$latitude,$longitude,$timestamp,$unicityTag"

                // Create location event
                val event = createEvent(KIND_AGENT_LOCATION, content, emptyList())

                // Broadcast to all connected relays
                publishEvent(event)

                Log.d(TAG, "Broadcasting location: ($latitude, $longitude)")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting location", e)
            }
        }
    }

    override fun updateAvailability(isAvailable: Boolean) {
        // Store availability status
        val sharedPrefs = context.getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("agent_available", isAvailable).apply()

        // Optionally broadcast availability status to network
        if (isRunning) {
            scope.launch {
                try {
                    val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
                    val content = if (isAvailable) "$unicityTag:available" else "$unicityTag:unavailable"
                    val event = createEvent(KIND_AGENT_PROFILE, content, emptyList())
                    publishEvent(event)
                    Log.d(TAG, "Updated availability: $isAvailable")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating availability", e)
                }
            }
        }
    }

    /**
     * Publish a nametag binding to the Nostr relay
     * This creates a replaceable event that maps the user's Nostr pubkey to their Unicity nametag
     */
    suspend fun publishNametagBinding(nametagId: String, unicityAddress: String): Boolean {
        return try {
            val bindingManager = NostrNametagBinding()
            val publicKey = keyManager.getPublicKey()

            Log.d(TAG, "Publishing nametag binding for: $nametagId")

            val bindingEvent = bindingManager.createBindingEvent(
                publicKeyHex = publicKey,
                nametagId = nametagId,
                unicityAddress = unicityAddress,
                keyManager = keyManager
            )

            // Publish the event to all connected relays
            publishEvent(bindingEvent)

            Log.d(TAG, "Nametag binding published successfully: $nametagId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish nametag binding", e)
            false
        }
    }

    /**
     * Query nametag by Nostr pubkey
     * Returns the nametag string if found, null otherwise
     */
    suspend fun queryNametagByPubkey(nostrPubkey: String): String? {
        return try {
            val bindingManager = NostrNametagBinding()
            val filter = bindingManager.createPubkeyToNametagFilter(nostrPubkey)

            Log.d(TAG, "Querying nametag for pubkey: ${nostrPubkey.take(16)}...")

            // Subscribe and wait for result
            val subscriptionId = "query-nametag-${System.currentTimeMillis()}"
            var result: String? = null
            val receivedEvent = kotlinx.coroutines.CompletableDeferred<Event?>()

            // Add temporary listener for this query
            val listener: (Event) -> Unit = { event ->
                if (event.kind == NostrNametagBinding.KIND_NAMETAG_BINDING &&
                    event.pubkey.equals(nostrPubkey, ignoreCase = true)) {
                    receivedEvent.complete(event)
                }
            }

            eventListeners.add(listener)

            try {
                // Send REQ message to all connected relays
                val reqMessage = listOf("REQ", subscriptionId, filter)
                val json = JsonMapper.toJson(reqMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(json)
                }

                // Wait for response with timeout
                kotlinx.coroutines.withTimeout(5000) {
                    val event = receivedEvent.await()
                    result = event?.let { bindingManager.parseNametagFromEvent(it) }
                }

                if (result != null) {
                    Log.d(TAG, "Found nametag: $result")
                } else {
                    Log.d(TAG, "No nametag found for pubkey")
                }

                result
            } finally {
                // Clean up
                eventListeners.remove(listener)
                // Send CLOSE message
                val closeMessage = listOf("CLOSE", subscriptionId)
                val closeJson = JsonMapper.toJson(closeMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(closeJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query nametag by pubkey", e)
            null
        }
    }

    /**
     * Query Nostr pubkey by nametag
     * Returns the pubkey (hex) if found, null otherwise
     * IMPORTANT: Uses 't' tag for querying which is indexed by the relay
     */
    suspend fun queryPubkeyByNametag(nametagId: String): String? {
        return try {
            val bindingManager = NostrNametagBinding()
            val filter = bindingManager.createNametagToPubkeyFilter(nametagId)

            Log.d(TAG, "Querying pubkey for nametag: $nametagId")
            Log.d(TAG, "Filter: $filter")

            // Subscribe and wait for result
            val subscriptionId = "query-pubkey-${System.currentTimeMillis()}"
            var result: String? = null
            val receivedEvent = kotlinx.coroutines.CompletableDeferred<Event?>()

            // Add temporary listener for this query
            val listener: (Event) -> Unit = { event ->
                if (event.kind == NostrNametagBinding.KIND_NAMETAG_BINDING) {
                    // Check if this event has the nametag we're looking for
                    val eventNametag = bindingManager.parseNametagFromEvent(event)
                    if (eventNametag == nametagId) {
                        receivedEvent.complete(event)
                    }
                }
            }

            eventListeners.add(listener)

            try {
                // Send REQ message to all connected relays
                val reqMessage = listOf("REQ", subscriptionId, filter)
                val json = JsonMapper.toJson(reqMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(json)
                }

                // Wait for response with timeout
                kotlinx.coroutines.withTimeout(5000) {
                    val event = receivedEvent.await()
                    result = event?.pubkey
                }

                if (result != null) {
                    Log.d(TAG, "Found pubkey: ${result!!.take(16)}...")
                } else {
                    Log.d(TAG, "No pubkey found for nametag")
                }

                result
            } finally {
                // Clean up
                eventListeners.remove(listener)
                // Send CLOSE message
                val closeMessage = listOf("CLOSE", subscriptionId)
                val closeJson = JsonMapper.toJson(closeMessage)
                relayConnections.values.forEach { ws ->
                    ws.send(closeJson)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query pubkey by nametag", e)
            null
        }
    }
}