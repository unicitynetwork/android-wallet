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
    private val transactionEvents: List<org.unicitylabs.wallet.model.TransactionEvent>,
    private val aggregatedAssets: List<AggregatedAsset>
) : RecyclerView.Adapter<TokenHistoryAdapter.HistoryViewHolder>() {

    private val aggregatedMap = aggregatedAssets.associateBy { it.coinId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(transactionEvents[position])
    }

    override fun getItemCount(): Int = transactionEvents.size

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: TextView = itemView.findViewById(android.R.id.text1)
        private val text2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(event: org.unicitylabs.wallet.model.TransactionEvent) {
            val token = event.token
            val dateFormatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            val date = dateFormatter.format(Date(token.timestamp))

            // Format amount with proper decimals
            val asset = token.coinId?.let { aggregatedMap[it] }
            val decimals = asset?.decimals ?: token.coinId?.let { coinIdHex ->
                try {
                    val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(itemView.context)
                    registry.getCoinDefinition(coinIdHex)?.decimals
                } catch (e: Exception) {
                    null
                }
            } ?: 0

            val amount = token.getAmountAsBigInteger()?.let { bigIntAmount ->
                val divisor = Math.pow(10.0, decimals.toDouble())
                val value = bigIntAmount.toDouble() / divisor
                String.format("%.${Math.min(decimals, 8)}f", value).trimEnd('0').trimEnd('.')
            } ?: "0"

            // Use the transaction type from the event
            val action = when (event.type) {
                org.unicitylabs.wallet.model.TransactionType.SENT -> "Sent"
                org.unicitylabs.wallet.model.TransactionType.RECEIVED -> "Received"
            }
            text1.text = "$action $amount ${token.symbol}"
            text2.text = date
        }
    }
}
