package org.unicitylabs.wallet.ui.chat

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import org.unicitylabs.wallet.data.chat.ChatConversation
import org.unicitylabs.wallet.data.chat.ChatDatabase
import org.unicitylabs.wallet.data.chat.ConversationDao
import org.unicitylabs.wallet.data.chat.MessageDao
import org.unicitylabs.wallet.data.chat.MessageType
import org.unicitylabs.wallet.databinding.ActivityChatBinding
import org.unicitylabs.wallet.p2p.IP2PService
import org.unicitylabs.wallet.p2p.P2PMessagingService
import org.unicitylabs.wallet.p2p.P2PServiceFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AGENT_TAG = "extra_agent_tag"
        const val EXTRA_AGENT_NAME = "extra_agent_name"
    }
    
    fun getCurrentChatPartner(): String? {
        return agentTag
    }
    
    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatDatabase: ChatDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private var p2pService: IP2PService? = null
    
    private var agentTag: String = ""
    private var agentName: String = ""
    private var userTag: String = ""
    private var isApproved: Boolean = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get agent info from intent
        agentTag = intent.getStringExtra(EXTRA_AGENT_TAG) ?: ""
        agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: agentTag
        
        if (agentTag.isEmpty()) {
            Toast.makeText(this, "Invalid agent", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Mark this as the current chat partner
        val prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        prefs.edit().putString("current_chat_partner", agentTag).apply()
        
        // Setup toolbar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = agentName
        }
        
        // Initialize database
        chatDatabase = ChatDatabase.getDatabase(this)
        messageDao = chatDatabase.messageDao()
        conversationDao = chatDatabase.conversationDao()
        
        // Get user tag from preferences
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", MODE_PRIVATE)
        userTag = sharedPrefs.getString("unicity_tag", "") ?: ""

        // If no unicity tag, check for temporary chat ID
        if (userTag.isEmpty()) {
            userTag = sharedPrefs.getString("temp_chat_id", "") ?: ""
        }
        
        // Initialize P2P service if available
        initializeP2PService()
        
        // Setup RecyclerView
        chatAdapter = ChatAdapter(userTag)
        binding.recyclerViewChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
        }
        
        // Setup send button
        binding.btnSend.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                if (!isApproved) {
                    showApprovalDialog()
                } else {
                    sendMessage(message)
                }
            }
        }
        
        // Observe conversation and messages
        observeConversation()
        observeMessages()
    }
    
    private fun initializeP2PService() {
        // Try to get existing instance first
        p2pService = P2PServiceFactory.getInstance()

        if (p2pService == null) {
            // If no instance exists, create one for any user who wants to chat
            val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", MODE_PRIVATE)

            // For non-agent users who don't have a saved tag, use a temporary identifier
            val effectiveUserTag = when {
                userTag.isNotEmpty() -> userTag
                else -> {
                    // Generate or retrieve a temporary user identifier for chatting
                    val existingTempId = sharedPrefs.getString("temp_chat_id", "") ?: ""
                    if (existingTempId.isNotEmpty()) {
                        existingTempId
                    } else {
                        val tempId = "user_${System.currentTimeMillis()}"
                        sharedPrefs.edit().putString("temp_chat_id", tempId).apply()
                        tempId
                    }
                }
            }

            // Get public key or generate one if needed
            var publicKey = sharedPrefs.getString("wallet_public_key", "") ?: ""
            if (publicKey.isEmpty()) {
                // Use the effective user tag as a fallback identifier
                publicKey = effectiveUserTag
            }

            Log.d("ChatActivity", "Initializing P2P service for user: $effectiveUserTag")

            p2pService = P2PServiceFactory.getInstance(
                context = applicationContext,
                userTag = effectiveUserTag,
                userPublicKey = publicKey
            )

            if (p2pService != null) {
                Log.d("ChatActivity", "P2P service initialized successfully")
                // Start the service if it's not running
                if (!p2pService!!.isRunning()) {
                    p2pService!!.start()
                }
            } else {
                Log.e("ChatActivity", "Failed to initialize P2P service")
            }
        }
    }
    
    private fun observeConversation() {
        lifecycleScope.launch {
            conversationDao.getConversationFlow(agentTag).collectLatest { conversation ->
                if (conversation != null) {
                    isApproved = conversation.isApproved
                    updateUIForApprovalStatus()
                    
                    // Show handshake dialog immediately if not approved
                    if (!isApproved) {
                        runOnUiThread {
                            showHandshakeDialogImmediately()
                        }
                    }
                } else {
                    // Create new conversation
                    val newConversation = ChatConversation(
                        conversationId = agentTag,
                        agentTag = agentTag,
                        agentPublicKey = null,
                        lastMessageTime = System.currentTimeMillis(),
                        lastMessageText = null,
                        isApproved = false
                    )
                    conversationDao.insertConversation(newConversation)
                    
                    // Show handshake dialog for new conversation
                    runOnUiThread {
                        showHandshakeDialogImmediately()
                    }
                }
            }
        }
    }
    
    private fun observeMessages() {
        lifecycleScope.launch {
            messageDao.getMessagesForConversation(agentTag).collectLatest { messages ->
                chatAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.recyclerViewChat.smoothScrollToPosition(messages.size - 1)
                }
                
                // Mark messages as read
                conversationDao.markAsRead(agentTag)
            }
        }
    }
    
    private fun updateUIForApprovalStatus() {
        if (!isApproved) {
            // Check if we have received a handshake request
            lifecycleScope.launch {
                val messages = messageDao.getMessagesForConversationList(agentTag)
                val hasIncomingHandshake = messages.any { 
                    it.type == MessageType.HANDSHAKE_REQUEST && !it.isFromMe 
                }
                
                if (hasIncomingHandshake) {
                    // Show accept/reject buttons for agent
                    binding.etMessage.visibility = View.GONE
                    binding.btnSend.visibility = View.GONE
                    
                    // Create accept/reject layout
                    showHandshakeResponseButtons()
                } else {
                    // Show normal handshake prompt for user
                    binding.etMessage.hint = "Send handshake request to start chatting"
                }
            }
        } else {
            binding.etMessage.hint = "Type a message..."
            binding.etMessage.visibility = View.VISIBLE
            binding.btnSend.visibility = View.VISIBLE
        }
    }
    
    private fun showHandshakeResponseButtons() {
        // For agents receiving handshakes, let them respond by typing
        binding.etMessage.hint = "Type a message to accept chat request..."
        binding.etMessage.visibility = View.VISIBLE
        binding.btnSend.visibility = View.VISIBLE
        
        // When agent sends first message, it auto-accepts the handshake
        binding.btnSend.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                // Accept handshake and send message
                p2pService?.acceptHandshake(agentTag)
                sendMessage(message)
                Toast.makeText(this, "Chat request accepted", Toast.LENGTH_SHORT).show()
                
                // Reset to normal send button behavior
                binding.btnSend.setOnClickListener {
                    val msg = binding.etMessage.text.toString().trim()
                    if (msg.isNotEmpty()) {
                        sendMessage(msg)
                    }
                }
            }
        }
    }
    
    private var handshakeDialogShown = false
    
    private fun showHandshakeDialogImmediately() {
        // Prevent showing dialog multiple times
        if (handshakeDialogShown) return
        handshakeDialogShown = true
        
        AlertDialog.Builder(this)
            .setTitle("Start Chat")
            .setMessage("Send a handshake request to $agentName?")
            .setPositiveButton("Send") { _, _ ->
                // Initialize P2P service if needed before sending handshake
                if (p2pService == null) {
                    initializeP2PService()
                }

                if (p2pService != null) {
                    p2pService?.initiateHandshake(agentTag)
                    Toast.makeText(this, "Handshake request sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to initialize chat service. Please try again.", Toast.LENGTH_LONG).show()
                    finish() // Close chat if P2P is not available
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                // User cancelled, close the chat activity and go back to map
                finish()
            }
            .setCancelable(false) // Force user to make a choice
            .show()
    }
    
    private fun showApprovalDialog() {
        Log.d("ChatActivity", "=== HANDSHAKE DEBUG: showApprovalDialog called for agent: $agentTag ===")
        AlertDialog.Builder(this)
            .setTitle("Start Chat")
            .setMessage("Send a handshake request to $agentName?")
            .setPositiveButton("Send") { _, _ ->
                Log.d("ChatActivity", "=== HANDSHAKE DEBUG: User clicked Send ===")
                // Initialize P2P service if needed before sending handshake
                if (p2pService == null) {
                    Log.d("ChatActivity", "=== HANDSHAKE DEBUG: P2P service is null, initializing... ===")
                    initializeP2PService()
                }

                if (p2pService != null) {
                    Log.d("ChatActivity", "=== HANDSHAKE DEBUG: Calling initiateHandshake($agentTag) ===")
                    Log.d("ChatActivity", "=== HANDSHAKE DEBUG: P2P service class: ${p2pService?.javaClass?.simpleName} ===")
                    p2pService?.initiateHandshake(agentTag)
                    Toast.makeText(this, "Handshake request sent", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("ChatActivity", "=== HANDSHAKE DEBUG: P2P service is still null after init ===")
                    Toast.makeText(this, "Failed to initialize chat service. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun sendMessage(message: String) {
        binding.etMessage.text.clear()
        p2pService?.sendMessage(agentTag, message)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Mark this as the current chat partner when resuming
        val prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        prefs.edit().putString("current_chat_partner", agentTag).apply()
    }
    
    override fun onPause() {
        super.onPause()
        // Clear current chat partner when pausing
        val prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE)
        prefs.edit().remove("current_chat_partner").apply()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Don't shutdown the shared instance
        // p2pService?.shutdown()
    }
}