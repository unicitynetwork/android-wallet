package com.unicity.nfcwalletdemo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.repository.WalletRepository
import com.unicity.nfcwalletdemo.model.CryptoCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    
    val tokens: StateFlow<List<Token>> = repository.tokens
    val isLoading: StateFlow<Boolean> = repository.isLoading
    
    private val _selectedToken = MutableStateFlow<Token?>(null)
    val selectedToken: StateFlow<Token?> = _selectedToken.asStateFlow()
    
    private val _mintResult = MutableStateFlow<Result<Token>?>(null)
    val mintResult: StateFlow<Result<Token>?> = _mintResult.asStateFlow()
    
    private val _cryptocurrencies = MutableStateFlow<List<CryptoCurrency>>(emptyList())
    val cryptocurrencies: StateFlow<List<CryptoCurrency>> = _cryptocurrencies.asStateFlow()
    
    fun selectToken(token: Token) {
        _selectedToken.value = token
    }
    
    fun removeToken(tokenId: String) {
        viewModelScope.launch {
            repository.removeToken(tokenId)
        }
    }
    
    fun addToken(token: Token) {
        viewModelScope.launch {
            repository.addToken(token)
        }
    }
    
    suspend fun refreshTokens() {
        repository.refreshTokens()
    }
    
    fun clearWallet() {
        repository.clearWallet()
        loadDemoCryptocurrencies()
    }
    
    fun loadDemoCryptocurrencies() {
        _cryptocurrencies.value = listOf(
            CryptoCurrency(
                id = "bitcoin",
                symbol = "BTC",
                name = "Bitcoin",
                balance = 1.5,
                priceUsd = 43251.57,
                priceEur = 39326.88,
                change24h = 3.7,
                iconResId = R.drawable.ic_bitcoin
            ),
            CryptoCurrency(
                id = "ethereum",
                symbol = "ETH", 
                name = "Ethereum",
                balance = 5.25,
                priceUsd = 2754.32,
                priceEur = 2503.92,
                change24h = -2.3,
                iconResId = R.drawable.ic_ethereum
            ),
            CryptoCurrency(
                id = "tether",
                symbol = "USDT",
                name = "Tether USD",
                balance = 1200.44,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.1,
                iconResId = R.drawable.ic_tether
            ),
            CryptoCurrency(
                id = "subway",
                symbol = "SUB",
                name = "Subway",
                balance = 200.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.0,
                iconResId = R.drawable.subway
            )
        )
    }
    
    fun updateCryptoBalance(cryptoId: String, newBalance: Double) {
        _cryptocurrencies.value = _cryptocurrencies.value.map { crypto ->
            if (crypto.id == cryptoId) {
                crypto.copy(balance = newBalance)
            } else {
                crypto
            }
        }
    }
    
    fun mintNewToken(name: String, data: String, amount: Long = 100) {
        viewModelScope.launch {
            val result = repository.mintNewToken(name, data, amount)
            _mintResult.value = result
        }
    }
    
    fun clearMintResult() {
        _mintResult.value = null
    }
    
    fun getSdkService() = repository.getSdkService()
    
    override fun onCleared() {
        super.onCleared()
        repository.destroy()
    }
}