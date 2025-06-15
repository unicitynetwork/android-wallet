package com.unicity.nfcwalletdemo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.repository.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SendState {
    READY_TO_SEND,
    CONNECTING,
    WAITING_FOR_ADDRESS,
    FINALIZING,
    SUCCESS,
    ERROR
}

class SendViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    
    private val _state = MutableStateFlow(SendState.READY_TO_SEND)
    val state: StateFlow<SendState> = _state.asStateFlow()
    
    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    private var tokenToSend: Token? = null
    
    fun setToken(token: Token) {
        tokenToSend = token
        updateStatus(SendState.READY_TO_SEND, "Ready to Send ${token.name}. Tap the recipient's phone.")
    }
    
    fun onNfcDetected(deviceName: String) {
        updateStatus(SendState.CONNECTING, "Connecting to $deviceName...")
    }
    
    fun onBluetoothConnected() {
        updateStatus(SendState.WAITING_FOR_ADDRESS, "Waiting for receiver address...")
    }
    
    fun onAddressReceived(address: String) {
        updateStatus(SendState.FINALIZING, "Finalizing transfer...")
    }
    
    fun onTransferComplete() {
        viewModelScope.launch {
            tokenToSend?.let { token ->
                repository.removeToken(token.id)
                updateStatus(SendState.SUCCESS, "Success! ${token.name} sent.")
            }
        }
    }
    
    fun onError(message: String) {
        updateStatus(SendState.ERROR, message)
    }
    
    private fun updateStatus(state: SendState, message: String) {
        _state.value = state
        _statusMessage.value = message
    }
    
    fun reset() {
        _state.value = SendState.READY_TO_SEND
        tokenToSend = null
    }
}