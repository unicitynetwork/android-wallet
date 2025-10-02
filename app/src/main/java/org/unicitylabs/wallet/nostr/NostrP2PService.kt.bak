package org.unicitylabs.wallet.nostr

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
// Nostr protocol implementation without external library dependency
import okhttp3.*
import org.unicitylabs.wallet.p2p.IP2PService
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.security.MessageDigest
import fr.acinq.secp256k1.Secp256k1
import org.spongycastle.util.encoders.Hex

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
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket connections
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
            val json = gson.fromJson(message, List::class.java)

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
            created_at = (data["created_at"] as Double).toLong(),
            kind = (data["kind"] as Double).toInt(),
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
                "since" to (System.currentTimeMillis() / 1000 - 86400) // Last 24 hours
            ),
            mapOf(
                "kinds" to listOf(KIND_AGENT_LOCATION, KIND_AGENT_PROFILE),
                "#t" to listOf("unicity-agent"),
                "since" to (System.currentTimeMillis() / 1000 - 3600) // Last hour
            )
        )

        val subscriptionId = UUID.randomUUID().toString().substring(0, 8)
        val request = listOf("REQ", subscriptionId, filters)

        webSocket.send(gson.toJson(request))
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
            content = gson.toJson(content),
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

        val json = gson.toJson(serialized)
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
        val json = gson.toJson(message)

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

                val event = createEncryptedMessage(recipientPubkey, gson.toJson(content))
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
                ws.send(gson.toJson(message))
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

        // For now, we'll use a deterministic way to generate pubkeys from tags
        // In production, this should query a name service or user registry

        // Generate a deterministic key from the tag for testing
        // This ensures both devices can derive the same pubkey for a given tag
        return try {
            val tagBytes = tag.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(tagBytes)
            val pubkey = Hex.toHexString(hash)
            Log.d(TAG, "=== RESOLVE DEBUG: Generated pubkey for $tag: ${pubkey.take(20)}... ===")
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
        // TODO: Handle token transfer request
        Log.d(TAG, "Received token transfer from ${event.pubkey}")
    }

    private fun handleFileMetadata(event: Event) {
        // TODO: Handle file metadata
        Log.d(TAG, "Received file metadata from ${event.pubkey}")
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
}