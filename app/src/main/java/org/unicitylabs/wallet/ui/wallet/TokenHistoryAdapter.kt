package org.unicitylabs.wallet.ui.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.model.AggregatedAsset
import java.text.SimpleDateFormat
import java.util.*

class TokenHistoryAdapter(
    private val tokens: List<Token>,
    private val aggregatedAssets: List<AggregatedAsset>,
    private val isSent: Boolean = false
) : RecyclerView.Adapter<TokenHistoryAdapter.HistoryViewHolder>() {

    private val aggregatedMap = aggregatedAssets.associateBy { it.coinId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(tokens[position])
    }

    override fun getItemCount(): Int = tokens.size

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(token: Token) {
            val dateFormatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            val date = dateFormatter.format(Date(token.timestamp))

            // Format amount with proper decimals
            val asset = token.coinId?.let { aggregatedMap[it] }
            val amount = if (asset != null) {
                val value = (token.amount ?: 0).toDouble() / Math.pow(10.0, asset.decimals.toDouble())
                String.format("%.${Math.min(asset.decimals, 8)}f", value).trimEnd('0').trimEnd('.')
            } else {
                (token.amount ?: 0).toString()
            }

            val action = if (isSent) "Sent" else "Received"
            text1.text = "$action $amount ${token.symbol}"
            text2.text = date
        }
    }
}
