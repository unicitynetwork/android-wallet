package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class P2PMessagingService(
    private val context: Context,
    private val userTag: String,
    private val userPublicKey: String
) {
    companion object {
        private const val TAG = "P2PMessagingService"
        private const val SERVICE_TYPE = "_unicity-chat._tcp"
        private const val SERVICE_NAME_PREFIX = "unicity-"
        private const val WS_PORT_MIN = 9000
        private const val WS_PORT_MAX = 9999
    }
    
    private val gson = Gson()
    private val chatDatabase = ChatDatabase.getDatabase(context)
    private val messageDao = chatDatabase.messageDao()
    private val conversationDao = chatDatabase.conversationDao()
    
    private var webSocketServer: WebSocketServer? = null
    private val activeConnections = ConcurrentHashMap<String, WebSocketClient>()
    private val pendingMessages = ConcurrentHashMap<String, MutableList<P2PMessage>>()
    
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionStatus>> = _connectionStatus
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class ConnectionStatus(
        val isConnected: Boolean,
        val isAvailable: Boolean = false,
        val lastSeen: Long = 0
    )
    
    data class P2PMessage(
        val messageId: String = UUID.randomUUID().toString(),
        val from: String,
        val to: String,
        val type: MessageType,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val signature: String? = null
    )
    
    init {
        startWebSocketServer()
        startNsdDiscovery()
        processPendingMessages()
    }
    
    private fun startWebSocketServer() {
        val port = findAvailablePort()
        val address = InetSocketAddress(port)
        
        webSocketServer = object : WebSocketServer(address) {
            override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                Log.d(TAG, "WebSocket connection opened")
            }
            
            override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket connection closed: $reason")
            }
            
            override fun onMessage(conn: WebSocket?, message: String?) {
                message?.let { handleIncomingMessage(it) }
            }
            
            override fun onError(conn: WebSocket?, ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
            
            override fun onStart() {
                Log.d(TAG, "WebSocket server started on port $port")
                registerNsdService(port)
            }
        }
        
        webSocketServer?.start()
    }
    
    private fun findAvailablePort(): Int {
        for (port in WS_PORT_MIN..WS_PORT_MAX) {
            try {
                val socket = java.net.ServerSocket(port)
                socket.close()
                return port
            } catch (e: Exception) {
                // Port is in use, try next
            }
        }
        throw RuntimeException("No available ports found")
    }
    
    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$userTag"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("tag", userTag)
            setAttribute("pubkey", userPublicKey)
        }
        
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
            }
            
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: $errorCode")
            }
            
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }
            
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: $errorCode")
            }
        }
        
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }
    
    private fun startNsdDiscovery() {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX) &&
                    !serviceInfo.serviceName.contains(userTag)) {
                    resolveService(serviceInfo)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val agentTag = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                updateConnectionStatus(agentTag, false)
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }
        
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
    
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val agentTag = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                val host = serviceInfo.host
                val port = serviceInfo.port
                
                host.hostAddress?.let { hostAddress ->
                    connectToPeer(agentTag, hostAddress, port)
                }
            }
        }
        
        nsdManager.resolveService(serviceInfo, resolveListener)
    }
    
    private fun connectToPeer(agentTag: String, host: String, port: Int) {
        if (activeConnections.containsKey(agentTag)) {
            return // Already connected
        }
        
        try {
            val uri = URI("ws://$host:$port")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "Connected to $agentTag")
                    activeConnections[agentTag] = this
                    updateConnectionStatus(agentTag, true)
                    
                    // Send any pending messages
                    sendPendingMessages(agentTag)
                }
                
                override fun onMessage(message: String?) {
                    message?.let { handleIncomingMessage(it) }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Disconnected from $agentTag: $reason")
                    activeConnections.remove(agentTag)
                    updateConnectionStatus(agentTag, false)
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Connection error with $agentTag", ex)
                }
            }
            
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $agentTag", e)
        }
    }
    
    private fun handleIncomingMessage(messageJson: String) {
        scope.launch {
            try {
                val p2pMessage = gson.fromJson(messageJson, P2PMessage::class.java)
                
                // Verify message is for us
                if (p2pMessage.to != userTag) {
                    return@launch
                }
                
                // Handle different message types
                when (p2pMessage.type) {
                    MessageType.HANDSHAKE_REQUEST -> handleHandshakeRequest(p2pMessage)
                    MessageType.HANDSHAKE_ACCEPT -> handleHandshakeAccept(p2pMessage)
                    MessageType.AVAILABILITY_UPDATE -> handleAvailabilityUpdate(p2pMessage)
                    else -> saveIncomingMessage(p2pMessage)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message", e)
            }
        }
    }
    
    private suspend fun handleHandshakeRequest(message: P2PMessage) {
        // Create or update conversation
        var conversation = conversationDao.getConversation(message.from)
        if (conversation == null) {
            conversation = ChatConversation(
                conversationId = message.from,
                agentTag = message.from,
                agentPublicKey = message.content, // Public key in content
                lastMessageTime = message.timestamp,
                lastMessageText = "New chat request",
                isApproved = false
            )
            conversationDao.insertConversation(conversation)
        }
        
        // Save handshake message
        saveIncomingMessage(message)
    }
    
    private suspend fun handleHandshakeAccept(message: P2PMessage) {
        conversationDao.updateApprovalStatus(message.from, true)
        saveIncomingMessage(message)
    }
    
    private suspend fun handleAvailabilityUpdate(message: P2PMessage) {
        val isAvailable = message.content.toBoolean()
        conversationDao.updateAvailability(message.from, isAvailable)
        updateConnectionStatus(message.from, activeConnections.containsKey(message.from), isAvailable)
    }
    
    private suspend fun saveIncomingMessage(p2pMessage: P2PMessage) {
        val chatMessage = ChatMessage(
            messageId = p2pMessage.messageId,
            conversationId = p2pMessage.from,
            content = p2pMessage.content,
            timestamp = p2pMessage.timestamp,
            isFromMe = false,
            status = MessageStatus.DELIVERED,
            type = p2pMessage.type,
            signature = p2pMessage.signature
        )
        
        messageDao.insertMessage(chatMessage)
        
        // Update conversation
        conversationDao.incrementUnreadCount(p2pMessage.from)
        val conversation = conversationDao.getConversation(p2pMessage.from)
        conversation?.let {
            conversationDao.updateConversation(
                it.copy(
                    lastMessageTime = p2pMessage.timestamp,
                    lastMessageText = when (p2pMessage.type) {
                        MessageType.LOCATION -> "📍 Location shared"
                        MessageType.MEETING_REQUEST -> "🤝 Meeting requested"
                        MessageType.TRANSACTION_CONFIRM -> "✅ Transaction confirmed"
                        else -> p2pMessage.content
                    }
                )
            )
        }
    }
    
    fun sendMessage(agentTag: String, content: String, type: MessageType = MessageType.TEXT): String {
        val messageId = UUID.randomUUID().toString()
        val p2pMessage = P2PMessage(
            messageId = messageId,
            from = userTag,
            to = agentTag,
            type = type,
            content = content,
            signature = signMessage(content)
        )
        
        scope.launch {
            // Save to database as pending
            val chatMessage = ChatMessage(
                messageId = messageId,
                conversationId = agentTag,
                content = content,
                timestamp = p2pMessage.timestamp,
                isFromMe = true,
                status = MessageStatus.PENDING,
                type = type,
                signature = p2pMessage.signature
            )
            messageDao.insertMessage(chatMessage)
            
            // Try to send
            if (sendP2PMessage(agentTag, p2pMessage)) {
                messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
            } else {
                // Queue for later
                pendingMessages.getOrPut(agentTag) { mutableListOf() }.add(p2pMessage)
            }
        }
        
        return messageId
    }
    
    private fun sendP2PMessage(agentTag: String, message: P2PMessage): Boolean {
        val client = activeConnections[agentTag] ?: return false
        
        return try {
            val messageJson = gson.toJson(message)
            client.send(messageJson)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }
    
    private fun sendPendingMessages(agentTag: String) {
        scope.launch {
            // Send queued P2P messages
            pendingMessages[agentTag]?.let { messages ->
                messages.forEach { message ->
                    if (sendP2PMessage(agentTag, message)) {
                        messageDao.updateMessageStatus(message.messageId, MessageStatus.SENT)
                    }
                }
                pendingMessages.remove(agentTag)
            }
            
            // Send database pending messages
            val pendingDbMessages = messageDao.getMessagesByStatus(agentTag, MessageStatus.PENDING)
            pendingDbMessages.forEach { chatMessage ->
                val p2pMessage = P2PMessage(
                    messageId = chatMessage.messageId,
                    from = userTag,
                    to = agentTag,
                    type = chatMessage.type,
                    content = chatMessage.content,
                    timestamp = chatMessage.timestamp,
                    signature = chatMessage.signature
                )
                
                if (sendP2PMessage(agentTag, p2pMessage)) {
                    messageDao.updateMessageStatus(chatMessage.messageId, MessageStatus.SENT)
                }
            }
        }
    }
    
    private fun processPendingMessages() {
        scope.launch {
            while (isActive) {
                delay(30000) // Check every 30 seconds
                
                // Try to send all pending messages
                activeConnections.keys.forEach { agentTag ->
                    sendPendingMessages(agentTag)
                }
            }
        }
    }
    
    private fun updateConnectionStatus(agentTag: String, isConnected: Boolean, isAvailable: Boolean = false) {
        val currentStatus = _connectionStatus.value.toMutableMap()
        currentStatus[agentTag] = ConnectionStatus(
            isConnected = isConnected,
            isAvailable = isAvailable,
            lastSeen = if (isConnected) System.currentTimeMillis() else currentStatus[agentTag]?.lastSeen ?: 0
        )
        _connectionStatus.value = currentStatus
    }
    
    private fun signMessage(content: String): String {
        // TODO: Implement proper signing with wallet keys
        return MessageDigest.getInstance("SHA-256")
            .digest("$content$userTag".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    fun initiateHandshake(agentTag: String) {
        sendMessage(agentTag, userPublicKey, MessageType.HANDSHAKE_REQUEST)
    }
    
    fun acceptHandshake(agentTag: String) {
        scope.launch {
            conversationDao.updateApprovalStatus(agentTag, true)
            sendMessage(agentTag, "accepted", MessageType.HANDSHAKE_ACCEPT)
        }
    }
    
    fun updateAvailability(isAvailable: Boolean) {
        // Broadcast to all connected peers
        val message = P2PMessage(
            from = userTag,
            to = "", // Will be filled per peer
            type = MessageType.AVAILABILITY_UPDATE,
            content = isAvailable.toString()
        )
        
        activeConnections.forEach { (agentTag, client) ->
            try {
                val personalizedMessage = message.copy(to = agentTag)
                client.send(gson.toJson(personalizedMessage))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send availability update to $agentTag", e)
            }
        }
    }
    
    fun shutdown() {
        scope.cancel()
        webSocketServer?.stop()
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
    }
}