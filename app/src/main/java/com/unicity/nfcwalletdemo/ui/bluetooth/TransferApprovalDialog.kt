package com.unicity.nfcwalletdemo.ui.bluetooth

import android.app.Dialog
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.bluetooth.TransferApprovalRequest

/**
 * Dialog shown to recipient when someone wants to send them a token
 */
class TransferApprovalDialog : DialogFragment() {
    
    interface ApprovalListener {
        fun onApproved(transferId: String)
        fun onRejected(transferId: String)
    }
    
    private lateinit var approvalRequest: TransferApprovalRequest
    private var listener: ApprovalListener? = null
    private var countDownTimer: CountDownTimer? = null
    
    fun setApprovalRequest(request: TransferApprovalRequest) {
        this.approvalRequest = request
    }
    
    fun setListener(listener: ApprovalListener) {
        this.listener = listener
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_transfer_approval, null)
        
        val senderNameText = view.findViewById<TextView>(R.id.senderName)
        val tokenInfoText = view.findViewById<TextView>(R.id.tokenInfo)
        val timerText = view.findViewById<TextView>(R.id.timerText)
        val timerProgress = view.findViewById<ProgressBar>(R.id.timerProgress)
        val acceptButton = view.findViewById<Button>(R.id.acceptButton)
        val rejectButton = view.findViewById<Button>(R.id.rejectButton)
        
        senderNameText.text = approvalRequest.senderName
        tokenInfoText.text = approvalRequest.tokenPreview
        
        // Start countdown timer (30 seconds)
        var secondsRemaining = 30
        timerProgress.max = 30
        timerProgress.progress = 30
        
        countDownTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsRemaining = (millisUntilFinished / 1000).toInt()
                timerText.text = "${secondsRemaining}s"
                timerProgress.progress = secondsRemaining
            }
            
            override fun onFinish() {
                // Auto-reject on timeout
                listener?.onRejected(approvalRequest.transferId)
                dismiss()
            }
        }.start()
        
        acceptButton.setOnClickListener {
            countDownTimer?.cancel()
            listener?.onApproved(approvalRequest.transferId)
            dismiss()
        }
        
        rejectButton.setOnClickListener {
            countDownTimer?.cancel()
            listener?.onRejected(approvalRequest.transferId)
            dismiss()
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Incoming Token Transfer")
            .setView(view)
            .setCancelable(false)
            .create()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
    }
}