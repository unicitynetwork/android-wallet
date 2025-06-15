package com.unicity.nfcwalletdemo.ui.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.databinding.ItemTokenBinding

class TokenAdapter(
    private val onItemClick: (Token) -> Unit
) : ListAdapter<Token, TokenAdapter.TokenViewHolder>(TokenDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val binding = ItemTokenBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TokenViewHolder(binding, onItemClick)
    }
    
    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class TokenViewHolder(
        private val binding: ItemTokenBinding,
        private val onItemClick: (Token) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(token: Token) {
            binding.tvTokenName.text = token.name
            binding.tvTokenId.text = "Token ID: ${token.id.take(8)}..."
            
            binding.root.setOnClickListener {
                onItemClick(token)
            }
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