package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * True P2P messaging service using Holepunch/Hyperswarm
 * This replaces the NSD-based implementation to enable global P2P connectivity
 */
class HolepunchP2PService private constructor(
    private val context: Context,
    private val userTag: String,
    private val userPublicKey: String
) : IP2PService {
    companion object {
        private const val TAG = "HolepunchP2PService"
        private const val CHAT_TOPIC_PREFIX = "unicity-chat-"
        
        @Volatile
        private var INSTANCE: HolepunchP2PService? = null
        
        fun getInstance(
            context: Context,
            userTag: String,
            userPublicKey: String
        ): HolepunchP2PService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HolepunchP2PService(context, userTag, userPublicKey).also {
                    INSTANCE = it
                }
            }
        }
        
        fun getExistingInstance(): HolepunchP2PService? = INSTANCE
    }
    
    private val gson = Gson()
    private val holepunchBridge = HolepunchBridge(context)
    private val chatDatabase = ChatDatabase.getDatabase(context)
    private val messageDao = chatDatabase.messageDao()
    private val conversationDao = chatDatabase.conversationDao()
    
    private val connectedPeers = ConcurrentHashMap<String, PeerInfo>()
    private val pendingMessages = ConcurrentHashMap<String, MutableList<P2PMessage>>()
    
    private val _connectionStatus = MutableStateFlow<Map<String, IP2PService.ConnectionStatus>>(emptyMap())
    override val connectionStatus: StateFlow<Map<String, IP2PService.ConnectionStatus>> = _connectionStatus
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class PeerInfo(
        val tag: String,
        val publicKey: String,
        val connectedAt: Long = System.currentTimeMillis()
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
        setupHolepunch()
    }
    
    private fun setupHolepunch() {
        Log.d(TAG, "Setting up Holepunch P2P for user: $userTag")
        
        // Initialize JavaScript bridge
        holepunchBridge.initialize()
        
        // Set up event listeners
        holepunchBridge.addEventListener("peer-connected") { data ->
            val peerId = data.optString("peerId")
            if (peerId.isNotEmpty()) {
                handlePeerConnected(peerId)
            }
        }
        
        holepunchBridge.addEventListener("peer-disconnected") { data ->
            val peerId = data.optString("peerId")
            if (peerId.isNotEmpty()) {
                handlePeerDisconnected(peerId)
            }
        }
        
        holepunchBridge.addEventListener("message-received") { data ->
            val peerId = data.optString("peerId")
            val message = data.optJSONObject("message")
            if (peerId.isNotEmpty() && message != null) {
                handleMessageReceived(peerId, message.toString())
            }
        }
        
        // Wait for bridge to be ready, then join topic
        scope.launch {
            holepunchBridge.isReady.collect { ready ->
                if (ready) {
                    val myTopic = "$CHAT_TOPIC_PREFIX$userTag"
                    holepunchBridge.sendCommand("joinTopic", org.json.JSONObject().apply {
                        put("topic", myTopic)
                    })
                    Log.d(TAG, "Joined topic: $myTopic")
                }
            }
        }
    }
    
    fun connectToAgent(agentTag: String) {
        scope.launch {
            try {
                // Join the agent's topic to establish connection
                val agentTopic = "$CHAT_TOPIC_PREFIX$agentTag"
                holepunchBridge.sendCommand("joinTopic", org.json.JSONObject().apply {
                    put("topic", agentTopic)
                })
                
                Log.d(TAG, "Attempting to connect to agent: $agentTag via topic: $agentTopic")
                
                // Update connection status
                updateConnectionStatus(agentTag, IP2PService.ConnectionStatus(
                    isConnected = false,
                    isAvailable = true,
                    lastSeen = System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to agent", e)
            }
        }
    }
    
    private fun handlePeerConnected(peerId: String) {
        Log.d(TAG, "Peer connected: $peerId")
        
        // Send identification message
        val identifyMsg = P2PMessage(
            from = userTag,
            to = peerId,
            type = MessageType.IDENTIFY,
            content = gson.toJson(mapOf(
                "tag" to userTag,
                "publicKey" to userPublicKey
            ))
        )
        
        scope.launch {
            holepunchBridge.sendCommand("sendMessage", org.json.JSONObject().apply {
                put("peerId", peerId)
                put("message", org.json.JSONObject(gson.toJson(identifyMsg)))
            })
        }
    }
    
    private fun handlePeerDisconnected(peerId: String) {
        Log.d(TAG, "Peer disconnected: $peerId")
        
        val peerInfo = connectedPeers.remove(peerId)
        peerInfo?.let {
            updateConnectionStatus(it.tag, IP2PService.ConnectionStatus(
                isConnected = false,
                isAvailable = false,
                lastSeen = System.currentTimeMillis()
            ))
        }
    }
    
    private fun handleMessageReceived(peerId: String, messageJson: String) {
        scope.launch {
            try {
                val message = gson.fromJson(messageJson, P2PMessage::class.java)
                
                when (message.type) {
                    MessageType.IDENTIFY -> {
                        // Handle peer identification
                        val data = gson.fromJson(message.content, Map::class.java)
                        val peerTag = data["tag"] as? String ?: return@launch
                        val peerPublicKey = data["publicKey"] as? String ?: return@launch
                        
                        connectedPeers[peerId] = PeerInfo(peerTag, peerPublicKey)
                        
                        updateConnectionStatus(peerTag, IP2PService.ConnectionStatus(
                            isConnected = true,
                            isAvailable = true,
                            lastSeen = System.currentTimeMillis()
                        ))
                        
                        // Send any pending messages
                        sendPendingMessages(peerTag)
                    }
                    
                    else -> {
                        // Handle regular messages
                        val peerInfo = connectedPeers[peerId] ?: return@launch
                        processIncomingMessage(message, peerInfo.tag)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle message", e)
            }
        }
    }
    
    override fun sendMessage(toTag: String, content: String) {
        scope.launch {
            try {
                val message = P2PMessage(
                    from = userTag,
                    to = toTag,
                    type = MessageType.TEXT,
                    content = content
                )
                
                // Find connected peer
                val peerId = connectedPeers.entries.find { it.value.tag == toTag }?.key
                
                if (peerId != null) {
                    // Send directly
                    holepunchBridge.sendCommand("sendMessage", org.json.JSONObject().apply {
                        put("peerId", peerId)
                        put("message", org.json.JSONObject(gson.toJson(message)))
                    })
                    
                    // Save to database
                    saveOutgoingMessage(message)
                } else {
                    // Queue for later
                    queueMessage(toTag, message)
                    
                    // Try to connect
                    connectToAgent(toTag)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }
    
    override fun initiateHandshake(agentTag: String) {
        scope.launch {
            try {
                val message = P2PMessage(
                    from = userTag,
                    to = agentTag,
                    type = MessageType.HANDSHAKE_REQUEST,
                    content = "Would like to start a conversation"
                )
                
                sendMessage(agentTag, gson.toJson(message))
                
                // Update conversation
                conversationDao.updateHandshakeStatus(agentTag, HandshakeStatus.SENT)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate handshake", e)
            }
        }
    }
    
    private suspend fun processIncomingMessage(message: P2PMessage, fromTag: String) {
        // Save to database
        val chatMessage = ChatMessage(
            messageId = message.messageId,
            conversationId = fromTag,
            content = message.content,
            timestamp = message.timestamp,
            isFromMe = false,
            type = message.type,
            status = MessageStatus.DELIVERED
        )
        
        messageDao.insertMessage(chatMessage)
        
        // Update conversation
        conversationDao.updateLastMessage(
            conversationId = fromTag,
            lastMessageTime = message.timestamp,
            lastMessageText = when (message.type) {
                MessageType.HANDSHAKE_REQUEST -> "Handshake request"
                MessageType.HANDSHAKE_ACCEPT -> "Handshake accepted"
                else -> message.content
            }
        )
    }
    
    private suspend fun saveOutgoingMessage(message: P2PMessage) {
        val chatMessage = ChatMessage(
            messageId = message.messageId,
            conversationId = message.to,
            content = message.content,
            timestamp = message.timestamp,
            isFromMe = true,
            type = message.type,
            status = MessageStatus.SENT
        )
        
        messageDao.insertMessage(chatMessage)
        conversationDao.updateLastMessage(
            conversationId = message.to,
            lastMessageTime = message.timestamp,
            lastMessageText = message.content
        )
    }
    
    private fun queueMessage(toTag: String, message: P2PMessage) {
        pendingMessages.getOrPut(toTag) { mutableListOf() }.add(message)
    }
    
    private suspend fun sendPendingMessages(toTag: String) {
        val messages = pendingMessages.remove(toTag) ?: return
        val peerId = connectedPeers.entries.find { it.value.tag == toTag }?.key ?: return
        
        messages.forEach { message ->
            holepunchBridge.sendCommand("sendMessage", org.json.JSONObject().apply {
                put("peerId", peerId)
                put("message", org.json.JSONObject(gson.toJson(message)))
            })
            saveOutgoingMessage(message)
        }
    }
    
    private fun updateConnectionStatus(tag: String, status: IP2PService.ConnectionStatus) {
        _connectionStatus.value = _connectionStatus.value + (tag to status)
    }
    
    override fun acceptHandshake(fromTag: String) {
        scope.launch {
            try {
                val message = P2PMessage(
                    from = userTag,
                    to = fromTag,
                    type = MessageType.HANDSHAKE_ACCEPT,
                    content = "Handshake accepted"
                )
                
                sendMessage(fromTag, gson.toJson(message))
                conversationDao.updateHandshakeStatus(fromTag, HandshakeStatus.APPROVED)
                conversationDao.updateApprovalStatus(fromTag, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept handshake", e)
            }
        }
    }
    
    override fun rejectHandshake(fromTag: String) {
        scope.launch {
            try {
                val message = P2PMessage(
                    from = userTag,
                    to = fromTag,
                    type = MessageType.HANDSHAKE_REJECT,
                    content = "Handshake rejected"
                )
                
                sendMessage(fromTag, gson.toJson(message))
                conversationDao.updateHandshakeStatus(fromTag, HandshakeStatus.REJECTED)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject handshake", e)
            }
        }
    }
    
    override fun start() {
        // Service starts automatically in init
        Log.d(TAG, "P2P service started")
    }
    
    override fun stop() {
        // Leave all topics but keep bridge alive
        scope.launch {
            holepunchBridge.sendCommand("leaveTopic", org.json.JSONObject().apply {
                put("topic", "$CHAT_TOPIC_PREFIX$userTag")
            })
        }
        Log.d(TAG, "P2P service stopped")
    }
    
    override fun isRunning(): Boolean {
        return holepunchBridge.isReady.value
    }
    
    override fun shutdown() {
        scope.cancel()
        holepunchBridge.destroy()
        INSTANCE = null
    }
}