package com.unicity.nfcwalletdemo.ui.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivityReceiveBinding
import com.unicity.nfcwalletdemo.viewmodel.ReceiveState
import com.unicity.nfcwalletdemo.viewmodel.ReceiveViewModel
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.nfc.HostCardEmulatorService
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
    
    private val tokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.unicity.nfcwalletdemo.TOKEN_RECEIVED" -> {
                    val tokenJson = intent.getStringExtra("token_json")
                    if (tokenJson != null) {
                        try {
                            // Check if this is a Unicity transfer or demo token
                            val transferData = gson.fromJson(tokenJson, Map::class.java)
                            if (transferData["type"] == "unicity_transfer") {
                                // Handle Unicity transfer
                                lifecycleScope.launch {
                                    handleUnicityTransfer(transferData)
                                }
                            } else {
                                // Handle demo token
                                val token = gson.fromJson(tokenJson, Token::class.java)
                                Log.d("ReceiveActivity", "Demo token received via NFC: ${token.name}")
                                runOnUiThread {
                                    viewModel.onTokenReceived(token)
                                    Toast.makeText(this@ReceiveActivity, "Token received: ${token.name}", Toast.LENGTH_SHORT).show()
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
        observeViewModel()
        
        // Check if this was auto-started from NFC tap
        val autoStarted = intent.getBooleanExtra("auto_started", true)
        if (autoStarted) {
            Log.d("ReceiveActivity", "Auto-started from NFC tap")
            viewModel.onNfcDetected()
            
            // Set to direct NFC mode
            HostCardEmulatorService.currentTransferMode = HostCardEmulatorService.TRANSFER_MODE_DIRECT
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
        
        // Register broadcast receiver for NFC transfers and progress
        val filter = IntentFilter().apply {
            addAction("com.unicity.nfcwalletdemo.TOKEN_RECEIVED")
            addAction("com.unicity.nfcwalletdemo.CRYPTO_RECEIVED")
            addAction("com.unicity.nfcwalletdemo.TRANSFER_PROGRESS")
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
    
    override fun onDestroy() {
        super.onDestroy()
        // Reset transfer mode when leaving
        HostCardEmulatorService.currentTransferMode = HostCardEmulatorService.TRANSFER_MODE_DIRECT
        sdkService.destroy()
    }
}