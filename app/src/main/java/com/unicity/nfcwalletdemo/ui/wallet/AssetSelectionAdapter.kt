package com.unicity.nfcwalletdemo.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.model.CryptoCurrency

class AssetSelectionAdapter(
    private val assets: List<CryptoCurrency>,
    private val onAssetSelected: (CryptoCurrency) -> Unit
) : RecyclerView.Adapter<AssetSelectionAdapter.AssetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset_selection, parent, false)
        return AssetViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        holder.bind(assets[position])
    }

    override fun getItemCount() = assets.size

    inner class AssetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val assetIcon: ImageView = itemView.findViewById(R.id.assetIcon)
        private val assetName: TextView = itemView.findViewById(R.id.assetName)
        private val assetSymbol: TextView = itemView.findViewById(R.id.assetSymbol)

        fun bind(crypto: CryptoCurrency) {
            assetIcon.setImageResource(crypto.iconResId)
            assetName.text = crypto.name
            assetSymbol.text = crypto.symbol

            itemView.setOnClickListener {
                onAssetSelected(crypto)
            }
        }
    }
}