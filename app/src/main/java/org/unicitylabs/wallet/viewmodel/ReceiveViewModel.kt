package org.unicitylabs.wallet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.repository.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ReceiveState {
    READY_TO_RECEIVE,
    CONNECTION_REQUEST,
    GENERATING_ADDRESS,
    RECEIVING_TOKEN,
    SUCCESS,
    ERROR
}

class ReceiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    
    private val _state = MutableStateFlow(ReceiveState.READY_TO_RECEIVE)
    val state: StateFlow<ReceiveState> = _state.asStateFlow()
    
    private val _statusMessage = MutableStateFlow("Ready to receive. Ask the sender to tap your phone.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    fun onConnectionRequest(deviceName: String) {
        updateStatus(ReceiveState.CONNECTION_REQUEST, "Connection request from $deviceName...")
    }
    
    fun generateAddress(): String {
        updateStatus(ReceiveState.GENERATING_ADDRESS, "Generating Unicity address...")
        return repository.generateNewAddress()
    }
    
    fun onReceivingToken() {
        updateStatus(ReceiveState.RECEIVING_TOKEN, "Receiving token...")
    }
    
    fun onTokenReceived(token: Token) {
        viewModelScope.launch {
            repository.addToken(token)
            updateStatus(ReceiveState.SUCCESS, "Success! ${token.name} received.")
        }
    }
    
    fun onError(message: String) {
        updateStatus(ReceiveState.ERROR, message)
    }
    
    private fun updateStatus(state: ReceiveState, message: String) {
        _state.value = state
        _statusMessage.value = message
    }
    
    fun onNfcDetected() {
        updateStatus(ReceiveState.RECEIVING_TOKEN, "NFC detected. Ready to receive...")
    }
    
    fun onReceivingProgress(currentBytes: Int, totalBytes: Int) {
        if (totalBytes > 0) {
            val progressPercent = (currentBytes * 100) / totalBytes
            val progressText = "Receiving ${formatBytes(currentBytes)} / ${formatBytes(totalBytes)} ($progressPercent%)"
            _statusMessage.value = progressText
        }
    }
    
    private fun formatBytes(bytes: Int): String {
        return when {
            bytes >= 1024 -> "${bytes / 1024}KB"
            else -> "${bytes}B"
        }
    }
    
    fun setReadyToReceive() {
        updateStatus(ReceiveState.READY_TO_RECEIVE, "Ready to receive. Ask the sender to tap your phone.")
    }
    
    fun reset() {
        _state.value = ReceiveState.READY_TO_RECEIVE
        _statusMessage.value = "Ready to receive. Ask the sender to tap your phone."
    }
}