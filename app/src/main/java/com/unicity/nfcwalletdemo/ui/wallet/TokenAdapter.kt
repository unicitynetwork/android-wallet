package com.unicity.nfcwalletdemo.ui.wallet

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.TokenStatus
import com.unicity.nfcwalletdemo.databinding.ItemTokenBinding
import java.text.SimpleDateFormat
import java.util.*

class TokenAdapter(
    private val onSendClick: (Token) -> Unit,
    private val onCancelClick: (Token) -> Unit,
    private val onManualSubmitClick: (Token) -> Unit = {},
    private val onShareClick: (Token) -> Unit = {}
) : ListAdapter<Token, TokenAdapter.TokenViewHolder>(TokenDiffCallback()) {
    
    private var expandedTokenId: String? = null
    private var transferringTokenId: String? = null
    private val transferProgress = mutableMapOf<String, Pair<Int, Int>>() // tokenId -> (current, total)
    private val gson = Gson()
    
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
    
    private fun extractAmountFromToken(tokenJson: Map<*, *>): Any? {
        // Try different paths where amount might be stored
        
        // Check in genesis transaction
        val genesis = tokenJson["genesis"] as? Map<*, *>
        val genesisData = genesis?.get("data") as? Map<*, *>
        val mintData = genesisData?.get("data")
        
        // If mintData is a Map (our new format)
        if (mintData is Map<*, *>) {
            // Direct amount field in our new format
            mintData["amount"]?.let { return it }
            
            // For TokenCoinData structure (coins field)
            val coins = mintData["coins"] as? Map<*, *>
            if (coins != null && coins.isNotEmpty()) {
                // Sum all coin values
                var totalAmount = 0.0
                coins.values.forEach { value ->
                    try {
                        totalAmount += value.toString().toDouble()
                    } catch (e: Exception) {
                        // Ignore parsing errors
                    }
                }
                if (totalAmount > 0) return totalAmount.toLong()
            }
            
            // Other possible fields
            mintData["value"]?.let { return it }
        }
        
        // Check message field for custom data that might contain amount
        val message = (mintData as? Map<*, *>)?.get("message") as? String
        if (message != null) {
            // Try to parse message as JSON
            try {
                val messageData = Gson().fromJson(message, Map::class.java)
                messageData["amount"]?.let { return it }
                messageData["value"]?.let { return it }
            } catch (e: Exception) {
                // Message is not JSON, ignore
            }
        }
        
        return null
    }
    
    private fun showTokenDetails(binding: ItemTokenBinding, token: Token) {
        try {
            // Find the views - they might not exist in older layouts
            val tvAmount = binding.root.findViewById<TextView?>(R.id.tvTokenAmount)
            val tvData = binding.root.findViewById<TextView?>(R.id.tvTokenData)
            
            // If views don't exist, just return
            if (tvAmount == null && tvData == null) return
            
            // Hide by default
            tvAmount?.visibility = View.GONE
            tvData?.visibility = View.GONE
            
            // Try to parse token data
            token.jsonData?.let { jsonData ->
                try {
                    val tokenJson = Gson().fromJson(jsonData, Map::class.java)
                    
                    // Debug logging
                    android.util.Log.d("TokenAdapter", "Token JSON structure: ${Gson().toJson(tokenJson).take(500)}")
                    val genesis = tokenJson["genesis"] as? Map<*, *>
                    val genesisData = genesis?.get("data") as? Map<*, *>
                    android.util.Log.d("TokenAdapter", "Genesis data: $genesisData")
                    val mintData = genesisData?.get("data")
                    android.util.Log.d("TokenAdapter", "Mint data type: ${mintData?.javaClass?.name}, value: $mintData")
                    
                    // Try to extract amount
                    val amount = extractAmountFromToken(tokenJson)
                    android.util.Log.d("TokenAdapter", "Extracted amount: $amount")
                    if (amount != null && tvAmount != null) {
                        tvAmount.visibility = View.VISIBLE
                        tvAmount.text = "Amount: $amount"
                    }
                    
                    // Try to extract data
                    val data = extractDataFromToken(tokenJson)
                    android.util.Log.d("TokenAdapter", "Extracted data: $data")
                    if (data != null && data.toString().isNotEmpty() && tvData != null) {
                        tvData.visibility = View.VISIBLE
                        tvData.text = "Data: $data"
                    } else {
                        tvData?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TokenAdapter", "Error parsing token JSON", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TokenAdapter", "Error showing token details", e)
        }
    }
    
    private fun extractDataFromToken(tokenJson: Map<*, *>): Any? {
        // Try different paths where custom data might be stored
        
        // Check in genesis transaction
        val genesis = tokenJson["genesis"] as? Map<*, *>
        val genesisData = genesis?.get("data") as? Map<*, *>
        val mintData = genesisData?.get("data")
        
        // If mintData is a Map (our new format), extract the data field
        if (mintData is Map<*, *>) {
            // Check for our structured data
            mintData["data"]?.let { 
                if (it.toString().isNotEmpty() && it.toString() != "null") return it 
            }
            
            // Check message field - this is where we store custom data
            val message = mintData["message"]
            if (message != null) {
                val messageStr = message.toString()
                // Check if it's base64 encoded
                try {
                    val decoded = android.util.Base64.decode(messageStr, android.util.Base64.DEFAULT)
                    val decodedStr = String(decoded, java.nio.charset.StandardCharsets.UTF_8)
                    if (decodedStr.isNotEmpty()) return decodedStr
                } catch (e: Exception) {
                    // Not base64, use as is
                    if (messageStr.isNotEmpty() && messageStr != "null") return messageStr
                }
            }
            
            // Other possible locations
            mintData["customData"]?.let { 
                if (it.toString().isNotEmpty() && it.toString() != "null") return it 
            }
            mintData["tokenData"]?.let { 
                if (it.toString().isNotEmpty() && it.toString() != "null") return it 
            }
        }
        
        return null
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
            
            // Try to show amount and data - simplified approach
            showTokenDetails(binding, token)
            
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
            
            binding.btnShare.setOnClickListener {
                onShareClick(token)
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