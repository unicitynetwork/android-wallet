package com.unicity.nfcwalletdemo.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.model.CryptoCurrency

class AssetDialogAdapter(
    private val assets: List<CryptoCurrency>,
    private val onAssetClick: (CryptoCurrency) -> Unit
) : RecyclerView.Adapter<AssetDialogAdapter.AssetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset_dialog, parent, false)
        return AssetViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
        holder.bind(assets[position], onAssetClick)
    }

    override fun getItemCount(): Int = assets.size

    class AssetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val assetIcon: ImageView = itemView.findViewById(R.id.assetIcon)
        private val assetName: TextView = itemView.findViewById(R.id.assetName)
        private val assetSymbol: TextView = itemView.findViewById(R.id.assetSymbol)

        fun bind(asset: CryptoCurrency, onAssetClick: (CryptoCurrency) -> Unit) {
            assetIcon.setImageResource(asset.iconResId)
            assetName.text = asset.name
            assetSymbol.text = asset.symbol

            itemView.setOnClickListener {
                onAssetClick(asset)
            }
        }
    }
}