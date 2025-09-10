package com.unicity.nfcwalletdemo.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.chat.ChatMessage
import com.unicity.nfcwalletdemo.data.chat.MessageStatus
import com.unicity.nfcwalletdemo.data.chat.MessageType
import com.unicity.nfcwalletdemo.databinding.ItemChatMessageReceivedBinding
import com.unicity.nfcwalletdemo.databinding.ItemChatMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private val currentUserTag: String) : 
    ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatMessageDiffCallback()) {
    
    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFromMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val binding = ItemChatMessageSentBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SentMessageViewHolder(binding)
            }
            else -> {
                val binding = ItemChatMessageReceivedBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                ReceivedMessageViewHolder(binding)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is SentMessageViewHolder -> holder.bind(message)
            is ReceivedMessageViewHolder -> holder.bind(message)
        }
    }
    
    inner class SentMessageViewHolder(
        private val binding: ItemChatMessageSentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(message: ChatMessage) {
            // Set message content based on type
            binding.tvMessage.text = when (message.type) {
                MessageType.LOCATION -> "📍 Location shared"
                MessageType.MEETING_REQUEST -> "🤝 Meeting requested"
                MessageType.TRANSACTION_CONFIRM -> "✅ Transaction confirmed"
                MessageType.HANDSHAKE_REQUEST -> "👋 Handshake request sent"
                MessageType.HANDSHAKE_ACCEPT -> "✅ Handshake accepted"
                else -> message.content
            }
            
            // Set timestamp
            binding.tvTimestamp.text = formatTimestamp(message.timestamp)
            
            // Set status indicator
            binding.ivStatus.visibility = View.VISIBLE
            when (message.status) {
                MessageStatus.PENDING -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_pending)
                    binding.ivStatus.alpha = 0.5f
                }
                MessageStatus.SENT -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_sent)
                    binding.ivStatus.alpha = 0.7f
                }
                MessageStatus.DELIVERED -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_delivered)
                    binding.ivStatus.alpha = 1.0f
                }
                MessageStatus.READ -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_read)
                    binding.ivStatus.alpha = 1.0f
                }
                MessageStatus.FAILED -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_close)
                    binding.ivStatus.alpha = 1.0f
                }
            }
        }
    }
    
    inner class ReceivedMessageViewHolder(
        private val binding: ItemChatMessageReceivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(message: ChatMessage) {
            // Set message content based on type
            binding.tvMessage.text = when (message.type) {
                MessageType.LOCATION -> "📍 Location shared"
                MessageType.MEETING_REQUEST -> "🤝 Meeting requested"
                MessageType.TRANSACTION_CONFIRM -> "✅ Transaction confirmed"
                MessageType.HANDSHAKE_REQUEST -> "👋 Handshake request received"
                MessageType.HANDSHAKE_ACCEPT -> "✅ Handshake accepted"
                else -> message.content
            }
            
            // Set timestamp
            binding.tvTimestamp.text = formatTimestamp(message.timestamp)
        }
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

class ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.messageId == newItem.messageId
    }
    
    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }
}