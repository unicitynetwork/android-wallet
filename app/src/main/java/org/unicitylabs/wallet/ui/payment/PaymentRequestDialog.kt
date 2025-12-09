package org.unicitylabs.wallet.ui.payment

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.nostr.NostrSdkService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog for displaying and handling incoming payment requests.
 * Observes the payment requests StateFlow to update in real-time.
 */
class PaymentRequestDialog(
    private val context: Context,
    private val requestsFlow: StateFlow<List<NostrSdkService.IncomingPaymentRequest>>,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onAccept: (NostrSdkService.IncomingPaymentRequest) -> Unit,
    private val onReject: (NostrSdkService.IncomingPaymentRequest) -> Unit,
    private val onRejectAll: (() -> Unit)? = null,
    private val onClearProcessed: (() -> Unit)? = null,
    private val onDismiss: (() -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var adapter: PaymentRequestAdapter? = null
    private var recyclerView: RecyclerView? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    fun show() {
        val builder = AlertDialog.Builder(context)

        val requests = requestsFlow.value
        val pendingCount = requests.count { it.status == NostrSdkService.PaymentRequestStatus.PENDING }
        builder.setTitle("Payment Requests ($pendingCount pending)")

        if (requests.isEmpty()) {
            builder.setMessage("No pending payment requests")
            builder.setPositiveButton("OK") { d, _ -> d.dismiss() }
        } else {
            // Create adapter that will be updated when flow changes
            adapter = PaymentRequestAdapter(
                requests.toMutableList(),
                onAccept,
                onReject
            )

            // Create RecyclerView for list
            recyclerView = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@PaymentRequestDialog.adapter
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
            stopCountdownTimer()
            onDismiss?.invoke()
        }
        dialog?.show()

        // Start countdown timer to update every second
        startCountdownTimer()

        // Observe the flow and update adapter when requests change
        lifecycleScope.launch {
            requestsFlow.collect { updatedRequests ->
                adapter?.updateRequests(updatedRequests)
                // Update dialog title with new pending count
                val pendingCount = updatedRequests.count { it.status == NostrSdkService.PaymentRequestStatus.PENDING }
                dialog?.setTitle("Payment Requests ($pendingCount pending)")
            }
        }
    }

    /**
     * Start the countdown timer that updates every second.
     */
    private fun startCountdownTimer() {
        countdownRunnable = object : Runnable {
            override fun run() {
                // Notify adapter to refresh countdown displays
                adapter?.notifyDataSetChanged()
                // Schedule next update in 1 second
                countdownHandler.postDelayed(this, 1000)
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    /**
     * Stop the countdown timer.
     */
    private fun stopCountdownTimer() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    /**
     * Adapter for payment request list items.
     * Supports updating the list when the StateFlow emits new values.
     */
    private class PaymentRequestAdapter(
        private var requests: MutableList<NostrSdkService.IncomingPaymentRequest>,
        private val onAccept: (NostrSdkService.IncomingPaymentRequest) -> Unit,
        private val onReject: (NostrSdkService.IncomingPaymentRequest) -> Unit
    ) : RecyclerView.Adapter<PaymentRequestAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val amountText: TextView = view.findViewById(R.id.payment_request_amount)
            val messageText: TextView = view.findViewById(R.id.payment_request_message)
            val recipientText: TextView = view.findViewById(R.id.payment_request_recipient)
            val timeText: TextView = view.findViewById(R.id.payment_request_time)
            val countdownText: TextView = view.findViewById(R.id.payment_request_countdown)
            val statusText: TextView = view.findViewById(R.id.payment_request_status)
            val acceptButton: Button = view.findViewById(R.id.payment_request_accept)
            val rejectButton: Button = view.findViewById(R.id.payment_request_reject)
        }

        /**
         * Update the requests list and refresh the adapter.
         */
        fun updateRequests(newRequests: List<NostrSdkService.IncomingPaymentRequest>) {
            requests.clear()
            requests.addAll(newRequests)
            notifyDataSetChanged()
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

            // Check if expired (real-time check)
            val isExpiredNow = request.isExpired()
            val remainingMs = request.getRemainingTimeMs()

            // Countdown display for pending requests with deadline
            if (request.status == NostrSdkService.PaymentRequestStatus.PENDING && request.deadline != null) {
                if (isExpiredNow) {
                    holder.countdownText.visibility = View.GONE
                } else if (remainingMs != null && remainingMs > 0) {
                    holder.countdownText.visibility = View.VISIBLE
                    holder.countdownText.text = "Expires in: ${formatCountdown(remainingMs)}"
                    // Change color based on urgency
                    holder.countdownText.setTextColor(
                        when {
                            remainingMs < 10_000 -> 0xFFF44336.toInt()  // Red for < 10 seconds
                            remainingMs < 30_000 -> 0xFFFF9800.toInt() // Orange for < 30 seconds
                            remainingMs < 60_000 -> 0xFFFFC107.toInt() // Yellow/amber for < 1 minute
                            else -> 0xFF4CAF50.toInt()                  // Green for >= 1 minute
                        }
                    )
                } else {
                    holder.countdownText.visibility = View.GONE
                }
            } else {
                holder.countdownText.visibility = View.GONE
            }

            // Status
            when {
                // Check expiration first, even if status is PENDING
                isExpiredNow && request.status == NostrSdkService.PaymentRequestStatus.PENDING -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Expired"
                    holder.statusText.setTextColor(0xFF9E9E9E.toInt())  // Gray
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                }
                request.status == NostrSdkService.PaymentRequestStatus.PENDING -> {
                    holder.statusText.visibility = View.GONE
                    holder.acceptButton.isEnabled = true
                    holder.rejectButton.isEnabled = true
                }
                request.status == NostrSdkService.PaymentRequestStatus.ACCEPTED -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Accepted"
                    holder.statusText.setTextColor(0xFF4CAF50.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                }
                request.status == NostrSdkService.PaymentRequestStatus.REJECTED -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Rejected"
                    holder.statusText.setTextColor(0xFFF44336.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                }
                request.status == NostrSdkService.PaymentRequestStatus.PAID -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Paid"
                    holder.statusText.setTextColor(0xFF2196F3.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                }
                request.status == NostrSdkService.PaymentRequestStatus.EXPIRED -> {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Expired"
                    holder.statusText.setTextColor(0xFF9E9E9E.toInt())  // Gray
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                }
            }

            // Button handlers
            holder.acceptButton.setOnClickListener {
                // Double-check expiration before accepting
                if (request.isExpired()) {
                    holder.statusText.visibility = View.VISIBLE
                    holder.statusText.text = "Expired"
                    holder.statusText.setTextColor(0xFF9E9E9E.toInt())
                    holder.acceptButton.isEnabled = false
                    holder.rejectButton.isEnabled = false
                    holder.countdownText.visibility = View.GONE
                } else {
                    onAccept(request)
                }
            }

            holder.rejectButton.setOnClickListener {
                onReject(request)
            }
        }

        override fun getItemCount() = requests.size

        /**
         * Format countdown time in mm:ss or h:mm:ss format.
         */
        private fun formatCountdown(milliseconds: Long): String {
            val totalSeconds = milliseconds / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

        private fun formatAmount(amount: java.math.BigInteger, symbol: String): String {
            // Convert from smallest units to display units
            val divisor = when (symbol.uppercase()) {
                "SOL" -> java.math.BigDecimal("1000000000")  // 9 decimals
                "BTC" -> java.math.BigDecimal("100000000")   // 8 decimals (satoshis)
                else -> java.math.BigDecimal("1000000")      // Default 6 decimals
            }
            val displayAmount = java.math.BigDecimal(amount).divide(divisor, 8, java.math.RoundingMode.HALF_UP)
            return displayAmount.stripTrailingZeros().toPlainString()
        }
    }
}
