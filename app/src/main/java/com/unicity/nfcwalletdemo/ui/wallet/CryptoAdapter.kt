package com.unicity.nfcwalletdemo.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.model.CryptoCurrency

class CryptoAdapter(
    private val onSendClick: (CryptoCurrency) -> Unit,
    private val currency: String = "USD"
) : ListAdapter<CryptoCurrency, CryptoAdapter.CryptoViewHolder>(CryptoDiffCallback()) {

    private var expandedItemId: String? = null

    fun updateCurrency(newCurrency: String) {
        // Trigger data refresh to update currency display
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CryptoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crypto, parent, false)
        return CryptoViewHolder(view, currency)
    }

    override fun onBindViewHolder(holder: CryptoViewHolder, position: Int) {
        val crypto = getItem(position)
        val isExpanded = expandedItemId == crypto.id
        holder.bind(crypto, isExpanded, onSendClick) { clickedCrypto ->
            val previousExpanded = expandedItemId
            expandedItemId = if (expandedItemId == clickedCrypto.id) null else clickedCrypto.id
            
            // Notify changes for smooth animation
            if (previousExpanded != null) {
                val previousIndex = currentList.indexOfFirst { it.id == previousExpanded }
                if (previousIndex != -1) notifyItemChanged(previousIndex)
            }
            notifyItemChanged(position)
        }
    }

    class CryptoViewHolder(
        itemView: View,
        private var currency: String
    ) : RecyclerView.ViewHolder(itemView) {
        private val layoutCollapsed: LinearLayout = itemView.findViewById(R.id.layoutCollapsed)
        private val layoutExpanded: LinearLayout = itemView.findViewById(R.id.layoutExpanded)
        private val ivExpandIcon: ImageView = itemView.findViewById(R.id.ivExpandIcon)
        
        private val cryptoIcon: ImageView = itemView.findViewById(R.id.cryptoIcon)
        private val cryptoName: TextView = itemView.findViewById(R.id.cryptoName)
        private val cryptoBalance: TextView = itemView.findViewById(R.id.cryptoBalance)
        private val cryptoTotalValue: TextView = itemView.findViewById(R.id.cryptoTotalValue)
        private val cryptoChange: TextView = itemView.findViewById(R.id.cryptoChange)
        
        private val cryptoUnitPrice: TextView = itemView.findViewById(R.id.cryptoUnitPrice)
        private val cryptoMarketCap: TextView = itemView.findViewById(R.id.cryptoMarketCap)
        private val cryptoVolume: TextView = itemView.findViewById(R.id.cryptoVolume)
        private val btnSendCrypto: MaterialButton = itemView.findViewById(R.id.btnSendCrypto)

        fun bind(
            crypto: CryptoCurrency, 
            isExpanded: Boolean, 
            onSendClick: (CryptoCurrency) -> Unit,
            onToggleExpand: (CryptoCurrency) -> Unit
        ) {
            // Log what's being displayed in UI
            android.util.Log.d("CryptoAdapter", "Binding ${crypto.symbol}: balance=${crypto.balance} formatted=${crypto.getFormattedBalance()}")
            
            // Basic info
            cryptoIcon.setImageResource(crypto.iconResId)
            cryptoName.text = crypto.name
            cryptoBalance.text = "${crypto.getFormattedBalance()} ${crypto.symbol}"
            cryptoTotalValue.text = crypto.getFormattedBalanceInFiat(currency)
            cryptoChange.text = crypto.getFormattedChange()
            
            // Set change color
            val changeColor = if (crypto.change24h >= 0) {
                itemView.context.getColor(R.color.green_positive)
            } else {
                itemView.context.getColor(R.color.red_negative)
            }
            cryptoChange.setTextColor(changeColor)
            
            // Expanded details
            cryptoUnitPrice.text = "Price: ${crypto.getFormattedPrice(currency)}"
            cryptoMarketCap.text = "Market Cap: ${getFormattedMarketCap(crypto)}"
            cryptoVolume.text = "24h Volume: ${getFormatted24hVolume(crypto)}"
            
            // Expansion state
            layoutExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivExpandIcon.rotation = if (isExpanded) 90f else 0f
            
            // Click handlers
            layoutCollapsed.setOnClickListener { onToggleExpand(crypto) }
            btnSendCrypto.setOnClickListener { onSendClick(crypto) }
        }
        
        private fun getFormattedMarketCap(crypto: CryptoCurrency): String {
            // Simulate market cap data
            val marketCap = when (crypto.symbol) {
                "BTC" -> 847.2
                "ETH" -> 330.8
                "USDT" -> 120.4
                "SUB" -> 5.2
                else -> 0.0
            }
            return "$${String.format("%.1f", marketCap)}B"
        }
        
        private fun getFormatted24hVolume(crypto: CryptoCurrency): String {
            // Simulate volume data
            val volume = when (crypto.symbol) {
                "BTC" -> 28.4
                "ETH" -> 15.2
                "USDT" -> 45.8
                "SUB" -> 2.1
                else -> 0.0
            }
            return "$${String.format("%.1f", volume)}B"
        }
    }

    class CryptoDiffCallback : DiffUtil.ItemCallback<CryptoCurrency>() {
        override fun areItemsTheSame(oldItem: CryptoCurrency, newItem: CryptoCurrency): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CryptoCurrency, newItem: CryptoCurrency): Boolean {
            return oldItem == newItem
        }
    }
}