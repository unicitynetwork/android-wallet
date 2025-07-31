package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel
import com.unicity.nfcwalletdemo.data.model.Contact
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.TokenStatus
import com.unicity.nfcwalletdemo.model.CryptoCurrency
import com.unicity.nfcwalletdemo.nfc.DirectNfcClient
import com.unicity.nfcwalletdemo.nfc.HybridNfcBluetoothClient
import com.unicity.nfcwalletdemo.nfc.RealNfcTransceiver
import com.unicity.nfcwalletdemo.nfc.SimpleNfcTransceiver
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import com.unicity.nfcwalletdemo.sdk.UnicityJavaSdkService
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.network.*
import kotlinx.coroutines.delay
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.unicity.nfcwalletdemo.ui.scanner.PortraitCaptureActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.VibrationEffect
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import android.os.Vibrator
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import com.unicity.nfcwalletdemo.bluetooth.BluetoothMeshManager
import com.unicity.nfcwalletdemo.bluetooth.BluetoothMeshTransferService
import com.unicity.nfcwalletdemo.bluetooth.BTMeshTransferCoordinator
import com.unicity.nfcwalletdemo.bluetooth.DiscoveredPeer
import com.unicity.nfcwalletdemo.ui.bluetooth.PeerSelectionDialog
import com.unicity.nfcwalletdemo.ui.bluetooth.TransferApprovalDialog
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenAdapter: TokenAdapter
    private lateinit var cryptoAdapter: CryptoAdapter
    private var nfcAdapter: NfcAdapter? = null
    private var realNfcTransceiver: RealNfcTransceiver? = null
    private var currentTransferringToken: Token? = null
    private var currentTransferringCrypto: CryptoCurrency? = null
    private var currentTab = 0 // 0 for Assets, 1 for NFTs
    private var selectedCurrency = "USD"
    
    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1001
        private const val DEBUG_BT = false // Set to true to enable verbose BT logging
    }
    
    private lateinit var sdkService: UnicityJavaSdkService
    private val gson = Gson()
    private var currentContactDialog: ContactListDialog? = null
    
    // BT Mesh components
    private lateinit var bluetoothMeshTransferService: BluetoothMeshTransferService
    
    // Success dialog properties
    private lateinit var confettiContainer: FrameLayout
    private lateinit var transferDetailsText: TextView
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogDismissRunnable: Runnable? = null
    
    // NFC waiting dialog
    private var nfcWaitingDialog: AlertDialog? = null
    
    // Transfer polling
    private var transferPollingJob: Job? = null
    private val transferApiService = TransferApiService()
    private val processedTransferIds = mutableSetOf<String>()
    
    // Handshake dialog receiver
    private val handshakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.unicity.nfcwalletdemo.p2p.P2PMessagingService.ACTION_HANDSHAKE_REQUEST) {
                val fromTag = intent.getStringExtra(com.unicity.nfcwalletdemo.p2p.P2PMessagingService.EXTRA_FROM_TAG) ?: return
                val fromName = intent.getStringExtra(com.unicity.nfcwalletdemo.p2p.P2PMessagingService.EXTRA_FROM_NAME) ?: return
                showHandshakeDialog(fromTag, fromName)
            }
        }
    }
    
    // P2P status update receiver
    private val p2pStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.unicity.nfcwalletdemo.UPDATE_P2P_STATUS") {
                updateLocationIconColor()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sdkService = UnicityJavaSdkService()
        
        // Initialize BT Mesh components
        initializeBTMesh()
        
        setupActionBar()
        setupNfc()
        setupUI()
        setupRecyclerView()
        setupTabLayout()
        setupSwipeRefresh()
        setupSuccessDialog()
        setupTestTrigger()
        setupTransferApprovalListener()
        
        // Request Bluetooth permissions on first launch
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        
        if (isFirstLaunch) {
            // Mark that we've launched the app
            prefs.edit().putBoolean("is_first_launch", false).apply()
            
            // Request Bluetooth permissions for mesh networking
            if (!checkBluetoothPermissions()) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Bluetooth Features")
                    .setMessage("This app uses Bluetooth for secure offline token transfers. Grant permission to enable this feature.")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        requestBluetoothPermissions()
                    }
                    .setNegativeButton("Later") { _, _ ->
                        // User can still use basic features
                    }
                    .show()
            } else {
                // Initialize Bluetooth mesh if permissions are granted
                initializeBluetoothMesh()
            }
        } else if (checkBluetoothPermissions()) {
            // Initialize Bluetooth mesh on normal app start if permissions are granted
            initializeBluetoothMesh()
        }
        observeViewModel()

        realNfcTransceiver = nfcAdapter?.let { RealNfcTransceiver(it) }
        
        // Don't load cryptocurrencies here - ViewModel init handles it
        
        // Initialize P2P service if agent is available
        initializeP2PServiceIfNeeded()
        
        // Handle deep links
        handleDeepLink(intent)
        
        // Start polling for transfer requests
        startTransferPolling()
        
        // Register handshake receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            handshakeReceiver,
            IntentFilter(com.unicity.nfcwalletdemo.p2p.P2PMessagingService.ACTION_HANDSHAKE_REQUEST)
        )
        
        // Register P2P status receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            p2pStatusReceiver,
            IntentFilter("com.unicity.nfcwalletdemo.UPDATE_P2P_STATUS")
        )
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }
    
    private fun initializeP2PServiceIfNeeded() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val isAgent = sharedPrefs.getBoolean("is_agent", false)
        val isAvailable = sharedPrefs.getBoolean("agent_available", true)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        Log.d("MainActivity", "initializeP2PServiceIfNeeded - isAgent: $isAgent, isAvailable: $isAvailable, unicityTag: $unicityTag")
        
        if (isAgent && isAvailable && unicityTag.isNotEmpty()) {
            // Check if P2P service is already running
            val existingService = com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getExistingInstance()
            if (existingService == null) {
                Log.d("MainActivity", "Starting P2P service automatically")
                try {
                    val publicKey = unicityTag // TODO: Get actual public key
                    com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getInstance(
                        context = applicationContext,
                        userTag = unicityTag,
                        userPublicKey = publicKey
                    )
                    Log.d("MainActivity", "P2P service started successfully")
                    Toast.makeText(this, "P2P messaging service started", Toast.LENGTH_SHORT).show()
                    updateLocationIconColor()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start P2P service", e)
                }
            } else {
                Log.d("MainActivity", "P2P service already running")
                updateLocationIconColor()
            }
        } else {
            Log.d("MainActivity", "Not starting P2P service - agent: $isAgent, available: $isAvailable")
            updateLocationIconColor()
        }
    }
    
    private fun updateLocationIconColor() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val isAgent = sharedPrefs.getBoolean("is_agent", false)
        val isAvailable = sharedPrefs.getBoolean("agent_available", true)
        val p2pService = com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getExistingInstance()
        
        when {
            !isAgent -> {
                // Not an agent - default black icon
                binding.btnLocation.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK))
            }
            isAgent && isAvailable && p2pService != null -> {
                // Agent available with P2P running - green icon
                binding.btnLocation.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50")))
            }
            isAgent && !isAvailable -> {
                // Agent not available - red icon
                binding.btnLocation.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336")))
            }
            else -> {
                // Agent available but P2P not running - orange icon (warning state)
                binding.btnLocation.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800")))
            }
        }
    }
    
    private fun handleDeepLink(intent: Intent) {
        val data = intent.data
        if (data != null) {
            when {
                data.scheme == "nfcwallet" -> {
                    when (data.host) {
                        "payment-request" -> handlePaymentRequest(data)
                        "mint-request" -> handleMintRequest(data)
                    }
                }
                data.scheme == "https" && data.host == "nfcwallet.unicity.io" -> {
                    // Handle HTTPS links that redirect to app
                    when {
                        data.path?.startsWith("/mint") == true -> {
                            // Convert HTTPS URL to mint-request parameters
                            val token = data.getQueryParameter("token")
                            val amount = data.getQueryParameter("amount")
                            val tokenData = data.getQueryParameter("tokenData")
                            
                            // Create a new URI with the parameters
                            val mintUri = Uri.parse("nfcwallet://mint-request?token=$token&amount=$amount&tokenData=$tokenData")
                            handleMintRequest(mintUri)
                        }
                    }
                }
            }
        }
    }
    
    private fun handlePaymentRequest(data: Uri) {
        val requestId = data.getQueryParameter("id")
        val recipientAddress = data.getQueryParameter("recipient")
        val currency = data.getQueryParameter("currency")
        val amount = data.getQueryParameter("amount")
        
        if (requestId != null && recipientAddress != null) {
            if (currency != null && amount != null) {
                // Recipient specified currency and amount
                showSpecificPaymentDialog(requestId, recipientAddress, currency, amount)
            } else {
                // Let sender choose currency and amount
                showPaymentDialog(requestId, recipientAddress)
            }
        }
    }
    
    private fun handleMintRequest(data: Uri) {
        val tokenName = data.getQueryParameter("token")
        val amount = data.getQueryParameter("amount")?.toLongOrNull()
        val tokenData = data.getQueryParameter("tokenData") // Uri.getQueryParameter automatically decodes URL encoding
        
        if (tokenName != null && amount != null) {
            // Build the token data string
            val finalTokenData = if (!tokenData.isNullOrEmpty()) {
                // Use the provided token data (already decoded by getQueryParameter)
                tokenData
            } else {
                // Default format if no tokenData provided
                "BoxyRun Score: $amount coins | Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}"
            }
            
            // Show confirmation dialog
            AlertDialog.Builder(this)
                .setTitle("🎮 ${tokenName} Achievement!")
                .setMessage("Congratulations! You collected $amount coins!\n\nGame Data: $finalTokenData\n\nWould you like to mint a Unicity NFT token to save this achievement on the blockchain?")
                .setPositiveButton("Mint Token") { _, _ ->
                    // Mint the token with the provided or default game data
                    viewModel.mintNewToken(tokenName, finalTokenData, amount)
                    
                    // Show minting progress
                    Toast.makeText(this, "🎮 Minting your ${tokenName} achievement token...", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            Toast.makeText(this, "Invalid mint request", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupActionBar() {
        supportActionBar?.hide()
    }
    
    private fun setupUI() {
        // Setup bottom navigation
        binding.navUnicity.setOnClickListener {
            // Open User Profile when Unicity icon is tapped
            startActivity(Intent(this, com.unicity.nfcwalletdemo.ui.profile.UserProfileActivity::class.java))
        }
        
        binding.navTawasal.setOnClickListener {
            // Navigation handled without toast feedback
        }
        
        binding.navSphere.setOnClickListener {
            // Navigation handled without toast feedback
        }
        
        binding.navSettings.setOnClickListener {
            showSettingsDialog()
        }
        
        // Setup QR scanner button
        binding.btnScanQr.setOnClickListener {
            scanQRCode()
        }
        
        // Setup location button
        binding.btnLocation.setOnClickListener {
            openAgentMap()
        }
        
        // Update location icon color based on P2P status
        updateLocationIconColor()
        
        // Setup overflow menu button
        binding.btnOverflowMenu.setOnClickListener { view ->
            showOverflowMenu(view)
        }
        
        // Setup action buttons
        binding.buyButton.setOnClickListener {
            showBuySellDialog()
        }
        
        binding.receiveButton.setOnClickListener {
            showReceiveQRDialog()
        }
        
        binding.depositButton.setOnClickListener {
            showDepositDialog()
        }
        
        binding.transferButton.setOnClickListener {
            if (currentTab == 0) {
                // For crypto assets
                val cryptos = viewModel.cryptocurrencies.value
                if (cryptos.isNotEmpty()) {
                    showSendDialog()
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
                    startHybridTokenTransfer(token)
                }
            },
            onCancelClick = { token ->
                cancelTokenTransfer(token)
            },
            onManualSubmitClick = { token ->
                manualSubmitOfflineTransfer(token)
            },
            onShareClick = { token ->
                shareToken(token)
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
                Log.d("MainActivity", "=== Tokens collected: ${tokens.size} tokens, currentTab=$currentTab ===")
                tokens.forEach { token ->
                    Log.d("MainActivity", "Token: ${token.name} (${token.type})")
                }
                
                if (currentTab == 1) {
                    Log.d("MainActivity", "Updating NFTs tab with ${tokens.size} tokens")
                    tokenAdapter.submitList(tokens)
                    binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Log.d("MainActivity", "Not updating UI - wrong tab (currentTab=$currentTab)")
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
                        val token = it.getOrNull()
                        if (token?.name == "BoxyRun") {
                            // Special message for BoxyRun tokens
                            Toast.makeText(this@MainActivity, "🎮 BoxyRun achievement saved! Your NFT is ready to use!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Token minted! Ready to use locally.", Toast.LENGTH_SHORT).show()
                        }
                        
                        // Switch to NFTs tab to show the new token
                        if (currentTab != 1) {
                            binding.tabLayout.getTabAt(1)?.select()
                        }
                        
                        // Note: If you see this token, it may not be fully confirmed on blockchain yet
                        // but it's saved locally and can be used
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
        
        
        // Add a small delay to show the refresh animation
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000) // Slightly longer delay to allow price fetch
            binding.swipeRefreshLayout.isRefreshing = false
            // Prices updated silently
            Log.d("MainActivity", "Wallet display and price refresh completed")
        }
    }
    
    private fun startTokenTransfer(token: Token) {
        currentTransferringToken = token
        tokenAdapter.setTransferring(token, true)
        viewModel.selectToken(token)
        
        Log.d("MainActivity", "Starting NFC transfer for token: ${token.name}")
        Toast.makeText(this, "Tap phones together to transfer token", Toast.LENGTH_SHORT).show()
        
        val realNfcTransceiver = nfcAdapter?.let { RealNfcTransceiver(it) }
        if (realNfcTransceiver == null) {
            Toast.makeText(this, "NFC not available or enabled", Toast.LENGTH_SHORT).show()
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            return
        }

        val directNfcClient = DirectNfcClient(
            sdkService = viewModel.getSdkService(),
            apduTransceiver = realNfcTransceiver,
            onTransferComplete = {
                Log.d("MainActivity", "✅ NFC transfer completed")
                runOnUiThread {
                    // Success vibration
                    vibrateSuccess()
                    
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    viewModel.removeToken(token.id)
                    realNfcTransceiver.disableReaderMode(this) // Use transceiver's disable
                    
                    // Show success dialog for token transfer
                    showSuccessDialog("${token.name} sent successfully!")
                    Toast.makeText(this@MainActivity, "Token sent successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "NFC transfer error: $error")
                runOnUiThread {
                    // Error vibration
                    vibrateError()
                    
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    realNfcTransceiver.disableReaderMode(this) // Use transceiver's disable
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            },
            onProgress = { current, total ->
                Log.d("MainActivity", "NFC progress: $current/$total chunks")
                runOnUiThread {
                    // Connection established vibration on first progress
                    if (current == 0 && total == 1) {
                        vibrateConnectionEstablished()
                        Toast.makeText(this@MainActivity, "Keep phones touching...", Toast.LENGTH_SHORT).show()
                    }
                    if (total > 1) {
                        // Update the token's transfer status in the adapter to show progress
                        tokenAdapter.updateTransferProgress(token, current, total)
                    }
                }
            }
        )
        
        directNfcClient.setTokenToSend(token)
        
        // Enable NFC reader mode for direct transfer
        try {
            realNfcTransceiver.enableReaderMode(this) // Use transceiver's enable
            Log.d("MainActivity", "NFC reader mode enabled for transfer")
            
            // Start the NFC transfer process
            directNfcClient.startNfcTransfer()
            Log.d("MainActivity", "NFC transfer started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enable NFC reader mode", e)
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            Toast.makeText(this, "Failed to enable NFC: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startHybridTokenTransfer(token: Token) {
        // Show BT mesh peer selection dialog which includes NFC option
        showBTMeshPeerSelection(token)
    }
    
    private fun startNfcTokenTransfer(token: Token) {
        currentTransferringToken = token
        tokenAdapter.setTransferring(token, true)
        viewModel.selectToken(token)
        
        Log.d("MainActivity", "Starting NFC transfer for token: ${token.name}")
        
        // Check Bluetooth permissions first for hybrid transfer
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            return
        }
        
        Toast.makeText(this, "Tap phones together to start transfer", Toast.LENGTH_SHORT).show()
        
        val realNfcTransceiver = nfcAdapter?.let { SimpleNfcTransceiver(it) }
        if (realNfcTransceiver == null) {
            Toast.makeText(this, "NFC not available or enabled", Toast.LENGTH_SHORT).show()
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            return
        }
        
        val hybridClient = HybridNfcBluetoothClient(
            context = this,
            sdkService = viewModel.getSdkService(),
            apduTransceiver = realNfcTransceiver,
            onTransferComplete = {
                Log.d("MainActivity", "✅ Hybrid transfer completed")
                runOnUiThread {
                    vibrateSuccess()
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    viewModel.removeToken(token.id)
                    realNfcTransceiver.disableReaderMode(this)
                    showSuccessDialog("${token.name} sent successfully via hybrid transfer!")
                    Toast.makeText(this@MainActivity, "Token sent successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "Hybrid transfer error: $error")
                runOnUiThread {
                    vibrateError()
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    realNfcTransceiver.disableReaderMode(this)
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            },
            onProgress = { current, total ->
                Log.d("MainActivity", "Hybrid transfer progress: $current/$total")
                runOnUiThread {
                    if (current == 1 && total > 1) {
                        vibrateConnectionEstablished()
                        Toast.makeText(this@MainActivity, "Bluetooth transfer in progress...", Toast.LENGTH_SHORT).show()
                    }
                    if (total > 1) {
                        tokenAdapter.updateTransferProgress(token, current, total)
                    }
                    
                    // Progress feedback at milestones
                    val percentComplete = (current * 100) / total
                    if (percentComplete == 25 || percentComplete == 50 || percentComplete == 75) {
                        vibrateConnectionEstablished() // Use existing vibration method
                    }
                }
            }
        )
        
        lifecycleScope.launch {
            try {
                // Extract sender identity from token's jsonData
                Log.d("MainActivity", "Token jsonData: ${token.jsonData}")
                val senderIdentityJson = token.jsonData?.let { jsonData ->
                    try {
                        val mintResult = Gson().fromJson(jsonData, Map::class.java)
                        Log.d("MainActivity", "Mint result keys: ${mintResult.keys}")
                        val identityData = mintResult["identity"] as? Map<*, *>
                        if (identityData != null) {
                            Log.d("MainActivity", "Found identity data: $identityData")
                            Gson().toJson(identityData)
                        } else {
                            Log.d("MainActivity", "No identity found in mint result")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to extract identity from token", e)
                        null
                    }
                }
                
                // Use identity from token or generate a temporary one
                val finalSenderIdentity = senderIdentityJson ?: run {
                    Log.w("MainActivity", "No identity in token, generating temporary identity")
                    // Generate a temporary identity for testing
                    val tempIdentity = mapOf(
                        "secret" to "temp_secret_${System.currentTimeMillis()}",
                        "nonce" to "temp_nonce_${System.currentTimeMillis()}"
                    )
                    Gson().toJson(tempIdentity)
                }
                
                realNfcTransceiver.enableReaderMode(this@MainActivity)
                Log.d("MainActivity", "NFC reader mode enabled for hybrid transfer")
                hybridClient.startTransferAsSender(token, finalSenderIdentity)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start hybrid transfer", e)
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    Toast.makeText(this@MainActivity, "Failed to start transfer: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun cancelTokenTransfer(token: Token) {
        tokenAdapter.setTransferring(token, false)
        currentTransferringToken = null
        disableNfcTransfer()
    }
    
    private fun checkBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Android 12+ requires runtime Bluetooth permissions
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Below Android 12, only location permission is needed for BLE scanning
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestBluetoothPermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        requestPermissions(permissions, BLUETOOTH_PERMISSION_REQUEST_CODE)
        Toast.makeText(this, "Bluetooth permissions required for token transfer", Toast.LENGTH_SHORT).show()
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
            realNfcTransceiver?.disableReaderMode(this)
            Log.d("MainActivity", "NFC reader mode disabled")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error disabling NFC reader mode", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called")
        
        // Update location icon color in case settings changed
        updateLocationIconColor()
        
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
            com.unicity.nfcwalletdemo.R.id.action_scan_qr -> {
                scanQRCode()
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_mint_token -> {
                showMintTokenDialog()
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_offline_transfer_test -> {
                runOfflineTransferTest()
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_bluetooth_mesh -> {
                startActivity(Intent(this, com.unicity.nfcwalletdemo.ui.bluetooth.BluetoothMeshActivity::class.java))
                true
            }
            com.unicity.nfcwalletdemo.R.id.action_reset_wallet -> {
                showResetWalletDialog()
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
    
    // Removed showChatConversations - moved to Agent Map header
    
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
    
    private fun runOfflineTransferTest() {
        // Check if we have any Unicity tokens to test with
        val tokens = viewModel.tokens.value
        val unicityTokens = tokens.filter { it.type == "Unicity Token" }
        
        if (unicityTokens.isEmpty()) {
            Toast.makeText(this, "Please mint a Unicity token first to test offline transfer", Toast.LENGTH_LONG).show()
            return
        }
        
        // Show progress dialog
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Testing Offline Transfer")
            .setMessage("Running offline transfer test...\nThis will create and complete an offline transfer locally.")
            .setCancelable(false)
            .create()
            
        progressDialog.show()
        
        // Run the test in a coroutine
        lifecycleScope.launch {
            try {
                // Run the test
                val result = viewModel.testOfflineTransfer()
                
                progressDialog.dismiss()
                
                if (result.isSuccess) {
                    showOfflineTransferSuccessDialog(result.getOrNull() ?: "")
                } else {
                    showOfflineTransferErrorDialog(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                showOfflineTransferErrorDialog(e.message ?: "Unexpected error occurred")
            }
        }
    }
    
    private fun showOfflineTransferSuccessDialog(details: String) {
        AlertDialog.Builder(this)
            .setTitle("✅ Offline Transfer Test Successful")
            .setMessage("The offline transfer test completed successfully!\n\nDetails:\n$details")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showOfflineTransferErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("❌ Offline Transfer Test Failed")
            .setMessage("The offline transfer test failed.\n\nError: $error")
            .setPositiveButton("OK", null)
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
        val options = arrayOf("Mint a Token", "Reset Wallet", "Test NFC Transfer", "Test Offline Transfer", "Bluetooth Mesh Discovery", "Demo Mode")
        
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMintTokenDialog()
                    1 -> showResetWalletDialog()
                    2 -> startNfcTestTransfer()
                    3 -> runOfflineTransferTest()
                    4 -> startActivity(Intent(this@MainActivity, com.unicity.nfcwalletdemo.ui.bluetooth.BluetoothMeshActivity::class.java))
                    5 -> showDemoModeDialog()
                }
            }
            .show()
    }
    
    private fun showDemoModeDialog() {
        val isDemoEnabled = com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)
        val currentLocation = com.unicity.nfcwalletdemo.utils.DemoLocationManager.getDemoLocation(this)
        
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        
        AlertDialog.Builder(this)
            .setTitle("Demo Mode Settings")
            .setMessage("Current: ${if (isDemoEnabled) "ON - ${currentLocation.city}, ${currentLocation.country}" else "OFF"}")
            .setPositiveButton("Toggle Demo Mode") { _, _ ->
                com.unicity.nfcwalletdemo.utils.DemoLocationManager.setDemoModeEnabled(this, !isDemoEnabled)
                Toast.makeText(this, "Demo mode ${if (!isDemoEnabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (isDemoEnabled) "Change Location" else "Select Location") { _, _ ->
                if (!isDemoEnabled) {
                    com.unicity.nfcwalletdemo.utils.DemoLocationManager.setDemoModeEnabled(this, true)
                }
                showLocationSelectionDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLocationSelectionDialog() {
        val locations = com.unicity.nfcwalletdemo.utils.DemoLocationManager.DemoLocation.values()
            .filter { it != com.unicity.nfcwalletdemo.utils.DemoLocationManager.DemoLocation.CUSTOM }
        val locationNames = locations.map { "${it.city}, ${it.country}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Demo Location")
            .setItems(locationNames) { _, which ->
                val selectedLocation = locations[which]
                com.unicity.nfcwalletdemo.utils.DemoLocationManager.setDemoLocation(this, selectedLocation)
                Toast.makeText(this, "Demo location set to ${selectedLocation.city}", Toast.LENGTH_SHORT).show()
                
                // Also create some demo agents if user is in agent mode
                createDemoAgents(selectedLocation)
            }
            .show()
    }
    
    private fun createDemoAgents(location: com.unicity.nfcwalletdemo.utils.DemoLocationManager.DemoLocation) {
        // Generate some demo agents near the selected location
        lifecycleScope.launch {
            try {
                val agentLocations = com.unicity.nfcwalletdemo.utils.DemoLocationManager.generateNearbyAgentLocations(this@MainActivity, 5)
                val agentNames = listOf("agent1", "agent2", "agent3", "agent4", "agent5")
                val agentApiService = com.unicity.nfcwalletdemo.network.AgentApiService()
                
                agentLocations.forEachIndexed { index, (lat, lon) ->
                    agentApiService.updateAgentLocation(
                        agentNames[index],
                        lat,
                        lon,
                        true
                    )
                }
                
                Toast.makeText(this@MainActivity, "Created ${agentLocations.size} demo agents nearby", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to create demo agents", e)
            }
        }
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Unicity Wallet")
            .setMessage("Version 2.0.0\n\nA demo wallet for Unicity tokens and cryptocurrencies with NFC transfer capabilities.\n\n© 2024 Unicity")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSendDialog() {
        // First show contact list
        showContactListDialog()
    }
    
    private fun showContactListDialog() {
        currentContactDialog = ContactListDialog(
            context = this,
            onContactSelected = { selectedContact ->
                // Check if contact has @unicity tag
                if (selectedContact.hasUnicityTag()) {
                    // After contact is selected, show asset selection dialog
                    showAssetSelectionDialog(selectedContact)
                } else {
                    // Show warning for non-@unicity contacts
                    AlertDialog.Builder(this)
                        .setTitle("Cannot Send")
                        .setMessage("Unknown @unicity tag. You can only send to contacts with @unicity tag.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            },
            onRequestPermission = { permission, requestCode ->
                // Request the permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(permission), requestCode)
                }
            }
        )
        currentContactDialog?.show()
    }
    
    private fun showAssetSelectionDialog(recipient: Contact) {
        try {
            Log.d("MainActivity", "showAssetSelectionDialog called with recipient: ${recipient.name}")
            
            val dialogView = layoutInflater.inflate(R.layout.dialog_send_asset, null)
            
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()
            
            // Setup recipient info
            val recipientName = dialogView.findViewById<TextView>(R.id.recipientName)
            val recipientAddress = dialogView.findViewById<TextView>(R.id.recipientAddress)
            val recipientBadge = dialogView.findViewById<ImageView>(R.id.recipientUnicityBadge)
        
        recipientName.text = recipient.name
        recipientAddress.text = recipient.address
        recipientBadge.visibility = if (recipient.hasUnicityTag()) View.VISIBLE else View.GONE
        
        // Setup close button
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // Setup asset selector
        val assetSelector = dialogView.findViewById<AutoCompleteTextView>(R.id.assetSelector)
        val assetNames = listOf("Bitcoin", "Ethereum", "eNaira", "eFranc")
        val assetAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, assetNames)
        assetSelector.setAdapter(assetAdapter)
        
        val availableBalanceText = dialogView.findViewById<TextView>(R.id.availableBalanceText)
        assetSelector.setOnItemClickListener { _, _, position, _ ->
            val selectedAsset = assetNames[position]
            when (selectedAsset) {
                "Bitcoin" -> availableBalanceText.text = "Available: 0.025 BTC"
                "Ethereum" -> availableBalanceText.text = "Available: 1.5 ETH" 
                "eNaira" -> availableBalanceText.text = "Available: 50,000 NGN"
                "eFranc" -> availableBalanceText.text = "Available: 25,000 XAF"
                else -> availableBalanceText.text = "Available balance will be shown when token is selected"
            }
        }
        
        // Replace the send button logic with crypto selection
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSend)
        btnSend.visibility = View.GONE // Hide the old send button
        
        // Show crypto selection dialog immediately
        dialog.dismiss()
        showCryptoSelectionForRecipient(recipient)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing asset selection dialog", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showCryptoSendDialog() {
        val cryptos = viewModel.cryptocurrencies.value
        
        // Create custom dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        
        // Setup RecyclerView
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        val adapter = AssetDialogAdapter(cryptos) { selectedAsset ->
            dialog.dismiss()
            showCryptoSendAmountDialog(selectedAsset)
        }
        
        recyclerView.adapter = adapter
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun showCryptoSelectionForRecipient(recipient: Contact) {
        val cryptos = viewModel.cryptocurrencies.value
        
        // Create custom dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        
        // Setup RecyclerView
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("Select Asset to Send")
            .create()
        
        val adapter = AssetDialogAdapter(cryptos) { selectedAsset ->
            dialog.dismiss()
            showCryptoSendAmountDialog(selectedAsset, recipient)
        }
        
        recyclerView.adapter = adapter
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun showCryptoSendAmountDialog(crypto: CryptoCurrency, selectedContact: Contact? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_crypto, null)
        
        // Set up dialog elements
        val cryptoIcon = dialogView.findViewById<android.widget.ImageView>(R.id.cryptoIcon)
        val cryptoName = dialogView.findViewById<TextView>(R.id.cryptoName)
        val availableBalance = dialogView.findViewById<TextView>(R.id.availableBalance)
        val amountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        val messageInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.messageInput)
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
            
            Log.d("MainActivity", "Validation: amount=$amount, crypto.balance=${crypto.balance}")
            
            // Use a small epsilon for floating point comparison to handle precision issues
            val epsilon = 0.00000001
            val balanceCheck = amount != null && amount <= crypto.balance + epsilon
            
            Log.d("MainActivity", "Checks: amount!=null=${amount != null}, amount>0=${amount != null && amount > 0}, amount<=balance=$balanceCheck")
            
            if (amount != null && amount > 0 && balanceCheck) {
                Log.d("MainActivity", "Starting transfer of exactly $amount ${crypto.symbol} (balance: ${crypto.balance})")
                dialog.dismiss()
                
                if (selectedContact != null) {
                    // Use backend transfer for @unicity contacts
                    val message = messageInput.text.toString().trim()
                    sendTransferRequestToBackend(selectedContact, crypto, amount, message)
                } else {
                    // Use NFC transfer for direct transfers
                    checkNfc {
                        startCryptoTransfer(crypto, amount)
                    }
                }
            } else {
                val reason = when {
                    amount == null -> "amount is null"
                    amount <= 0 -> "amount is not positive ($amount)"
                    amount > crypto.balance + epsilon -> "amount ($amount) exceeds balance (${crypto.balance})"
                    else -> "unknown reason"
                }
                Log.d("MainActivity", "Transfer failed: $reason")
                Toast.makeText(this, "Invalid amount: $reason", Toast.LENGTH_LONG).show()
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
        // First ask if they want to specify amount/currency
        AlertDialog.Builder(this)
            .setTitle("Create Payment Request")
            .setMessage("Do you want to specify the amount and currency?")
            .setPositiveButton("Yes, specify amount") { _, _ ->
                showAmountSpecificationDialog()
            }
            .setNegativeButton("No, let sender choose") { _, _ ->
                showQRCodeGenerationDialog(null, null)
            }
            .show()
    }
    
    private fun showAmountSpecificationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_specify_amount, null)
        
        val assetsRecyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.assetsRecyclerView)
        val selectedAssetLayout = dialogView.findViewById<LinearLayout>(R.id.selectedAssetLayout)
        val selectedAssetIcon = dialogView.findViewById<ImageView>(R.id.selectedAssetIcon)
        val selectedAssetName = dialogView.findViewById<TextView>(R.id.selectedAssetName)
        val amountInputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.amountInputLayout)
        val amountInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.amountInput)
        
        // Get available currencies from cryptocurrencies
        val cryptos = viewModel.cryptocurrencies.value
        var selectedCrypto: CryptoCurrency? = null
        
        // Setup RecyclerView
        assetsRecyclerView.layoutManager = LinearLayoutManager(this)
        val assetAdapter = AssetSelectionAdapter(cryptos) { crypto ->
            selectedCrypto = crypto
            
            // Show selected asset
            selectedAssetLayout.visibility = View.VISIBLE
            selectedAssetIcon.setImageResource(crypto.iconResId)
            selectedAssetName.text = "${crypto.name} (${crypto.symbol})"
            
            // Hide the recycler view and show amount input
            assetsRecyclerView.visibility = View.GONE
            amountInputLayout.visibility = View.VISIBLE
            
            // Focus on amount input
            amountInput.requestFocus()
        }
        assetsRecyclerView.adapter = assetAdapter
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Specify Payment Details")
            .setView(dialogView)
            .setPositiveButton("Generate QR") { _, _ ->
                val currency = selectedCrypto?.symbol
                val amount = amountInput.text?.toString()
                
                if (currency.isNullOrEmpty() || amount.isNullOrEmpty()) {
                    Toast.makeText(this, "Please specify currency and amount", Toast.LENGTH_SHORT).show()
                } else {
                    showQRCodeGenerationDialog(currency, amount)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            
        dialog.show()
    }
    
    private fun showQRCodeGenerationDialog(currencySymbol: String?, amount: String?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        
        val qrCodeImage = dialogView.findViewById<ImageView>(R.id.qrCodeImage)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)
        val statusText = dialogView.findViewById<TextView>(R.id.statusText)
        val timerText = dialogView.findViewById<TextView>(R.id.timerText)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        var currentRequestJob: kotlinx.coroutines.Job? = null
        var timerJob: kotlinx.coroutines.Job? = null
        var pollingJob: kotlinx.coroutines.Job? = null
        
        fun createPaymentRequest() {
            // Cancel any existing jobs
            currentRequestJob?.cancel()
            timerJob?.cancel()
            pollingJob?.cancel()
            
            // Show loading state
            qrCodeImage.visibility = View.GONE
            statusText?.text = "Generating payment request..."
            timerText?.visibility = View.GONE
            
            // Get wallet address
            val sharedPrefs = getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
            val walletAddress = sharedPrefs.getString("wallet_address", null) ?: run {
                val newAddress = "wallet_${java.util.UUID.randomUUID().toString().take(8)}"
                sharedPrefs.edit().putString("wallet_address", newAddress).apply()
                newAddress
            }
            
            currentRequestJob = lifecycleScope.launch {
                try {
                    val request = PaymentRequestService.api.createPaymentRequest(
                        CreatePaymentRequestDto(
                            recipientAddress = walletAddress,
                            currencySymbol = currencySymbol,
                            amount = amount
                        )
                    )
                    
                    // Generate QR code
                    val qrCodeBitmap = generateQRCode(request.qrData)
                    qrCodeImage.setImageBitmap(qrCodeBitmap)
                    qrCodeImage.visibility = View.VISIBLE
                    statusText?.text = if (currencySymbol != null && amount != null) {
                        "Scan to send $amount $currencySymbol"
                    } else {
                        "Scan this QR code to send payment"
                    }
                    timerText?.visibility = View.VISIBLE
                    
                    // Update share button
                    btnShare.setOnClickListener {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, request.qrData)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Payment Request"))
                    }
                    
                    // Start countdown timer
                    var remainingSeconds = 60
                    timerJob = lifecycleScope.launch {
                        while (remainingSeconds > 0 && isActive) {
                            timerText?.text = "Expires in ${remainingSeconds}s"
                            delay(1000)
                            remainingSeconds--
                        }
                        if (remainingSeconds == 0 && dialog.isShowing) {
                            // QR code expired, create a new one
                            createPaymentRequest()
                        }
                    }
                    
                    // Start polling for payment
                    pollingJob = lifecycleScope.launch {
                        pollForPaymentWithCallback(request.requestId) { payment ->
                            // Cancel all jobs
                            currentRequestJob?.cancel()
                            timerJob?.cancel()
                            pollingJob?.cancel()
                            
                            // Dismiss dialog and show success
                            dialog.dismiss()
                            showPaymentReceivedDialog(payment)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error creating payment request", e)
                    Toast.makeText(this@MainActivity, "Error creating payment request", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        
        // Create initial payment request
        createPaymentRequest()
        
        btnClose.setOnClickListener {
            currentRequestJob?.cancel()
            timerJob?.cancel()
            pollingJob?.cancel()
            dialog.dismiss()
        }
        
        dialog.setOnDismissListener {
            currentRequestJob?.cancel()
            timerJob?.cancel()
            pollingJob?.cancel()
        }
        
        dialog.show()
    }
    
    private fun showDepositDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_deposit, null)
        
        // Get views
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
        val btnDepositBtc = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDepositBtc)
        val btnWithdrawBtc = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWithdrawBtc)
        val btnDepositEth = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDepositEth)
        val btnWithdrawEth = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWithdrawEth)
        
        // Update balance displays with actual crypto balances
        val btcCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.btcCard)
        val ethCard = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.ethCard)
        val btcBalanceText = btcCard?.findViewById<TextView>(R.id.btcBalance)
        val ethBalanceText = ethCard?.findViewById<TextView>(R.id.ethBalance)
        
        // Get current balances
        val btcBalance = viewModel.cryptocurrencies.value.find { it.symbol == "BTC" }?.balance ?: 0.0
        val ethBalance = viewModel.cryptocurrencies.value.find { it.symbol == "ETH" }?.balance ?: 0.0
        
        // Update balance texts if views exist
        btcBalanceText?.text = "Balance: ${String.format("%.2f", btcBalance)}"
        ethBalanceText?.text = "Balance: ${String.format("%.2f", ethBalance)}"
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // Set up click listeners
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // Button click listeners that do nothing as requested
        btnDepositBtc.setOnClickListener {
            // Do nothing
        }
        
        btnWithdrawBtc.setOnClickListener {
            // Do nothing
        }
        
        btnDepositEth.setOnClickListener {
            // Do nothing
        }
        
        btnWithdrawEth.setOnClickListener {
            // Do nothing
        }
        
        dialog.show()
    }
    
    private fun showBuySellDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_buy_sell, null)
        
        // Get views
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
        val btnBuyBtc = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBuyBtc)
        val btnSellBtc = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSellBtc)
        val btnBuyEth = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBuyEth)
        val btnSellEth = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSellEth)
        val btnBuyUsdt = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBuyUsdt)
        val btnSellUsdt = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSellUsdt)
        
        // Update prices with current data from cryptocurrencies
        val btcCrypto = viewModel.cryptocurrencies.value.find { it.symbol == "BTC" }
        val ethCrypto = viewModel.cryptocurrencies.value.find { it.symbol == "ETH" }
        val usdtCrypto = viewModel.cryptocurrencies.value.find { it.symbol == "USDT" }
        
        // Update BTC price
        val btcPriceText = dialogView.findViewById<TextView>(R.id.btcPrice)
        val btcChangeText = dialogView.findViewById<TextView>(R.id.btcChange)
        if (btcCrypto != null) {
            btcPriceText?.text = "$${String.format("%,.2f", btcCrypto.priceUsd)}"
            btcChangeText?.text = "${if (btcCrypto.change24h >= 0) "+" else ""}${String.format("%.2f", btcCrypto.change24h)}%"
            btcChangeText?.setTextColor(if (btcCrypto.change24h >= 0) getColor(R.color.green_positive) else getColor(R.color.red_negative))
        }
        
        // Update ETH price
        val ethPriceText = dialogView.findViewById<TextView>(R.id.ethPrice)
        val ethChangeText = dialogView.findViewById<TextView>(R.id.ethChange)
        if (ethCrypto != null) {
            ethPriceText?.text = "$${String.format("%,.2f", ethCrypto.priceUsd)}"
            ethChangeText?.text = "${if (ethCrypto.change24h >= 0) "+" else ""}${String.format("%.2f", ethCrypto.change24h)}%"
            ethChangeText?.setTextColor(if (ethCrypto.change24h >= 0) getColor(R.color.green_positive) else getColor(R.color.red_negative))
        }
        
        // Update USDT price
        val usdtPriceText = dialogView.findViewById<TextView>(R.id.usdtPrice)
        if (usdtCrypto != null) {
            usdtPriceText?.text = "$${String.format("%.4f", usdtCrypto.priceUsd)}"
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // Set up click listeners
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // Button click listeners that do nothing as requested
        btnBuyBtc.setOnClickListener {
            // Do nothing
        }
        
        btnSellBtc.setOnClickListener {
            // Do nothing
        }
        
        btnBuyEth.setOnClickListener {
            // Do nothing
        }
        
        btnSellEth.setOnClickListener {
            // Do nothing
        }
        
        btnBuyUsdt.setOnClickListener {
            // Do nothing
        }
        
        btnSellUsdt.setOnClickListener {
            // Do nothing
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
    
    private fun setupSuccessDialog() {
        try {
            confettiContainer = binding.confettiOverlay.confettiContainer
            transferDetailsText = binding.confettiOverlay.transferDetailsText
            
            // Initially hide dialog
            confettiContainer.visibility = View.GONE
            
            // Set up tap to dismiss
            confettiContainer.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    dismissSuccessDialog()
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up success dialog", e)
        }
    }
    
    private fun showInsufficientFundsDialog(crypto: CryptoCurrency, requiredAmount: Double) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_insufficient_funds, null)
        
        // Set up dialog elements
        val assetIcon = dialogView.findViewById<ImageView>(R.id.assetIcon)
        val assetName = dialogView.findViewById<TextView>(R.id.assetName)
        val assetSymbol = dialogView.findViewById<TextView>(R.id.assetSymbol)
        val requiredAmountText = dialogView.findViewById<TextView>(R.id.requiredAmount)
        val availableBalanceText = dialogView.findViewById<TextView>(R.id.availableBalance)
        val shortageText = dialogView.findViewById<TextView>(R.id.shortage)
        
        // Set values
        assetIcon.setImageResource(crypto.iconResId)
        assetName.text = crypto.name
        assetSymbol.text = crypto.symbol
        requiredAmountText.text = "$requiredAmount ${crypto.symbol}"
        availableBalanceText.text = "${crypto.getFormattedBalance()} ${crypto.symbol}"
        
        val shortage = requiredAmount - crypto.balance
        shortageText.text = "${String.format("%.6f", shortage)} ${crypto.symbol}"
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSuccessDialog(message: String) {
        try {
            transferDetailsText.text = message
            confettiContainer.visibility = View.VISIBLE
            
            // Auto-dismiss after 3 seconds
            dialogDismissRunnable = Runnable { 
                dismissSuccessDialog() 
            }
            dialogHandler.postDelayed(dialogDismissRunnable!!, 3000)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing success dialog", e)
        }
    }
    
    private fun dismissSuccessDialog() {
        try {
            dialogDismissRunnable?.let { dialogHandler.removeCallbacks(it) }
            if (::confettiContainer.isInitialized) {
                confettiContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error dismissing success dialog", e)
        }
    }
    
    private fun setupTestTrigger() {
        // Add long-press listener to balance card for testing success dialog
        binding.balanceCard.setOnLongClickListener {
            Log.d("MainActivity", "Long press detected on balance card - triggering test success dialog")
            showSuccessDialog("Excellent, you've sent 1.5 BTC!")
            true
        }
        
        // Long press on currency selector to test BT mesh
        binding.currencySelector.setOnLongClickListener {
            testBluetoothMesh()
            true
        }
    }
    
    private fun testBluetoothMesh() {
        Log.d("MainActivity", "=== TESTING BLUETOOTH MESH ===")
        
        // Get diagnostics
        val diagnostics = BluetoothMeshManager.getDiagnostics()
        Log.d("MainActivity", diagnostics)
        
        // Test local message
        BluetoothMeshManager.testLocalMessage()
        
        // Show diagnostics to user
        AlertDialog.Builder(this)
            .setTitle("BT Mesh Diagnostics")
            .setMessage(diagnostics)
            .setPositiveButton("Test Send") { _, _ ->
                // Try to send a test message to first discovered peer
                val peers = BTMeshTransferCoordinator.getDiscoveredPeers()
                if (peers.isNotEmpty()) {
                    val peer = peers.first()
                    lifecycleScope.launch {
                        val result = BluetoothMeshManager.sendMessage(
                            peer.peerId,
                            "DIAGNOSTIC_TEST_${System.currentTimeMillis()}"
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "Test send to ${peer.deviceName}: ${if (result) "SUCCESS" else "FAILED"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "No peers discovered", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Test Transfer") { _, _ ->
                // Initiate a real test token transfer
                val peers = BTMeshTransferCoordinator.getDiscoveredPeers()
                if (peers.isNotEmpty()) {
                    val peer = peers.first()
                    
                    // Create a test token
                    val testToken = Token(
                        id = "test_${System.currentTimeMillis()}",
                        name = "Test Token",
                        type = "Unicity Token",
                        jsonData = "{}", // Minimal JSON data
                        status = TokenStatus.CONFIRMED
                    )
                    
                    Toast.makeText(
                        this@MainActivity,
                        "Starting test transfer to ${peer.deviceName}...",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    // Use the real transfer flow
                    BTMeshTransferCoordinator.initiateTransfer(testToken, peer.peerId, peer.deviceName)
                    
                    Log.d("MainActivity", "Initiated test token transfer to ${peer.peerId}")
                } else {
                    Toast.makeText(this, "No peers discovered", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Ping Test") { _, _ ->
                // Send ping to test bi-directional communication
                val peers = BTMeshTransferCoordinator.getDiscoveredPeers()
                if (peers.isNotEmpty()) {
                    val peer = peers.first()
                    lifecycleScope.launch {
                        val pingId = System.currentTimeMillis().toString()
                        val pingMessage = "PING:$pingId"
                        Log.d("MainActivity", "Sending PING to ${peer.peerId}: $pingMessage")
                        
                        val result = BluetoothMeshManager.sendMessage(peer.peerId, pingMessage)
                        
                        Toast.makeText(
                            this@MainActivity,
                            "PING sent to ${peer.deviceName}: ${if (result) "SUCCESS (check logs for PONG)" else "FAILED"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "No peers discovered", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
    
    private fun showTestMessageDialog(message: String, fromDevice: String) {
        AlertDialog.Builder(this)
            .setTitle("🎉 BT Mesh Test Message Received!")
            .setMessage("""
                ✅ Message successfully received via Bluetooth Mesh!
                
                From: $fromDevice
                Message: $message
                
                This confirms that:
                • BT mesh communication is working
                • GATT server is receiving messages
                • Event flow is functioning properly
                
                Now let's test token transfers!
            """.trimIndent())
            .setPositiveButton("Great!") { dialog, _ ->
                dialog.dismiss()
                
                // Automatically show transfer approval dialog for testing
                showTestTransferApprovalDialog(fromDevice)
            }
            .setNegativeButton("Close", null)
            .show()
    }
    
    private fun showTestTransferApprovalDialog(senderDevice: String) {
        AlertDialog.Builder(this)
            .setTitle("Test Token Transfer")
            .setMessage("""
                Would you like to test the token transfer approval dialog?
                
                This will simulate receiving a token transfer request from:
                $senderDevice
            """.trimIndent())
            .setPositiveButton("Yes, Test It") { _, _ ->
                // Create a test approval request
                val testApproval = com.unicity.nfcwalletdemo.bluetooth.TransferApprovalRequest(
                    transferId = "TEST_${System.currentTimeMillis()}",
                    senderPeerId = senderDevice,
                    senderName = "Test Device",
                    tokenType = "Test Token",
                    tokenName = "Demo Token",
                    tokenPreview = "Test Token: Demo Token",
                    timestamp = System.currentTimeMillis()
                )
                
                // Show the actual transfer approval dialog
                showTransferApprovalDialog(testApproval)
            }
            .setNegativeButton("Not Now", null)
            .show()
    }
    
    private fun initializeBluetoothMesh() {
        // Initialize the Bluetooth mesh manager
        BluetoothMeshManager.initialize(this)
        
        // Don't observe mesh events here - let BTMeshTransferCoordinator handle all messages
        // This prevents MainActivity from showing transfer messages as toasts
    }
    
    
    private fun showNfcWaitingDialog(crypto: CryptoCurrency, amount: Double) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_nfc_waiting, null)
        val transferDetails = dialogView.findViewById<TextView>(R.id.transferDetails)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelNfc)
        
        transferDetails.text = "Tap phones together to send $amount ${crypto.symbol}"
        
        nfcWaitingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        btnCancel.setOnClickListener {
            cancelNfcTransfer()
        }
        
        nfcWaitingDialog?.show()
    }
    
    private fun hideNfcWaitingDialog() {
        nfcWaitingDialog?.dismiss()
        nfcWaitingDialog = null
    }
    
    private fun cancelNfcTransfer() {
        Log.d("MainActivity", "User cancelled NFC transfer")
        currentTransferringCrypto = null
        disableNfcTransfer()
        hideNfcWaitingDialog()
        Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun startCryptoTransfer(crypto: CryptoCurrency, amount: Double) {
        currentTransferringCrypto = crypto
        
        Log.d("MainActivity", "Starting NFC crypto transfer: EXACT amount = $amount ${crypto.symbol}")
        showNfcWaitingDialog(crypto, amount)
        
        val realNfcTransceiver = nfcAdapter?.let { RealNfcTransceiver(it) }
        if (realNfcTransceiver == null) {
            Toast.makeText(this, "NFC not available or enabled", Toast.LENGTH_SHORT).show()
            currentTransferringCrypto = null
            return
        }

        val directNfcClient = DirectNfcClient(
            sdkService = viewModel.getSdkService(),
            apduTransceiver = realNfcTransceiver,
            onTransferComplete = {
                Log.d("MainActivity", "✅ NFC crypto transfer completed")
                runOnUiThread {
                    hideNfcWaitingDialog()
                    currentTransferringCrypto = null
                    val newBalance = crypto.balance - amount
                    Log.d("MainActivity", "Deducting from sender: ${crypto.balance} - $amount = $newBalance")
                    viewModel.updateCryptoBalance(crypto.id, newBalance)
                    realNfcTransceiver.disableReaderMode(this) // Use transceiver's disable
                    
                    // Show success dialog for crypto transfer
                    showSuccessDialog("Sent $amount ${crypto.symbol} successfully!")
                    Toast.makeText(this@MainActivity, "Sent $amount ${crypto.symbol} successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "NFC crypto transfer error: $error")
                runOnUiThread {
                    hideNfcWaitingDialog()
                    currentTransferringCrypto = null
                    realNfcTransceiver.disableReaderMode(this) // Use transceiver's disable
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
        try {
            realNfcTransceiver.enableReaderMode(this) // Use transceiver's enable
            Log.d("MainActivity", "NFC reader mode enabled for crypto transfer")
            
            // Start the NFC transfer process
            directNfcClient.startNfcTransfer()
            Log.d("MainActivity", "NFC crypto transfer started")
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
    
    private fun manualSubmitOfflineTransfer(token: Token) {
        if (!token.isOfflineTransfer || token.pendingOfflineData.isNullOrEmpty()) {
            Toast.makeText(this, "Token is not an offline transfer or missing data", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Update token status to submitted
                val submittedToken = token.copy(status = TokenStatus.SUBMITTED)
                viewModel.updateToken(submittedToken)
                
                Toast.makeText(this@MainActivity, "Submitting ${token.name} to network...", Toast.LENGTH_SHORT).show()
                
                // Parse the pending offline data
                val pendingData = gson.fromJson(token.pendingOfflineData, Map::class.java)
                val receiverIdentityJson = pendingData["receiverIdentity"] as? String ?: ""
                val offlineTransactionJson = pendingData["offlineTransaction"] as? String ?: ""
                
                if (receiverIdentityJson.isEmpty() || offlineTransactionJson.isEmpty()) {
                    throw Exception("Invalid pending offline data")
                }
                
                // Attempt to complete the offline transfer
                val result = completeOfflineTransfer(receiverIdentityJson, offlineTransactionJson)
                
                // Update token status to confirmed
                val confirmedToken = submittedToken.copy(
                    status = TokenStatus.CONFIRMED,
                    unicityAddress = "unicity_manual_submit_${System.currentTimeMillis()}",
                    jsonData = result,
                    sizeBytes = result.length,
                    pendingOfflineData = null
                )
                
                viewModel.updateToken(confirmedToken)
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Token ${token.name} successfully submitted to network!", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to manually submit offline transfer", e)
                
                // Update token status back to failed
                val failedToken = token.copy(status = TokenStatus.FAILED)
                viewModel.updateToken(failedToken)
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to submit ${token.name}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private suspend fun completeOfflineTransfer(receiverIdentityJson: String, offlineTransactionJson: String): String {
        val result = sdkService.completeOfflineTransfer(receiverIdentityJson, offlineTransactionJson)
        return result.fold(
            onSuccess = { it },
            onFailure = { throw it }
        )
    }
    
    private fun shareToken(token: Token) {
        // Show dialog to choose between saving to filesystem or sharing
        val options = arrayOf("Save to Downloads", "Share via app")
        
        AlertDialog.Builder(this)
            .setTitle("Export Token")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveTokenToDownloads(token)
                    1 -> shareTokenViaIntent(token)
                }
            }
            .show()
    }
    
    private fun saveTokenToDownloads(token: Token) {
        try {
            // Get the token's JSON data
            val tokenJson = token.jsonData
            if (tokenJson.isNullOrEmpty()) {
                Toast.makeText(this, "Token has no data to save", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create filename
            val fileName = "token_${token.name.replace(" ", "_")}_${System.currentTimeMillis()}.txf"
            
            // Use MediaStore for Android 12+
            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(tokenJson.toByteArray())
                }
                Toast.makeText(this, "Token saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                Log.d("MainActivity", "Token saved to Downloads: $fileName")
            } ?: run {
                Toast.makeText(this, "Failed to create file in Downloads", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save token", e)
            Toast.makeText(this, "Failed to save token: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareTokenViaIntent(token: Token) {
        try {
            // Get the token's JSON data
            val tokenJson = token.jsonData
            if (tokenJson.isNullOrEmpty()) {
                Toast.makeText(this, "Token has no data to share", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create a temporary file in the cache directory
            val fileName = "token_${token.name.replace(" ", "_")}_${System.currentTimeMillis()}.txf"
            val file = File(cacheDir, fileName)
            
            // Write the JSON data to the file
            file.writeText(tokenJson)
            
            // Create a content URI for the file
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            
            // Create the share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Unicity Token: ${token.name}")
                putExtra(Intent.EXTRA_TEXT, "Sharing Unicity token: ${token.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Launch the share chooser
            startActivity(Intent.createChooser(shareIntent, "Share Token"))
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to share token", e)
            Toast.makeText(this, "Failed to share token: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startNfcTestTransfer() {
        checkNfc {
            Toast.makeText(this, "Starting NFC test transfer...", Toast.LENGTH_SHORT).show()
            
            val realNfcTransceiver = nfcAdapter?.let { RealNfcTransceiver(it) }
            if (realNfcTransceiver == null) {
                Toast.makeText(this, "NFC not available", Toast.LENGTH_SHORT).show()
                return@checkNfc
            }
            
            val testNfcClient = DirectNfcClient(
                sdkService = viewModel.getSdkService(),
                apduTransceiver = realNfcTransceiver,
                onTransferComplete = {
                    Log.d("MainActivity", "✅ NFC test transfer completed")
                    runOnUiThread {
                        vibrateSuccess()
                        realNfcTransceiver.disableReaderMode(this)
                        showSuccessDialog("NFC test successful! Connection is working.")
                    }
                },
                onError = { error ->
                    Log.e("MainActivity", "NFC test transfer error: $error")
                    runOnUiThread {
                        vibrateError()
                        realNfcTransceiver.disableReaderMode(this)
                        Toast.makeText(this@MainActivity, "Test failed: $error", Toast.LENGTH_LONG).show()
                    }
                },
                onProgress = { current, total ->
                    Log.d("MainActivity", "NFC test progress: $current/$total")
                    runOnUiThread {
                        if (current == 0 && total == 1) {
                            vibrateConnectionEstablished()
                            Toast.makeText(this@MainActivity, "NFC connected! Testing...", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            
            // Set test mode
            testNfcClient.setTestMode(true)
            
            // Enable NFC reader mode
            try {
                realNfcTransceiver.enableReaderMode(this)
                Log.d("MainActivity", "NFC reader mode enabled for test transfer")
                
                // Start the test transfer
                testNfcClient.startNfcTransfer()
                Log.d("MainActivity", "NFC test transfer started")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to enable NFC for test", e)
                Toast.makeText(this, "Failed to enable NFC: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun vibrateConnectionEstablished() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Short pulse to indicate connection
                    it.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to vibrate: ${e.message}")
        }
    }
    
    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Double pulse for success
                    val pattern = longArrayOf(0, 100, 100, 100)
                    it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(longArrayOf(0, 100, 100, 100), -1)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to vibrate: ${e.message}")
        }
    }
    
    private fun vibrateError() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Long pulse for error
                    it.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(300)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to vibrate: ${e.message}")
        }
    }
    
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            BLUETOOTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(this, "Bluetooth permissions granted! Tap token to transfer again.", Toast.LENGTH_SHORT).show()
                    // Initialize Bluetooth mesh after permissions are granted
                    initializeBluetoothMesh()
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for token transfer", Toast.LENGTH_LONG).show()
                }
            }
            ContactListDialog.REQUEST_CODE_CONTACTS -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                currentContactDialog?.onPermissionResult(requestCode, granted)
                if (!granted) {
                    Toast.makeText(this, "Contact permission denied. Showing sample contacts instead.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // BT Mesh Transfer Methods
    private fun initializeBTMesh() {
        if (DEBUG_BT) Log.d("MainActivity", "=== initializeBTMesh STARTED ===")
        
        bluetoothMeshTransferService = BluetoothMeshTransferService(this)
        if (DEBUG_BT) Log.d("MainActivity", "BluetoothMeshTransferService created")
        
        // Initialize the singleton BTMeshTransferCoordinator FIRST
        // This ensures it's ready to receive messages before BluetoothMeshManager starts
        BTMeshTransferCoordinator.initialize(
            appContext = applicationContext,
            transferService = bluetoothMeshTransferService,
            sdk = sdkService
        )
        if (DEBUG_BT) Log.d("MainActivity", "BTMeshTransferCoordinator initialized")
        
        // Initialize BluetoothMeshManager AFTER coordinator is ready
        if (checkBluetoothPermissions()) {
            if (DEBUG_BT) Log.d("MainActivity", "Bluetooth permissions granted, initializing BluetoothMeshManager")
            BluetoothMeshManager.initialize(this)
            
            // Add a test collector to verify events are flowing
            lifecycleScope.launch {
                Log.d("MainActivity", "Starting test event collector...")
                try {
                    BluetoothMeshManager.meshEvents.collect { event ->
                        if (DEBUG_BT) Log.d("MainActivity", "!!! MAIN ACTIVITY GOT EVENT: ${event::class.simpleName}")
                        when (event) {
                            is BluetoothMeshManager.MeshEvent.MessageReceived -> {
                                Log.d("MainActivity", "!!! Message from ${event.fromDevice}: ${event.message}")
                                
                                // ALWAYS show a toast for ANY message to debug
                                runOnUiThread {
                                    val messagePreview = event.message.take(40)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Msg: $messagePreview...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    
                                    // Handle TRIGGER_TRANSFER message for testing
                                    if (event.message == "TRIGGER_TRANSFER") {
                                        Log.d("MainActivity", "!!! RECEIVED TRIGGER_TRANSFER - Forwarding to coordinator")
                                        
                                        // Don't create a test approval here!
                                        // Instead, let the coordinator handle the actual transfer request
                                        // The sender should have already sent a proper transfer request
                                        
                                        runOnUiThread {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Test trigger received - waiting for actual transfer request...",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    
                                    // Force show dialog for ANY message containing "TRIGGER" (legacy)
                                    if (event.message.contains("TRIGGER", ignoreCase = true) && event.message != "TRIGGER_TRANSFER") {
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("TRIGGER Found!")
                                            .setMessage("Full message:\n\n${event.message}\n\nLength: ${event.message.length}")
                                            .setPositiveButton("Show Transfer Dialog") { _, _ ->
                                                // Show the transfer approval dialog
                                                val testApproval = com.unicity.nfcwalletdemo.bluetooth.TransferApprovalRequest(
                                                    transferId = "TRIGGER_TEST_${System.currentTimeMillis()}",
                                                    senderPeerId = event.fromDevice,
                                                    senderName = "Test Device",
                                                    tokenType = "Unicity Token",
                                                    tokenName = "Test Token from Trigger",
                                                    tokenPreview = "Test Token Preview",
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                showTransferApprovalDialog(testApproval)
                                            }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    }
                                }
                                
                                // Debug all messages that start with specific patterns
                                when {
                                    event.message.startsWith("DIAGNOSTIC_TEST_") -> {
                                        Log.d("MainActivity", "MATCHED: DIAGNOSTIC_TEST_")
                                        runOnUiThread {
                                            showTestMessageDialog(event.message, event.fromDevice)
                                        }
                                    }
                                    event.message.contains("TRIGGER_TOKEN_TRANSFER_TEST") -> {
                                        Log.d("MainActivity", "MATCHED: Contains TRIGGER_TOKEN_TRANSFER_TEST")
                                        runOnUiThread {
                                            AlertDialog.Builder(this@MainActivity)
                                                .setTitle("Contains Trigger!")
                                                .setMessage("Message: ${event.message}")
                                                .setPositiveButton("Show Approval") { _, _ ->
                                                    val testApproval = com.unicity.nfcwalletdemo.bluetooth.TransferApprovalRequest(
                                                        transferId = "CONTAINS_TEST_${System.currentTimeMillis()}",
                                                        senderPeerId = event.fromDevice,
                                                        senderName = "Test Device",
                                                        tokenType = "Unicity Token",
                                                        tokenName = "Test Token via Contains",
                                                        tokenPreview = "Test Token Preview",
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    showTransferApprovalDialog(testApproval)
                                                }
                                                .show()
                                        }
                                    }
                                    else -> {
                                        Log.d("MainActivity", "NO MATCH for message: '${event.message}'")
                                    }
                                }
                                
                                // Check if it's a simple token transfer notification
                                if (event.message.startsWith("TOKEN_TRANSFER_REQUEST:")) {
                                    Log.d("MainActivity", "!!! RECEIVED TOKEN TRANSFER NOTIFICATION !!!")
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Token transfer notification received!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                
                                
                                // Forward ALL messages to BTMeshTransferCoordinator
                                // It will handle parsing and filtering
                                lifecycleScope.launch {
                                    Log.d("MainActivity", "Forwarding ALL messages to BTMeshTransferCoordinator")
                                    val messageData = event.message.toByteArray(Charsets.UTF_8)
                                    BTMeshTransferCoordinator.handleIncomingMessage(messageData, event.fromDevice)
                                }
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Test collector failed", e)
                }
                Log.d("MainActivity", "!!! Test collector ended !!!")
            }
        } else {
            Log.e("MainActivity", "Bluetooth permissions NOT granted!")
        }
        
        if (DEBUG_BT) Log.d("MainActivity", "=== initializeBTMesh COMPLETED ===")
    }
    
    private val shownApprovals = mutableSetOf<String>()
    
    private fun setupTransferApprovalListener() {
        Log.d("MainActivity", "Setting up transfer approval listener")
        
        // Observe pending transfer approvals
        lifecycleScope.launch {
            Log.d("MainActivity", "Starting to collect pending approvals")
            BTMeshTransferCoordinator.pendingApprovals.collectLatest { approvals ->
                Log.d("MainActivity", "Pending approvals updated: ${approvals.size} approvals")
                approvals.forEach { approval ->
                    Log.d("MainActivity", "Approval: ${approval.transferId} from ${approval.senderName}")
                    
                    // Only show dialog if we haven't shown it already
                    if (!shownApprovals.contains(approval.transferId)) {
                        Log.d("MainActivity", "Showing approval dialog for ${approval.transferId}")
                        shownApprovals.add(approval.transferId)
                        
                        runOnUiThread {
                            showTransferApprovalDialog(approval)
                        }
                    } else {
                        Log.d("MainActivity", "Already shown approval for ${approval.transferId}")
                    }
                }
            }
        }
    }
    
    private fun showTransferApprovalDialog(approval: com.unicity.nfcwalletdemo.bluetooth.TransferApprovalRequest) {
        val dialog = TransferApprovalDialog()
        dialog.setApprovalRequest(approval)
        dialog.setListener(object : TransferApprovalDialog.ApprovalListener {
            override fun onApproved(transferId: String) {
                BTMeshTransferCoordinator.approveTransfer(transferId)
                vibrateSuccess()
            }
            
            override fun onRejected(transferId: String) {
                BTMeshTransferCoordinator.rejectTransfer(transferId)
            }
        })
        dialog.show(supportFragmentManager, "transfer_approval")
    }
    
    private fun showBTMeshPeerSelection(token: Token) {
        // Show peer selection dialog
        val dialog = PeerSelectionDialog()
        
        // Get discovered peers
        val peers = getDiscoveredPeers()
        dialog.setPeers(peers)
        
        dialog.setListener(object : PeerSelectionDialog.PeerSelectionListener {
            override fun onPeerSelected(peer: DiscoveredPeer) {
                // Start BT mesh transfer directly - NO NFC TAP NEEDED!
                currentTransferringToken = token
                tokenAdapter.setTransferring(token, true)
                
                // Show initial progress (1 of 10 steps)
                tokenAdapter.updateTransferProgress(token, 1, 10)
                
                // Initiate the BT mesh transfer
                BTMeshTransferCoordinator.initiateTransfer(token, peer.peerId, peer.deviceName)
                
                // Start observing the transfer progress
                observeTransferProgress(token.id)
                
                // Show a toast to indicate transfer started
                Toast.makeText(this@MainActivity, "Requesting permission from ${peer.deviceName}...", Toast.LENGTH_SHORT).show()
            }
            
            override fun onNfcSelected() {
                // Use existing NFC flow
                startNfcTokenTransfer(token)
            }
            
            override fun onCancelled() {
                // Do nothing
            }
        })
        
        dialog.show(supportFragmentManager, "peer_selection")
    }
    
    private fun getDiscoveredPeers(): List<DiscoveredPeer> {
        // Get peers from BTMeshTransferCoordinator
        return BTMeshTransferCoordinator.getDiscoveredPeers()
    }
    
    private fun getTokenById(tokenId: String): Token? {
        return viewModel.tokens.value.find { it.id == tokenId }
    }
    
    private fun observeTransferProgress(tokenId: String) {
        lifecycleScope.launch {
            BTMeshTransferCoordinator.activeTransferStates.collectLatest { states ->
                val transferState = states.entries.find { 
                    val transfer = BTMeshTransferCoordinator.getActiveTransfer(it.key)
                    transfer?.tokenId == tokenId
                }
                
                transferState?.let { (transferId, state) ->
                    when (state) {
                        com.unicity.nfcwalletdemo.bluetooth.TransferState.COMPLETED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            vibrateSuccess()
                            showSuccessDialog("Token transferred successfully!")
                            
                            // Remove token from wallet
                            viewModel.removeToken(tokenId)
                        }
                        com.unicity.nfcwalletdemo.bluetooth.TransferState.REJECTED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            // Different vibration for rejection
                            vibrateError()
                            showErrorDialog("Transfer Cancelled", "The recipient declined the transfer request.")
                        }
                        com.unicity.nfcwalletdemo.bluetooth.TransferState.FAILED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            vibrateError()
                            showErrorDialog("Transfer Failed", "The token transfer could not be completed.")
                        }
                        else -> {
                            // Update progress UI if needed
                            val progressText = when (state) {
                                com.unicity.nfcwalletdemo.bluetooth.TransferState.REQUESTING_PERMISSION -> "Requesting permission..."
                                com.unicity.nfcwalletdemo.bluetooth.TransferState.WAITING_FOR_ADDRESS -> "Waiting for recipient..."
                                com.unicity.nfcwalletdemo.bluetooth.TransferState.CREATING_PACKAGE -> "Creating transfer..."
                                com.unicity.nfcwalletdemo.bluetooth.TransferState.SENDING_PACKAGE -> "Sending token..."
                                com.unicity.nfcwalletdemo.bluetooth.TransferState.APPROVED -> "Transfer approved..."
                                else -> "Processing..."
                            }
                            val token = getTokenById(tokenId)
                            token?.let { 
                                // Only show transferring state and progress after approval
                                if (state == com.unicity.nfcwalletdemo.bluetooth.TransferState.REQUESTING_PERMISSION) {
                                    // Don't show progress while waiting for permission
                                    tokenAdapter.setTransferring(it, false)
                                } else {
                                    tokenAdapter.setTransferring(it, true)
                                    // Update the progress based on state
                                    val progress = when (state) {
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.APPROVED -> Pair(1, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.WAITING_FOR_ADDRESS -> Pair(2, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.GENERATING_ADDRESS -> Pair(3, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.CREATING_PACKAGE -> Pair(5, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.SENDING_PACKAGE -> Pair(7, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.WAITING_FOR_PACKAGE -> Pair(6, 10)
                                        com.unicity.nfcwalletdemo.bluetooth.TransferState.COMPLETING_TRANSFER -> Pair(9, 10)
                                        else -> Pair(1, 10)
                                    }
                                    tokenAdapter.updateTransferProgress(it, progress.first, progress.second)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    // QR Code Scanner
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQRCode(result.contents)
        }
    }
    
    private fun scanQRCode() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan payment request QR code")
            .setCameraId(0)
            .setBeepEnabled(true)
            .setOrientationLocked(true)
            .setCaptureActivity(PortraitCaptureActivity::class.java) // Force portrait mode
        
        barcodeLauncher.launch(options)
    }
    
    private fun openAgentMap() {
        val intent = Intent(this, com.unicity.nfcwalletdemo.ui.agent.AgentMapActivity::class.java)
        startActivity(intent)
    }
    
    private fun showOverflowMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val menu = popup.menu
        
        // Add custom menu items
        menu.add(0, 1, 0, "About")
        menu.add(0, 2, 1, "Mint a Token")
        menu.add(0, 3, 2, "Test Offline Transfer")
        menu.add(0, 4, 3, "Bluetooth Mesh Discovery")
        menu.add(0, 5, 4, "Demo Mode")
        menu.add(0, 6, 5, "Reset Wallet")
        
        // Chat conversations moved to Agent Map header
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showAboutDialog()
                    true
                }
                2 -> {
                    showMintTokenDialog()
                    true
                }
                3 -> {
                    runOfflineTransferTest()
                    true
                }
                4 -> {
                    startActivity(Intent(this, com.unicity.nfcwalletdemo.ui.bluetooth.BluetoothMeshActivity::class.java))
                    true
                }
                5 -> {
                    showDemoModeDialog()
                    true
                }
                6 -> {
                    showResetWalletDialog()
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }
    
    private fun handleScannedQRCode(content: String) {
        // Check if it's a deep link
        if (content.startsWith("nfcwallet://")) {
            try {
                val uri = Uri.parse(content)
                
                when (uri.host) {
                    "payment-request" -> {
                        val requestId = uri.getQueryParameter("id")
                        val recipientAddress = uri.getQueryParameter("recipient")
                        val currency = uri.getQueryParameter("currency")
                        val amount = uri.getQueryParameter("amount")
                        
                        if (requestId != null && recipientAddress != null) {
                            if (currency != null && amount != null) {
                                // Recipient specified currency and amount
                                showSpecificPaymentDialog(requestId, recipientAddress, currency, amount)
                            } else {
                                // Let sender choose currency and amount
                                showPaymentDialog(requestId, recipientAddress)
                            }
                        } else {
                            Toast.makeText(this, "Invalid payment request", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "mint-request" -> {
                        handleMintRequest(uri)
                    }
                    else -> {
                        Toast.makeText(this, "Unknown QR code type", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parsing QR code", e)
                Toast.makeText(this, "Error parsing QR code", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Not a valid wallet QR code", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPaymentDialog(requestId: String, recipientAddress: String) {
        showPaymentCryptoSelector(requestId, recipientAddress)
    }
    
    private fun showSpecificPaymentDialog(requestId: String, recipientAddress: String, currency: String, amount: String) {
        // Find the crypto matching the specified currency
        val crypto = viewModel.cryptocurrencies.value.find { it.symbol == currency }
        
        if (crypto == null) {
            Toast.makeText(this, "Currency $currency not available in your wallet", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check if user has sufficient balance
        val requestedAmount = amount.toDoubleOrNull() ?: 0.0
        if (requestedAmount > crypto.balance) {
            showInsufficientFundsDialog(crypto, requestedAmount)
            return
        }
        
        // Show confirmation dialog
        AlertDialog.Builder(this)
            .setTitle("Confirm Payment")
            .setMessage("Send $amount $currency to the requested address?")
            .setPositiveButton("Send") { _, _ ->
                // Execute payment with specified amount
                executePayment(requestId, crypto, amount)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun executePayment(requestId: String, crypto: CryptoCurrency, amount: String) {
        lifecycleScope.launch {
            try {
                // Show loading
                Toast.makeText(this@MainActivity, "Sending payment...", Toast.LENGTH_SHORT).show()
                
                // Get sender address
                val sharedPrefs = getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
                val senderAddress = sharedPrefs.getString("wallet_address", null) ?: run {
                    val newAddress = "wallet_${java.util.UUID.randomUUID().toString().take(8)}"
                    sharedPrefs.edit().putString("wallet_address", newAddress).apply()
                    newAddress
                }
                
                // Complete the payment request
                val response = PaymentRequestService.api.completePaymentRequest(
                    requestId,
                    CompletePaymentRequestDto(
                        senderAddress = senderAddress,
                        currencySymbol = crypto.symbol,
                        amount = amount
                    )
                )
                
                // Update local balance
                val newBalance = crypto.balance - (amount.toDoubleOrNull() ?: 0.0)
                viewModel.updateCryptoBalance(crypto.id, newBalance)
                
                // Show success
                Toast.makeText(this@MainActivity, "Payment sent successfully!", Toast.LENGTH_SHORT).show()
                vibrateSuccess()
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending payment", e)
                Toast.makeText(this@MainActivity, "Error sending payment: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showPaymentCryptoSelector(requestId: String, recipientAddress: String) {
        val cryptoList = viewModel.cryptocurrencies.value
        if (cryptoList.isEmpty()) {
            Toast.makeText(this, "No cryptocurrencies available", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (cryptoList.size == 1) {
            // If only one crypto, skip selection
            showPaymentAmountDialog(cryptoList[0], requestId, recipientAddress)
        } else {
            // Show crypto selection dialog
            val cryptoNames = cryptoList.map { "${it.name} (${it.symbol})" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Currency")
                .setItems(cryptoNames) { _, which ->
                    showPaymentAmountDialog(cryptoList[which], requestId, recipientAddress)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun showPaymentAmountDialog(crypto: CryptoCurrency, requestId: String, recipientAddress: String) {
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
        cryptoName.text = "${crypto.name} Payment"
        availableBalance.text = "Available: ${crypto.getFormattedBalance()} ${crypto.symbol}"
        
        // Update estimated value on amount change
        fun updateEstimatedValue(amount: Double) {
            val value = when (selectedCurrency) {
                "USD" -> amount * crypto.priceUsd
                "EUR" -> amount * crypto.priceEur
                else -> amount * crypto.priceUsd
            }
            val currencySymbol = if (selectedCurrency == "EUR") "€" else "$"
            estimatedValue.text = "≈ ${currencySymbol}${String.format("%.2f", value)}"
        }
        
        // Set up amount input listener
        amountInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val amount = s.toString().toDoubleOrNull() ?: 0.0
                updateEstimatedValue(amount)
            }
        })
        
        // Set up percentage chips
        chip25.setOnClickListener {
            val amount = crypto.balance * 0.25
            amountInput.setText(formatCryptoAmount(amount))
        }
        
        chip50.setOnClickListener {
            val amount = crypto.balance * 0.5
            amountInput.setText(formatCryptoAmount(amount))
        }
        
        chip75.setOnClickListener {
            val amount = crypto.balance * 0.75
            amountInput.setText(formatCryptoAmount(amount))
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
            val amount = amountInput.text.toString().toDoubleOrNull()
            
            if (amount != null && amount > 0) {
                if (amount <= crypto.balance) {
                    dialog.dismiss()
                    executePayment(requestId, crypto, amount.toString())
                } else {
                    showInsufficientFundsDialog(crypto, amount)
                }
            } else {
                Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
    }
    
    private fun sendPaymentRequest(requestId: String, recipientAddress: String, crypto: CryptoCurrency, amount: Double) {
        lifecycleScope.launch {
            try {
                // Show loading
                Toast.makeText(this@MainActivity, "Sending payment...", Toast.LENGTH_SHORT).show()
                
                // Complete the payment request
                val sharedPrefs = getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
                val senderAddress = sharedPrefs.getString("wallet_address", null) ?: run {
                    val newAddress = "wallet_${java.util.UUID.randomUUID().toString().take(8)}"
                    sharedPrefs.edit().putString("wallet_address", newAddress).apply()
                    newAddress
                }
                val response = PaymentRequestService.api.completePaymentRequest(
                    requestId,
                    CompletePaymentRequestDto(
                        senderAddress = senderAddress,
                        currencySymbol = crypto.symbol,
                        amount = amount.toString()
                    )
                )
                
                // Update local balance
                viewModel.updateCryptoBalance(crypto.id, crypto.balance - amount)
                
                Toast.makeText(this@MainActivity, 
                    "Payment sent: $amount ${crypto.symbol}", 
                    Toast.LENGTH_LONG).show()
                    
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending payment", e)
                Toast.makeText(this@MainActivity, 
                    "Error sending payment: ${e.message}", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showPaymentReceivedDialog(payment: PaymentDetails) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_payment_received, null)
        
        val currencyIcon = dialogView.findViewById<ImageView>(R.id.currencyIcon)
        val amountText = dialogView.findViewById<TextView>(R.id.amountText)
        val btnOk = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOk)
        
        // Set currency icon
        val crypto = viewModel.cryptocurrencies.value.find { it.symbol == payment.currencySymbol }
        currencyIcon.setImageResource(crypto?.iconResId ?: R.drawable.ic_bitcoin)
        
        // Set amount text
        amountText.text = "${payment.amount} ${payment.currencySymbol}"
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        btnOk.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // Also update the local balance
        crypto?.let {
            val newBalance = it.balance + (payment.amount.toDoubleOrNull() ?: 0.0)
            viewModel.updateCryptoBalance(it.id, newBalance)
        }
    }
    
    private fun pollForPaymentWithCallback(requestId: String, onPaymentReceived: (PaymentDetails) -> Unit) {
        lifecycleScope.launch {
            var polling = true
            var attempts = 0
            val maxAttempts = 60 // Poll for 1 minute
            
            while (polling && attempts < maxAttempts && isActive) {
                try {
                    val response = PaymentRequestService.api.pollPaymentRequest(requestId)
                    
                    when (response.status) {
                        "completed" -> {
                            response.paymentDetails?.let { payment ->
                                onPaymentReceived(payment)
                                polling = false
                            }
                        }
                        "expired" -> {
                            // Don't show toast, just stop polling as we'll auto-renew
                            polling = false
                        }
                    }
                    
                    attempts++
                    if (polling) {
                        delay(1000) // Poll every second
                    }
                    
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error polling payment request", e)
                    attempts++
                    delay(1000)
                }
            }
        }
    }
    
    private fun pollForPayment(requestId: String, onPaymentReceived: (PaymentDetails) -> Unit) {
        lifecycleScope.launch {
            var polling = true
            var attempts = 0
            val maxAttempts = 60 // Poll for 1 minute
            
            while (polling && attempts < maxAttempts) {
                try {
                    val response = PaymentRequestService.api.pollPaymentRequest(requestId)
                    
                    when (response.status) {
                        "completed" -> {
                            response.paymentDetails?.let { payment ->
                                onPaymentReceived(payment)
                                polling = false
                            }
                        }
                        "expired" -> {
                            Toast.makeText(this@MainActivity, "Payment request expired", Toast.LENGTH_SHORT).show()
                            polling = false
                        }
                    }
                    
                    attempts++
                    if (polling) {
                        delay(1000) // Poll every second
                    }
                    
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error polling payment request", e)
                    attempts++
                    delay(1000)
                }
            }
            
            if (attempts >= maxAttempts) {
                Toast.makeText(this@MainActivity, "Payment request timed out", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun formatCryptoAmount(amount: Double): String {
        return if (amount % 1 == 0.0) {
            amount.toInt().toString()
        } else {
            String.format("%.8f", amount).trimEnd('0').trimEnd('.')
        }
    }
    
    // Transfer Request Polling
    private fun startTransferPolling() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        if (unicityTag.isEmpty()) {
            Log.d("MainActivity", "No Unicity tag configured, skipping transfer polling")
            return
        }
        
        Log.d("MainActivity", "Starting transfer polling for tag: $unicityTag")
        
        transferPollingJob?.cancel()
        transferPollingJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val result = transferApiService.getPendingTransfers(unicityTag)
                    result.onSuccess { transfers ->
                        transfers.forEach { transfer ->
                            if (!processedTransferIds.contains(transfer.requestId)) {
                                processedTransferIds.add(transfer.requestId)
                                showTransferNotification(transfer)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error polling for transfers", e)
                }
                delay(5000) // Poll every 5 seconds
            }
        }
    }
    
    private fun stopTransferPolling() {
        transferPollingJob?.cancel()
        transferPollingJob = null
    }
    
    private fun showTransferNotification(transfer: TransferRequest) {
        runOnUiThread {
            val dialogView = layoutInflater.inflate(R.layout.dialog_transfer_notification, null)
            
            // Set up the dialog view
            val assetIcon = dialogView.findViewById<ImageView>(R.id.assetIcon)
            val assetName = dialogView.findViewById<TextView>(R.id.assetName)
            val assetAmount = dialogView.findViewById<TextView>(R.id.assetAmount)
            val senderTag = dialogView.findViewById<TextView>(R.id.senderTag)
            val message = dialogView.findViewById<TextView>(R.id.message)
            
            // Set the asset icon based on type
            if (transfer.assetType == "crypto") {
                // Set crypto icon based on asset name
                val iconRes = when (transfer.assetName.uppercase()) {
                    "BTC" -> R.drawable.ic_bitcoin
                    "ETH" -> R.drawable.ic_ethereum
                    "USDT" -> R.drawable.ic_tether
                    "EXAF" -> R.drawable.ic_franc
                    "ENGN" -> R.drawable.ic_naira
                    "SUB" -> R.drawable.subway
                    "USDC" -> R.drawable.usdc
                    "SOL" -> R.drawable.sol
                    else -> R.drawable.ic_coin_placeholder
                }
                assetIcon.setImageResource(iconRes)
            } else {
                // For tokens, use a generic token icon
                assetIcon.setImageResource(R.drawable.ic_token)
            }
            
            assetName.text = transfer.assetName
            assetAmount.text = transfer.amount
            senderTag.text = "From: ${transfer.senderTag}@unicity"
            
            if (!transfer.message.isNullOrEmpty()) {
                message.text = transfer.message
                message.visibility = View.VISIBLE
            } else {
                message.visibility = View.GONE
            }
            
            val dialog = AlertDialog.Builder(this)
                .setTitle("Incoming Transfer Request")
                .setView(dialogView)
                .setPositiveButton("Accept") { _, _ ->
                    acceptTransferRequest(transfer)
                }
                .setNegativeButton("Decline") { _, _ ->
                    rejectTransferRequest(transfer)
                }
                .setCancelable(false)
                .create()
            
            dialog.show()
            
            // Vibrate to notify user
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        }
    }
    
    private fun acceptTransferRequest(transfer: TransferRequest) {
        lifecycleScope.launch {
            try {
                val result = transferApiService.acceptTransfer(transfer.requestId)
                result.onSuccess {
                    Toast.makeText(this@MainActivity, "Transfer accepted", Toast.LENGTH_SHORT).show()
                    
                    // Update balance based on transfer details
                    if (transfer.assetType == "crypto") {
                        // Find the cryptocurrency
                        val crypto = viewModel.cryptocurrencies.value.find { 
                            it.symbol.equals(transfer.assetName, ignoreCase = true) 
                        }
                        
                        if (crypto != null) {
                            // Parse amount and add to balance
                            val amount = transfer.amount.toDoubleOrNull() ?: 0.0
                            if (amount > 0) {
                                val currentBalance = crypto.balance
                                val newBalance = currentBalance + amount
                                viewModel.updateCryptoBalance(crypto.id, newBalance)
                                
                                Toast.makeText(this@MainActivity, 
                                    "Received ${transfer.amount} ${transfer.assetName}", 
                                    Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Add new cryptocurrency if it doesn't exist
                            val amount = transfer.amount.toDoubleOrNull() ?: 0.0
                            if (amount > 0) {
                                viewModel.addReceivedCrypto(transfer.assetName, amount)
                                Toast.makeText(this@MainActivity, 
                                    "Received ${transfer.amount} ${transfer.assetName}", 
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (transfer.assetType == "token") {
                        // TODO: Handle token transfers
                        // For now, just refresh to potentially load new tokens
                        refreshWallet()
                    }
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, "Failed to accept transfer: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error accepting transfer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun rejectTransferRequest(transfer: TransferRequest) {
        lifecycleScope.launch {
            try {
                val result = transferApiService.rejectTransfer(transfer.requestId)
                result.onSuccess {
                    Toast.makeText(this@MainActivity, "Transfer declined", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, "Failed to decline transfer: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error declining transfer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun sendTransferRequestToBackend(recipient: Contact, crypto: CryptoCurrency, amount: Double, message: String) {
        lifecycleScope.launch {
            try {
                // Get sender's unicity tag from preferences
                val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                val senderTag = sharedPrefs.getString("unicity_tag", "") ?: ""
                
                // Extract recipient's unicity tag
                val recipientTag = recipient.getUnicityTag()
                
                if (recipientTag.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Recipient does not have a valid @unicity tag", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                // Send transfer request to backend
                val result = transferApiService.createTransferRequest(
                    senderTag = senderTag.ifEmpty { null },
                    recipientTag = recipientTag,
                    assetType = "crypto",
                    assetName = crypto.symbol,
                    amount = formatCryptoAmount(amount),
                    message = message.ifEmpty { null }
                )
                
                result.onSuccess { transferRequest ->
                    Toast.makeText(this@MainActivity, "Transfer request sent to ${recipient.name}", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "Transfer request created: ${transferRequest.requestId}")
                    
                    // Deduct balance immediately (optimistic update)
                    val currentBalance = crypto.balance
                    val newBalance = currentBalance - amount
                    viewModel.updateCryptoBalance(crypto.id, newBalance)
                    
                    // Store the transfer request ID and amount in case we need to revert
                    val prefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("pending_transfer_${transferRequest.requestId}", "${crypto.id}:$amount")
                        .apply()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, "Failed to send transfer request: ${error.message}", Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "Error creating transfer request", error)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error sending transfer request: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("MainActivity", "Error in sendTransferRequestToBackend", e)
            }
        }
    }
    
    private fun startOutgoingTransferStatusPolling() {
        // Poll for status updates on pending outgoing transfers
        lifecycleScope.launch {
            val prefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
            val pendingKeys = prefs.all.keys.filter { it.startsWith("pending_transfer_") }
            
            // For each pending transfer, we could implement status checking
            // This would require adding a GET endpoint to check transfer status
            // For now, we'll rely on the optimistic update approach
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopTransferPolling()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(handshakeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(p2pStatusReceiver)
    }
    
    private fun showHandshakeDialog(fromTag: String, fromName: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("New Chat Request")
                .setMessage("$fromTag is trying to chat")
                .setPositiveButton("Accept") { _, _ ->
                    // Get P2P service instance and accept handshake
                    val p2pService = com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getExistingInstance()
                    p2pService?.acceptHandshake(fromTag)
                    
                    // Open chat activity
                    val intent = Intent(this, com.unicity.nfcwalletdemo.ui.chat.ChatActivity::class.java).apply {
                        putExtra("extra_agent_tag", fromTag)
                        putExtra("extra_agent_name", fromName)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Close", null)
                .setCancelable(true)
                .show()
        }
    }
}