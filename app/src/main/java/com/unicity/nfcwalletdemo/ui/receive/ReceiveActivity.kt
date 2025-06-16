package com.unicity.nfcwalletdemo.ui.receive

import android.content.Intent
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
import com.unicity.nfcwalletdemo.bluetooth.BluetoothServer
import com.unicity.nfcwalletdemo.ui.wallet.MainActivity
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import kotlinx.coroutines.launch
import android.bluetooth.BluetoothAdapter

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private val viewModel: ReceiveViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    private var bluetoothServer: BluetoothServer? = null
    
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
            startBluetoothServer()
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
    }
    
    override fun onPause() {
        super.onPause()
        // HCE is automatically disabled when activity is paused
    }
    
    private fun startBluetoothServer() {
        Log.d("ReceiveActivity", "Starting Bluetooth server for token reception...")
        
        // Check Bluetooth permissions first
        if (!PermissionUtils.hasBluetoothPermissions(this)) {
            Log.e("ReceiveActivity", "Missing Bluetooth permissions")
            PermissionUtils.requestBluetoothPermissions(this)
            return
        }
        
        // Make device discoverable for 120 seconds
        makeDeviceDiscoverable()
        
        bluetoothServer = BluetoothServer(
            context = this,
            onConnectionRequest = { deviceName ->
                Log.d("ReceiveActivity", "Connection request from: $deviceName")
                runOnUiThread {
                    viewModel.onConnectionRequest(deviceName)
                }
            },
            onGenerateAddress = {
                Log.d("ReceiveActivity", "Generating address...")
                viewModel.generateAddress()
            },
            onTokenReceived = { token ->
                Log.d("ReceiveActivity", "Token received via Bluetooth: ${token.name}")
                runOnUiThread {
                    viewModel.onTokenReceived(token)
                    Toast.makeText(this, "Token received: ${token.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("ReceiveActivity", "Bluetooth server error: $error")
                runOnUiThread {
                    viewModel.onError(error)
                }
            }
        )
        
        lifecycleScope.launch {
            try {
                Log.d("ReceiveActivity", "Calling bluetoothServer.start()...")
                bluetoothServer?.start()
                Log.d("ReceiveActivity", "bluetoothServer.start() completed")
            } catch (e: Exception) {
                Log.e("ReceiveActivity", "Exception starting Bluetooth server", e)
                runOnUiThread {
                    viewModel.onError("Failed to start Bluetooth server: ${e.message}")
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
                    startBluetoothServer()
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for receiving tokens", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    private fun makeDeviceDiscoverable() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter?.isEnabled == true) {
            // Check if already discoverable
            if (bluetoothAdapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                Log.d("ReceiveActivity", "Device is already discoverable")
                return
            }
            
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }
            try {
                startActivityForResult(discoverableIntent, DISCOVERABLE_REQUEST_CODE)
                Log.d("ReceiveActivity", "Requested device discoverability for 120 seconds")
            } catch (e: Exception) {
                Log.e("ReceiveActivity", "Failed to make device discoverable", e)
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            DISCOVERABLE_REQUEST_CODE -> {
                if (resultCode == 120) { // Duration in seconds
                    Log.d("ReceiveActivity", "Device is now discoverable for 120 seconds")
                    Toast.makeText(this, "Device is discoverable. Waiting for sender...", Toast.LENGTH_LONG).show()
                } else if (resultCode == RESULT_CANCELED) {
                    Log.e("ReceiveActivity", "User denied discoverability")
                    Toast.makeText(this, "Discoverability required for receiving tokens", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    companion object {
        private const val DISCOVERABLE_REQUEST_CODE = 200
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothServer?.stop()
    }
}