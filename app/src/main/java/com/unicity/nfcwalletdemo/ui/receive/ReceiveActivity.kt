package com.unicity.nfcwalletdemo.ui.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivityReceiveBinding
import com.unicity.nfcwalletdemo.viewmodel.ReceiveState
import com.unicity.nfcwalletdemo.viewmodel.ReceiveViewModel
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.TokenStatus
import com.unicity.nfcwalletdemo.nfc.HostCardEmulatorLogic
import com.unicity.nfcwalletdemo.ui.wallet.MainActivity
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import com.unicity.nfcwalletdemo.sdk.UnicityIdentity
import com.unicity.nfcwalletdemo.sdk.UnicityToken
import com.google.gson.Gson
import kotlinx.coroutines.launch

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private val viewModel: ReceiveViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    private val gson = Gson()
    private lateinit var sdkService: UnicitySdkService
    
    // Success dialog properties
    private lateinit var confettiContainer: FrameLayout
    private lateinit var transferDetailsText: TextView
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogDismissRunnable: Runnable? = null
    
    private val tokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.unicity.nfcwalletdemo.TOKEN_RECEIVED" -> {
                    // Check if this is the new offline transfer format
                    val transferType = intent.getStringExtra("transfer_type")
                    
                    if (transferType == "unicity_offline_transfer") {
                        // New offline transfer format from handshake
                        val tokenName = intent.getStringExtra("token_name") ?: "Unknown Token"
                        val offlineTransaction = intent.getStringExtra("offline_transaction") ?: ""
                        
                        Log.d("ReceiveActivity", "New offline transfer received: $tokenName")
                        lifecycleScope.launch {
                            handleNewOfflineTransfer(tokenName, offlineTransaction)
                        }
                        
                    } else {
                        // Legacy format
                        val tokenJson = intent.getStringExtra("token_json")
                        if (tokenJson != null) {
                            try {
                                // Check if this is a Unicity transfer or demo token
                                val transferData = gson.fromJson(tokenJson, Map::class.java)
                                when (transferData["type"]) {
                                    "unicity_transfer" -> {
                                        // Handle online Unicity transfer
                                        lifecycleScope.launch {
                                            handleUnicityTransfer(transferData)
                                        }
                                    }
                                    "unicity_offline_transfer" -> {
                                        // Handle offline Unicity transfer (legacy format)
                                        lifecycleScope.launch {
                                            handleOfflineUnicityTransfer(transferData)
                                        }
                                    }
                                    else -> {
                                        // Handle demo token
                                        val token = gson.fromJson(tokenJson, Token::class.java)
                                        Log.d("ReceiveActivity", "Demo token received via NFC: ${token.name}")
                                        runOnUiThread {
                                            viewModel.onTokenReceived(token)
                                            showSuccessDialog("Received ${token.name} successfully!")
                                            Toast.makeText(this@ReceiveActivity, "Token received: ${token.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ReceiveActivity", "Error parsing received token", e)
                                runOnUiThread {
                                    Toast.makeText(this@ReceiveActivity, "Error receiving token: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                "com.unicity.nfcwalletdemo.CRYPTO_RECEIVED" -> {
                    val cryptoJson = intent.getStringExtra("crypto_json")
                    if (cryptoJson != null) {
                        handleCryptoTransfer(cryptoJson)
                    }
                }
                "com.unicity.nfcwalletdemo.TRANSFER_PROGRESS" -> {
                    val currentBytes = intent.getIntExtra("current_bytes", 0)
                    val totalBytes = intent.getIntExtra("total_bytes", 0)
                    runOnUiThread {
                        viewModel.onReceivingProgress(currentBytes, totalBytes)
                    }
                }
                "com.unicity.nfcwalletdemo.NFC_TEST_RECEIVED" -> {
                    val pingMessage = intent.getStringExtra("ping_message") ?: "Unknown"
                    val timestamp = intent.getLongExtra("timestamp", 0)
                    
                    Log.d("ReceiveActivity", "NFC test received: $pingMessage at $timestamp")
                    runOnUiThread {
                        Toast.makeText(this@ReceiveActivity, "NFC Test Received: $pingMessage", Toast.LENGTH_LONG).show()
                        showSuccessDialog("NFC Test Success!\n\nReceived: $pingMessage")
                        
                        // Navigate back to MainActivity after showing success
                        binding.root.postDelayed({
                            navigateToMainActivity()
                        }, 3500) // Wait slightly longer than success dialog auto-dismiss
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sdkService = UnicitySdkService(this)
        
        setupActionBar()
        setupNfc()
        setupViews()
        setupSuccessDialog()
        observeViewModel()
        
        // Check if this was auto-started from NFC tap
        val autoStarted = intent.getBooleanExtra("auto_started", true)
        if (autoStarted) {
            Log.d("ReceiveActivity", "Auto-started from NFC tap")
            viewModel.onNfcDetected()
            
            // Set to direct NFC mode
            HostCardEmulatorLogic.currentTransferMode = HostCardEmulatorLogic.TRANSFER_MODE_DIRECT
            Toast.makeText(this, "Ready to receive token", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupActionBar() {
        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            setDisplayShowTitleEnabled(false)
            setCustomView(R.layout.actionbar_receive_layout)
        }
    }
    
    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.error_nfc_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, R.string.error_nfc_disabled, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupViews() {
        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
    
    
    private fun observeViewModel() {
        // Observe receive state
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateUI(state)
            }
        }
        
        // Observe status message
        lifecycleScope.launch {
            viewModel.statusMessage.collect { message ->
                binding.tvStatus.text = message
            }
        }
    }
    
    private fun updateUI(state: ReceiveState) {
        when (state) {
            ReceiveState.READY_TO_RECEIVE -> {
                binding.ivNfcIcon.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.GONE
            }
            ReceiveState.CONNECTION_REQUEST,
            ReceiveState.GENERATING_ADDRESS,
            ReceiveState.RECEIVING_TOKEN -> {
                binding.ivNfcIcon.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                binding.ivSuccess.visibility = View.GONE
            }
            ReceiveState.SUCCESS -> {
                binding.ivNfcIcon.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.VISIBLE
                binding.btnCancel.text = "Done"
                // Navigate back to MainActivity to show updated wallet
                binding.root.postDelayed({
                    navigateToMainActivity()
                }, 1500)
            }
            ReceiveState.ERROR -> {
                binding.ivNfcIcon.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.GONE
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // HCE is automatically enabled when the service is declared in manifest
        
        // Check for recent test results from SharedPreferences
        checkForRecentTestResults()
        
        // Register broadcast receiver for NFC transfers and progress
        val filter = IntentFilter().apply {
            addAction("com.unicity.nfcwalletdemo.TOKEN_RECEIVED")
            addAction("com.unicity.nfcwalletdemo.CRYPTO_RECEIVED")
            addAction("com.unicity.nfcwalletdemo.TRANSFER_PROGRESS")
            addAction("com.unicity.nfcwalletdemo.NFC_TEST_RECEIVED")
        }
        
        // For Android 14+ (API 34+), we need to specify RECEIVER_NOT_EXPORTED
        // since this is an internal app broadcast
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(tokenReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tokenReceiver, filter)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // HCE is automatically disabled when activity is paused
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(tokenReceiver)
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Error unregistering receiver", e)
        }
    }
    
    private fun checkForRecentTestResults() {
        val prefs = getSharedPreferences("nfc_test_results", Context.MODE_PRIVATE)
        val lastTestTime = prefs.getLong("last_test_time", 0)
        val currentTime = System.currentTimeMillis()
        
        // Check if there was a test in the last 5 seconds
        if (lastTestTime > 0 && (currentTime - lastTestTime) < 5000) {
            val pingMessage = prefs.getString("last_test_ping", "Unknown") ?: "Unknown"
            
            // Clear the saved test result
            prefs.edit().clear().apply()
            
            Log.d("ReceiveActivity", "Found recent NFC test: $pingMessage")
            Toast.makeText(this, "NFC Test Received: $pingMessage", Toast.LENGTH_LONG).show()
            showSuccessDialog("NFC Test Success!\n\nReceived: $pingMessage")
            
            // Navigate back to MainActivity after showing success
            binding.root.postDelayed({
                navigateToMainActivity()
            }, 3500) // Wait slightly longer than success dialog auto-dismiss
        }
    }
    
    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("ReceiveActivity", "Configuration changed - maintaining HCE state")
        // The activity is not recreated, so HCE continues
    }
    
    
    private suspend fun handleUnicityTransfer(transferData: Map<*, *>) {
        try {
            Log.d("ReceiveActivity", "Processing Unicity transfer")
            
            runOnUiThread {
                binding.tvStatus.text = "Processing Unicity transfer..."
                viewModel.onReceivingToken()
            }
            
            // Extract transfer details
            val tokenName = transferData["token_name"] as? String ?: "Unknown Token"
            val transferJson = transferData["transfer_data"] as? String ?: ""
            val receiverIdentityJson = transferData["receiver_identity"] as? String ?: ""
            
            if (transferJson.isEmpty() || receiverIdentityJson.isEmpty()) {
                throw Exception("Invalid transfer data")
            }
            
            // Use SDK to finish the transfer
            val result = finishUnicityTransfer(receiverIdentityJson, transferJson)
            
            // Create Token object from the result
            val finalToken = createTokenFromUnicityResult(tokenName, result)
            
            Log.d("ReceiveActivity", "Unicity token processed: ${finalToken.name}")
            
            runOnUiThread {
                viewModel.onTokenReceived(finalToken)
                showSuccessDialog("Received ${finalToken.name} successfully!")
                Toast.makeText(this@ReceiveActivity, "Unicity token received: ${finalToken.name}", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Failed to process Unicity transfer", e)
            runOnUiThread {
                viewModel.onError("Failed to process Unicity transfer: ${e.message}")
                Toast.makeText(this@ReceiveActivity, "Failed to receive Unicity token: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun finishUnicityTransfer(receiverIdentityJson: String, transferJson: String): String {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            sdkService.finishTransfer(receiverIdentityJson, transferJson) { result ->
                result.onSuccess { resultJson ->
                    continuation.resumeWith(Result.success(resultJson))
                }
                result.onFailure { error ->
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    }
    
    private suspend fun handleOfflineUnicityTransfer(transferData: Map<*, *>) {
        try {
            Log.d("ReceiveActivity", "Processing offline Unicity transfer")
            
            runOnUiThread {
                binding.tvStatus.text = "Processing offline Unicity transfer..."
                viewModel.onReceivingToken()
            }
            
            // Extract offline transfer details
            val tokenName = transferData["token_name"] as? String ?: "Unknown Token"
            val offlineTransactionJson = transferData["offline_transaction"] as? String ?: ""
            val receiverIdentityJson = transferData["receiver_identity"] as? String ?: ""
            
            if (offlineTransactionJson.isEmpty() || receiverIdentityJson.isEmpty()) {
                throw Exception("Invalid offline transfer data")
            }
            
            // First, create a pending token to show in UI immediately
            val pendingToken = Token(
                name = tokenName,
                type = "Unicity Token",
                status = TokenStatus.PENDING,
                isOfflineTransfer = true,
                pendingOfflineData = gson.toJson(mapOf(
                    "receiverIdentity" to receiverIdentityJson,
                    "offlineTransaction" to offlineTransactionJson
                )),
                unicityAddress = "pending_${System.currentTimeMillis()}",
                jsonData = offlineTransactionJson,
                sizeBytes = offlineTransactionJson.length
            )
            
            runOnUiThread {
                viewModel.onTokenReceived(pendingToken)
                showSuccessDialog("Received ${pendingToken.name} via offline transfer! Submitting to network...")
                Toast.makeText(this@ReceiveActivity, "Offline token received: ${pendingToken.name} (submitting...)", Toast.LENGTH_SHORT).show()
            }
            
            // Now try to complete the offline transfer in the background with auto-retry
            var retryCount = 0
            val maxRetries = 2
            
            while (retryCount <= maxRetries) {
                try {
                    val result = completeOfflineUnicityTransfer(receiverIdentityJson, offlineTransactionJson)
                    
                    // Update token status to confirmed
                    val confirmedToken = pendingToken.copy(
                        status = TokenStatus.CONFIRMED,
                        unicityAddress = "unicity_received_${System.currentTimeMillis()}",
                        jsonData = result,
                        sizeBytes = result.length,
                        pendingOfflineData = null
                    )
                    
                    Log.d("ReceiveActivity", "Offline Unicity token confirmed: ${confirmedToken.name}")
                    
                    runOnUiThread {
                        viewModel.onTokenReceived(confirmedToken)
                        Toast.makeText(this@ReceiveActivity, "Token ${confirmedToken.name} confirmed on network!", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Success - exit retry loop
                    break
                    
                } catch (e: Exception) {
                    retryCount++
                    Log.e("ReceiveActivity", "Failed to submit offline transfer to network (attempt $retryCount)", e)
                    
                    if (retryCount <= maxRetries) {
                        // Wait before retrying
                        try {
                            Thread.sleep((2000 * retryCount).toLong()) // Exponential backoff: 2s, 4s
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        
                        runOnUiThread {
                            Toast.makeText(this@ReceiveActivity, "Retrying network submission (attempt $retryCount)...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Final failure - update token status to failed but keep the token
                        val failedToken = pendingToken.copy(
                            status = TokenStatus.FAILED
                        )
                        
                        runOnUiThread {
                            viewModel.onTokenReceived(failedToken)
                            Toast.makeText(this@ReceiveActivity, "Token received but network submission failed after $maxRetries retries. You can retry manually.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Failed to process offline Unicity transfer", e)
            runOnUiThread {
                viewModel.onError("Failed to process offline Unicity transfer: ${e.message}")
                Toast.makeText(this@ReceiveActivity, "Failed to receive offline Unicity token: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun completeOfflineUnicityTransfer(receiverIdentityJson: String, offlineTransactionJson: String): String {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            sdkService.completeOfflineTransfer(receiverIdentityJson, offlineTransactionJson) { result ->
                result.onSuccess { resultJson ->
                    continuation.resumeWith(Result.success(resultJson))
                }
                result.onFailure { error ->
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    }
    
    /**
     * Handle new offline transfer format from proper handshake
     */
    private suspend fun handleNewOfflineTransfer(tokenName: String, offlineTransactionJson: String) {
        try {
            Log.d("ReceiveActivity", "Processing new offline transfer for: $tokenName")
            
            runOnUiThread {
                binding.tvStatus.text = "Processing offline transfer..."
                viewModel.onReceivingToken()
            }
            
            if (offlineTransactionJson.isEmpty()) {
                throw Exception("Empty offline transaction data")
            }
            
            // Get the receiver identity that was already generated during the handshake
            // This should match the one used to generate the receiver address
            val receiverIdentity = HostCardEmulatorLogic.getGeneratedReceiverIdentity()
                ?: throw Exception("No receiver identity found - handshake may have failed")
            val receiverIdentityJson = gson.toJson(receiverIdentity)
            
            Log.d("ReceiveActivity", "Generated receiver identity for offline transfer")
            
            // Create pending token immediately
            val pendingToken = Token(
                name = tokenName,
                type = "Unicity Token",
                status = TokenStatus.PENDING,
                isOfflineTransfer = true,
                pendingOfflineData = gson.toJson(mapOf(
                    "receiverIdentity" to receiverIdentityJson,
                    "offlineTransaction" to offlineTransactionJson
                )),
                unicityAddress = "pending_${System.currentTimeMillis()}",
                jsonData = offlineTransactionJson,
                sizeBytes = offlineTransactionJson.length
            )
            
            runOnUiThread {
                viewModel.onTokenReceived(pendingToken)
                showSuccessDialog("Received ${pendingToken.name} via offline transfer! Submitting to network...")
                Toast.makeText(this@ReceiveActivity, "Offline token received: ${pendingToken.name} (submitting...)", Toast.LENGTH_SHORT).show()
            }
            
            // Try to complete the offline transfer in the background
            var retryCount = 0
            val maxRetries = 2
            
            while (retryCount <= maxRetries) {
                try {
                    val result = completeOfflineUnicityTransfer(receiverIdentityJson, offlineTransactionJson)
                    
                    // Update token status to confirmed
                    val confirmedToken = pendingToken.copy(
                        status = TokenStatus.CONFIRMED,
                        unicityAddress = "unicity_received_${System.currentTimeMillis()}",
                        jsonData = result,
                        sizeBytes = result.length,
                        pendingOfflineData = null
                    )
                    
                    Log.d("ReceiveActivity", "New offline transfer confirmed: ${confirmedToken.name}")
                    
                    runOnUiThread {
                        viewModel.onTokenReceived(confirmedToken)
                        Toast.makeText(this@ReceiveActivity, "Token ${confirmedToken.name} confirmed on network!", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Success - exit retry loop
                    break
                    
                } catch (e: Exception) {
                    retryCount++
                    Log.e("ReceiveActivity", "Failed to submit new offline transfer (attempt $retryCount)", e)
                    
                    if (retryCount <= maxRetries) {
                        try {
                            Thread.sleep((2000 * retryCount).toLong()) // Exponential backoff: 2s, 4s
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        
                        runOnUiThread {
                            Toast.makeText(this@ReceiveActivity, "Retrying network submission (attempt $retryCount)...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Final failure - update token status to failed
                        val failedToken = pendingToken.copy(
                            status = TokenStatus.FAILED
                        )
                        
                        runOnUiThread {
                            viewModel.onTokenReceived(failedToken)
                            Toast.makeText(this@ReceiveActivity, "Token received but network submission failed. You can retry manually.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Failed to process new offline transfer", e)
            runOnUiThread {
                viewModel.onError("Failed to process offline transfer: ${e.message}")
                Toast.makeText(this@ReceiveActivity, "Failed to receive offline token: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Generate receiver identity for this device
     */
    private suspend fun generateReceiverIdentity(): Map<String, String> {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            sdkService.generateIdentity { result ->
                result.onSuccess { identityJson ->
                    try {
                        val identity = gson.fromJson(identityJson, Map::class.java) as Map<String, String>
                        continuation.resumeWith(Result.success(identity))
                    } catch (e: Exception) {
                        Log.e("ReceiveActivity", "Failed to parse receiver identity", e)
                        // Fallback identity
                        continuation.resumeWith(Result.success(mapOf(
                            "secret" to "fallback_receiver_secret", 
                            "nonce" to "fallback_receiver_nonce"
                        )))
                    }
                }
                result.onFailure { error ->
                    Log.e("ReceiveActivity", "Failed to generate receiver identity", error)
                    // Fallback identity
                    continuation.resumeWith(Result.success(mapOf(
                        "secret" to "fallback_receiver_secret", 
                        "nonce" to "fallback_receiver_nonce"
                    )))
                }
            }
        }
    }
    
    private fun createTokenFromUnicityResult(tokenName: String, resultJson: String): Token {
        return try {
            // Parse the result and create a Token object
            val resultData = gson.fromJson(resultJson, Map::class.java)
            
            Token(
                name = tokenName,
                type = "Unicity Token",
                unicityAddress = "unicity_received_${System.currentTimeMillis()}",
                jsonData = resultJson,
                sizeBytes = resultJson.length
            )
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Failed to create token from result", e)
            // Create fallback token
            Token(
                name = tokenName,
                type = "Unicity Token (Error)",
                unicityAddress = "error_${System.currentTimeMillis()}",
                jsonData = "{\"error\": \"${e.message}\"}",
                sizeBytes = 0
            )
        }
    }
    
    private fun handleCryptoTransfer(cryptoJson: String) {
        try {
            Log.d("ReceiveActivity", "Crypto transfer received via NFC")
            
            val cryptoData = gson.fromJson(cryptoJson, Map::class.java)
            val cryptoSymbol = cryptoData["crypto_symbol"] as? String ?: "UNKNOWN"
            val cryptoName = cryptoData["crypto_name"] as? String ?: "Unknown Crypto"
            
            // Handle amount with better precision - Gson might parse as Number
            val amountRaw = cryptoData["amount"]
            val amount = when (amountRaw) {
                is Number -> amountRaw.toDouble()
                is String -> amountRaw.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            
            val priceUsdRaw = cryptoData["price_usd"]
            val priceUsd = when (priceUsdRaw) {
                is Number -> priceUsdRaw.toDouble()
                is String -> priceUsdRaw.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            
            val priceEurRaw = cryptoData["price_eur"]
            val priceEur = when (priceEurRaw) {
                is Number -> priceEurRaw.toDouble()
                is String -> priceEurRaw.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            
            Log.d("ReceiveActivity", "Received amount: $amount (from $amountRaw)")
            Log.d("ReceiveActivity", "Crypto transfer: $amount $cryptoSymbol")
            
            runOnUiThread {
                binding.tvStatus.text = "Processing crypto transfer..."
                viewModel.onReceivingToken()
                
                // Store the crypto data for MainActivity to process via SharedPreferences
                val prefs = getSharedPreferences("crypto_transfers", MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("crypto_received", true)
                    putString("crypto_symbol", cryptoSymbol)
                    putString("crypto_amount", amount.toString()) // Store as string to preserve precision
                    putString("crypto_name", cryptoName)
                    putString("price_usd", priceUsd.toString()) // Store as string to preserve precision
                    putString("price_eur", priceEur.toString()) // Store as string to preserve precision
                    putLong("timestamp", System.currentTimeMillis())
                    apply()
                }
                
                // Show success
                viewModel.onTokenReceived(Token(
                    id = "temp_crypto",
                    name = "Crypto Received",
                    type = "Success"
                ))
                
                val value = String.format("%.2f", amount * priceUsd)
                showSuccessDialog("Received $amount $cryptoSymbol successfully!")
                Toast.makeText(this@ReceiveActivity, "Received $amount $cryptoSymbol (~$$value)", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Error processing crypto transfer", e)
            runOnUiThread {
                viewModel.onError("Failed to process crypto transfer: ${e.message}")
                Toast.makeText(this@ReceiveActivity, "Failed to receive crypto: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
            Log.e("ReceiveActivity", "Error setting up success dialog", e)
        }
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
            Log.e("ReceiveActivity", "Error showing success dialog", e)
        }
    }
    
    private fun dismissSuccessDialog() {
        try {
            dialogDismissRunnable?.let { dialogHandler.removeCallbacks(it) }
            if (::confettiContainer.isInitialized) {
                confettiContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Error dismissing success dialog", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Reset transfer mode when leaving
        HostCardEmulatorLogic.currentTransferMode = HostCardEmulatorLogic.TRANSFER_MODE_DIRECT
        sdkService.destroy()
    }
}