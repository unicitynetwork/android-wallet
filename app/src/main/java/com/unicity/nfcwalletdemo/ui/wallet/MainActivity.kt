package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Intent
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicity.nfcwalletdemo.databinding.ActivityMainBinding
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.nfc.NfcReaderCallback
import com.unicity.nfcwalletdemo.bluetooth.BluetoothClient
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenAdapter: TokenAdapter
    private var nfcAdapter: NfcAdapter? = null
    private var currentTransferringToken: Token? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNfc()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
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
        
        binding.rvTokens.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tokenAdapter
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            refreshWallet()
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.tokens.collect { tokens ->
                tokenAdapter.submitList(tokens)
                binding.tvEmptyState.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
                binding.rvTokens.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
    
    private fun refreshWallet() {
        // Refresh the tokens from repository
        lifecycleScope.launch {
            try {
                viewModel.refreshTokens()
            } finally {
                // Always stop the refresh spinner
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
    
    private fun startTokenTransfer(token: Token) {
        currentTransferringToken = token
        tokenAdapter.setTransferring(token, true)
        viewModel.selectToken(token)
        enableNfcForTransfer(token)
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
    
    private fun enableNfcForTransfer(token: Token) {
        if (nfcAdapter?.isEnabled != true) {
            Toast.makeText(this, "Please enable NFC to transfer tokens", Toast.LENGTH_SHORT).show()
            cancelTokenTransfer(token)
            return
        }
        
        val nfcCallback = NfcReaderCallback(
            onBluetoothAddressReceived = { readySignal ->
                Log.d("MainActivity", "Received ready signal: $readySignal")
                runOnUiThread {
                    disableNfcTransfer()
                    startBluetoothDiscovery(readySignal, token)
                }
            },
            onError = { error ->
                Log.e("MainActivity", "NFC error: $error")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "NFC failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
        
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        try {
            nfcAdapter!!.enableReaderMode(this, nfcCallback, flags, null)
            Log.d("MainActivity", "NFC reader mode enabled for token transfer")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enable NFC reader mode", e)
            cancelTokenTransfer(token)
        }
    }
    
    private fun startBluetoothDiscovery(readySignal: String, token: Token) {
        Log.d("MainActivity", "Starting Bluetooth discovery with signal: $readySignal")
        
        // Check Bluetooth permissions first
        if (!PermissionUtils.hasBluetoothPermissions(this)) {
            Log.e("MainActivity", "Missing Bluetooth permissions")
            PermissionUtils.requestBluetoothPermissions(this)
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check if Bluetooth is enabled
        if (!PermissionUtils.isBluetoothEnabled()) {
            Log.e("MainActivity", "Bluetooth is not enabled")
            PermissionUtils.openBluetoothSettings(this)
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        
        val bluetoothClient = BluetoothClient(
            context = this,
            onConnected = {
                Log.d("MainActivity", "Bluetooth connected")
                // UI already shows transferring state
            },
            onAddressReceived = { address ->
                Log.d("MainActivity", "Address received: $address")
                // Continue with transfer process
            },
            onTransferComplete = {
                Log.d("MainActivity", "Bluetooth transfer completed")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    // Remove token from wallet
                    viewModel.removeToken(token.id)
                    Toast.makeText(this@MainActivity, "Token sent successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "Bluetooth transfer error: $error")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    Toast.makeText(this@MainActivity, "Transfer failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
        
        lifecycleScope.launch {
            try {
                bluetoothClient.discoverAndConnect(readySignal, token)
            } catch (e: Exception) {
                Log.e("MainActivity", "Exception in Bluetooth discovery", e)
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    Toast.makeText(this@MainActivity, "Discovery failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
        // The activity is not recreated, so NFC transfer continues
        // Just log that we handled the configuration change
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
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionUtils.BLUETOOTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(this, "Bluetooth permissions granted. Please try again.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for token transfer", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}