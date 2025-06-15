package com.unicity.nfcwalletdemo.ui.send

import android.nfc.NfcAdapter
import android.os.Bundle
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
import com.unicity.nfcwalletdemo.bluetooth.BluetoothClient
import com.unicity.nfcwalletdemo.data.model.Token
import kotlinx.coroutines.launch

class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private val sendViewModel: SendViewModel by viewModels()
    private val walletViewModel: WalletViewModel by viewModels()
    
    private var nfcAdapter: NfcAdapter? = null
    private var bluetoothClient: BluetoothClient? = null
    private var currentToken: Token? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNfc()
        setupViews()
        observeViewModels()
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
    
    private fun observeViewModels() {
        // Set the token to send from wallet view model
        lifecycleScope.launch {
            walletViewModel.selectedToken.collect { token ->
                token?.let {
                    currentToken = it
                    sendViewModel.setToken(it)
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
        enableNfcReaderMode()
    }
    
    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }
    
    private fun enableNfcReaderMode() {
        nfcAdapter?.let { adapter ->
            val nfcReaderCallback = com.unicity.nfcwalletdemo.nfc.NfcReaderCallback(
                onBluetoothAddressReceived = { bluetoothAddress ->
                    runOnUiThread {
                        sendViewModel.onNfcDetected("Device")
                        startBluetoothTransfer(bluetoothAddress)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        sendViewModel.onError(error)
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            
            adapter.enableReaderMode(this, nfcReaderCallback, flags, null)
        }
    }
    
    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }
    
    private fun startBluetoothTransfer(bluetoothAddress: String) {
        val token = currentToken ?: return
        
        bluetoothClient = BluetoothClient(
            onConnected = {
                sendViewModel.onBluetoothConnected()
            },
            onAddressReceived = { address ->
                sendViewModel.onAddressReceived(address)
            },
            onTransferComplete = {
                sendViewModel.onTransferComplete()
            },
            onError = { error ->
                sendViewModel.onError(error)
            }
        )
        
        lifecycleScope.launch {
            bluetoothClient?.connect(bluetoothAddress, token)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothClient?.disconnect()
    }
}