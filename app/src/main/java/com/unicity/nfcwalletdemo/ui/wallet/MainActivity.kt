package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
        setupSwipeRefresh()
        observeViewModel()
        
        // Don't load cryptocurrencies here - ViewModel init handles it
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
        binding.buyButton.setOnClickListener {
            Toast.makeText(this, "Buy feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.receiveButton.setOnClickListener {
            showReceiveQRDialog()
        }
        
        binding.depositButton.setOnClickListener {
            Toast.makeText(this, "Deposit feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.transferButton.setOnClickListener {
            if (currentTab == 0) {
                // For crypto assets
                val cryptos = viewModel.cryptocurrencies.value
                if (cryptos.isNotEmpty()) {
                    showCryptoSendDialog()
                } else {
                    Toast.makeText(this, "No assets to transfer", Toast.LENGTH_SHORT).show()
                }
            } else {
                // For NFTs/Tokens
                val tokens = viewModel.tokens.value
                if (tokens.isNotEmpty()) {
                    Toast.makeText(this, "Select a token from the list to transfer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No tokens to transfer", Toast.LENGTH_SHORT).show()
                }
            }
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
            currency = selectedCurrency,
            onLongPress = { crypto ->
                showEditBalanceDialog(crypto)
            }
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
    
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.apply {
            setColorSchemeColors(
                getColor(R.color.primary_blue),
                getColor(R.color.green_positive),
                getColor(R.color.purple_accent)
            )
            setOnRefreshListener {
                refreshWallet()
            }
        }
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
                // Stop swipe refresh when loading is complete
                if (!isLoading) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
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
        // Show refresh animation if initiated by swipe
        if (!binding.swipeRefreshLayout.isRefreshing) {
            binding.swipeRefreshLayout.isRefreshing = true
        }
        
        Log.d("MainActivity", "Refreshing wallet display and prices...")
        
        // Log all current crypto balances
        val cryptos = viewModel.cryptocurrencies.value
        Log.d("MainActivity", "=== CURRENT CRYPTO BALANCES ===")
        cryptos.forEach { crypto ->
            Log.d("MainActivity", "${crypto.symbol}: ${crypto.balance} (ID: ${crypto.id})")
        }
        Log.d("MainActivity", "==============================")
        
        // Refresh the tokens from repository (this doesn't change balances)
        lifecycleScope.launch {
            viewModel.refreshTokens()
        }
        
        // Refresh crypto prices from API
        viewModel.refreshPrices()
        
        // Refresh UI display without changing balances
        updateListDisplay()
        updateBalanceDisplay()
        
        // Show feedback toast
        Toast.makeText(this, "Refreshing prices...", Toast.LENGTH_SHORT).show()
        
        // Add a small delay to show the refresh animation
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000) // Slightly longer delay to allow price fetch
            binding.swipeRefreshLayout.isRefreshing = false
            Toast.makeText(this@MainActivity, "Prices updated", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Wallet display and price refresh completed")
        }
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
        Log.d("MainActivity", "onResume called")
        
        // Log current balances before any operations
        val cryptosBefore = viewModel.cryptocurrencies.value
        Log.d("MainActivity", "=== BALANCES ON RESUME (BEFORE) ===")
        cryptosBefore.forEach { crypto ->
            Log.d("MainActivity", "${crypto.symbol}: ${crypto.balance}")
        }
        
        // Check for received crypto transfers
        checkForReceivedCrypto()
        // Refresh wallet when returning to MainActivity
        refreshWallet()
    }
    
    private fun checkForReceivedCrypto() {
        val prefs = getSharedPreferences("crypto_transfers", MODE_PRIVATE)
        if (prefs.getBoolean("crypto_received", false)) {
            val cryptoSymbol = prefs.getString("crypto_symbol", "") ?: ""
            val amount = prefs.getString("crypto_amount", "0.0")?.toDoubleOrNull() ?: 0.0
            val cryptoName = prefs.getString("crypto_name", "") ?: ""
            val priceUsd = prefs.getString("price_usd", "0.0")?.toDoubleOrNull() ?: 0.0
            
            if (cryptoSymbol.isNotEmpty() && amount > 0) {
                Log.d("MainActivity", "Processing received crypto: EXACT amount = $amount $cryptoSymbol")
                
                // Add to existing crypto balance
                val success = viewModel.addReceivedCrypto(cryptoSymbol, amount)
                
                if (success) {
                    val value = String.format("%.2f", amount * priceUsd)
                    Toast.makeText(this, "Added $amount $cryptoSymbol (~$$value) to your wallet!", Toast.LENGTH_LONG).show()
                    Log.d("MainActivity", "Successfully added received crypto: EXACT amount = $amount $cryptoSymbol")
                } else {
                    Toast.makeText(this, "Received $amount $cryptoSymbol but couldn't add to wallet (unsupported crypto)", Toast.LENGTH_LONG).show()
                    Log.w("MainActivity", "Received unsupported crypto: $cryptoSymbol")
                }
                
                // Clear the received crypto data
                prefs.edit().clear().apply()
            }
        }
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_crypto, null)
        
        // Set up dialog elements
        val cryptoIcon = dialogView.findViewById<android.widget.ImageView>(R.id.cryptoIcon)
        val cryptoName = dialogView.findViewById<TextView>(R.id.cryptoName)
        val availableBalance = dialogView.findViewById<TextView>(R.id.availableBalance)
        val amountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        val estimatedValue = dialogView.findViewById<TextView>(R.id.estimatedValue)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSend)
        val chip25 = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chip25)
        val chip50 = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chip50)
        val chip75 = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chip75)
        val chipMax = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipMax)
        
        // Set crypto info
        cryptoIcon.setImageResource(crypto.iconResId)
        cryptoName.text = crypto.name
        availableBalance.text = "Available: ${crypto.getFormattedBalance()} ${crypto.symbol}"
        
        // Set default amount to total balance
        amountInput.setText(crypto.getFormattedBalance())
        
        // Update estimated value
        fun updateEstimatedValue() {
            val amount = amountInput.text.toString().toDoubleOrNull() ?: 0.0
            val value = amount * (if (selectedCurrency == "EUR") crypto.priceEur else crypto.priceUsd)
            val symbol = if (selectedCurrency == "EUR") "€" else "$"
            estimatedValue.text = "≈ $symbol${String.format("%.2f", value)}"
        }
        
        // Initial value calculation
        updateEstimatedValue()
        
        // Add text watcher for real-time value updates
        amountInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateEstimatedValue()
            }
        })
        
        // Set up quick amount chips
        chip25.setOnClickListener {
            val amount = crypto.balance * 0.25
            amountInput.setText(String.format("%.8f", amount).trimEnd('0').trimEnd('.'))
        }
        
        chip50.setOnClickListener {
            val amount = crypto.balance * 0.50
            amountInput.setText(String.format("%.8f", amount).trimEnd('0').trimEnd('.'))
        }
        
        chip75.setOnClickListener {
            val amount = crypto.balance * 0.75
            amountInput.setText(String.format("%.8f", amount).trimEnd('0').trimEnd('.'))
        }
        
        chipMax.setOnClickListener {
            amountInput.setText(crypto.getFormattedBalance())
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // Set up button listeners
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnSend.setOnClickListener {
            val inputText = amountInput.text.toString()
            val amount = inputText.toDoubleOrNull()
            Log.d("MainActivity", "User entered: '$inputText' parsed as: $amount")
            
            if (amount != null && amount > 0 && amount <= crypto.balance) {
                Log.d("MainActivity", "Starting transfer of exactly $amount ${crypto.symbol} (balance: ${crypto.balance})")
                dialog.dismiss()
                checkNfc {
                    startCryptoTransfer(crypto, amount)
                }
            } else {
                Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
    }
    
    private fun showEditBalanceDialog(crypto: CryptoCurrency) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_balance, null)
        
        // Set up dialog elements
        val cryptoIcon = dialogView.findViewById<android.widget.ImageView>(R.id.cryptoIcon)
        val cryptoName = dialogView.findViewById<TextView>(R.id.cryptoName)
        val currentBalance = dialogView.findViewById<TextView>(R.id.currentBalance)
        val balanceInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.balanceInput)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        
        // Set crypto info
        cryptoIcon.setImageResource(crypto.iconResId)
        cryptoName.text = crypto.name
        currentBalance.text = "Current: ${crypto.getFormattedBalance()} ${crypto.symbol}"
        
        // Set current balance in input
        balanceInput.setText(crypto.getFormattedBalance())
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // Set up button listeners
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnSave.setOnClickListener {
            val inputText = balanceInput.text.toString()
            val newBalance = inputText.toDoubleOrNull()
            
            if (newBalance != null && newBalance >= 0) {
                Log.d("MainActivity", "🔧 Hidden feature: Updating ${crypto.symbol} balance from ${crypto.balance} to $newBalance")
                viewModel.updateCryptoBalance(crypto.id, newBalance)
                dialog.dismiss()
                Toast.makeText(this, "🔧 Balance updated: ${crypto.symbol} = $newBalance", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid balance amount", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
    }
    
    private fun showReceiveQRDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        
        val qrCodeImage = dialogView.findViewById<ImageView>(R.id.qrCodeImage)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)
        
        // Generate QR code
        val url = "https://github.com/unicitynetwork/"
        
        try {
            val qrCodeBitmap = generateQRCode(url)
            qrCodeImage.setImageBitmap(qrCodeBitmap)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error generating QR code", e)
            Toast.makeText(this, "Error generating QR code", Toast.LENGTH_SHORT).show()
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Unicity Network"))
        }
        
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun generateQRCode(content: String): Bitmap {
        val writer = QRCodeWriter()
        val hints = hashMapOf<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
        hints[EncodeHintType.MARGIN] = 1
        
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }
    
    private fun startCryptoTransfer(crypto: CryptoCurrency, amount: Double) {
        currentTransferringCrypto = crypto
        
        Log.d("MainActivity", "Starting NFC crypto transfer: EXACT amount = $amount ${crypto.symbol}")
        Toast.makeText(this, "Tap phones together to transfer $amount ${crypto.symbol}", Toast.LENGTH_SHORT).show()
        
        val directNfcClient = DirectNfcClient(
            sdkService = viewModel.getSdkService(),
            onTransferComplete = {
                Log.d("MainActivity", "✅ NFC crypto transfer completed")
                runOnUiThread {
                    currentTransferringCrypto = null
                    val newBalance = crypto.balance - amount
                    Log.d("MainActivity", "Deducting from sender: ${crypto.balance} - $amount = $newBalance")
                    viewModel.updateCryptoBalance(crypto.id, newBalance)
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Sent $amount ${crypto.symbol} successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "NFC crypto transfer error: $error")
                runOnUiThread {
                    currentTransferringCrypto = null
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Crypto transfer failed: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onProgress = { current, total ->
                Log.d("MainActivity", "NFC crypto progress: $current/$total chunks")
                runOnUiThread {
                    if (total > 1) {
                        // Could show progress in UI if needed
                        val progress = (current * 100) / total
                        Log.d("MainActivity", "Crypto transfer progress: ${progress}%")
                    }
                }
            }
        )
        
        // Create a crypto transfer token
        val cryptoTransferToken = Token(
            id = "crypto_transfer_${System.currentTimeMillis()}",
            name = "${amount} ${crypto.symbol}",
            type = "Crypto Transfer",
            jsonData = createCryptoTransferData(crypto, amount)
        )
        
        directNfcClient.setCryptoToSend(cryptoTransferToken)
        
        // Enable NFC reader mode for crypto transfer
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        try {
            nfcAdapter!!.enableReaderMode(this, directNfcClient, flags, null)
            Log.d("MainActivity", "NFC reader mode enabled for crypto transfer")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enable NFC reader mode for crypto", e)
            currentTransferringCrypto = null
            Toast.makeText(this, "Failed to enable NFC: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createCryptoTransferData(crypto: CryptoCurrency, amount: Double): String {
        val transferData = mapOf(
            "type" to "crypto_transfer",
            "crypto_id" to crypto.id,
            "crypto_symbol" to crypto.symbol,
            "crypto_name" to crypto.name,
            "amount" to amount,
            "price_usd" to crypto.priceUsd,
            "price_eur" to crypto.priceEur,
            "timestamp" to System.currentTimeMillis(),
            "icon_res_id" to crypto.iconResId
        )
        val jsonData = com.google.gson.Gson().toJson(transferData)
        Log.d("MainActivity", "Created transfer JSON with amount: $amount")
        Log.d("MainActivity", "Transfer JSON: $jsonData")
        return jsonData
    }
}