package com.unicity.nfcwalletdemo.ui.wallet

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicity.nfcwalletdemo.databinding.ActivityMainBinding
import com.unicity.nfcwalletdemo.ui.receive.ReceiveActivity
import com.unicity.nfcwalletdemo.ui.send.SendActivity
import com.unicity.nfcwalletdemo.viewmodel.WalletViewModel
import com.unicity.nfcwalletdemo.utils.PermissionUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: WalletViewModel by viewModels()
    private lateinit var tokenAdapter: TokenAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupButtons()
        observeViewModel()
        checkPermissions()
    }
    
    private fun setupRecyclerView() {
        tokenAdapter = TokenAdapter { token ->
            // Token clicked - start send flow
            checkNfcAndBluetooth {
                viewModel.selectToken(token)
                startActivity(Intent(this, SendActivity::class.java))
            }
        }
        
        binding.rvTokens.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tokenAdapter
        }
    }
    
    private fun setupButtons() {
        binding.btnSend.setOnClickListener {
            checkNfcAndBluetooth {
                // If no token is selected, show token selection
                // For now, we'll use the first token if available
                lifecycleScope.launch {
                    viewModel.tokens.value.firstOrNull()?.let { token ->
                        viewModel.selectToken(token)
                        startActivity(Intent(this@MainActivity, SendActivity::class.java))
                    }
                }
            }
        }
        
        // Receive button removed - receiving is automatic when NFC is detected
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
    
    private fun checkPermissions() {
        if (!PermissionUtils.hasBluetoothPermissions(this)) {
            PermissionUtils.requestBluetoothPermissions(this)
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
                    Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Bluetooth permissions are required for transferring tokens. Please grant permissions in settings.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun checkNfcAndBluetooth(onSuccess: () -> Unit) {
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
            !PermissionUtils.isBluetoothEnabled() -> {
                AlertDialog.Builder(this)
                    .setTitle("Bluetooth Required")
                    .setMessage("Please enable Bluetooth to transfer tokens")
                    .setPositiveButton("Enable") { _, _ ->
                        PermissionUtils.openBluetoothSettings(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            !PermissionUtils.hasBluetoothPermissions(this) -> {
                PermissionUtils.requestBluetoothPermissions(this)
            }
            else -> {
                onSuccess()
            }
        }
    }
}