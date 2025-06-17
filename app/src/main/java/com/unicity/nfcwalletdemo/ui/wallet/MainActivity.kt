package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Intent
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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
import com.unicity.nfcwalletdemo.nfc.DirectNfcClient
import com.unicity.nfcwalletdemo.ble.BleClient
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import kotlinx.coroutines.delay
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
                    startBleConnection(readySignal, token)
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
    
    private fun startBleConnection(readySignal: String, token: Token) {
        Log.d("MainActivity", "Starting hybrid transfer (BLE → NFC fallback)")
        
        // Check Bluetooth permissions first
        if (!PermissionUtils.hasBluetoothPermissions(this)) {
            Log.e("MainActivity", "Missing Bluetooth permissions, falling back to direct NFC")
            startDirectNfcTransfer(token)
            return
        }
        
        // Check if Bluetooth is enabled
        if (!PermissionUtils.isBluetoothEnabled()) {
            Log.e("MainActivity", "Bluetooth disabled, falling back to direct NFC")
            startDirectNfcTransfer(token)
            return
        }
        
        // Try BLE first with timeout
        var bleCompleted = false
        
        val bleClient = BleClient(
            context = this,
            onConnected = {
                Log.d("MainActivity", "✅ BLE connected")
                bleCompleted = true
            },
            onAddressReceived = { address ->
                Log.d("MainActivity", "BLE address received: $address")
            },
            onTransferComplete = {
                Log.d("MainActivity", "✅ BLE transfer completed")
                bleCompleted = true
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    viewModel.removeToken(token.id)
                    Toast.makeText(this@MainActivity, "Token sent via BLE!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "BLE error: $error")
                if (!bleCompleted) {
                    bleCompleted = true
                    runOnUiThread {
                        Log.d("MainActivity", "Falling back to direct NFC transfer")
                        Toast.makeText(this@MainActivity, "BLE failed, trying direct NFC...", Toast.LENGTH_SHORT).show()
                        startDirectNfcTransfer(token)
                    }
                }
            }
        )
        
        // Set timeout for BLE - if no connection in 3 seconds, fall back to NFC
        lifecycleScope.launch {
            delay(3000) // 3 second timeout
            if (!bleCompleted) {
                bleCompleted = true
                Log.d("MainActivity", "BLE timeout, falling back to direct NFC")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "BLE timeout, using direct NFC...", Toast.LENGTH_SHORT).show()
                    startDirectNfcTransfer(token)
                }
            }
        }
        
        lifecycleScope.launch {
            try {
                bleClient.scanAndConnect(readySignal, token)
            } catch (e: Exception) {
                if (!bleCompleted) {
                    bleCompleted = true
                    Log.e("MainActivity", "BLE exception, falling back to NFC: ${e.message}")
                    runOnUiThread {
                        startDirectNfcTransfer(token)
                    }
                }
            }
        }
    }
    
    private fun startDirectNfcTransfer(token: Token) {
        Log.d("MainActivity", "Starting direct NFC transfer for token: ${token.name}")
        Toast.makeText(this, "Keep phones together for direct NFC transfer...", Toast.LENGTH_LONG).show()
        
        val directNfcClient = DirectNfcClient(
            onTransferComplete = {
                Log.d("MainActivity", "✅ Direct NFC transfer completed")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    viewModel.removeToken(token.id)
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Token sent via direct NFC!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e("MainActivity", "Direct NFC transfer error: $error")
                runOnUiThread {
                    tokenAdapter.setTransferring(token, false)
                    currentTransferringToken = null
                    disableNfcTransfer()
                    Toast.makeText(this@MainActivity, "Direct NFC failed: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onProgress = { current, total ->
                Log.d("MainActivity", "Direct NFC progress: $current/$total chunks")
                runOnUiThread {
                    // Update transfer status to show progress
                    val progressMsg = if (total > 1) "Sending chunk $current/$total..." else "Sending..."
                    // We could update the UI here to show progress
                }
            }
        )
        
        directNfcClient.setTokenToSend(token)
        
        // Enable NFC reader mode for direct transfer
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        try {
            nfcAdapter!!.enableReaderMode(this, directNfcClient, flags, null)
            Log.d("MainActivity", "NFC reader mode enabled for direct transfer")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to enable NFC reader mode for direct transfer", e)
            tokenAdapter.setTransferring(token, false)
            currentTransferringToken = null
            Toast.makeText(this, "Failed to enable NFC: ${e.message}", Toast.LENGTH_SHORT).show()
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
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(com.unicity.nfcwalletdemo.R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.unicity.nfcwalletdemo.R.id.action_reset_wallet -> {
                showResetWalletDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showResetWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Wallet")
            .setMessage("This will clear all tokens and create demo tokens with different sizes (50KB, 100KB, 250KB, 500KB). Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                viewModel.clearWalletAndCreateDemo()
                Toast.makeText(this, "Wallet reset with demo tokens", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}