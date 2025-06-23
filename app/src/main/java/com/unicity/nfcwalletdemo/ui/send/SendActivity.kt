package com.unicity.nfcwalletdemo.ui.send

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivitySendBinding
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel

class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private val viewModel: WalletViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupTokenSelector()
        setupRecipientSelector()
        observeViewModel()
    }
    
    private fun setupUI() {
        binding.btnClose.setOnClickListener {
            finish()
        }
        
        binding.btnSend.setOnClickListener {
            // TODO: Implement transfer preparation
            Toast.makeText(this, "Transfer preparation not implemented yet", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupTokenSelector() {
        val tokenNames = listOf("Bitcoin", "Ethereum", "eNaira", "eFranc")
        val tokenAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tokenNames)
        binding.tokenSelector.setAdapter(tokenAdapter)
        
        binding.tokenSelector.setOnItemClickListener { _, _, position, _ ->
            val selectedToken = tokenNames[position]
            updateAvailableBalance(selectedToken)
        }
    }
    
    private fun setupRecipientSelector() {
        val recipients = listOf("Contact 1", "Contact 2", "Contact 3", "Add new recipient...")
        val recipientAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, recipients)
        binding.recipientSelector.setAdapter(recipientAdapter)
    }
    
    private fun updateAvailableBalance(tokenName: String) {
        // TODO: Get actual balance from viewModel
        when (tokenName) {
            "Bitcoin" -> binding.availableBalanceText.text = "Available: 0.025 BTC"
            "Ethereum" -> binding.availableBalanceText.text = "Available: 1.5 ETH" 
            "eNaira" -> binding.availableBalanceText.text = "Available: 50,000 NGN"
            "eFranc" -> binding.availableBalanceText.text = "Available: 25,000 XAF"
            else -> binding.availableBalanceText.text = "Available balance will be shown when token is selected"
        }
    }
    
    private fun observeViewModel() {
        // TODO: Observe cryptocurrency data from viewModel
    }
}