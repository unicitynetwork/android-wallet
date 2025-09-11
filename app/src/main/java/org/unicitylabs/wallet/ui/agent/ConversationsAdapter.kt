package org.unicitylabs.wallet.ui.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.unicitylabs.wallet.data.chat.ChatConversation
import org.unicitylabs.wallet.databinding.ItemConversationBinding

class ConversationsAdapter(
    private val onItemClick: (ChatConversation) -> Unit,
    private val onClearClick: (ChatConversation) -> Unit,
    private val onDeleteClick: (ChatConversation) -> Unit
) : ListAdapter<ChatConversation, ConversationsAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConversationViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: ChatConversation) {
            // Display agent name without @unicity
            binding.tvAgentName.text = conversation.agentTag

            // Display time ago
            binding.tvTimeAgo.text = getTimeAgo(conversation.lastMessageTime)

            // Display unread count if any
            if (conversation.unreadCount > 0) {
                binding.tvUnreadCount.visibility = View.VISIBLE
                binding.tvUnreadCount.text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
            } else {
                binding.tvUnreadCount.visibility = View.GONE
            }

            // Set click listeners
            binding.root.setOnClickListener {
                onItemClick(conversation)
            }

            binding.btnClear.setOnClickListener {
                onClearClick(conversation)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(conversation)
            }
        }

        private fun getTimeAgo(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60_000 -> "just now"
                diff < 3600_000 -> "${diff / 60_000} min ago"
                diff < 86400_000 -> "${diff / 3600_000} hours ago"
                diff < 604800_000 -> "${diff / 86400_000} days ago"
                else -> "${diff / 604800_000} weeks ago"
            }
        }
    }

    class ConversationDiffCallback : DiffUtil.ItemCallback<ChatConversation>() {
        override fun areItemsTheSame(oldItem: ChatConversation, newItem: ChatConversation): Boolean {
            return oldItem.conversationId == newItem.conversationId
        }

        override fun areContentsTheSame(oldItem: ChatConversation, newItem: ChatConversation): Boolean {
            return oldItem == newItem
        }
    }
}