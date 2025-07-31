package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.ui.chat.ChatActivity

class P2PMessagingService private constructor(
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
        private const val NOTIFICATION_CHANNEL_ID = "unicity_chat"
        private const val NOTIFICATION_ID = 1001
        
        @Volatile
        private var INSTANCE: P2PMessagingService? = null
        
        fun getInstance(
            context: Context,
            userTag: String,
            userPublicKey: String
        ): P2PMessagingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PMessagingService(context, userTag, userPublicKey).also {
                    INSTANCE = it
                }
            }
        }
        
        fun getExistingInstance(): P2PMessagingService? = INSTANCE
    }
    
    private val gson = Gson()
    private val chatDatabase = ChatDatabase.getDatabase(context)
    private val messageDao = chatDatabase.messageDao()
    private val conversationDao = chatDatabase.conversationDao()
    
    private var webSocketServer: WebSocketServer? = null
    private val activeConnections = ConcurrentHashMap<String, WebSocketClient>()
    private val serverConnections = ConcurrentHashMap<String, WebSocket>() // Incoming connections
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
        Log.d(TAG, "P2PMessagingService initializing for user: $userTag")
        createNotificationChannel()
        startWebSocketServer()
        // Delay NSD discovery to ensure server is ready
        scope.launch {
            delay(500) // Give WebSocket server time to start
            startNsdDiscovery()
        }
        processPendingMessages()
    }
    
    private fun startWebSocketServer() {
        val port = findAvailablePort()
        Log.d(TAG, "Starting WebSocket server on port $port")
        // Bind to all interfaces (0.0.0.0) instead of localhost
        val address = InetSocketAddress("0.0.0.0", port)
        
        webSocketServer = object : WebSocketServer(address) {
            override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                Log.d(TAG, "WebSocket server connection opened from: ${conn?.remoteSocketAddress}")
                // Wait for identification message to know who connected
            }
            
            override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket connection closed: $reason")
                // Remove from server connections
                serverConnections.values.remove(conn)
            }
            
            override fun onMessage(conn: WebSocket?, message: String?) {
                Log.d(TAG, "WebSocket server received message: $message")
                message?.let { 
                    try {
                        val p2pMessage = gson.fromJson(it, P2PMessage::class.java)
                        // Store the connection mapping when we receive the first message
                        if (!serverConnections.containsKey(p2pMessage.from)) {
                            conn?.let { ws ->
                                serverConnections[p2pMessage.from] = ws
                                Log.d(TAG, "Mapped incoming connection from ${p2pMessage.from}")
                            }
                        }
                        handleIncomingMessage(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse incoming message", e)
                    }
                }
            }
            
            override fun onError(conn: WebSocket?, ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
            
            override fun onStart() {
                Log.d(TAG, "WebSocket server started on port $port")
                // Verify server is actually listening
                scope.launch {
                    delay(100) // Small delay to ensure server is ready
                    try {
                        val testSocket = java.net.Socket()
                        testSocket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                        testSocket.close()
                        Log.d(TAG, "WebSocket server verified listening on port $port")
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket server NOT listening on port $port", e)
                    }
                }
                registerNsdService(port)
            }
        }
        
        try {
            webSocketServer?.isReuseAddr = true
            webSocketServer?.start()
            Log.d(TAG, "WebSocket server start() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            throw e
        }
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
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX) &&
                    !serviceInfo.serviceName.contains(userTag)) {
                    Log.d(TAG, "Resolving service: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                } else {
                    Log.d(TAG, "Ignoring service: ${serviceInfo.serviceName} (own service or wrong prefix)")
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
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
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}")
                val agentTag = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                val host = serviceInfo.host
                val port = serviceInfo.port
                Log.d(TAG, "Agent: $agentTag, Host: $host, Port: $port")
                
                host.hostAddress?.let { hostAddress ->
                    Log.d(TAG, "Connecting to peer at $hostAddress:$port")
                    connectToPeer(agentTag, hostAddress, port)
                } ?: Log.e(TAG, "No host address for resolved service")
            }
        }
        
        nsdManager.resolveService(serviceInfo, resolveListener)
    }
    
    private fun connectToPeer(agentTag: String, host: String, port: Int, retryCount: Int = 0) {
        Log.d(TAG, "connectToPeer called - agentTag: $agentTag, host: $host, port: $port, retry: $retryCount")
        if (activeConnections.containsKey(agentTag)) {
            Log.d(TAG, "Already connected to $agentTag")
            return // Already connected
        }
        
        try {
            val uri = URI("ws://$host:$port")
            Log.d(TAG, "Creating WebSocket client to $uri")
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "Connected to $agentTag")
                    activeConnections[agentTag] = this
                    updateConnectionStatus(agentTag, true)
                    
                    // Send identification message so server knows who we are
                    val identMessage = P2PMessage(
                        from = userTag,
                        to = agentTag,
                        type = MessageType.IDENTIFICATION,
                        content = userPublicKey
                    )
                    send(gson.toJson(identMessage))
                    Log.d(TAG, "Sent identification message to $agentTag")
                    
                    // Send any pending messages
                    sendPendingMessages(agentTag)
                }
                
                override fun onMessage(message: String?) {
                    Log.d(TAG, "WebSocket client received message: $message")
                    message?.let { handleIncomingMessage(it) }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Disconnected from $agentTag: $reason")
                    activeConnections.remove(agentTag)
                    updateConnectionStatus(agentTag, false)
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Connection error with $agentTag", ex)
                    // Retry connection after a delay
                    if (retryCount < 3) {
                        scope.launch {
                            delay(2000) // Wait 2 seconds before retry
                            connectToPeer(agentTag, host, port, retryCount + 1)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Connecting WebSocket client...")
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $agentTag", e)
        }
    }
    
    private fun handleIncomingMessage(messageJson: String) {
        Log.d(TAG, "Received message: $messageJson")
        Log.d(TAG, "Current userTag: $userTag")
        scope.launch {
            try {
                Log.d(TAG, "Inside coroutine, parsing message...")
                val p2pMessage = gson.fromJson(messageJson, P2PMessage::class.java)
                Log.d(TAG, "Parsed message - from: ${p2pMessage.from}, to: ${p2pMessage.to}, type: ${p2pMessage.type}")
                
                // Verify message is for us
                Log.d(TAG, "Checking if message is for us - to: ${p2pMessage.to}, userTag: $userTag, equals: ${p2pMessage.to == userTag}")
                if (p2pMessage.to != userTag) {
                    Log.d(TAG, "Message not for us - ignoring (to: ${p2pMessage.to}, our tag: $userTag)")
                    return@launch
                }
                
                // Handle different message types
                Log.d(TAG, "Message is for us, handling type: ${p2pMessage.type}")
                Log.d(TAG, "MessageType enum check - HANDSHAKE_REQUEST: ${MessageType.HANDSHAKE_REQUEST}, equals: ${p2pMessage.type == MessageType.HANDSHAKE_REQUEST}")
                when (p2pMessage.type) {
                    MessageType.IDENTIFICATION -> {
                        // Don't save identification messages, they're just for connection mapping
                        Log.d(TAG, "Received identification from ${p2pMessage.from}")
                    }
                    MessageType.HANDSHAKE_REQUEST -> handleHandshakeRequest(p2pMessage)
                    MessageType.HANDSHAKE_ACCEPT -> handleHandshakeAccept(p2pMessage)
                    MessageType.AVAILABILITY_UPDATE -> handleAvailabilityUpdate(p2pMessage)
                    else -> saveIncomingMessage(p2pMessage)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }
    
    private suspend fun handleHandshakeRequest(message: P2PMessage) {
        Log.d(TAG, "handleHandshakeRequest called for message from: ${message.from}")
        // Create or update conversation
        var conversation = conversationDao.getConversation(message.from)
        Log.d(TAG, "Existing conversation: ${conversation != null}")
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
            Log.d(TAG, "Created new conversation for ${message.from}")
        }
        
        // Save handshake message
        Log.d(TAG, "Saving handshake message...")
        saveIncomingMessage(message)
        Log.d(TAG, "Handshake request handled successfully - awaiting agent approval")
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
        Log.d(TAG, "saveIncomingMessage called - type: ${p2pMessage.type}, from: ${p2pMessage.from}")
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
        
        // Show notification for new message
        showMessageNotification(p2pMessage)
    }
    
    fun sendMessage(agentTag: String, content: String, type: MessageType = MessageType.TEXT): String {
        Log.d(TAG, "sendMessage called - to: $agentTag, type: $type, content: $content")
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
        Log.d(TAG, "sendP2PMessage - looking for connection to: $agentTag")
        Log.d(TAG, "Active connections: ${activeConnections.keys}")
        Log.d(TAG, "Server connections: ${serverConnections.keys}")
        
        // Check both outgoing and incoming connections
        val client = activeConnections[agentTag]
        val serverConn = serverConnections[agentTag]
        
        return when {
            client != null -> {
                // Use outgoing connection
                try {
                    val messageJson = gson.toJson(message)
                    Log.d(TAG, "Sending message via client connection: $messageJson")
                    client.send(messageJson)
                    Log.d(TAG, "Message sent successfully via client")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send via client connection", e)
                    false
                }
            }
            serverConn != null -> {
                // Use incoming connection
                try {
                    val messageJson = gson.toJson(message)
                    Log.d(TAG, "Sending message via server connection: $messageJson")
                    serverConn.send(messageJson)
                    Log.d(TAG, "Message sent successfully via server")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send via server connection", e)
                    false
                }
            }
            else -> {
                Log.w(TAG, "No connection available to $agentTag")
                // Try to rediscover and reconnect
                scope.launch {
                    Log.d(TAG, "Attempting to rediscover $agentTag")
                    // Force a new discovery
                    startNsdDiscovery()
                }
                false
            }
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
        Log.d(TAG, "initiateHandshake called for: $agentTag")
        
        // Create conversation on sender side
        scope.launch {
            var conversation = conversationDao.getConversation(agentTag)
            if (conversation == null) {
                conversation = ChatConversation(
                    conversationId = agentTag,
                    agentTag = agentTag,
                    agentPublicKey = null, // Will be updated when we get response
                    lastMessageTime = System.currentTimeMillis(),
                    lastMessageText = "Handshake sent",
                    isApproved = false // Will be true when we get acceptance
                )
                conversationDao.insertConversation(conversation)
                Log.d(TAG, "Created conversation for handshake to $agentTag")
            }
        }
        
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
        
        // Send to outgoing connections
        activeConnections.forEach { (agentTag, client) ->
            try {
                val personalizedMessage = message.copy(to = agentTag)
                client.send(gson.toJson(personalizedMessage))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send availability update to $agentTag (client)", e)
            }
        }
        
        // Send to incoming connections
        serverConnections.forEach { (agentTag, conn) ->
            try {
                val personalizedMessage = message.copy(to = agentTag)
                conn.send(gson.toJson(personalizedMessage))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send availability update to $agentTag (server)", e)
            }
        }
    }
    
    fun shutdown() {
        Log.d(TAG, "Shutting down P2P service")
        scope.cancel()
        webSocketServer?.stop()
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        INSTANCE = null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Unicity Chat Messages"
            val descriptionText = "Notifications for new chat messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showMessageNotification(message: P2PMessage) {
        Log.d(TAG, "Showing notification for message from ${message.from}")
        
        // Create intent to open chat
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatActivity.EXTRA_AGENT_TAG, message.from)
            putExtra(ChatActivity.EXTRA_AGENT_NAME, "${message.from}@unicity")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            message.from.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationText = when (message.type) {
            MessageType.HANDSHAKE_REQUEST -> "New chat request from ${message.from}"
            MessageType.HANDSHAKE_ACCEPT -> "${message.from} accepted your chat request"
            MessageType.LOCATION -> "${message.from} shared their location"
            MessageType.MEETING_REQUEST -> "${message.from} requested a meeting"
            MessageType.TRANSACTION_CONFIRM -> "${message.from} confirmed the transaction"
            else -> message.content
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_bubble)
            .setContentTitle("${message.from}@unicity")
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(context).notify(
                    message.from.hashCode(),
                    notification
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to show notification - missing permission", e)
                showInAppAlert(message)
            }
        } else {
            Log.w(TAG, "Notifications are disabled")
            showInAppAlert(message)
        }
    }
    
    private fun showInAppAlert(message: P2PMessage) {
        Log.d(TAG, "Showing in-app alert for message from ${message.from}")
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            val alertText = when (message.type) {
                MessageType.HANDSHAKE_REQUEST -> "New chat request from ${message.from}"
                MessageType.HANDSHAKE_ACCEPT -> "${message.from} accepted your chat request"
                MessageType.LOCATION -> "${message.from} shared their location"
                MessageType.MEETING_REQUEST -> "${message.from} requested a meeting"
                MessageType.TRANSACTION_CONFIRM -> "${message.from} confirmed the transaction"
                else -> "New message from ${message.from}: ${message.content}"
            }
            
            android.widget.Toast.makeText(
                context,
                alertText,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}