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
import com.unicity.nfcwalletdemo.ble.BleServer
import com.unicity.nfcwalletdemo.nfc.HostCardEmulatorService
import com.unicity.nfcwalletdemo.ui.wallet.MainActivity
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import com.google.gson.Gson
import kotlinx.coroutines.launch

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private val viewModel: ReceiveViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    private var bleServer: BleServer? = null
    private val gson = Gson()
    
    private val tokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.unicity.nfcwalletdemo.TOKEN_RECEIVED") {
                val tokenJson = intent.getStringExtra("token_json")
                if (tokenJson != null) {
                    try {
                        val token = gson.fromJson(tokenJson, Token::class.java)
                        Log.d("ReceiveActivity", "Token received via direct NFC: ${token.name}")
                        runOnUiThread {
                            viewModel.onTokenReceived(token)
                            Toast.makeText(this@ReceiveActivity, "Token received via direct NFC: ${token.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("ReceiveActivity", "Error parsing received token", e)
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNfc()
        setupViews()
        observeViewModel()
        
        // Check if this was auto-started from NFC tap
        val autoStarted = intent.getBooleanExtra("auto_started", false)
        if (autoStarted) {
            Log.d("ReceiveActivity", "Auto-started from NFC tap")
            viewModel.onNfcDetected()
            
            // Try BLE first, but fallback to direct NFC if needed
            if (isBleSupported()) {
                startBleServer()
            } else {
                Log.d("ReceiveActivity", "BLE not supported on this device, using direct NFC")
                setDirectNfcMode()
                Toast.makeText(this, "Ready for direct NFC transfer", Toast.LENGTH_SHORT).show()
            }
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
        
        // Register broadcast receiver for direct NFC transfers
        val filter = IntentFilter("com.unicity.nfcwalletdemo.TOKEN_RECEIVED")
        registerReceiver(tokenReceiver, filter)
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
    
    private fun startBleServer() {
        Log.d("ReceiveActivity", "Starting BLE server for token reception...")
        
        // Check Bluetooth permissions first
        if (!PermissionUtils.hasBluetoothPermissions(this)) {
            Log.e("ReceiveActivity", "Missing Bluetooth permissions")
            PermissionUtils.requestBluetoothPermissions(this)
            return
        }
        
        bleServer = BleServer(
            context = this,
            onConnectionRequest = { deviceName ->
                Log.d("ReceiveActivity", "BLE connection request from: $deviceName")
                runOnUiThread {
                    viewModel.onConnectionRequest(deviceName)
                }
            },
            onGenerateAddress = {
                Log.d("ReceiveActivity", "Generating address...")
                viewModel.generateAddress()
            },
            onTokenReceived = { token ->
                Log.d("ReceiveActivity", "Token received via BLE: ${token.name}")
                runOnUiThread {
                    viewModel.onTokenReceived(token)
                    Toast.makeText(this, "Token received: ${token.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("ReceiveActivity", "BLE server error: $error")
                runOnUiThread {
                    if (error.contains("not supported")) {
                        // BLE not supported - switch to direct NFC mode
                        Log.d("ReceiveActivity", "BLE not supported, switching to direct NFC mode")
                        Toast.makeText(this@ReceiveActivity, 
                            "Ready for direct NFC transfer", 
                            Toast.LENGTH_SHORT).show()
                        // Set HCE service to direct transfer mode
                        setDirectNfcMode()
                    } else {
                        viewModel.onError(error)
                    }
                }
            }
        )
        
        try {
            Log.d("ReceiveActivity", "Starting BLE server...")
            bleServer?.start()
            Log.d("ReceiveActivity", "BLE server started successfully")
            // Set BLE mode in HCE service
            HostCardEmulatorService.currentTransferMode = HostCardEmulatorService.TRANSFER_MODE_BLE
            Toast.makeText(this, "Ready to receive tokens via BLE", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ReceiveActivity", "Exception starting BLE server", e)
            runOnUiThread {
                viewModel.onError("Failed to start BLE server: ${e.message}")
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
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("ReceiveActivity", "Configuration changed - maintaining HCE state")
        // The activity is not recreated, so HCE continues
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionUtils.BLUETOOTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
                    startBleServer()
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for receiving tokens", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    
    override fun onDestroy() {
        super.onDestroy()
        bleServer?.stop()
    }
    
    private fun isBleSupported(): Boolean {
        return packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE) &&
               PermissionUtils.hasBluetoothPermissions(this) &&
               PermissionUtils.isBluetoothEnabled()
    }
    
    private fun setDirectNfcMode() {
        Log.d("ReceiveActivity", "Setting HCE service to direct NFC mode")
        // Set the transfer mode in the HCE service
        HostCardEmulatorService.currentTransferMode = HostCardEmulatorService.TRANSFER_MODE_DIRECT
        viewModel.onNfcDetected()
    }
}