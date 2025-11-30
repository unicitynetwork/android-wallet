package org.unicitylabs.wallet.ui.payment

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.nostr.NostrSdkService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog for displaying and handling incoming payment requests.
 */
class PaymentRequestDialog(
    private val context: Context,
    private val requests: List<NostrSdkService.IncomingPaymentRequest>,
    private val onAccept: (NostrSdkService.IncomingPaymentRequest) -> Unit,
    private val onReject: (NostrSdkService.IncomingPaymentRequest) -> Unit,
    private val onRejectAll: (() -> Unit)? = null,
    private val onClearProcessed: (() -> Unit)? = null,
    private val onDismiss: (() -> Unit)? = null
) {
    private var dialog: AlertDialog? = null

    fun show() {
        val builder = AlertDialog.Builder(context)

        val pendingCount = requests.count { it.status == NostrSdkService.PaymentRequestStatus.PENDING }
        builder.setTitle("Payment Requests ($pendingCount pending)")

        if (requests.isEmpty()) {
            builder.setMessage("No pending payment requests")
            builder.setPositiveButton("OK") { d, _ -> d.dismiss() }
        } else {
            // Create RecyclerView for list
            val recyclerView = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = PaymentRequestAdapter(requests, onAccept, onReject) {
                    // Dismiss dialog when action taken
                    dialog?.dismiss()
                }
                setPadding(16, 16, 16, 16)
            }

            builder.setView(recyclerView)

            // Add "Reject All" button if there are pending requests
            val hasPending = requests.any { it.status == NostrSdkService.PaymentRequestStatus.PENDING }
            if (hasPending && onRejectAll != null) {
                builder.setPositiveButton("Reject All") { _, _ ->
                    onRejectAll.invoke()
                }
            }

            // Add "Clear Processed" button if there are any processed requests
            val hasProcessed = requests.any { it.status != NostrSdkService.PaymentRequestStatus.PENDING }
            if (hasProcessed && onClearProcessed != null) {
                builder.setNeutralButton("Clear Processed") { _, _ ->
                    onClearProcessed.invoke()
                }
            }

            builder.setNegativeButton("Close") { d, _ -> d.dismiss() }
        }

        dialog = builder.create()
        dialog?.setOnDismissListener {
            onDismiss?.invoke()
        }
        dialog?.show()
    }

    /**
     * Adapter for payment request list items.
     */
    private class PaymentRequestAdapter(
        private val requests: List<NostrSdkService.IncomingPaymentRequest>,
        private val onAccept: (NostrSdkService.IncomingPaymentRequest) -> Unit,
        private val onReject: (NostrSdkService.IncomingPaymentRequest) -> Unit,
        private val onActionTaken: () -> Unit
    ) : RecyclerView.Adapter<PaymentRequestAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val amountText: TextView = view.findViewById(R.id.payment_request_amount)
            val messageText: TextView = view.findViewById(R.id.payment_request_message)
            val recipientText: TextView = view.findViewById(R.id.payment_request_recipient)
            val timeText: TextView = view.findViewById(R.id.payment_request_time)
            val statusText: TextView = view.findViewById(R.id.payment_request_status)
            val acceptButton: Button = view.findViewById(R.id.payment_request_accept)
            val rejectButton: Button = view.findViewById(R.id.payment_request_reject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_payment_request, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = requests[position]

            // Format amount (convert from smallest units)
            val displayAmount = formatAmount(request.amount, request.symbol)
            holder.amountText.text = "$displayAmount ${request.symbol}"

            // Message
            holder.messageText.text = request.message ?: "No message"
            holder.messageText.visibility = if (request.message.isNullOrEmpty()) View.GONE else View.VISIBLE

            // Recipient (who is requesting payment)
            holder.recipientText.text = "Pay to: @${request.recipientNametag}"

            // Time
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            holder.timeText.text = dateFormat.format(Date(request.timestamp))

            // Status
            when (request.status) {
                NostrSdkService.PaymentRequestStatus.PENDING -> {
                    holder.statusText.visibility = View.GONE
                    holder.acceptButton.isEnabled = true
                    holder.rejectButton.isEnabled = true
                }
                NostrSdkService.PaymentRequestStatus.ACCEPTED -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Accepted"
                    holder.statusText.setTextColor(0xFF4CAF50.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                }
                NostrSdkService.PaymentRequestStatus.REJECTED -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Rejected"
                    holder.statusText.setTextColor(0xFFF44336.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                }
                NostrSdkService.PaymentRequestStatus.PAID -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Paid"
                    holder.statusText.setTextColor(0xFF2196F3.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                }
            }

            // Button handlers
            holder.acceptButton.setOnClickListener {
                onAccept(request)
                onActionTaken()
            }

            holder.rejectButton.setOnClickListener {
                onReject(request)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = requests.size

        private fun formatAmount(amount: Long, symbol: String): String {
            // Convert from smallest units to display units
            val divisor = when (symbol.uppercase()) {
                "SOL" -> 1_000_000_000.0  // 9 decimals
                "BTC" -> 100_000_000.0    // 8 decimals (satoshis)
                else -> 1_000_000.0       // Default 6 decimals
            }
            val displayAmount = amount / divisor
            return String.format("%.6f", displayAmount).trimEnd('0').trimEnd('.')
        }
    }
}
