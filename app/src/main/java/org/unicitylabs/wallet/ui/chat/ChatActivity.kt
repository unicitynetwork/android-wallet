package org.unicitylabs.wallet.ui.chat

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.unicitylabs.wallet.data.chat.ChatConversation
import org.unicitylabs.wallet.data.chat.ChatDatabase
import org.unicitylabs.wallet.data.chat.ConversationDao
import org.unicitylabs.wallet.data.chat.MessageDao
import org.unicitylabs.wallet.databinding.ActivityChatBinding
import org.unicitylabs.wallet.nostr.NostrSdkService

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
    private var nostrService: NostrSdkService? = null

    private var agentTag: String = ""
    private var agentName: String = ""
    private var userTag: String = ""
    // NIP-17 messaging doesn't require handshake approval
    private var isApproved: Boolean = true
    
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
        
        // Initialize Nostr service for NIP-17 messaging
        initializeNostrService()
        
        // Setup RecyclerView
        chatAdapter = ChatAdapter(userTag)
        binding.recyclerViewChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
        }
        
        // Setup send button - NIP-17 allows direct messaging without handshake
        binding.btnSend.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
            }
        }

        // Allow sending with Enter key (for emulator keyboard)
        binding.etMessage.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val message = binding.etMessage.text.toString().trim()
                if (message.isNotEmpty()) {
                    sendMessage(message)
                }
                true
            } else {
                false
            }
        }

        // Observe conversation and messages
        observeConversation()
        observeMessages()
    }
    
    private fun initializeNostrService() {
        nostrService = NostrSdkService.getInstance(applicationContext)
        if (nostrService != null) {
            Log.d("ChatActivity", "Nostr service initialized for NIP-17 messaging")
        } else {
            Log.e("ChatActivity", "Failed to get Nostr service - messages may not send")
            Toast.makeText(this, "Messaging service unavailable", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun observeConversation() {
        lifecycleScope.launch {
            conversationDao.getConversationFlow(agentTag).collectLatest { conversation ->
                if (conversation == null) {
                    // Create new conversation - NIP-17 doesn't require handshake
                    val newConversation = ChatConversation(
                        conversationId = agentTag,
                        agentTag = agentTag,
                        agentPublicKey = null,
                        lastMessageTime = System.currentTimeMillis(),
                        lastMessageText = null,
                        isApproved = true  // No handshake needed with NIP-17
                    )
                    conversationDao.insertConversation(newConversation)
                }
                // UI is always ready for messaging with NIP-17
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
    
    private fun sendMessage(message: String) {
        binding.etMessage.text.clear()

        if (nostrService == null) {
            Toast.makeText(this, "Messaging service not available", Toast.LENGTH_SHORT).show()
            return
        }

        // Send via NIP-17 (this handles nametag resolution, encryption, and local DB save)
        nostrService?.sendMessage(agentTag, message)
        Log.d("ChatActivity", "Sending message to $agentTag via NIP-17")
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