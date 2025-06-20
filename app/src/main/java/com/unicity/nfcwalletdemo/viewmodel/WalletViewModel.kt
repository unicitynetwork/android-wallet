package com.unicity.nfcwalletdemo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.repository.WalletRepository
import com.unicity.nfcwalletdemo.model.CryptoCurrency
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    private val prefs = application.getSharedPreferences("crypto_balances", android.content.Context.MODE_PRIVATE)
    
    val tokens: StateFlow<List<Token>> = repository.tokens
    val isLoading: StateFlow<Boolean> = repository.isLoading
    
    private val _selectedToken = MutableStateFlow<Token?>(null)
    val selectedToken: StateFlow<Token?> = _selectedToken.asStateFlow()
    
    private val _mintResult = MutableStateFlow<Result<Token>?>(null)
    val mintResult: StateFlow<Result<Token>?> = _mintResult.asStateFlow()
    
    private val _cryptocurrencies = MutableStateFlow<List<CryptoCurrency>>(emptyList())
    val cryptocurrencies: StateFlow<List<CryptoCurrency>> = _cryptocurrencies.asStateFlow()
    
    init {
        Log.d("WalletViewModel", "ViewModel initialized - loading saved balances")
        loadSavedCryptocurrencies()
    }
    
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
        // Clear saved balances
        prefs.edit().clear().apply()
        // Clear existing cryptocurrencies to force regeneration
        _cryptocurrencies.value = emptyList()
        loadDemoCryptocurrencies()
    }
    
    private fun loadSavedCryptocurrencies() {
        // Check if we have saved balances
        if (prefs.contains("btc_balance")) {
            Log.d("WalletViewModel", "Loading saved crypto balances")
            _cryptocurrencies.value = listOf(
                CryptoCurrency(
                    id = "bitcoin",
                    symbol = "BTC",
                    name = "Bitcoin",
                    balance = prefs.getFloat("btc_balance", 1.0f).toDouble(),
                    priceUsd = 43251.57,
                    priceEur = 39326.88,
                    change24h = 3.7,
                    iconResId = R.drawable.ic_bitcoin
                ),
                CryptoCurrency(
                    id = "ethereum",
                    symbol = "ETH",
                    name = "Ethereum",
                    balance = prefs.getFloat("eth_balance", 5.0f).toDouble(),
                    priceUsd = 2754.32,
                    priceEur = 2503.92,
                    change24h = -2.3,
                    iconResId = R.drawable.ic_ethereum
                ),
                CryptoCurrency(
                    id = "tether",
                    symbol = "USDT",
                    name = "Tether USD",
                    balance = prefs.getFloat("usdt_balance", 1200.0f).toDouble(),
                    priceUsd = 1.0,
                    priceEur = 0.91,
                    change24h = 0.1,
                    iconResId = R.drawable.ic_tether
                ),
                CryptoCurrency(
                    id = "subway",
                    symbol = "SUB",
                    name = "Subway",
                    balance = prefs.getFloat("sub_balance", 200.0f).toDouble(),
                    priceUsd = 1.0,
                    priceEur = 0.91,
                    change24h = 0.0,
                    iconResId = R.drawable.subway
                )
            )
            Log.d("WalletViewModel", "Loaded BTC balance: ${_cryptocurrencies.value.find { it.symbol == "BTC" }?.balance}")
        } else {
            Log.d("WalletViewModel", "No saved balances, loading demo cryptocurrencies")
            loadDemoCryptocurrencies()
        }
    }
    
    private fun saveCryptocurrencies() {
        val cryptos = _cryptocurrencies.value
        prefs.edit().apply {
            cryptos.forEach { crypto ->
                when (crypto.symbol) {
                    "BTC" -> putFloat("btc_balance", crypto.balance.toFloat())
                    "ETH" -> putFloat("eth_balance", crypto.balance.toFloat())
                    "USDT" -> putFloat("usdt_balance", crypto.balance.toFloat())
                    "SUB" -> putFloat("sub_balance", crypto.balance.toFloat())
                }
            }
            apply()
        }
        Log.d("WalletViewModel", "Saved crypto balances")
    }
    
    fun loadDemoCryptocurrencies() {
        // Only generate new balances if the list is empty (first load or after reset)
        if (_cryptocurrencies.value.isNotEmpty()) {
            Log.d("WalletViewModel", "Cryptocurrencies already loaded, skipping regeneration")
            return
        }
        
        // Generate slightly randomized balances for more realistic appearance
        val btcBalance = (1.0 + kotlin.random.Random.nextDouble(-0.8, 2.0)).coerceAtLeast(0.1)
        val ethBalance = (5.0 + kotlin.random.Random.nextDouble(-3.0, 10.0)).coerceAtLeast(0.1)
        val usdtBalance = (1200.0 + kotlin.random.Random.nextDouble(-800.0, 3000.0)).coerceAtLeast(50.0)
        val subBalance = (200.0 + kotlin.random.Random.nextDouble(-150.0, 500.0)).coerceAtLeast(10.0)
        
        // Also slightly randomize price changes
        val btcChange = 3.7 + kotlin.random.Random.nextDouble(-2.0, 2.0)
        val ethChange = -2.3 + kotlin.random.Random.nextDouble(-2.0, 2.0)
        val usdtChange = 0.1 + kotlin.random.Random.nextDouble(-0.2, 0.2)
        
        _cryptocurrencies.value = listOf(
            CryptoCurrency(
                id = "bitcoin",
                symbol = "BTC",
                name = "Bitcoin",
                balance = kotlin.math.round(btcBalance * 100) / 100.0, // Round to 2 decimals
                priceUsd = 43251.57,
                priceEur = 39326.88,
                change24h = kotlin.math.round(btcChange * 10) / 10.0, // Round to 1 decimal
                iconResId = R.drawable.ic_bitcoin
            ),
            CryptoCurrency(
                id = "ethereum",
                symbol = "ETH", 
                name = "Ethereum",
                balance = kotlin.math.round(ethBalance * 100) / 100.0,
                priceUsd = 2754.32,
                priceEur = 2503.92,
                change24h = kotlin.math.round(ethChange * 10) / 10.0,
                iconResId = R.drawable.ic_ethereum
            ),
            CryptoCurrency(
                id = "tether",
                symbol = "USDT",
                name = "Tether USD",
                balance = kotlin.math.round(usdtBalance * 100) / 100.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = kotlin.math.round(usdtChange * 10) / 10.0,
                iconResId = R.drawable.ic_tether
            ),
            CryptoCurrency(
                id = "subway",
                symbol = "SUB",
                name = "Subway",
                balance = kotlin.math.round(subBalance * 100) / 100.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.0, // Keep Subway stable
                iconResId = R.drawable.subway
            )
        )
        
        saveCryptocurrencies() // Save after generating new balances
    }
    
    fun updateCryptoBalance(cryptoId: String, newBalance: Double) {
        val oldBalance = _cryptocurrencies.value.find { it.id == cryptoId }?.balance ?: 0.0
        Log.d("WalletViewModel", "Updating crypto balance for $cryptoId: $oldBalance -> $newBalance")
        
        _cryptocurrencies.value = _cryptocurrencies.value.map { crypto ->
            if (crypto.id == cryptoId) {
                crypto.copy(balance = newBalance)
            } else {
                crypto
            }
        }
        
        saveCryptocurrencies() // Save after balance update
    }
    
    fun addReceivedCrypto(cryptoSymbol: String, amount: Double): Boolean {
        Log.d("WalletViewModel", "addReceivedCrypto called with: $cryptoSymbol, amount = $amount")
        
        val currentCryptos = _cryptocurrencies.value.toMutableList()
        val existingCrypto = currentCryptos.find { it.symbol == cryptoSymbol }
        
        return if (existingCrypto != null) {
            // Add to existing balance
            val oldBalance = existingCrypto.balance
            val newBalance = oldBalance + amount
            Log.d("WalletViewModel", "Adding $amount $cryptoSymbol to existing balance: $oldBalance + $amount = $newBalance")
            
            val updatedCrypto = existingCrypto.copy(balance = newBalance)
            _cryptocurrencies.value = currentCryptos.map { crypto ->
                if (crypto.id == existingCrypto.id) updatedCrypto else crypto
            }
            saveCryptocurrencies() // Save after receiving crypto
            true
        } else {
            // Crypto doesn't exist in wallet, return false to indicate it couldn't be added
            Log.e("WalletViewModel", "Crypto $cryptoSymbol not found in wallet!")
            false
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