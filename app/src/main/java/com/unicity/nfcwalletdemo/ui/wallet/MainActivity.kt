package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Intent
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivityMainBinding
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.model.CryptoCurrency
import com.unicity.nfcwalletdemo.nfc.DirectNfcClient
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenAdapter: TokenAdapter
    private lateinit var cryptoAdapter: CryptoAdapter
    private var nfcAdapter: NfcAdapter? = null
    private var currentTransferringToken: Token? = null
    private var currentTransferringCrypto: CryptoCurrency? = null
    private var currentTab = 0 // 0 for Assets, 1 for NFTs
    private var selectedCurrency = "USD"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupActionBar()
        setupNfc()
        setupUI()
        setupRecyclerView()
        setupTabLayout()
        observeViewModel()
        
        // Load initial demo cryptocurrencies
        viewModel.loadDemoCryptocurrencies()
    }
    
    private fun setupActionBar() {
        supportActionBar?.hide()
    }
    
    private fun setupUI() {
        // Setup bottom navigation
        binding.navUnicity.setOnClickListener {
            Toast.makeText(this, "Unicity selected", Toast.LENGTH_SHORT).show()
        }
        
        binding.navTawasal.setOnClickListener {
            Toast.makeText(this, "Tawasal selected", Toast.LENGTH_SHORT).show()
        }
        
        binding.navSphere.setOnClickListener {
            Toast.makeText(this, "Sphere selected", Toast.LENGTH_SHORT).show()
        }
        
        binding.navSettings.setOnClickListener {
            showSettingsDialog()
        }
        
        // Setup action buttons
        binding.sendButton.setOnClickListener {
            if (currentTab == 0) {
                // For crypto assets
                val cryptos = viewModel.cryptocurrencies.value
                if (cryptos.isNotEmpty()) {
                    showCryptoSendDialog()
                } else {
                    Toast.makeText(this, "No assets to send", Toast.LENGTH_SHORT).show()
                }
            } else {
                // For NFTs/Tokens
                val tokens = viewModel.tokens.value
                if (tokens.isNotEmpty()) {
                    Toast.makeText(this, "Select a token from the list to send", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No tokens to send", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        binding.receiveButton.setOnClickListener {
            Toast.makeText(this, "Receive feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.swapButton.setOnClickListener {
            Toast.makeText(this, "Swap feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        // Setup currency selector
        binding.currencySelector.setOnClickListener {
            showCurrencyDialog()
        }
        
        updateBalanceDisplay()
    }
    
    private fun setupRecyclerView() {
        tokenAdapter = TokenAdapter(
            onSendClick = { token ->
                checkNfc {
                    startTokenTransfer(token)
                }
            },
            onCancelClick = { token ->
                cancelTokenTransfer(token)
            }
        )
        
        cryptoAdapter = CryptoAdapter(
            onSendClick = { crypto ->
                showCryptoSendAmountDialog(crypto)
            },
            currency = selectedCurrency
        )
        
        binding.rvTokens.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = cryptoAdapter // Start with crypto adapter
        }
    }
    
    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateListDisplay()
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Select first tab
        binding.tabLayout.getTabAt(0)?.select()
    }
    
    private fun updateListDisplay() {
        if (currentTab == 0) {
            // Show crypto assets
            binding.rvTokens.adapter = cryptoAdapter
            val cryptos = viewModel.cryptocurrencies.value
            cryptoAdapter.submitList(cryptos)
            binding.emptyStateContainer.visibility = if (cryptos.isEmpty()) View.VISIBLE else View.GONE
        } else {
            // Show NFTs/Tokens
            binding.rvTokens.adapter = tokenAdapter
            val tokens = viewModel.tokens.value
            tokenAdapter.submitList(tokens)
            binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    private fun updateBalanceDisplay() {
        val cryptos = viewModel.cryptocurrencies.value
        val totalBalance = cryptos.sumOf { it.getBalanceInFiat(selectedCurrency) }
        
        val symbol = if (selectedCurrency == "EUR") "€" else "$"
        binding.balanceAmount.text = "$symbol${String.format("%,.2f", totalBalance)}"
        binding.selectedCurrency.text = selectedCurrency
        
        // Calculate 24h change
        val totalPreviousBalance = cryptos.sumOf { 
            val previousPrice = when (selectedCurrency) {
                "EUR" -> it.priceEur / (1 + it.change24h / 100)
                else -> it.priceUsd / (1 + it.change24h / 100)
            }
            it.balance * previousPrice
        }
        
        val changePercent = if (totalPreviousBalance > 0) {
            ((totalBalance - totalPreviousBalance) / totalPreviousBalance) * 100
        } else 0.0
        
        val changeAmount = totalBalance - totalPreviousBalance
        val changeSign = if (changePercent >= 0) "+" else ""
        
        binding.balanceChange.text = "$changeSign${String.format("%.2f", changePercent)}% ($changeSign$symbol${String.format("%.2f", changeAmount)})"
        binding.balanceChange.setTextColor(
            if (changePercent >= 0) getColor(R.color.green_positive) else getColor(R.color.red_negative)
        )
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.tokens.collect { tokens ->
                if (currentTab == 1) {
                    tokenAdapter.submitList(tokens)
                    binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.cryptocurrencies.collect { cryptos ->
                if (currentTab == 0) {
                    cryptoAdapter.submitList(cryptos)
                    binding.emptyStateContainer.visibility = if (cryptos.isEmpty()) View.VISIBLE else View.GONE
                }
                updateBalanceDisplay()
            }
        }
        
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                // Remove swipe refresh as it's not in the new layout
            }
        }
        
        lifecycleScope.launch {
            viewModel.mintResult.collect { result ->
                result?.let {
                    if (it.isSuccess) {
                        Toast.makeText(this@MainActivity, "Token minted successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to mint token: ${it.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                    viewModel.clearMintResult()
                }
            }
        }
    }
    
    private fun refreshWallet() {
        // Refresh the tokens from repository
        lifecycleScope.launch {
            viewModel.refreshTokens()
        }
        viewModel.loadDemoCryptocurrencies()
    }
    
    private fun startTokenTransfer(token: Token) {
        currentTransferringToken = token
        tokenAdapter.setTransferring(token, true)
        viewModel.selectToken(token)
        
        Log.d("MainActivity", "Starting NFC transfer for token: ${token.name}")
        Toast.makeText(this, "Tap phones together to transfer token", Toast.LENGTH_SHORT).show()
        
        val directNfcClient = DirectNfcClient(
            sdkService = viewModel.getSdkService(),
            onTransferComplete = {
                Log.d("MainActivity", "✅ NFC transfer completed")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    viewModel.removeToken(token.id)
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Token sent successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "NFC transfer error: $error")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Transfer failed: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onProgress = { current, total ->
                Log.d("MainActivity", "NFC progress: $current/$total chunks")
                runOnUiThread {
                    if (total > 1) {
                        // Update the token's transfer status in the adapter to show progress
                        tokenAdapter.updateTransferProgress(token, current, total)
                    }
                }
            }
        )
        
        directNfcClient.setTokenToSend(token)
        
        // Enable NFC reader mode for direct transfer
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        try {
            nfcAdapter!!.enableReaderMode(this, directNfcClient, flags, null)
            Log.d("MainActivity", "NFC reader mode enabled for transfer")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enable NFC reader mode", e)
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            Toast.makeText(this, "Failed to enable NFC: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun cancelTokenTransfer(token: Token) {
        tokenAdapter.setTransferring(token, false)
        currentTransferringToken = null
        disableNfcTransfer()
    }
    
    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e("MainActivity", "NFC not available on this device")
        } else if (!nfcAdapter!!.isEnabled) {
            Log.w("MainActivity", "NFC is disabled")
        }
    }
    
    private fun disableNfcTransfer() {
        try {
            nfcAdapter?.disableReaderMode(this)
            Log.d("MainActivity", "NFC reader mode disabled")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error disabling NFC reader mode", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh wallet when returning to MainActivity
        refreshWallet()
    }
    
    override fun onPause() {
        super.onPause()
        // Only disable NFC if we're actually pausing (not just configuration change)
        if (isFinishing || isChangingConfigurations.not()) {
            currentTransferringToken?.let { token ->
                cancelTokenTransfer(token)
            }
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("MainActivity", "Configuration changed - maintaining transfer state")
    }
    
    private fun checkNfc(onSuccess: () -> Unit) {
        when {
            !PermissionUtils.isNfcEnabled(this) -> {
                AlertDialog.Builder(this)
                    .setTitle("NFC Required")
                    .setMessage("Please enable NFC to transfer tokens")
                    .setPositiveButton("Settings") { _, _ ->
                        PermissionUtils.openNfcSettings(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                onSuccess()
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(com.unicity.nfcwalletdemo.R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.unicity.nfcwalletdemo.R.id.action_mint_token -> {
                showMintTokenDialog()
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_reset_wallet -> {
                showResetWalletDialog()
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_test_transfer -> {
                runAutomatedTransferTest()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showMintTokenDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mint_token, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.tokenNameInput)
        val amountInput = dialogView.findViewById<EditText>(R.id.tokenAmountInput)
        val dataInput = dialogView.findViewById<EditText>(R.id.tokenDataInput)
        
        AlertDialog.Builder(this)
            .setTitle("Mint a Unicity Token")
            .setView(dialogView)
            .setPositiveButton("Mint") { _, _ ->
                val name = nameInput.text.toString().ifEmpty { "My Token" }
                val amount = amountInput.text.toString().toLongOrNull() ?: 100L
                val data = dataInput.text.toString().ifEmpty { "Custom token data" }
                
                viewModel.mintNewToken(name, data, amount)
                Toast.makeText(this, "Minting token...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showResetWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Wallet")
            .setMessage("This will permanently delete all tokens from your wallet. Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                viewModel.clearWallet()
                Toast.makeText(this, "Wallet reset successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun runAutomatedTransferTest() {
        AlertDialog.Builder(this)
            .setTitle("Automated Transfer Test")
            .setMessage("This will test the complete token transfer flow between two virtual wallets. Check logs for detailed output.")
            .setPositiveButton("Run Test") { _, _ ->
                Toast.makeText(this, "Starting automated transfer test...", Toast.LENGTH_SHORT).show()
                
                // Call the JavaScript function through the SDK service
                lifecycleScope.launch {
                    try {
                        viewModel.getSdkService().runAutomatedTransferTest { result ->
                            runOnUiThread {
                                result.onSuccess {
                                    Toast.makeText(this@MainActivity, "Test completed! Check logs.", Toast.LENGTH_LONG).show()
                                }.onFailure { error ->
                                    Toast.makeText(this@MainActivity, "Test failed: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Failed to start test: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showCurrencyDialog() {
        val currencies = arrayOf("USD", "EUR")
        val currentIndex = currencies.indexOf(selectedCurrency)
        
        AlertDialog.Builder(this)
            .setTitle("Select Currency")
            .setSingleChoiceItems(currencies, currentIndex) { dialog, which ->
                selectedCurrency = currencies[which]
                binding.selectedCurrency.text = selectedCurrency
                cryptoAdapter.updateCurrency(selectedCurrency)
                updateBalanceDisplay()
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showSettingsDialog() {
        val options = arrayOf("Mint a Token", "Reset Wallet", "Test Transfer", "About")
        
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMintTokenDialog()
                    1 -> showResetWalletDialog()
                    2 -> runAutomatedTransferTest()
                    3 -> showAboutDialog()
                }
            }
            .show()
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Unicity Wallet")
            .setMessage("Version 2.0.0\n\nA demo wallet for Unicity tokens and cryptocurrencies with NFC transfer capabilities.\n\n© 2024 Unicity")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showCryptoSendDialog() {
        val cryptos = viewModel.cryptocurrencies.value
        val cryptoNames = cryptos.map { "${it.name} (${it.getFormattedBalance()} ${it.symbol})" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Asset to Send")
            .setItems(cryptoNames) { _, which ->
                showCryptoSendAmountDialog(cryptos[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showCryptoSendAmountDialog(crypto: CryptoCurrency) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Amount to send"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Send ${crypto.name}")
            .setMessage("Available balance: ${crypto.getFormattedBalance()} ${crypto.symbol}")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount != null && amount > 0 && amount <= crypto.balance) {
                    checkNfc {
                        startCryptoTransfer(crypto, amount)
                    }
                } else {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startCryptoTransfer(crypto: CryptoCurrency, amount: Double) {
        currentTransferringCrypto = crypto
        Toast.makeText(this, "Tap phones together to transfer ${amount} ${crypto.symbol}", Toast.LENGTH_SHORT).show()
        
        // For demo purposes, we'll simulate the transfer
        // In a real implementation, this would use NFC to transfer the crypto
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000) // Simulate NFC tap delay
            runOnUiThread {
                currentTransferringCrypto = null
                viewModel.updateCryptoBalance(crypto.id, crypto.balance - amount)
                Toast.makeText(this@MainActivity, "Sent ${amount} ${crypto.symbol} successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}