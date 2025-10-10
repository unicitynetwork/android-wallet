package org.unicitylabs.wallet.ui.receive

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.token.TokenDefinition

/**
 * Adapter for selecting tokens from the registry (for receive QR generation)
 * Styled consistently with AssetSelectionAdapter from Send feature
 */
class TokenDefinitionAdapter(
    private val tokens: List<TokenDefinition>,
    private val onTokenSelected: (TokenDefinition) -> Unit
) : RecyclerView.Adapter<TokenDefinitionAdapter.TokenViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset_selection, parent, false)
        return TokenViewHolder(view)
    }

    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        holder.bind(tokens[position])
    }

    override fun getItemCount() = tokens.size

    inner class TokenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val assetIcon: ImageView = itemView.findViewById(R.id.assetIcon)
        private val assetName: TextView = itemView.findViewById(R.id.assetName)
        private val assetSymbol: TextView = itemView.findViewById(R.id.assetSymbol)

        fun bind(token: TokenDefinition) {
            // Load icon from URL using IconCacheManager
            val iconUrl = token.getIconUrl()
            if (iconUrl != null) {
                val iconManager = org.unicitylabs.wallet.util.IconCacheManager.getInstance(itemView.context)
                iconManager.loadIcon(
                    url = iconUrl,
                    imageView = assetIcon,
                    placeholder = R.drawable.ic_coin_placeholder,
                    error = R.drawable.ic_coin_placeholder
                )
            } else {
                assetIcon.setImageResource(R.drawable.ic_coin_placeholder)
            }

            assetName.text = token.name
            assetSymbol.text = token.symbol ?: token.id.take(4)

            itemView.setOnClickListener {
                onTokenSelected(token)
            }
        }
    }
}
