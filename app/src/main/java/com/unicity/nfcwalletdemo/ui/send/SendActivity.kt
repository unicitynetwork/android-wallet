package com.unicity.nfcwalletdemo.ui.send

import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivitySendBinding
import com.unicity.nfcwalletdemo.viewmodel.SendState
import com.unicity.nfcwalletdemo.viewmodel.SendViewModel
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.coroutines.launch

class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private val sendViewModel: SendViewModel by viewModels()
    private val walletViewModel: WalletViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    private var currentToken: Token? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d("SendActivity", "onCreate called")
        setupNfc()
        setupViews()
        observeViewModels()
        
        // Enable NFC reader mode immediately if NFC is available
        if (nfcAdapter?.isEnabled == true) {
            Log.d("SendActivity", "Enabling NFC reader mode from onCreate")
            enableNfcReaderMode()
        }
    }
    
    private fun setupNfc() {
        Log.d("SendActivity", "Setting up NFC...")
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e("SendActivity", "NFC not available on this device")
            Toast.makeText(this, R.string.error_nfc_not_available, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            Log.e("SendActivity", "NFC is disabled")
            Toast.makeText(this, R.string.error_nfc_disabled, Toast.LENGTH_LONG).show()
        } else {
            Log.d("SendActivity", "NFC is available and enabled")
        }
    }
    
    private fun setupViews() {
        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
    
    private fun observeViewModels() {
        // Set the token to send from wallet view model
        lifecycleScope.launch {
            walletViewModel.selectedToken.collect { token ->
                Log.d("SendActivity", "Token selection changed: ${token?.name ?: "null"}")
                token?.let {
                    currentToken = it
                    sendViewModel.setToken(it)
                    Log.d("SendActivity", "Token set successfully: ${it.name}")
                } ?: run {
                    Log.w("SendActivity", "No token selected from wallet")
                    // Try to get the first available token if none selected
                    getFirstAvailableToken()
                }
            }
        }
        
        // Observe send state
        lifecycleScope.launch {
            sendViewModel.state.collect { state ->
                updateUI(state)
            }
        }
        
        // Observe status message
        lifecycleScope.launch {
            sendViewModel.statusMessage.collect { message ->
                binding.tvStatus.text = message
            }
        }
    }
    
    private fun updateUI(state: SendState) {
        when (state) {
            SendState.READY_TO_SEND -> {
                binding.ivNfcIcon.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.GONE
            }
            SendState.CONNECTING,
            SendState.WAITING_FOR_ADDRESS,
            SendState.FINALIZING -> {
                binding.ivNfcIcon.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                binding.ivSuccess.visibility = View.GONE
            }
            SendState.SUCCESS -> {
                binding.ivNfcIcon.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.VISIBLE
                binding.btnCancel.text = "Done"
                // Navigate back to MainActivity after short delay
                binding.root.postDelayed({
                    finish()
                }, 1500)
            }
            SendState.ERROR -> {
                binding.ivNfcIcon.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.ivSuccess.visibility = View.GONE
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("SendActivity", "onResume called")
        enableNfcReaderMode()
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("SendActivity", "onPause called")
        disableNfcReaderMode()
    }
    
    private fun enableNfcReaderMode() {
        Log.d("SendActivity", "enableNfcReaderMode called")
        
        if (nfcAdapter == null) {
            Log.e("SendActivity", "NFC adapter is null, cannot enable reader mode")
            return
        }
        
        if (!nfcAdapter!!.isEnabled) {
            Log.e("SendActivity", "NFC is not enabled, cannot enable reader mode")
            return
        }
        
        Log.d("SendActivity", "Creating NFC reader callback...")
        
        val tokenForTransfer = getTokenForTransfer()
        if (tokenForTransfer == null) {
            Log.e("SendActivity", "Cannot enable NFC reader mode - no token available")
            return
        }
        
        sendViewModel.onNfcDetected("Receiver Device")
        
        val nfcReaderCallback = com.unicity.nfcwalletdemo.nfc.NfcReaderCallback(
            onTokenSent = { 
                Log.d("SendActivity", "NFC callback: Token sent successfully")
                runOnUiThread {
                    sendViewModel.onTransferComplete()
                    Toast.makeText(this, "Token sent successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("SendActivity", "NFC callback error: $error")
                runOnUiThread {
                    sendViewModel.onError(error)
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            },
            tokenToSend = tokenForTransfer
        )
        
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        
        Log.d("SendActivity", "Enabling NFC reader mode with flags: $flags")
        try {
            nfcAdapter!!.enableReaderMode(this, nfcReaderCallback, flags, null)
            Log.d("SendActivity", "✅ NFC reader mode enabled successfully")
            
            Log.d("SendActivity", "🔍 App is now ready to scan NFC tags")
        } catch (e: Exception) {
            Log.e("SendActivity", "❌ Failed to enable NFC reader mode", e)
            runOnUiThread {
                sendViewModel.onError("Failed to enable NFC scanning: ${e.message}")
            }
        }
    }
    
    private fun disableNfcReaderMode() {
        Log.d("SendActivity", "disableNfcReaderMode called")
        try {
            nfcAdapter?.disableReaderMode(this)
            Log.d("SendActivity", "NFC reader mode disabled")
        } catch (e: Exception) {
            Log.e("SendActivity", "Error disabling NFC reader mode", e)
        }
    }
    
    private fun getTokenForTransfer(): Token? {
        var token = currentToken
        if (token == null) {
            Log.e("SendActivity", "No token selected - attempting to get first available token")
            getFirstAvailableToken()
            token = currentToken
            if (token == null) {
                Log.e("SendActivity", "Still no token available - cannot proceed")
                sendViewModel.onError("No token selected for transfer")
                return null
            }
            Log.d("SendActivity", "Using fallback token: ${token.name}")
        } else {
            Log.d("SendActivity", "Token to send: ${token.name}")
        }
        return token
    }
    
    
    private fun getFirstAvailableToken() {
        Log.d("SendActivity", "Attempting to get first available token...")
        lifecycleScope.launch {
            walletViewModel.tokens.value.firstOrNull()?.let { firstToken ->
                Log.d("SendActivity", "Found first available token: ${firstToken.name}")
                walletViewModel.selectToken(firstToken)
                currentToken = firstToken
                sendViewModel.setToken(firstToken)
            } ?: run {
                Log.e("SendActivity", "No tokens available in wallet")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
}