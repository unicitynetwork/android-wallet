package com.unicity.nfcwalletdemo.ui.chat

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicity.nfcwalletdemo.data.chat.*
import com.unicity.nfcwalletdemo.databinding.ActivityChatBinding
import com.unicity.nfcwalletdemo.p2p.P2PMessagingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AGENT_TAG = "extra_agent_tag"
        const val EXTRA_AGENT_NAME = "extra_agent_name"
    }
    
    private lateinit var binding: ActivityChatBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatDatabase: ChatDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private var p2pService: P2PMessagingService? = null
    
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
        val publicKey = userTag // TODO: Get actual public key from wallet
        p2pService = P2PMessagingService(
            context = applicationContext,
            userTag = userTag,
            userPublicKey = publicKey
        )
    }
    
    private fun observeConversation() {
        lifecycleScope.launch {
            conversationDao.getConversationFlow(agentTag).collectLatest { conversation ->
                if (conversation != null) {
                    isApproved = conversation.isApproved
                    updateUIForApprovalStatus()
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
            binding.etMessage.hint = "Send handshake request to start chatting"
        } else {
            binding.etMessage.hint = "Type a message..."
        }
    }
    
    private fun showApprovalDialog() {
        AlertDialog.Builder(this)
            .setTitle("Start Chat")
            .setMessage("Send a handshake request to $agentName?")
            .setPositiveButton("Send") { _, _ ->
                p2pService?.initiateHandshake(agentTag)
                Toast.makeText(this, "Handshake request sent", Toast.LENGTH_SHORT).show()
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
    
    override fun onDestroy() {
        super.onDestroy()
        p2pService?.shutdown()
    }
}