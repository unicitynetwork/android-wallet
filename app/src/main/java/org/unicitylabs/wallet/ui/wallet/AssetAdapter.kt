package org.unicitylabs.wallet.ui.wallet

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
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.model.AggregatedAsset

/**
 * Adapter for displaying aggregated Unicity coin assets
 * Shows coins grouped by coinId with total amounts and fiat values
 */
class AssetAdapter(
    private val onSendClick: (AggregatedAsset) -> Unit,
    private var currency: String = "USD"
) : ListAdapter<AggregatedAsset, AssetAdapter.AssetViewHolder>(AssetDiffCallback()) {

    private var expandedItemId: String? = null

    fun updateCurrency(newCurrency: String) {
        currency = newCurrency
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crypto, parent, false) // Reuse crypto item layout
        return AssetViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        val asset = getItem(position)
        val isExpanded = expandedItemId == asset.coinId
        holder.bind(asset, isExpanded, currency, onSendClick) { clickedAsset ->
            val previousExpanded = expandedItemId
            expandedItemId = if (expandedItemId == clickedAsset.coinId) null else clickedAsset.coinId

            // Notify changes for smooth animation
            if (previousExpanded != null) {
                val previousIndex = currentList.indexOfFirst { it.coinId == previousExpanded }
                if (previousIndex != -1) notifyItemChanged(previousIndex)
            }
            notifyItemChanged(position)
        }
    }

    class AssetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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
            asset: AggregatedAsset,
            isExpanded: Boolean,
            currency: String,
            onSendClick: (AggregatedAsset) -> Unit,
            onToggleExpand: (AggregatedAsset) -> Unit
        ) {
            // Load icon from URL if available
            if (asset.iconUrl != null) {
                val iconManager = org.unicitylabs.wallet.util.IconCacheManager.getInstance(itemView.context)
                iconManager.loadIcon(
                    url = asset.iconUrl,
                    imageView = cryptoIcon,
                    placeholder = R.drawable.ic_coin_placeholder,
                    error = R.drawable.ic_coin_placeholder
                )
            } else {
                cryptoIcon.setImageResource(R.drawable.ic_coin_placeholder)
            }

            // Capitalize first letter of name
            cryptoName.text = (asset.name ?: asset.symbol).replaceFirstChar { it.uppercase() }

            // Format as "SYMBOL · amount"
            cryptoBalance.text = "${asset.symbol} · ${asset.getFormattedAmount()}"

            // Show fiat value
            cryptoTotalValue.text = asset.getFormattedFiatValue(currency)

            // Show price change
            cryptoChange.text = asset.getFormattedChange()
            cryptoChange.visibility = View.VISIBLE

            // Set change color
            val changeColor = if (asset.change24h >= 0) {
                itemView.context.getColor(R.color.green_positive)
            } else {
                itemView.context.getColor(R.color.red_negative)
            }
            cryptoChange.setTextColor(changeColor)

            // Expanded details
            val currencySymbol = if (currency == "EUR") "€" else "$"
            val unitPrice = if (currency == "EUR") asset.priceEur else asset.priceUsd
            cryptoUnitPrice.text = "Price: $currencySymbol${String.format("%,.2f", unitPrice)}"
            cryptoMarketCap.text = "Coin ID: ${asset.coinId.take(16)}..."
            cryptoVolume.text = "From ${asset.tokenCount} token${if (asset.tokenCount != 1) "s" else ""}"

            // Expansion state
            layoutExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivExpandIcon.rotation = if (isExpanded) 90f else 0f

            // Click handlers
            layoutCollapsed.setOnClickListener { onToggleExpand(asset) }
            btnSendCrypto.setOnClickListener { onSendClick(asset) }
        }
    }

    class AssetDiffCallback : DiffUtil.ItemCallback<AggregatedAsset>() {
        override fun areItemsTheSame(oldItem: AggregatedAsset, newItem: AggregatedAsset): Boolean {
            return oldItem.coinId == newItem.coinId
        }

        override fun areContentsTheSame(oldItem: AggregatedAsset, newItem: AggregatedAsset): Boolean {
            return oldItem == newItem
        }
    }
}
