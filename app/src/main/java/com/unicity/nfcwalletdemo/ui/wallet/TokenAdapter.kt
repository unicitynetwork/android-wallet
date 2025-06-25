package com.unicity.nfcwalletdemo.ui.wallet

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.TokenStatus
import com.unicity.nfcwalletdemo.databinding.ItemTokenBinding
import java.text.SimpleDateFormat
import java.util.*

class TokenAdapter(
    private val onSendClick: (Token) -> Unit,
    private val onCancelClick: (Token) -> Unit,
    private val onManualSubmitClick: (Token) -> Unit = {}
) : ListAdapter<Token, TokenAdapter.TokenViewHolder>(TokenDiffCallback()) {
    
    private var expandedTokenId: String? = null
    private var transferringTokenId: String? = null
    private val transferProgress = mutableMapOf<String, Pair<Int, Int>>() // tokenId -> (current, total)
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val binding = ItemTokenBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TokenViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val token = getItem(position)
        val isExpanded = expandedTokenId == token.id
        val isTransferring = transferringTokenId == token.id
        holder.bind(token, isExpanded, isTransferring)
    }
    
    fun expandToken(token: Token) {
        val wasExpanded = expandedTokenId == token.id
        val oldExpandedId = expandedTokenId
        
        expandedTokenId = if (wasExpanded) null else token.id
        
        // Notify changes for animation
        if (oldExpandedId != null) {
            val oldIndex = currentList.indexOfFirst { it.id == oldExpandedId }
            if (oldIndex != -1) notifyItemChanged(oldIndex)
        }
        
        if (!wasExpanded) {
            val newIndex = currentList.indexOfFirst { it.id == token.id }
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }
    
    fun setTransferring(token: Token, isTransferring: Boolean) {
        transferringTokenId = if (isTransferring) token.id else null
        if (!isTransferring) {
            transferProgress.remove(token.id)
        }
        val index = currentList.indexOfFirst { it.id == token.id }
        if (index != -1) notifyItemChanged(index)
    }
    
    fun updateTransferProgress(token: Token, current: Int, total: Int) {
        transferProgress[token.id] = Pair(current, total)
        val index = currentList.indexOfFirst { it.id == token.id }
        if (index != -1) notifyItemChanged(index)
    }
    
    fun collapseAll() {
        val oldExpandedId = expandedTokenId
        expandedTokenId = null
        transferringTokenId = null
        
        if (oldExpandedId != null) {
            val index = currentList.indexOfFirst { it.id == oldExpandedId }
            if (index != -1) notifyItemChanged(index)
        }
    }
    
    inner class TokenViewHolder(
        private val binding: ItemTokenBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(token: Token, isExpanded: Boolean, isTransferring: Boolean) {
            // Basic token info
            binding.tvTokenName.text = token.name
            binding.tvTokenIdShort.text = token.id.take(8)
            
            // Set Unicity logo for token icon
            binding.ivTokenIcon.setImageResource(R.drawable.unicity_logo)
            binding.ivTokenIcon.background = null
            binding.ivTokenIcon.setPadding(0, 0, 0, 0)
            binding.ivTokenIcon.imageTintList = null // Remove any tint
            binding.ivTokenIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            
            // Expanded details
            binding.tvTokenId.text = "ID: ${token.id.take(12)}..."
            binding.tvTokenTimestamp.text = "Created: ${formatDate(token.timestamp)}"
            binding.tvUnicityAddress.text = if (token.unicityAddress != null) {
                "Address: ${token.unicityAddress.take(12)}..."
            } else {
                "Address: Not set"
            }
            binding.tvTokenSize.text = "Size: ${token.getFormattedSize()}"
            
            // Show token status
            updateTokenStatus(token)
            
            // Set up expansion/collapse
            binding.layoutExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            
            // Update expand icon rotation
            val rotation = if (isExpanded) 90f else 0f
            ObjectAnimator.ofFloat(binding.ivExpandIcon, "rotation", rotation).apply {
                duration = 200
                start()
            }
            
            // Handle transfer state
            if (isTransferring) {
                binding.layoutTransferStatus.visibility = View.VISIBLE
                binding.btnSend.visibility = View.GONE
                binding.btnCancel.visibility = View.VISIBLE
                binding.btnManualSubmit.visibility = View.GONE
                
                // Check if we have progress info
                val progress = transferProgress[token.id]
                if (progress != null) {
                    val (current, total) = progress
                    binding.tvTransferStatus.text = if (total > 1) {
                        "Sending chunk $current/$total..."
                    } else {
                        "Sending..."
                    }
                } else {
                    binding.tvTransferStatus.text = "Waiting for tap..."
                }
            } else {
                binding.layoutTransferStatus.visibility = View.GONE
                
                // Show appropriate buttons based on token status
                when (token.status ?: TokenStatus.CONFIRMED) {
                    TokenStatus.PENDING, TokenStatus.FAILED -> {
                        binding.btnSend.visibility = View.GONE
                        binding.btnManualSubmit.visibility = View.VISIBLE
                        binding.btnCancel.visibility = View.GONE
                    }
                    TokenStatus.SUBMITTED -> {
                        binding.btnSend.visibility = View.GONE
                        binding.btnManualSubmit.visibility = View.GONE
                        binding.btnCancel.visibility = View.GONE
                    }
                    TokenStatus.CONFIRMED -> {
                        binding.btnSend.visibility = View.VISIBLE
                        binding.btnManualSubmit.visibility = View.GONE
                        binding.btnCancel.visibility = View.GONE
                    }
                }
            }
            
            // Click listeners
            binding.layoutCollapsed.setOnClickListener {
                expandToken(token)
            }
            
            binding.btnSend.setOnClickListener {
                onSendClick(token)
            }
            
            binding.btnCancel.setOnClickListener {
                onCancelClick(token)
            }
            
            binding.btnManualSubmit.setOnClickListener {
                onManualSubmitClick(token)
            }
        }
        
        private fun updateTokenStatus(token: Token) {
            when (token.status ?: TokenStatus.CONFIRMED) {
                TokenStatus.PENDING -> {
                    binding.tvTokenStatus.visibility = View.VISIBLE
                    binding.ivStatusIcon.visibility = View.VISIBLE
                    binding.tvTokenStatus.text = "Submitting to network..."
                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_orange_light))
                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_info)
                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_orange_light))
                }
                TokenStatus.SUBMITTED -> {
                    binding.tvTokenStatus.visibility = View.VISIBLE
                    binding.ivStatusIcon.visibility = View.VISIBLE
                    binding.tvTokenStatus.text = "Awaiting confirmation..."
                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_blue_light))
                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_info)
                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_blue_light))
                }
                TokenStatus.FAILED -> {
                    binding.tvTokenStatus.visibility = View.VISIBLE
                    binding.ivStatusIcon.visibility = View.VISIBLE
                    binding.tvTokenStatus.text = "Submit failed - retry manually"
                    binding.tvTokenStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_red_light))
                    binding.ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                    binding.ivStatusIcon.setColorFilter(binding.root.context.getColor(android.R.color.holo_red_light))
                }
                TokenStatus.CONFIRMED -> {
                    binding.tvTokenStatus.visibility = View.GONE
                    binding.ivStatusIcon.visibility = View.GONE
                }
            }
        }
        
        private fun formatDate(timestamp: Long): String {
            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            return formatter.format(Date(timestamp))
        }
    }
    
    class TokenDiffCallback : DiffUtil.ItemCallback<Token>() {
        override fun areItemsTheSame(oldItem: Token, newItem: Token): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Token, newItem: Token): Boolean {
            return oldItem == newItem
        }
    }
}