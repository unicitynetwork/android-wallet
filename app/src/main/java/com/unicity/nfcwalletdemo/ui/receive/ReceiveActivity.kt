package com.unicity.nfcwalletdemo.ui.receive

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivityReceiveBinding
import com.unicity.nfcwalletdemo.viewmodel.ReceiveState
import com.unicity.nfcwalletdemo.viewmodel.ReceiveViewModel
import com.unicity.nfcwalletdemo.bluetooth.BluetoothServer
import kotlinx.coroutines.launch

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
        startBluetoothServer()
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
                // Navigate back to wallet after delay
                binding.root.postDelayed({
                    finish()
                }, 2000)
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
        bluetoothServer = BluetoothServer(
            onConnectionRequest = { deviceName ->
                viewModel.onConnectionRequest(deviceName)
            },
            onGenerateAddress = {
                viewModel.generateAddress()
            },
            onTokenReceived = { token ->
                viewModel.onTokenReceived(token)
            },
            onError = { error ->
                viewModel.onError(error)
            }
        )
        
        lifecycleScope.launch {
            bluetoothServer?.start()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothServer?.stop()
    }
}