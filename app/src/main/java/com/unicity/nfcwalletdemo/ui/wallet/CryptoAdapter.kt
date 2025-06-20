package com.unicity.nfcwalletdemo.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.model.CryptoCurrency

class CryptoAdapter(
    private val onItemClick: (CryptoCurrency) -> Unit,
    private val currency: String = "USD"
) : ListAdapter<CryptoCurrency, CryptoAdapter.CryptoViewHolder>(CryptoDiffCallback()) {

    fun updateCurrency(newCurrency: String) {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CryptoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crypto, parent, false)
        return CryptoViewHolder(view, currency)
    }

    override fun onBindViewHolder(holder: CryptoViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class CryptoViewHolder(
        itemView: View,
        private var currency: String
    ) : RecyclerView.ViewHolder(itemView) {
        private val cryptoIcon: ImageView = itemView.findViewById(R.id.cryptoIcon)
        private val cryptoName: TextView = itemView.findViewById(R.id.cryptoName)
        private val cryptoBalance: TextView = itemView.findViewById(R.id.cryptoBalance)
        private val cryptoTotalValue: TextView = itemView.findViewById(R.id.cryptoTotalValue)
        private val cryptoChange: TextView = itemView.findViewById(R.id.cryptoChange)

        fun bind(crypto: CryptoCurrency, onItemClick: (CryptoCurrency) -> Unit) {
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
            
            itemView.setOnClickListener { onItemClick(crypto) }
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