package org.unicitylabs.wallet.ui.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.model.TokenStatus
import org.unicitylabs.wallet.databinding.ActivityReceiveBinding
import org.unicitylabs.wallet.model.PaymentRequest
import org.unicitylabs.wallet.nfc.HostCardEmulatorLogic
import org.unicitylabs.wallet.sdk.UnicitySdkService
import org.unicitylabs.wallet.token.UnicityTokenRegistry
import org.unicitylabs.wallet.ui.wallet.MainActivity
import org.unicitylabs.wallet.util.JsonMapper
import org.unicitylabs.wallet.viewmodel.ReceiveState
import org.unicitylabs.wallet.viewmodel.ReceiveViewModel

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private val viewModel: ReceiveViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    // Using shared JsonMapper.mapper
    private lateinit var sdkService: UnicitySdkService
    
    // Success dialog properties
    private lateinit var confettiContainer: FrameLayout
    private lateinit var transferDetailsText: TextView
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogDismissRunnable: Runnable? = null
    
    private val tokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "org.unicitylabs.nfcwalletdemo.TOKEN_RECEIVED" -> {
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
                                val transferData = JsonMapper.fromJson(tokenJson, Map::class.java)
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
                                        val token = JsonMapper.fromJson(tokenJson, Token::class.java)
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
                "org.unicitylabs.nfcwalletdemo.CRYPTO_RECEIVED" -> {
                    val cryptoJson = intent.getStringExtra("crypto_json")
                    if (cryptoJson != null) {
                        handleCryptoTransfer(cryptoJson)
                    }
                }
                "org.unicitylabs.nfcwalletdemo.TRANSFER_PROGRESS" -> {
                    val currentBytes = intent.getIntExtra("current_bytes", 0)
                    val totalBytes = intent.getIntExtra("total_bytes", 0)
                    runOnUiThread {
                        viewModel.onReceivingProgress(currentBytes, totalBytes)
                    }
                }
                "org.unicitylabs.nfcwalletdemo.NFC_TEST_RECEIVED" -> {
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
                "org.unicitylabs.nfcwalletdemo.HYBRID_HANDSHAKE_RECEIVED" -> {
                    val handshakeJson = intent.getStringExtra("handshake") ?: ""
                    val senderMAC = intent.getStringExtra("sender_mac") ?: ""
                    val transferId = intent.getStringExtra("transfer_id") ?: ""
                    val tokenPreviewJson = intent.getStringExtra("token_preview") ?: ""
                    
                    Log.d("ReceiveActivity", "Hybrid handshake received from $senderMAC")
                    runOnUiThread {
                        Toast.makeText(this@ReceiveActivity, "Bluetooth transfer incoming...", Toast.LENGTH_SHORT).show()
                        binding.tvStatus.text = "Bluetooth transfer starting..."
                        
                        // TODO: Start Bluetooth receiver mode with the transfer info
                        // For now, just show that we received the handshake
                        lifecycleScope.launch {
                            handleHybridHandshake(senderMAC, transferId, tokenPreviewJson)
                        }
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

        // Show receive options dialog immediately
        showReceiveOptionsDialog()
    }
    
    private fun setupActionBar() {
        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            setDisplayShowTitleEnabled(false)
            setCustomView(R.layout.actionbar_receive_layout)
        }
    }
    
    private fun setupNfc() {
        // NFC is no longer required for QR-based receive flow
        // NFC functionality kept for legacy NFC token receive (handled by HCE service)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
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
        
        // Check for recent token transfers from SharedPreferences
        checkForRecentTokenTransfers()
        
        // Register broadcast receiver for NFC transfers and progress
        val filter = IntentFilter().apply {
            addAction("org.unicitylabs.nfcwalletdemo.TOKEN_RECEIVED")
            addAction("org.unicitylabs.nfcwalletdemo.CRYPTO_RECEIVED")
            addAction("org.unicitylabs.nfcwalletdemo.TRANSFER_PROGRESS")
            addAction("org.unicitylabs.nfcwalletdemo.NFC_TEST_RECEIVED")
            addAction("org.unicitylabs.nfcwalletdemo.HYBRID_HANDSHAKE_RECEIVED")
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
    
    private fun checkForRecentTokenTransfers() {
        val prefs = getSharedPreferences("nfc_token_transfers", Context.MODE_PRIVATE)
        val lastTokenTime = prefs.getLong("last_token_time", 0)
        val lastOfflineTime = prefs.getLong("last_offline_time", 0)
        val currentTime = System.currentTimeMillis()
        
        // Check for recent regular token transfer (within 5 seconds)
        if (lastTokenTime > 0 && (currentTime - lastTokenTime) < 5000) {
            val tokenJson = prefs.getString("last_token_json", null)
            val tokenName = prefs.getString("last_token_name", "Unknown")
            
            // Clear the saved token transfer
            prefs.edit().apply {
                remove("last_token_json")
                remove("last_token_time")
                remove("last_token_name")
                apply()
            }
            
            if (tokenJson != null) {
                Log.d("ReceiveActivity", "Found recent token transfer: $tokenName")
                // Process the token transfer
                lifecycleScope.launch {
                    try {
                        val token = JsonMapper.fromJson(tokenJson, Token::class.java)
                        viewModel.onTokenReceived(token)
                        showSuccessDialog("Received ${token.name} successfully!")
                        Toast.makeText(this@ReceiveActivity, "Token received: ${token.name}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ReceiveActivity", "Error processing saved token transfer", e)
                    }
                }
            }
        }
        
        // Check for recent offline transfer (within 5 seconds)
        else if (lastOfflineTime > 0 && (currentTime - lastOfflineTime) < 5000) {
            val offlineTransaction = prefs.getString("last_offline_transaction", null)
            val tokenName = prefs.getString("last_offline_token_name", "Unknown") ?: "Unknown"
            val transferType = prefs.getString("last_transfer_type", "")
            
            // Clear the saved offline transfer
            prefs.edit().apply {
                remove("last_offline_transaction")
                remove("last_offline_time")
                remove("last_offline_token_name")
                remove("last_transfer_type")
                apply()
            }
            
            if (offlineTransaction != null && transferType == "unicity_offline_transfer") {
                Log.d("ReceiveActivity", "Found recent offline transfer: $tokenName")
                lifecycleScope.launch {
                    handleNewOfflineTransfer(tokenName, offlineTransaction)
                }
            }
        }
    }
    
    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
    
    private suspend fun handleHybridHandshake(senderMAC: String, transferId: String, tokenPreviewJson: String) {
        Log.d("ReceiveActivity", "Starting Bluetooth receiver for transfer $transferId from $senderMAC")
        
        // TODO: Implement actual Bluetooth receiver logic
        // For now, just show progress
        runOnUiThread {
            binding.tvStatus.text = "Waiting for Bluetooth connection from $senderMAC..."
            viewModel.onNfcDetected()
        }
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
                pendingOfflineData = JsonMapper.toJson(mapOf(
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
            val receiverIdentityJson = JsonMapper.toJson(receiverIdentity)
            
            Log.d("ReceiveActivity", "Generated receiver identity for offline transfer")
            
            // Create pending token immediately
            val pendingToken = Token(
                name = tokenName,
                type = "Unicity Token",
                status = TokenStatus.PENDING,
                isOfflineTransfer = true,
                pendingOfflineData = JsonMapper.toJson(mapOf(
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
                        val identity = JsonMapper.fromJson(identityJson, Map::class.java) as Map<String, String>
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
            val resultData = JsonMapper.fromJson(resultJson, Map::class.java)
            
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
            
            val cryptoData = JsonMapper.fromJson(cryptoJson, Map::class.java)
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

    // ========== NEW NOSTR-BASED RECEIVE FUNCTIONALITY ==========

    /**
     * Show receive options dialog: Specify Amount or Let Sender Choose
     */
    private fun showReceiveOptionsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_receive_options, null)

        val cardSpecifyAmount = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardSpecifyAmount)
        val cardLetSenderChoose = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardLetSenderChoose)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cardSpecifyAmount.setOnClickListener {
            dialog.dismiss()
            showSpecifyAmountDialog()
        }

        cardLetSenderChoose.setOnClickListener {
            dialog.dismiss()
            showOpenQRCode()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    /**
     * Show dialog to specify asset and amount (reuses existing UI from Send feature)
     */
    private fun showSpecifyAmountDialog() {
        // Step 1: Show asset selection dialog (reuse existing layout)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_asset, null)
        val rvAssets = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAssets)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        // Load token registry
        val registry = UnicityTokenRegistry.getInstance(this)
        val assets = registry.getFungibleTokens() // Only show fungible tokens for requests

        val assetDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Setup RecyclerView with TokenDefinitionAdapter
        rvAssets.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = TokenDefinitionAdapter(assets) { selectedAsset ->
            assetDialog.dismiss()
            // Step 2: Show amount dialog for selected asset
            showAmountInputDialog(selectedAsset)
        }
        rvAssets.adapter = adapter

        btnCancel.setOnClickListener {
            assetDialog.dismiss()
            showReceiveOptionsDialog()
        }

        assetDialog.show()
    }

    /**
     * Show amount input dialog for selected asset (reuses existing layout from Send feature)
     */
    private fun showAmountInputDialog(asset: org.unicitylabs.wallet.token.TokenDefinition) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_amount_input, null)
        val etAmount = dialogView.findViewById<TextInputEditText>(R.id.etAmount)
        val tvBalance = dialogView.findViewById<TextView>(R.id.tvBalance)
        val tvSplitInfo = dialogView.findViewById<TextView>(R.id.tvSplitInfo)

        // Hide split info for receive (not applicable)
        tvSplitInfo.visibility = View.GONE

        // Hide balance for receive (we're requesting, not sending)
        tvBalance.visibility = View.GONE

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

                val amount = try {
                    java.math.BigInteger(amountText)
                } catch (e: Exception) {
                    null
                }

                if (amount == null || amount <= java.math.BigInteger.ZERO) {
                    Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                showQRCodeWithRequest(asset.id, amount)
            }
            .setNegativeButton("Back") { _, _ ->
                showSpecifyAmountDialog()
            }
            .setCancelable(false)
            .create()

        dialog.show()
    }

    /**
     * Show QR code with open payment request (no coinId/amount specified)
     */
    private fun showOpenQRCode() {
        val nametag = getNametag()
        if (nametag == null) {
            Toast.makeText(this, "Please set up your nametag in Profile first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val paymentRequest = PaymentRequest(nametag = nametag)
        showQRCodeDialog(paymentRequest)
    }

    /**
     * Show QR code with specific payment request (coinId + amount)
     */
    private fun showQRCodeWithRequest(coinId: String, amount: java.math.BigInteger) {
        val nametag = getNametag()
        if (nametag == null) {
            Toast.makeText(this, "Please set up your nametag in Profile first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val paymentRequest = PaymentRequest(
            nametag = nametag,
            coinId = coinId,
            amount = amount
        )
        showQRCodeDialog(paymentRequest)
    }

    /**
     * Show QR code dialog with the payment request
     */
    private fun showQRCodeDialog(paymentRequest: PaymentRequest) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_code, null)

        val qrCodeImage = dialogView.findViewById<ImageView>(R.id.qrCodeImage)
        val statusText = dialogView.findViewById<TextView>(R.id.statusText)
        val timerText = dialogView.findViewById<TextView>(R.id.timerText)
        val btnShare = dialogView.findViewById<MaterialButton>(R.id.btnShare)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Generate QR code
        try {
            val qrContent = paymentRequest.toJson()
            val qrBitmap = generateQRCode(qrContent)
            qrCodeImage.setImageBitmap(qrBitmap)

            // Update status text
            val registry = UnicityTokenRegistry.getInstance(this)
            val statusMessage = if (paymentRequest.isSpecific()) {
                val asset = registry.getCoinById(paymentRequest.coinId!!)
                "Scan to send ${paymentRequest.amount} ${asset?.symbol ?: "tokens"}"
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
                finish()
            }

            dialog.show()

        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Error generating QR code", e)
            Toast.makeText(this, "Error generating QR code: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Generate QR code bitmap from content
     */
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

    /**
     * Get user's nametag from SharedPreferences
     */
    private fun getNametag(): String? {
        val prefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val nametag = prefs.getString("unicity_tag", null)
        return if (nametag.isNullOrEmpty()) null else nametag
    }

    override fun onDestroy() {
        super.onDestroy()
        // Reset transfer mode when leaving
        HostCardEmulatorLogic.currentTransferMode = HostCardEmulatorLogic.TRANSFER_MODE_DIRECT
        sdkService.destroy()
    }
}