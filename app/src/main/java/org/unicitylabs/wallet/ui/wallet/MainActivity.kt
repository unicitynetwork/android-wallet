package org.unicitylabs.wallet.ui.wallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.bluetooth.BTMeshTransferCoordinator
import org.unicitylabs.wallet.bluetooth.BluetoothMeshManager
import org.unicitylabs.wallet.bluetooth.BluetoothMeshTransferService
import org.unicitylabs.wallet.bluetooth.DiscoveredPeer
import org.unicitylabs.wallet.data.model.Contact
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.model.TokenStatus
import org.unicitylabs.wallet.databinding.ActivityMainBinding
import org.unicitylabs.wallet.model.CryptoCurrency
import org.unicitylabs.wallet.nfc.DirectNfcClient
import org.unicitylabs.wallet.nfc.HybridNfcBluetoothClient
import org.unicitylabs.wallet.nfc.RealNfcTransceiver
import org.unicitylabs.wallet.nfc.SimpleNfcTransceiver
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import org.unicitylabs.wallet.transfer.toHexString
import org.unicitylabs.wallet.ui.bluetooth.PeerSelectionDialog
import org.unicitylabs.wallet.ui.bluetooth.TransferApprovalDialog
import org.unicitylabs.wallet.ui.scanner.PortraitCaptureActivity
import org.unicitylabs.wallet.util.JsonMapper
import org.unicitylabs.wallet.utils.PermissionUtils
import org.unicitylabs.wallet.viewmodel.WalletViewModel
import java.io.File
import org.unicitylabs.wallet.util.HexUtils

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenAdapter: TokenAdapter
    private lateinit var assetAdapter: AssetAdapter
    private var nfcAdapter: NfcAdapter? = null
    private var realNfcTransceiver: RealNfcTransceiver? = null
    private var currentTransferringToken: Token? = null
    private var currentTab = 0 // 0 for Assets (aggregated), 1 for Tokens (NFTs)
    private var selectedCurrency = "USD" // Kept for future use
    
    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1001
        private const val DEBUG_BT = false // Set to true to enable verbose BT logging
    }
    
    private lateinit var sdkService: UnicityJavaSdkService
    // Using shared JsonMapper.mapper
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
    
    // Handshake dialog receiver
    private val handshakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "org.unicitylabs.nfcwalletdemo.ACTION_HANDSHAKE_REQUEST") {
                val fromTag = intent.getStringExtra("from_tag") ?: return
                val fromName = intent.getStringExtra("from_name") ?: return
                showHandshakeDialog(fromTag, fromName)
            }
        }
    }
    
    // P2P status update receiver
    private val p2pStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "org.unicitylabs.nfcwalletdemo.UPDATE_P2P_STATUS") {
                updateLocationIconColor()
            }
        }
    }

    // Crypto received receiver (for demo crypto transfers via Nostr)
    private val cryptoReceivedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED") {
                val cryptoId = intent.getStringExtra("crypto_id") ?: return
                val amount = intent.getDoubleExtra("amount", 0.0)
                val symbol = intent.getStringExtra("crypto_symbol") ?: ""

                Log.d("MainActivity", "Crypto received broadcast: $amount $symbol (id: $cryptoId)")

                // Update the cryptocurrency balance
                val currentCryptos = viewModel.cryptocurrencies.value ?: emptyList()
                val crypto = currentCryptos.find { it.id == cryptoId }

                if (crypto != null) {
                    val newBalance = crypto.balance + amount
                    Log.d("MainActivity", "Updating ${crypto.symbol} balance: ${crypto.balance} + $amount = $newBalance")
                    viewModel.updateCryptoBalance(cryptoId, newBalance)

                    runOnUiThread {
                        // Format amount nicely
                        val formattedAmount = if (amount % 1 == 0.0) {
                            amount.toInt().toString()
                        } else {
                            String.format("%.8f", amount).trimEnd('0').trimEnd('.')
                        }

                        showSuccessDialog("Received $formattedAmount $symbol!")
                    }
                } else {
                    Log.w("MainActivity", "Crypto $cryptoId not found in local wallet")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sdkService = UnicityJavaSdkService()

        // Initialize BT Mesh components - DISABLED, focusing on Nostr P2P transfers
        // initializeBTMesh()
        
        setupActionBar()
        // Initialize ServiceProvider with application context for trustbase loading
        org.unicitylabs.wallet.di.ServiceProvider.init(applicationContext)

        // Start Nostr P2P service to listen for token transfers
        startNostrService()

        setupNfc()
        setupUI()
        setupRecyclerView()
        setupTabLayout()
        setupSwipeRefresh()
        setupSuccessDialog()
        // setupTestTrigger()  // Disabled - causes Bluetooth TEST_DEVICE errors
        setupTransferApprovalListener()

        // Collect history flows to keep them active
        lifecycleScope.launch {
            viewModel.incomingHistory.collect { /* Keep flow active */ }
        }
        lifecycleScope.launch {
            viewModel.outgoingHistory.collect { /* Keep flow active */ }
        }
        
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

        // Register handshake receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            handshakeReceiver,
            IntentFilter("org.unicitylabs.nfcwalletdemo.ACTION_HANDSHAKE_REQUEST")
        )
        
        // Register P2P status receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            p2pStatusReceiver,
            IntentFilter("org.unicitylabs.nfcwalletdemo.UPDATE_P2P_STATUS")
        )

        // Register crypto received receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cryptoReceivedReceiver,
            IntentFilter("org.unicitylabs.wallet.ACTION_CRYPTO_RECEIVED")
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
            val existingService = org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance()
            if (existingService == null) {
                Log.d("MainActivity", "Starting P2P service automatically")
                try {
                    // Get wallet public key from preferences
                    val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
                    val publicKey = sharedPrefs.getString("wallet_public_key", unicityTag) ?: unicityTag
                    org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance(
                        context = applicationContext,
                        userTag = unicityTag,
                        userPublicKey = publicKey
                    )
                    Log.d("MainActivity", "P2P service started successfully")
                    Toast.makeText(this, "P2P messaging service started", Toast.LENGTH_SHORT).show()
                    updateLocationIconColor()
                    
                    // Register agent location with backend
                    registerAgentLocation()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start P2P service", e)
                }
            } else {
                Log.d("MainActivity", "P2P service already running")
                updateLocationIconColor()
                
                // Ensure agent is registered even if service is already running
                registerAgentLocation()
            }
        } else {
            Log.d("MainActivity", "Not starting P2P service - agent: $isAgent, available: $isAvailable")
            updateLocationIconColor()
        }
    }
    
    private fun registerAgentLocation() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        if (unicityTag.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    // Always use demo location for initial registration from MainActivity
                    // Real location updates will be handled by UserProfileActivity
                    val location = org.unicitylabs.wallet.utils.UnicityLocationManager.createDemoLocation(this@MainActivity)
                    
                    if (location != null) {
                        val agentApiService = org.unicitylabs.wallet.network.AgentApiService()
                        agentApiService.updateAgentLocation(
                            unicityTag,
                            location.latitude,
                            location.longitude,
                            true
                        )
                        Log.d("MainActivity", "Agent location registered: ${location.latitude}, ${location.longitude}")
                    } else {
                        Log.w("MainActivity", "Unable to get location for agent registration")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to register agent location", e)
                }
            }
        }
    }
    
    private fun updateLocationIconColor() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val isAgent = sharedPrefs.getBoolean("is_agent", false)
        val isAvailable = sharedPrefs.getBoolean("agent_available", true)
        val p2pService = org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance()

        when {
            !isAgent -> {
                // Not an agent - default white icon
                binding.btnLocation.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE))
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
                data.scheme == "unicity" -> {
                    when (data.host) {
                        "pay" -> {
                            // Handle unicity://pay?nametag=...&coinId=...&amount=...
                            val paymentRequest = org.unicitylabs.wallet.model.PaymentRequest.fromUri(data.toString())
                            if (paymentRequest != null) {
                                handlePaymentRequest(paymentRequest)
                            } else {
                                Toast.makeText(this, "Invalid payment request", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                data.scheme == "nfcwallet" -> {
                    when (data.host) {
                        "mint-request" -> handleMintRequest(data)
                        "payment-request" -> {
                            // Legacy - try to parse as new PaymentRequest format
                            val paymentRequest = org.unicitylabs.wallet.model.PaymentRequest.fromUri(data.toString())
                            if (paymentRequest != null) {
                                handlePaymentRequest(paymentRequest)
                            }
                        }
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
        // Update nametag display
        updateNametagDisplay()

        // Setup wallet header click (open profile selection dialog in future)
        binding.walletHeader.setOnClickListener {
            // TODO: Show wallet/nametag selection dialog
            startActivity(Intent(this, org.unicitylabs.wallet.ui.profile.UserProfileActivity::class.java))
        }

        // Setup bottom navigation
        binding.navProfile.setOnClickListener {
            // Open User Profile
            startActivity(Intent(this, org.unicitylabs.wallet.ui.profile.UserProfileActivity::class.java))
        }

        binding.navUnicity.setOnClickListener {
            // Unicity home - refresh/scroll to top
            binding.swipeRefreshLayout.isRefreshing = true
            lifecycleScope.launch {
                viewModel.refreshTokens()
            }
        }

        binding.navHistory.setOnClickListener {
            showTransactionHistory()
        }

        binding.navChat.setOnClickListener {
            Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
        }

        binding.receiveButton.setOnClickListener {
            showReceiveQRDialog()
        }

        binding.depositButton.setOnClickListener {
            Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
        }
        
        binding.transferButton.setOnClickListener {
            if (currentTab == 0) {
                // For aggregated assets
                val assets = viewModel.aggregatedAssets.value
                if (assets.isNotEmpty()) {
                    showSendDialog()
                } else {
                    Toast.makeText(this, "No assets to transfer", Toast.LENGTH_SHORT).show()
                }
            } else {
                // For Tokens
                val tokens = viewModel.tokens.value
                if (tokens.isNotEmpty()) {
                    Toast.makeText(this, "Select a token from the list to transfer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No tokens to transfer", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        binding.mineButton.setOnClickListener {
            showMineDialog()
        }
        
        // Currency selector removed - now in header dropdown
        
        updateBalanceDisplay()
    }
    
    private fun setupRecyclerView() {
        tokenAdapter = TokenAdapter(
            onSendClick = { token ->
                showTokenSendMethodDialog(token)
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

        assetAdapter = AssetAdapter(
            onSendClick = { asset ->
                // Show dialog to select which tokens to send for this asset
                showAssetSendDialog(asset)
            },
            currency = selectedCurrency
        )

        binding.rvTokens.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = assetAdapter // Start with asset adapter (Assets tab is default)
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
            // Assets tab - show aggregated coins
            binding.rvTokens.adapter = assetAdapter
            val assets = viewModel.aggregatedAssets.value
            assetAdapter.submitList(assets)
            binding.emptyStateContainer.visibility = if (assets.isEmpty()) View.VISIBLE else View.GONE
        } else {
            // Tokens tab - show individual NFT tokens
            binding.rvTokens.adapter = tokenAdapter
            val tokens = viewModel.tokens.value
            tokenAdapter.submitList(tokens)
            binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    private fun updateNametagDisplay() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""

        if (unicityTag.isNotEmpty()) {
            binding.walletNametag.text = "@$unicityTag"
        } else {
            binding.walletNametag.text = "@unicity"
        }
    }

    private fun updateBalanceDisplay() {
        val assets = viewModel.aggregatedAssets.value
        val totalBalance = assets.sumOf { it.getTotalFiatValue(selectedCurrency) }

        val symbol = if (selectedCurrency == "EUR") "€" else "$"
        binding.balanceAmount.text = "$symbol${String.format("%,.1f", totalBalance)}"

        // Calculate 24h change
        val totalPreviousBalance = assets.sumOf {
            val previousPrice = when (selectedCurrency) {
                "EUR" -> it.priceEur / (1 + it.change24h / 100)
                else -> it.priceUsd / (1 + it.change24h / 100)
            }
            it.getAmountAsDecimal() * previousPrice
        }

        val changePercent = if (totalPreviousBalance > 0) {
            ((totalBalance - totalPreviousBalance) / totalPreviousBalance) * 100
        } else 0.0

        val changeAmount = totalBalance - totalPreviousBalance
        val changeSign = if (changePercent >= 0) "" else ""

        // Single line format: "$-520.9 -0.65%"
        binding.balanceChange.text = "$symbol${changeSign}${String.format("%.1f", changeAmount)} ${changeSign}${String.format("%.2f", changePercent)}%"
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

                // Always update the adapter so new tokens appear immediately
                Log.d("MainActivity", "Updating Tokens adapter with ${tokens.size} tokens")
                tokenAdapter.submitList(tokens.toList()) // toList() creates new instance to trigger DiffUtil

                // Update visibility if we're on the tokens tab
                if (currentTab == 1) {
                    binding.emptyStateContainer.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        
        // Observe aggregated assets for Assets tab
        lifecycleScope.launch {
            viewModel.aggregatedAssets.collect { assets ->
                if (currentTab == 0) {
                    assetAdapter.submitList(assets)
                    binding.emptyStateContainer.visibility = if (assets.isEmpty()) View.VISIBLE else View.GONE
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
                        
                        // Switch to Tokens tab to show the new token
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
                        val mintResultNode = UnicityObjectMapper.JSON.readTree(jsonData)
                        Log.d("MainActivity", "Mint result has identity: ${mintResultNode.has("identity")}")
                        val identityNode = mintResultNode.get("identity")
                        if (identityNode != null && !identityNode.isNull) {
                            Log.d("MainActivity", "Found identity data: $identityNode")
                            identityNode.toString()
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
                    JsonMapper.toJson(tempIdentity)
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
        menuInflater.inflate(org.unicitylabs.wallet.R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            org.unicitylabs.wallet.R.id.action_scan_qr -> {
                scanQRCode()
                true
            }
            org.unicitylabs.wallet.R.id.action_mint_token -> {
                showMintTokenDialog()
                true
            }
            org.unicitylabs.wallet.R.id.action_offline_transfer_test -> {
                runOfflineTransferTest()
                true
            }
            org.unicitylabs.wallet.R.id.action_bluetooth_mesh -> {
                startActivity(Intent(this, org.unicitylabs.wallet.ui.bluetooth.BluetoothMeshActivity::class.java))
                true
            }
            org.unicitylabs.wallet.R.id.action_reset_wallet -> {
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
                assetAdapter.updateCurrency(selectedCurrency)
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
                    4 -> startActivity(Intent(this@MainActivity, org.unicitylabs.wallet.ui.bluetooth.BluetoothMeshActivity::class.java))
                    5 -> showDemoModeDialog()
                }
            }
            .show()
    }
    
    private fun showDemoModeDialog() {
        val isDemoEnabled = org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this)
        val currentLocation = org.unicitylabs.wallet.utils.UnicityLocationManager.getDemoLocation(this)
        
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        
        AlertDialog.Builder(this)
            .setTitle("Demo Mode Settings")
            .setMessage("Current: ${if (isDemoEnabled) "ON - ${currentLocation.city}, ${currentLocation.country}" else "OFF"}")
            .setPositiveButton("Toggle Demo Mode") { _, _ ->
                org.unicitylabs.wallet.utils.UnicityLocationManager.setDemoModeEnabled(this, !isDemoEnabled)
                Toast.makeText(this, "Demo mode ${if (!isDemoEnabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (isDemoEnabled) "Change Location" else "Select Location") { _, _ ->
                if (!isDemoEnabled) {
                    org.unicitylabs.wallet.utils.UnicityLocationManager.setDemoModeEnabled(this, true)
                }
                showLocationSelectionDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLocationSelectionDialog() {
        val locations = org.unicitylabs.wallet.utils.UnicityLocationManager.DemoLocation.values()
            .filter { it != org.unicitylabs.wallet.utils.UnicityLocationManager.DemoLocation.CUSTOM }
        val locationNames = locations.map { "${it.city}, ${it.country}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Demo Location")
            .setItems(locationNames) { _, which ->
                val selectedLocation = locations[which]
                org.unicitylabs.wallet.utils.UnicityLocationManager.setDemoLocation(this, selectedLocation)
                Toast.makeText(this, "Demo location set to ${selectedLocation.city}", Toast.LENGTH_SHORT).show()
                
                // Also create some demo agents if user is in agent mode
                createDemoAgents(selectedLocation)
            }
            .show()
    }
    
    private fun createDemoAgents(location: org.unicitylabs.wallet.utils.UnicityLocationManager.DemoLocation) {
        // Generate some demo agents near the selected location
        lifecycleScope.launch {
            try {
                val agentLocations = org.unicitylabs.wallet.utils.UnicityLocationManager.generateNearbyAgentLocations(this@MainActivity, 5)
                val agentNames = listOf("agent1", "agent2", "agent3", "agent4", "agent5")
                val agentApiService = org.unicitylabs.wallet.network.AgentApiService()
                
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
        val assets = viewModel.aggregatedAssets.value

        if (assets.isEmpty()) {
            Toast.makeText(this, "No assets available", Toast.LENGTH_SHORT).show()
            return
        }

        // If only one asset, skip picker and go to next step
        if (assets.size == 1) {
            showAssetSendAmountDialog(assets[0], recipient)
            return
        }

        // Create custom dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        // Setup RecyclerView
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val adapter = AggregatedAssetDialogAdapter(assets) { selectedAsset ->
            dialog.dismiss()
            showAssetSendAmountDialog(selectedAsset, recipient)
        }

        recyclerView.adapter = adapter

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    
    private fun showAssetSendDialog(asset: org.unicitylabs.wallet.model.AggregatedAsset) {
        // Show contact selection dialog, then amount dialog (skip asset selection)
        currentContactDialog = ContactListDialog(
            context = this,
            onContactSelected = { selectedContact ->
                // Check if contact has @unicity tag
                if (selectedContact.hasUnicityTag()) {
                    // Go directly to amount dialog (asset already selected)
                    showAssetSendAmountDialog(asset, selectedContact)
                } else {
                    // Show warning for non-@unicity contacts
                    AlertDialog.Builder(this)
                        .setTitle("Cannot Send")
                        .setMessage("This contact doesn't have a @unicity nametag. Transfers require @unicity nametags.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
        currentContactDialog?.show()
    }

    private fun showAssetSendAmountDialog(asset: org.unicitylabs.wallet.model.AggregatedAsset, selectedContact: Contact) {
        // Get all tokens for this coinId
        val tokensForCoin = viewModel.getTokensByCoinId(asset.coinId)

        if (tokensForCoin.isEmpty()) {
            Toast.makeText(this, "No tokens available for ${asset.symbol}", Toast.LENGTH_SHORT).show()
            return
        }

        // Create amount input dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_amount_input, null)
        val etAmount = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAmount)
        val tilAmount = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilAmount)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tvBalance)

        // Show available balance
        val formattedBalance = asset.getFormattedAmount()
        tvBalance.text = "Available: $formattedBalance ${asset.symbol}"
        tilAmount.hint = "Amount to send (${asset.symbol})"

        // Quick amount buttons
        val btn25 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn25Percent)
        val btn50 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn50Percent)
        val btn75 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn75Percent)
        val btnMax = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMax)

        val balanceDecimal = asset.getAmountAsDecimal()

        btn25.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.25).toString())
        }

        btn50.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.50).toString())
        }

        btn75.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.75).toString())
        }

        btnMax.setOnClickListener {
            etAmount.setText(balanceDecimal.toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Send ${asset.symbol} to ${selectedContact.name}")
            .setView(dialogView)
            .setPositiveButton("Send") { _, _ ->
                val amountStr = etAmount.text.toString()
                if (amountStr.isNotEmpty()) {
                    try {
                        // Use BigDecimal to avoid overflow with high-decimal tokens
                        val amountDecimal = java.math.BigDecimal(amountStr)
                        val multiplier = java.math.BigDecimal.TEN.pow(asset.decimals)
                        val amountInSmallestUnitBD = amountDecimal.multiply(multiplier)
                        val amountInSmallestUnit = amountInSmallestUnitBD.toBigInteger()

                        if (amountInSmallestUnit <= java.math.BigInteger.ZERO) {
                            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        val totalBalanceBigInt = asset.getAmountAsBigInteger()
                        if (amountInSmallestUnit > totalBalanceBigInt) {
                            Toast.makeText(this, "Insufficient balance", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        // Proceed with split-aware transfer
                        sendTokensWithSplitting(
                            tokensForCoin = tokensForCoin,
                            targetAmount = amountInSmallestUnit,
                            asset = asset,
                            recipient = selectedContact
                        )
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showTransactionHistory() {
        val transactionEvents = viewModel.transactionHistory.value

        Log.d("MainActivity", "Transaction History: ${transactionEvents.size} events")
        transactionEvents.forEach { event ->
            Log.d("MainActivity", "${event.type}: ${event.token.name} ${event.token.symbol}")
        }

        if (transactionEvents.isEmpty()) {
            Toast.makeText(this, "No transactions yet", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            setPadding(0, 16, 0, 16)
        }

        val adapter = TokenHistoryAdapter(transactionEvents, viewModel.aggregatedAssets.value)
        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("Transaction History (${transactionEvents.size})")
            .setView(recyclerView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showIncomingHistory() {
        val incoming = viewModel.incomingHistory.value
        if (incoming.isEmpty()) {
            Toast.makeText(this, "No received transactions", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            setPadding(0, 16, 0, 16)
        }

        val incomingEvents = incoming.map {
            org.unicitylabs.wallet.model.TransactionEvent(
                token = it,
                type = org.unicitylabs.wallet.model.TransactionType.RECEIVED,
                timestamp = it.timestamp
            )
        }
        val adapter = TokenHistoryAdapter(incomingEvents, viewModel.aggregatedAssets.value)
        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("Received (${incoming.size})")
            .setView(recyclerView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showOutgoingHistory() {
        val outgoing = viewModel.outgoingHistory.value
        if (outgoing.isEmpty()) {
            Toast.makeText(this, "No sent transactions", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            setPadding(0, 16, 0, 16)
        }

        val outgoingEvents = outgoing.map {
            org.unicitylabs.wallet.model.TransactionEvent(
                token = it,
                type = org.unicitylabs.wallet.model.TransactionType.SENT,
                timestamp = it.transferredAt ?: it.timestamp
            )
        }
        val adapter = TokenHistoryAdapter(outgoingEvents, viewModel.aggregatedAssets.value)
        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("Sent (${outgoing.size})")
            .setView(recyclerView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun sendTokensWithSplitting(
        tokensForCoin: List<Token>,
        targetAmount: java.math.BigInteger,
        asset: org.unicitylabs.wallet.model.AggregatedAsset,
        recipient: Contact
    ) {
        lifecycleScope.launch {
            // Show progress dialog (outside try so it's always accessible for dismiss)
            val progressDialog = android.app.ProgressDialog(this@MainActivity).apply {
                setTitle("Processing Transfer")
                setMessage("Calculating optimal transfer strategy...")
                setCancelable(false)
                show()
            }

            try {

                // Step 1: Calculate optimal split plan
                val calculator = org.unicitylabs.wallet.transfer.TokenSplitCalculator()

                Log.d("MainActivity", "=== Starting split calculation ===")
                Log.d("MainActivity", "Target amount: $targetAmount")
                Log.d("MainActivity", "Asset coinId (hex string): ${asset.coinId}")
                Log.d("MainActivity", "Available tokens: ${tokensForCoin.size}")

                // Convert hex string coinId to bytes (not UTF-8 bytes of the string!)
                val coinId = org.unicitylabs.sdk.token.fungible.CoinId(HexUtils.decodeHex(asset.coinId))
                Log.d("MainActivity", "CoinId bytes: ${coinId.bytes.joinToString { it.toString() }}")

                // Convert wallet tokens to SDK tokens
                val sdkTokens = tokensForCoin.mapNotNull { token ->
                    try {
                        val sdkToken = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.readValue(
                            token.jsonData,
                            org.unicitylabs.sdk.token.Token::class.java
                        )
                        Log.d("MainActivity", "Parsed SDK token: id=${sdkToken.id.toHexString().take(8)}...")
                        val coins = sdkToken.getCoins()
                        if (coins.isPresent) {
                            Log.d("MainActivity", "Token has coins: ${coins.get().coins}")
                        } else {
                            Log.d("MainActivity", "Token has NO coins!")
                        }
                        sdkToken
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to parse token: ${e.message}", e)
                        null
                    }
                }

                Log.d("MainActivity", "Successfully parsed ${sdkTokens.size} SDK tokens")

                // Calculate total available balance
                val totalAvailable = sdkTokens.mapNotNull { token ->
                    val coins = token.getCoins()
                    if (coins.isPresent) {
                        coins.get().coins[coinId]
                    } else null
                }.fold(java.math.BigInteger.ZERO) { acc, amount -> acc.add(amount) }

                Log.d("MainActivity", "Total available: $totalAvailable, requested: $targetAmount")

                // Check if insufficient balance BEFORE calling calculator
                if (totalAvailable < targetAmount) {
                    progressDialog.dismiss()
                    val availableDecimal = java.math.BigDecimal(totalAvailable).divide(java.math.BigDecimal.TEN.pow(asset.decimals)).stripTrailingZeros()
                    val requestedDecimal = java.math.BigDecimal(targetAmount).divide(java.math.BigDecimal.TEN.pow(asset.decimals)).stripTrailingZeros()
                    val message = "Insufficient balance!\n\nRequested: $requestedDecimal ${asset.symbol}\nAvailable: $availableDecimal ${asset.symbol}"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "Insufficient balance: requested=$requestedDecimal, available=$availableDecimal")
                    return@launch
                }

                val splitPlan = calculator.calculateOptimalSplit(sdkTokens, targetAmount, coinId)

                if (splitPlan == null) {
                    progressDialog.dismiss()
                    Log.e("MainActivity", "Split calculator returned null - unexpected!")
                    Toast.makeText(this@MainActivity, "Cannot create transfer plan. This should not happen - check logs.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("MainActivity", "Split plan created: ${splitPlan.describe()}")

                // Step 2: Extract recipient nametag
                val recipientNametag = recipient.address
                    ?.removePrefix("@")
                    ?.removeSuffix("@unicity")
                    ?.trim()

                if (recipientNametag.isNullOrEmpty()) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Invalid recipient nametag", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Step 3: Create recipient proxy address
                val recipientTokenId = org.unicitylabs.sdk.token.TokenId.fromNameTag(recipientNametag)
                val recipientProxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(recipientTokenId)

                // Step 4: Get signing service
                val identityManager = org.unicitylabs.wallet.identity.IdentityManager(this@MainActivity)
                val identity = identityManager.getCurrentIdentity()
                if (identity == null) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Wallet identity not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val secret = HexUtils.decodeHex(identity.privateKey)
                val signingService = org.unicitylabs.sdk.signing.SigningService.createFromSecret(secret)

                // Step 5: Execute split if needed
                val tokensToTransfer = mutableListOf<org.unicitylabs.sdk.token.Token<*>>()
                var successCount = 0 // Track successful regular transfers
                var splitResult: org.unicitylabs.wallet.transfer.TokenSplitExecutor.SplitExecutionResult? = null

                // Get Nostr service early (needed for both paths)
                val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(applicationContext)
                if (nostrService == null) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Nostr service not available", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!nostrService.isRunning()) {
                    nostrService.start()
                    kotlinx.coroutines.delay(2000)
                }

                val recipientPubkey = nostrService.queryPubkeyByNametag(recipientNametag)
                if (recipientPubkey == null) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Recipient not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Step 5a: Execute split if needed
                if (splitPlan.requiresSplit) {
                    progressDialog.setMessage("Executing token split...")

                    val executor = org.unicitylabs.wallet.transfer.TokenSplitExecutor(
                        org.unicitylabs.wallet.di.ServiceProvider.stateTransitionClient,
                        org.unicitylabs.wallet.di.ServiceProvider.getRootTrustBase(),
                        viewModel.repository
                    )

                    splitResult = executor.executeSplitPlan(
                        splitPlan,
                        recipientProxyAddress,
                        signingService,
                        secret,
                        onTokenBurned = { burnedToken ->
                            // Mark token as burned immediately so it disappears from UI
                            viewModel.markTokenAsBurned(burnedToken)
                        }
                    )

                    // Update local wallet with new sender tokens
                    Log.d("MainActivity", "=== Adding split tokens to wallet ===")
                    Log.d("MainActivity", "Tokens kept by sender: ${splitResult.tokensKeptBySender.size}")
                    Log.d("MainActivity", "Tokens for recipient: ${splitResult.tokensForRecipient.size}")
                    Log.d("MainActivity", "Burned tokens: ${splitResult.burnedTokens.size}")

                    val burnedToken = splitResult.burnedTokens.firstOrNull()
                    val sentAmount = splitResult.tokensForRecipient.firstOrNull()?.getCoins()?.map { coinData ->
                        coinData.coins.values.firstOrNull()
                    }?.orElse(null)

                    splitResult.tokensKeptBySender.forEach { newToken ->
                        val amount = newToken.getCoins().map { coinData ->
                            coinData.coins.values.firstOrNull()
                        }.orElse(null)
                        Log.d("MainActivity", "Adding KEPT token: ID=${newToken.id.toHexString().take(16)}... amount=$amount")

                        // Track the split source token ID and sent amount for history
                        val sourceTokenId = burnedToken?.id?.bytes?.joinToString("") { "%02x".format(it) }
                        Log.d("MainActivity", "Split source token: $sourceTokenId, sent amount: $sentAmount")

                        viewModel.addNewTokenFromSplit(newToken, sourceTokenId, sentAmount)
                    }

                    splitResult.tokensForRecipient.forEach { recipToken ->
                        val amount = recipToken.getCoins().map { coinData ->
                            coinData.coins.values.firstOrNull()
                        }.orElse(null)
                        Log.d("MainActivity", "Recipient token (NOT added to wallet): ID=${recipToken.id.toHexString().take(16)}... amount=$amount")
                    }
                }

                // Step 5b: Create transfer transactions for direct tokens (if any)
                // These need to be transferred whether or not a split happened
                if (splitPlan.tokensToTransferDirectly.isNotEmpty()) {
                    progressDialog.setMessage("Creating transfer transactions...")

                    for (tokenToTransfer in splitPlan.tokensToTransferDirectly) {
                        val salt = ByteArray(32)
                        java.security.SecureRandom().nextBytes(salt)

                        Log.d("MainActivity", "Creating transfer commitment:")
                        Log.d("MainActivity", "  Token: ${tokenToTransfer.id.bytes.joinToString("") { "%02x".format(it) }.take(16)}...")
                        Log.d("MainActivity", "  Recipient ProxyAddress: ${recipientProxyAddress.address}")
                        Log.d("MainActivity", "  RecipientPredicateHash: null (proxy transfer)")
                        Log.d("MainActivity", "  RecipientDataHash: null")

                        val transferCommitment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            org.unicitylabs.sdk.transaction.TransferCommitment.create(
                                tokenToTransfer,
                                recipientProxyAddress,
                                salt,
                                null,  // recipientPredicateHash - null for proxy transfers
                                null,  // recipientDataHash
                                signingService
                            )
                        }

                        Log.d("MainActivity", "Transfer commitment created successfully")

                        val client = org.unicitylabs.wallet.di.ServiceProvider.stateTransitionClient
                        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            client.submitCommitment(transferCommitment).get()
                        }

                        if (response.status == org.unicitylabs.sdk.api.SubmitCommitmentStatus.SUCCESS) {
                            val inclusionProof = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val trustBase = org.unicitylabs.wallet.di.ServiceProvider.getRootTrustBase()
                                org.unicitylabs.sdk.util.InclusionProofUtils.waitInclusionProof(
                                    client,
                                    trustBase,
                                    transferCommitment
                                ).get(30, java.util.concurrent.TimeUnit.SECONDS)
                            }

                            val transferTransaction = transferCommitment.toTransaction(inclusionProof)

                            // Create transfer package (like old sendTokenViaNostr)
                            val sourceTokenJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(tokenToTransfer)
                            val transferTxJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(transferTransaction)

                            val payload = mapOf(
                                "sourceToken" to sourceTokenJson,
                                "transferTx" to transferTxJson
                            )
                            val payloadJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(payload)
                            val transferPackage = "token_transfer:$payloadJson"

                            Log.d("MainActivity", "Transfer payload size: ${transferPackage.length / 1024}KB")

                            // Send via sendDirectMessage (not sendTokenTransfer) for proper format
                            val sent = try {
                                nostrService.sendDirectMessage(recipientPubkey!!, transferPackage)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to send token: ${e.message}", e)
                                false
                            }

                            if (sent) {
                                successCount++
                                // Only remove from wallet if send succeeded
                                // Note: This doesn't guarantee relay accepted it (size limits, etc)
                                // TODO: Wait for relay OK response before removing
                                viewModel.removeTokenAfterTransfer(tokenToTransfer)
                            } else {
                                Log.w("MainActivity", "Token NOT removed from wallet - send failed")
                            }
                        } else {
                            Log.e("MainActivity", "Failed to transfer token: ${response.status}")
                        }
                    }
                }

                // Step 6: Send SPLIT tokens via Nostr (if any)
                // Regular tokens already sent in the else block above
                if (splitPlan.requiresSplit && splitResult != null) {
                    progressDialog.setMessage("Sending split tokens to recipient...")

                    val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(applicationContext)
                    if (nostrService != null && nostrService.isRunning()) {
                        // For split tokens, we need to send sourceToken + transferTx (same as regular transfers)
                        // The splitResult has parallel lists: tokensForRecipient and recipientTransferTxs
                        for ((index, sourceToken) in splitResult.tokensForRecipient.withIndex()) {
                            try {
                                val transferTx = splitResult.recipientTransferTxs[index]

                                Log.d("MainActivity", "Sending split token ${sourceToken.id.toHexString().take(8)}... via Nostr")

                                val sourceTokenJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(sourceToken)
                                val transferTxJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(transferTx)

                                val payload = mapOf(
                                    "sourceToken" to sourceTokenJson,
                                    "transferTx" to transferTxJson
                                )
                                val payloadJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(payload)
                                val transferPackage = "token_transfer:$payloadJson"

                                Log.d("MainActivity", "Split token transfer payload size: ${transferPackage.length / 1024}KB")

                                val sent = nostrService.sendDirectMessage(recipientPubkey!!, transferPackage)
                                if (!sent) {
                                    Log.e("MainActivity", "Failed to send split token via Nostr")
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to send split token", e)
                            }
                        }
                    }
                }

                progressDialog.dismiss()

                // Calculate total sent: direct transfers + split tokens
                val totalSent = successCount + (splitResult?.tokensForRecipient?.size ?: 0)

                if (totalSent > 0) {
                    vibrateSuccess()
                    val formattedAmount = targetAmount.toDouble() / Math.pow(10.0, asset.decimals.toDouble())
                    showSuccessDialog("Successfully sent $formattedAmount ${asset.symbol} to ${recipient.name}")
                } else {
                    Toast.makeText(this@MainActivity, "Transfer failed", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("MainActivity", "Error in sendTokensWithSplitting", e)
                Toast.makeText(this@MainActivity, "Transfer error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showTokenSendMethodDialog(token: Token) {
        AlertDialog.Builder(this)
            .setTitle("Send ${token.symbol ?: "Token"}")
            .setMessage("Choose transfer method:")
            .setPositiveButton("NFC Tap") { _, _ ->
                checkNfc {
                    startHybridTokenTransfer(token)
                }
            }
            .setNegativeButton("Choose Contact") { _, _ ->
                // Show contact list, then send whole token
                currentContactDialog = ContactListDialog(
                    context = this,
                    onContactSelected = { selectedContact ->
                        if (selectedContact.hasUnicityTag()) {
                            // Get asset info for this token
                            val asset = viewModel.aggregatedAssets.value.find { it.coinId == token.coinId }
                            if (asset != null) {
                                sendTokenViaNostr(token, selectedContact, asset)
                            } else {
                                Toast.makeText(this, "Asset not found", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Cannot Send")
                                .setMessage("This contact doesn't have a @unicity nametag. Transfers require @unicity nametags.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                )
                currentContactDialog?.show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun sendTokenViaNostr(token: Token, recipient: Contact, asset: org.unicitylabs.wallet.model.AggregatedAsset) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Sending to ${recipient.name}...", Toast.LENGTH_SHORT).show()

                // Step 1: Extract recipient nametag from contact
                val recipientNametag = recipient.address
                    ?.removePrefix("@")
                    ?.removeSuffix("@unicity")
                    ?.trim()

                if (recipientNametag.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "Invalid recipient nametag", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("MainActivity", "Starting transfer to nametag: $recipientNametag")

                // Step 2: Query recipient's Nostr pubkey
                val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(applicationContext)
                if (nostrService == null) {
                    Toast.makeText(this@MainActivity, "Nostr service not available", Toast.LENGTH_LONG).show()
                    return@launch
                }

                if (!nostrService.isRunning()) {
                    nostrService.start()
                    kotlinx.coroutines.delay(2000)
                }

                val recipientPubkey = nostrService.queryPubkeyByNametag(recipientNametag)
                if (recipientPubkey == null) {
                    Toast.makeText(this@MainActivity, "Recipient not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("MainActivity", "Found recipient pubkey: ${recipientPubkey.take(16)}...")

                // Step 3: Create proxy address from recipient nametag
                val recipientTokenId = org.unicitylabs.sdk.token.TokenId.fromNameTag(recipientNametag)
                val recipientProxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(recipientTokenId)

                Log.d("MainActivity", "Recipient proxy address: ${recipientProxyAddress.address}")

                // Step 4: Parse token and create transfer
                val sourceToken = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.readValue(
                    token.jsonData,
                    org.unicitylabs.sdk.token.Token::class.java
                )

                val identityManager = org.unicitylabs.wallet.identity.IdentityManager(this@MainActivity)
                val identity = identityManager.getCurrentIdentity()
                if (identity == null) {
                    Toast.makeText(this@MainActivity, "Wallet identity not found", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val secret = HexUtils.decodeHex(identity.privateKey)
                val signingService = org.unicitylabs.sdk.signing.SigningService.createFromSecret(secret)

                val salt = ByteArray(32)
                java.security.SecureRandom().nextBytes(salt)

                // Step 5-7: Create transfer, submit, wait for proof (no toasts - fast)
                val transferCommitment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    org.unicitylabs.sdk.transaction.TransferCommitment.create(
                        sourceToken,
                        recipientProxyAddress,
                        salt,
                        null,
                        null,
                        signingService
                    )
                }

                val client = org.unicitylabs.wallet.di.ServiceProvider.stateTransitionClient
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.submitCommitment(transferCommitment).get()
                }

                if (response.status != org.unicitylabs.sdk.api.SubmitCommitmentStatus.SUCCESS) {
                    Toast.makeText(this@MainActivity, "Transfer failed: ${response.status}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val inclusionProof = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val trustBase = org.unicitylabs.wallet.di.ServiceProvider.getRootTrustBase()
                    org.unicitylabs.sdk.util.InclusionProofUtils.waitInclusionProof(
                        client,
                        trustBase,
                        transferCommitment
                    ).get(30, java.util.concurrent.TimeUnit.SECONDS)
                }

                // Step 8-9: Create transfer package
                val transferTransaction = transferCommitment.toTransaction(inclusionProof)
                val sourceTokenJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(sourceToken)
                val transferTxJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(transferTransaction)

                val payload = mapOf(
                    "sourceToken" to sourceTokenJson,
                    "transferTx" to transferTxJson
                )
                val payloadJson = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.writeValueAsString(payload)
                val transferPackage = "token_transfer:$payloadJson"

                Log.d("MainActivity", "Transfer package created (${transferPackage.length} chars)")

                // Step 10: Send via Nostr
                val sent = nostrService.sendDirectMessage(recipientPubkey, transferPackage)

                if (sent) {
                    Toast.makeText(this@MainActivity, "✅ Sent to ${recipient.name}!", Toast.LENGTH_SHORT).show()
                    Log.i("MainActivity", "Transfer completed to $recipientNametag")

                    viewModel.markTokenAsTransferred(token)
                    Log.d("MainActivity", "Token archived")
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending token via Nostr", e)
                Toast.makeText(this@MainActivity, "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
        val btnDelete = dialogView.findViewById<android.widget.ImageButton>(R.id.btnDelete)

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

        btnDelete.setOnClickListener {
            Log.d("MainActivity", "🔧 Hidden feature: Deleting ${crypto.symbol}")
            viewModel.deleteCrypto(crypto.id)
            dialog.dismiss()
            Toast.makeText(this, "🔧 ${crypto.symbol} deleted", Toast.LENGTH_SHORT).show()
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
    
    /**
     * Show Receive QR dialog - shows options directly in MainActivity
     */
    private fun showReceiveQRDialog() {
        showReceiveOptionsDialog()
    }

    /**
     * Show receive options dialog: Specify Amount or Let Sender Choose
     */
    private fun showReceiveOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_receive_options, null)

        val cardSpecifyAmount = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardSpecifyAmount)
        val cardLetSenderChoose = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardLetSenderChoose)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        cardSpecifyAmount.setOnClickListener {
            dialog.dismiss()
            showReceiveSpecifyAmountDialog()
        }

        cardLetSenderChoose.setOnClickListener {
            dialog.dismiss()
            showReceiveOpenQRCode()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Show dialog to specify asset and amount for receive
     */
    private fun showReceiveSpecifyAmountDialog() {
        // Step 1: Show asset selection dialog (reuse existing layout)
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val rvAssets = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        // Update title for receive context
        val titleView = dialogView.findViewById<TextView>(android.R.id.text1)
        if (titleView == null) {
            // Title is directly in the layout, find it by traversing
            val rootLayout = dialogView as? android.view.ViewGroup
            rootLayout?.let { root ->
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    if (child is TextView && child.text.toString().contains("Select Asset")) {
                        child.text = "Select Asset to Request"
                        break
                    }
                }
            }
        }

        // Load token registry
        val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(this)
        val assets = registry.getFungibleTokens() // Only show fungible tokens for requests

        val assetDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Setup RecyclerView with TokenDefinitionAdapter
        rvAssets.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = org.unicitylabs.wallet.ui.receive.TokenDefinitionAdapter(assets) { selectedAsset ->
            assetDialog.dismiss()
            // Step 2: Show amount dialog for selected asset
            showReceiveAmountInputDialog(selectedAsset)
        }
        rvAssets.adapter = adapter

        btnCancel.setOnClickListener {
            assetDialog.dismiss()
            showReceiveOptionsDialog()
        }

        assetDialog.show()
    }

    /**
     * Show amount input dialog for receive request
     */
    private fun showReceiveAmountInputDialog(asset: org.unicitylabs.wallet.token.TokenDefinition) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_amount_input, null)
        val etAmount = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAmount)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tvBalance)
        val tvSplitInfo = dialogView.findViewById<TextView>(R.id.tvSplitInfo)

        // Hide split info for receive (not applicable)
        tvSplitInfo?.visibility = View.GONE

        // Hide balance for receive (we're requesting, not sending)
        tvBalance?.visibility = View.GONE

        // Hide percentage buttons for receive (we want exact amount)
        dialogView.findViewById<View>(R.id.btn25Percent)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn50Percent)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn75Percent)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btnMax)?.visibility = View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle("Request ${asset.symbol ?: asset.name}")
            .setView(dialogView)
            .setPositiveButton("Generate QR") { _, _ ->
                val amountText = etAmount.text?.toString()

                if (amountText.isNullOrEmpty()) {
                    Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                try {
                    // Convert decimal amount to smallest units (SAME AS SEND FLOW)
                    val amountDecimal = java.math.BigDecimal(amountText)
                    val decimals = asset.decimals ?: 0
                    val multiplier = java.math.BigDecimal.TEN.pow(decimals)
                    val amountInSmallestUnitBD = amountDecimal.multiply(multiplier)
                    val amountInSmallestUnit = amountInSmallestUnitBD.toBigInteger()

                    if (amountInSmallestUnit <= java.math.BigInteger.ZERO) {
                        Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    showReceiveQRCodeWithRequest(asset.id, amountInSmallestUnit)

                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showReceiveSpecifyAmountDialog()
            }
            .setCancelable(true)
            .create()

        dialog.show()
    }

    /**
     * Show QR code with open payment request (no coinId/amount specified)
     */
    private fun showReceiveOpenQRCode() {
        val nametag = getMyNametag()
        if (nametag == null) {
            Toast.makeText(this, "Please set up your nametag in Profile first", Toast.LENGTH_LONG).show()
            return
        }

        val paymentRequest = org.unicitylabs.wallet.model.PaymentRequest(nametag = nametag)
        showReceiveQRCodeDialog(paymentRequest)
    }

    /**
     * Show QR code with specific payment request (coinId + amount)
     */
    private fun showReceiveQRCodeWithRequest(coinId: String, amount: java.math.BigInteger) {
        val nametag = getMyNametag()
        if (nametag == null) {
            Toast.makeText(this, "Please set up your nametag in Profile first", Toast.LENGTH_LONG).show()
            return
        }

        val paymentRequest = org.unicitylabs.wallet.model.PaymentRequest(
            nametag = nametag,
            coinId = coinId,
            amount = amount
        )
        showReceiveQRCodeDialog(paymentRequest)
    }

    /**
     * Show QR code dialog with the payment request
     */
    private fun showReceiveQRCodeDialog(paymentRequest: org.unicitylabs.wallet.model.PaymentRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)

        val qrCodeImage = dialogView.findViewById<ImageView>(R.id.qrCodeImage)
        val statusText = dialogView.findViewById<TextView>(R.id.statusText)
        val timerText = dialogView.findViewById<TextView>(R.id.timerText)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Generate QR code
        try {
            // Use URI format so camera apps can recognize it as a deep link
            val qrContent = paymentRequest.toUri()
            val qrBitmap = generateQRCode(qrContent)
            qrCodeImage.setImageBitmap(qrBitmap)

            // Update status text
            val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(this)
            val statusMessage = if (paymentRequest.isSpecific()) {
                val asset = registry.getCoinById(paymentRequest.coinId!!)
                val decimals = asset?.decimals ?: 0
                val divisor = java.math.BigDecimal.TEN.pow(decimals)
                val displayAmount = java.math.BigDecimal(paymentRequest.amount).divide(divisor).stripTrailingZeros().toPlainString()
                "Scan to send $displayAmount ${asset?.symbol ?: "tokens"}"
            } else {
                "Scan to send tokens to ${paymentRequest.nametag}@unicity"
            }
            statusText.text = statusMessage

            // Hide timer for now (we can add expiry later if needed)
            timerText.visibility = View.GONE

            // Share button
            btnShare.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, qrContent)
                    putExtra(Intent.EXTRA_SUBJECT, "Unicity Payment Request")
                }
                startActivity(Intent.createChooser(shareIntent, "Share Payment Request"))
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error generating QR code", e)
            Toast.makeText(this, "Error generating QR code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Get user's nametag from SharedPreferences
     */
    private fun getMyNametag(): String? {
        val prefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val nametag = prefs.getString("unicity_tag", null)
        return if (nametag.isNullOrEmpty()) null else nametag
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
    
    private fun showMineDialog() {
        // Open Friendly Miners website in browser
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://friendly-miners.com/")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open browser", e)
            Toast.makeText(this, "Unable to open browser", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupTestTrigger() {
        // Add long-press listener to balance amount for testing success dialog
        binding.balanceAmount.setOnLongClickListener {
            Log.d("MainActivity", "Long press detected on balance - triggering test success dialog")
            showSuccessDialog("Excellent, you've sent 1.5 BTC!")
            true
        }

        // Long press on wallet header to test BT mesh
        binding.walletHeader.setOnLongClickListener {
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
                val testApproval = org.unicitylabs.wallet.bluetooth.TransferApprovalRequest(
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
        // currentTransferringCrypto = null // Removed - no longer using demo cryptos
        disableNfcTransfer()
        hideNfcWaitingDialog()
        Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
    }

    // Removed startCryptoTransfer - demo crypto transfers no longer supported
    private fun startCryptoTransfer(crypto: CryptoCurrency, amount: Double) {
        // currentTransferringCrypto = crypto
        
        Log.d("MainActivity", "Starting NFC crypto transfer: EXACT amount = $amount ${crypto.symbol}")
        showNfcWaitingDialog(crypto, amount)
        
        val realNfcTransceiver = nfcAdapter?.let { RealNfcTransceiver(it) }
        if (realNfcTransceiver == null) {
            Toast.makeText(this, "NFC not available or enabled", Toast.LENGTH_SHORT).show()
            // currentTransferringCrypto = null
            return
        }

        val directNfcClient = DirectNfcClient(
            sdkService = viewModel.getSdkService(),
            apduTransceiver = realNfcTransceiver,
            onTransferComplete = {
                Log.d("MainActivity", "✅ NFC crypto transfer completed")
                runOnUiThread {
                    hideNfcWaitingDialog()
                    // currentTransferringCrypto = null
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
                    // currentTransferringCrypto = null
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
            // currentTransferringCrypto = null
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
            "icon_res_id" to crypto.iconResId,
            "is_demo" to crypto.isDemo,
            "icon_url" to crypto.iconUrl
        )
        val jsonData = JsonMapper.toJson(transferData)
        Log.d("MainActivity", "Created transfer JSON with amount: $amount, isDemo: ${crypto.isDemo}")
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
                val pendingData = JsonMapper.fromJson(token.pendingOfflineData, Map::class.java)
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
        // Parse receiver identity
        val identityMap = JsonMapper.fromJson(receiverIdentityJson, Map::class.java)
        val receiverSecret = (identityMap["secret"] as? String ?: "").toByteArray()
        val receiverNonce = HexUtils.decodeHex(identityMap["nonce"] as? String ?: "")
        
        val receivedToken = sdkService.completeOfflineTransfer(
            offlineTransactionJson,
            receiverSecret,
            receiverNonce
        )
        
        if (receivedToken != null) {
            // Serialize token to JSON string
            return sdkService.serializeToken(receivedToken) ?: throw Exception("Failed to serialize token")
        } else {
            throw Exception("Failed to complete offline transfer")
        }
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
                                                val testApproval = org.unicitylabs.wallet.bluetooth.TransferApprovalRequest(
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
                                                    val testApproval = org.unicitylabs.wallet.bluetooth.TransferApprovalRequest(
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
    
    private fun showTransferApprovalDialog(approval: org.unicitylabs.wallet.bluetooth.TransferApprovalRequest) {
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
                        org.unicitylabs.wallet.bluetooth.TransferState.COMPLETED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            vibrateSuccess()
                            showSuccessDialog("Token transferred successfully!")
                            
                            // Remove token from wallet
                            viewModel.removeToken(tokenId)
                        }
                        org.unicitylabs.wallet.bluetooth.TransferState.REJECTED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            // Different vibration for rejection
                            vibrateError()
                            showErrorDialog("Transfer Cancelled", "The recipient declined the transfer request.")
                        }
                        org.unicitylabs.wallet.bluetooth.TransferState.FAILED -> {
                            val token = getTokenById(tokenId)
                            token?.let { tokenAdapter.setTransferring(it, false) }
                            hideNfcWaitingDialog()
                            vibrateError()
                            showErrorDialog("Transfer Failed", "The token transfer could not be completed.")
                        }
                        else -> {
                            // Update progress UI if needed
                            val progressText = when (state) {
                                org.unicitylabs.wallet.bluetooth.TransferState.REQUESTING_PERMISSION -> "Requesting permission..."
                                org.unicitylabs.wallet.bluetooth.TransferState.WAITING_FOR_ADDRESS -> "Waiting for recipient..."
                                org.unicitylabs.wallet.bluetooth.TransferState.CREATING_PACKAGE -> "Creating transfer..."
                                org.unicitylabs.wallet.bluetooth.TransferState.SENDING_PACKAGE -> "Sending token..."
                                org.unicitylabs.wallet.bluetooth.TransferState.APPROVED -> "Transfer approved..."
                                else -> "Processing..."
                            }
                            val token = getTokenById(tokenId)
                            token?.let { 
                                // Only show transferring state and progress after approval
                                if (state == org.unicitylabs.wallet.bluetooth.TransferState.REQUESTING_PERMISSION) {
                                    // Don't show progress while waiting for permission
                                    tokenAdapter.setTransferring(it, false)
                                } else {
                                    tokenAdapter.setTransferring(it, true)
                                    // Update the progress based on state
                                    val progress = when (state) {
                                        org.unicitylabs.wallet.bluetooth.TransferState.APPROVED -> Pair(1, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.WAITING_FOR_ADDRESS -> Pair(2, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.GENERATING_ADDRESS -> Pair(3, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.CREATING_PACKAGE -> Pair(5, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.SENDING_PACKAGE -> Pair(7, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.WAITING_FOR_PACKAGE -> Pair(6, 10)
                                        org.unicitylabs.wallet.bluetooth.TransferState.COMPLETING_TRANSFER -> Pair(9, 10)
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
        val intent = Intent(this, org.unicitylabs.wallet.ui.agent.AgentMapActivity::class.java)
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
                    startActivity(Intent(this, org.unicitylabs.wallet.ui.bluetooth.BluetoothMeshActivity::class.java))
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
        Log.d("MainActivity", "Scanned QR: ${content.take(100)}...")

        if (content.startsWith("unicity://pay")) {
            val paymentRequest = org.unicitylabs.wallet.model.PaymentRequest.fromUri(content)
            if (paymentRequest != null) {
                handlePaymentRequest(paymentRequest)
                return
            }
        }

        if (content.startsWith("nfcwallet://mint-request")) {
            handleMintRequest(Uri.parse(content))
            return
        }

        Toast.makeText(this, "Not a valid QR code", Toast.LENGTH_SHORT).show()
    }

    /**
     * Handle PaymentRequest by looking up nametag via Nostr and sending token
     */
    private fun handlePaymentRequest(paymentRequest: org.unicitylabs.wallet.model.PaymentRequest) {
        Log.d("MainActivity", "Handling payment request for nametag: ${paymentRequest.nametag}")

        lifecycleScope.launch {
            try {
                // Show loading
                val loadingDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Looking up ${paymentRequest.nametag}@unicity...")
                    .setMessage("Querying Nostr relay...")
                    .setCancelable(false)
                    .create()
                loadingDialog.show()

                // Get Nostr service
                val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(applicationContext)
                if (nostrService == null) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Nostr service not available", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Ensure service is started
                if (!nostrService.isRunning()) {
                    nostrService.start()
                    delay(2000)
                }

                // Query nametag → Nostr pubkey
                val recipientPubkey = nostrService.queryPubkeyByNametag(paymentRequest.nametag)
                loadingDialog.dismiss()

                if (recipientPubkey == null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Nametag Not Found")
                        .setMessage("Could not find ${paymentRequest.nametag}@unicity on Nostr relay.\n\nMake sure the recipient has minted their nametag and it's published to the relay.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                Log.d("MainActivity", "Found recipient pubkey: ${recipientPubkey.take(16)}...")

                // Show send confirmation dialog
                showPaymentRequestConfirmationDialog(paymentRequest, recipientPubkey)

            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling payment request", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Show confirmation dialog for payment request and execute transfer
     */
    private fun showPaymentRequestConfirmationDialog(
        paymentRequest: org.unicitylabs.wallet.model.PaymentRequest,
        recipientPubkey: String
    ) {
        val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(this)

        // For specific requests, check balance BEFORE showing confirmation
        if (paymentRequest.isSpecific()) {
            val asset = viewModel.aggregatedAssets.value.find { it.coinId == paymentRequest.coinId }
            if (asset != null) {
                val availableBalance = asset.getAmountAsBigInteger()
                val requestedAmount = paymentRequest.amount!!

                if (availableBalance < requestedAmount) {
                    val assetDef = registry.getCoinById(paymentRequest.coinId!!)
                    val decimals = assetDef?.decimals ?: 0
                    val divisor = java.math.BigDecimal.TEN.pow(decimals)
                    val availableDecimal = java.math.BigDecimal(availableBalance).divide(divisor).stripTrailingZeros().toPlainString()
                    val requestedDecimal = java.math.BigDecimal(requestedAmount).divide(divisor).stripTrailingZeros().toPlainString()

                    AlertDialog.Builder(this)
                        .setTitle("Insufficient Balance")
                        .setMessage("Cannot send $requestedDecimal ${assetDef?.symbol ?: "tokens"} to ${paymentRequest.nametag}@unicity\n\nYour balance: $availableDecimal ${assetDef?.symbol ?: "tokens"}")
                        .setPositiveButton("OK", null)
                        .show()
                    return
                }
            }
        }

        val message = if (paymentRequest.isSpecific()) {
            val asset = registry.getCoinById(paymentRequest.coinId!!)
            val decimals = asset?.decimals ?: 0
            val divisor = java.math.BigDecimal.TEN.pow(decimals)
            val displayAmount = java.math.BigDecimal(paymentRequest.amount).divide(divisor).stripTrailingZeros().toPlainString()
            "Send $displayAmount ${asset?.symbol ?: "tokens"}\nto ${paymentRequest.nametag}@unicity?"
        } else {
            "Send tokens to ${paymentRequest.nametag}@unicity?\n\nYou will choose the amount in the next step."
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm Token Transfer")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ ->
                if (paymentRequest.isSpecific()) {
                    // Execute specific transfer
                    executeNostrTokenTransfer(
                        recipientNametag = paymentRequest.nametag,
                        recipientPubkey = recipientPubkey,
                        coinId = paymentRequest.coinId!!,
                        amount = paymentRequest.amount!!
                    )
                } else {
                    // Show asset/amount selector
                    showNostrTransferAssetSelector(paymentRequest.nametag, recipientPubkey)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show asset selector for open payment request (sender chooses asset and amount)
     */
    private fun showNostrTransferAssetSelector(recipientNametag: String, recipientPubkey: String) {
        // Step 1: Show asset selection dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_asset, null)
        val rvAssets = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        // Get assets from aggregated view (only those with balance)
        val availableAssets = viewModel.aggregatedAssets.value.filter { asset ->
            asset.getAmountAsBigInteger().compareTo(java.math.BigInteger.ZERO) > 0
        }

        if (availableAssets.isEmpty()) {
            Toast.makeText(this, "No assets available to send", Toast.LENGTH_SHORT).show()
            return
        }

        val assetDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Setup RecyclerView with AssetSelectorAdapter
        rvAssets.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = AssetSelectorAdapter(availableAssets) { selectedAsset ->
            assetDialog.dismiss()
            // Step 2: Show amount dialog for selected asset
            showNostrTransferAmountDialog(recipientNametag, recipientPubkey, selectedAsset)
        }
        rvAssets.adapter = adapter

        btnCancel.setOnClickListener {
            assetDialog.dismiss()
        }

        assetDialog.show()
    }

    /**
     * Show amount input dialog for Nostr transfer
     */
    private fun showNostrTransferAmountDialog(
        recipientNametag: String,
        recipientPubkey: String,
        asset: org.unicitylabs.wallet.model.AggregatedAsset
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_amount_input, null)
        val etAmount = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAmount)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tvBalance)
        val tvSplitInfo = dialogView.findViewById<TextView>(R.id.tvSplitInfo)

        // Show available balance
        val formattedBalance = asset.getFormattedAmount()
        tvBalance.text = "Available: $formattedBalance ${asset.symbol}"

        // Hide split info initially
        tvSplitInfo.visibility = View.GONE

        // Quick amount buttons
        val btn25 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn25Percent)
        val btn50 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn50Percent)
        val btn75 = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn75Percent)
        val btnMax = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMax)

        val balanceDecimal = asset.getAmountAsDecimal()

        btn25?.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.25).toString())
        }

        btn50?.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.50).toString())
        }

        btn75?.setOnClickListener {
            etAmount.setText((balanceDecimal * 0.75).toString())
        }

        btnMax?.setOnClickListener {
            etAmount.setText(balanceDecimal.toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Send ${asset.symbol}")
            .setView(dialogView)
            .setPositiveButton("Send") { _, _ ->
                val amountText = etAmount.text?.toString()

                if (amountText.isNullOrEmpty()) {
                    Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                try {
                    // Use BigDecimal to avoid overflow with high-decimal tokens
                    val amountDecimal = java.math.BigDecimal(amountText)
                    val multiplier = java.math.BigDecimal.TEN.pow(asset.decimals)
                    val amountInSmallestUnitBD = amountDecimal.multiply(multiplier)
                    val amountInSmallestUnit = amountInSmallestUnitBD.toBigInteger()

                    if (amountInSmallestUnit <= java.math.BigInteger.ZERO) {
                        Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val totalBalanceBigInt = asset.getAmountAsBigInteger()
                    if (amountInSmallestUnit > totalBalanceBigInt) {
                        Toast.makeText(this, "Insufficient balance", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // Execute transfer
                    executeNostrTokenTransfer(recipientNametag, recipientPubkey, asset.coinId, amountInSmallestUnit)

                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .create()

        dialog.show()
    }

    /**
     * Execute token transfer via Nostr (reuses existing split logic)
     */
    private fun executeNostrTokenTransfer(
        recipientNametag: String,
        recipientPubkey: String,
        coinId: String,
        amount: java.math.BigInteger
    ) {
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "━━━ executeNostrTokenTransfer START ━━━")
                Log.d("MainActivity", "  Recipient nametag: $recipientNametag")
                Log.d("MainActivity", "  Recipient pubkey: ${recipientPubkey.take(16)}...")
                Log.d("MainActivity", "  CoinId: $coinId")
                Log.d("MainActivity", "  Amount: $amount")

                // 1. Get all tokens with matching coinId
                val tokensForCoin = viewModel.getTokensByCoinId(coinId)

                if (tokensForCoin.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No tokens found with coinId $coinId", Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "❌ No tokens found for coinId: $coinId")
                    return@launch
                }

                Log.d("MainActivity", "✅ Found ${tokensForCoin.size} token(s) for coinId")

                // 2. Get the asset metadata
                val asset = viewModel.aggregatedAssets.value.find { it.coinId == coinId }
                if (asset == null) {
                    Toast.makeText(this@MainActivity, "Asset not found in wallet", Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "❌ Asset not found for coinId: $coinId")
                    return@launch
                }

                Log.d("MainActivity", "✅ Asset found: ${asset.symbol}")

                // 3. Create a Contact object from nametag for compatibility
                val recipientContact = Contact(
                    id = recipientNametag,
                    name = recipientNametag,
                    address = "$recipientNametag@unicity",
                    avatarUrl = null,
                    isUnicityUser = true
                )

                Log.d("MainActivity", "✅ Created contact: name=${recipientContact.name}, address=${recipientContact.address}")
                Log.d("MainActivity", "🚀 Calling sendTokensWithSplitting...")

                // 4. Use existing sendTokensWithSplitting logic
                sendTokensWithSplitting(tokensForCoin, amount, asset, recipientContact)

            } catch (e: Exception) {
                Log.e("MainActivity", "Error executing Nostr transfer", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Find tokens by coinId from wallet
     */
    private fun findTokenByCoinId(coinIdHex: String): Token? {
        return viewModel.tokens.value.find { token ->
            token.coinId?.equals(coinIdHex, ignoreCase = true) == true
        }
    }

    private fun formatCryptoAmount(amount: Double): String {
        return if (amount % 1 == 0.0) {
            amount.toInt().toString()
        } else {
            String.format("%.8f", amount).trimEnd('0').trimEnd('.')
        }
    }

    private fun sendTransferRequestToBackend(recipient: Contact, crypto: CryptoCurrency, amount: Double, message: String) {
        lifecycleScope.launch {
            try {
                // Extract recipient's unicity tag
                val recipientTag = recipient.getUnicityTag()

                if (recipientTag.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Recipient does not have a valid @unicity tag", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("MainActivity", "Sending ${amount} ${crypto.symbol} to $recipientTag via Nostr")

                // Show progress
                Toast.makeText(this@MainActivity, "Sending ${crypto.symbol} to ${recipient.name}...", Toast.LENGTH_SHORT).show()

                // Create crypto transfer data (simplified - will be replaced with SDK token)
                val cryptoTransferData = createCryptoTransferData(crypto, amount)

                // Get Nostr P2P service
                val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(this@MainActivity)

                if (nostrService == null) {
                    Toast.makeText(this@MainActivity, "Nostr service not initialized", Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "NostrP2PService is null")
                    return@launch
                }

                // Start Nostr service if not running
                if (!nostrService.isRunning()) {
                    nostrService.start()
                    // Give it a moment to connect
                    delay(2000)
                }

                // Send token via Nostr
                val result = nostrService.sendTokenTransfer(
                    recipientNametag = recipientTag,
                    tokenJson = cryptoTransferData,
                    amount = (amount * 100000000).toLong(), // Convert to smallest unit
                    symbol = crypto.symbol
                )

                result.onSuccess { eventId ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Sent ${amount} ${crypto.symbol} to ${recipient.name}", Toast.LENGTH_SHORT).show()
                        Log.d("MainActivity", "Token transfer sent via Nostr: $eventId")
                    }

                    // Deduct balance immediately (optimistic update)
                    val currentBalance = crypto.balance
                    val newBalance = currentBalance - amount
                    viewModel.updateCryptoBalance(crypto.id, newBalance)

                    Log.d("MainActivity", "Balance updated: $currentBalance -> $newBalance ${crypto.symbol}")
                }.onFailure { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to send: ${error.message}", Toast.LENGTH_LONG).show()
                        Log.e("MainActivity", "Error sending token via Nostr", error)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error sending: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("MainActivity", "Error in sendTransferRequestToBackend", e)
                }
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(handshakeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(p2pStatusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cryptoReceivedReceiver)
    }
    
    private fun showHandshakeDialog(fromTag: String, fromName: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("New Chat Request")
                .setMessage("$fromTag is trying to chat")
                .setPositiveButton("Accept") { _, _ ->
                    // Get P2P service instance and accept handshake
                    val p2pService = org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance()
                    p2pService?.acceptHandshake(fromTag)
                    
                    // Open chat activity
                    val intent = Intent(this, org.unicitylabs.wallet.ui.chat.ChatActivity::class.java).apply {
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

    private fun startNostrService() {
        try {
            val prefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
            val unicityTag = prefs.getString("unicity_tag", "") ?: ""

            if (unicityTag.isNotEmpty()) {
                val nostrService = org.unicitylabs.wallet.nostr.NostrP2PService.getInstance(applicationContext)
                if (nostrService != null && !nostrService.isRunning()) {
                    nostrService.start()
                    android.util.Log.d("MainActivity", "Nostr P2P service started to listen for token transfers")
                }
            } else {
                android.util.Log.d("MainActivity", "No nametag yet, will start Nostr service after minting")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start Nostr service", e)
        }
    }

}