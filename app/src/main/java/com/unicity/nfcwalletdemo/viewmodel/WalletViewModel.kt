package com.unicity.nfcwalletdemo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.repository.WalletRepository
import com.unicity.nfcwalletdemo.data.service.CryptoPriceService
import com.unicity.nfcwalletdemo.model.CryptoCurrency
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    private val prefs = application.getSharedPreferences("crypto_balances", android.content.Context.MODE_PRIVATE)
    private val priceService = CryptoPriceService(application)
    private var priceUpdateJob: Job? = null
    
    val tokens: StateFlow<List<Token>> = repository.tokens
    val isLoading: StateFlow<Boolean> = repository.isLoading
    
    private val _selectedToken = MutableStateFlow<Token?>(null)
    val selectedToken: StateFlow<Token?> = _selectedToken.asStateFlow()
    
    private val _mintResult = MutableStateFlow<Result<Token>?>(null)
    val mintResult: StateFlow<Result<Token>?> = _mintResult.asStateFlow()
    
    private val _allCryptocurrencies = MutableStateFlow<List<CryptoCurrency>>(emptyList())
    
    // Public cryptocurrencies flow that filters out zero balances
    val cryptocurrencies: StateFlow<List<CryptoCurrency>> = _allCryptocurrencies
        .map { cryptos -> cryptos.filter { it.balance > 0.00000001 } } // Use epsilon for floating point comparison
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())
    
    init {
        Log.d("WalletViewModel", "ViewModel initialized - loading saved balances")
        loadSavedCryptocurrencies()
        startPriceUpdates()
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
    
    fun updateToken(token: Token) {
        viewModelScope.launch {
            repository.updateToken(token)
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
        _allCryptocurrencies.value = emptyList()
        loadDemoCryptocurrencies()
    }
    
    private fun loadSavedCryptocurrencies() {
        // Check if we have saved balances
        if (prefs.contains("btc_balance")) {
            Log.d("WalletViewModel", "Loading saved crypto balances")
            
            // Get cached prices or use defaults
            val efrancPrice = priceService.getCachedPrice("efranc") ?: CryptoPriceService.DEFAULT_PRICES["efranc"]!!
            val enairaPrice = priceService.getCachedPrice("enaira") ?: CryptoPriceService.DEFAULT_PRICES["enaira"]!!
            val btcPrice = priceService.getCachedPrice("bitcoin") ?: CryptoPriceService.DEFAULT_PRICES["bitcoin"]!!
            val ethPrice = priceService.getCachedPrice("ethereum") ?: CryptoPriceService.DEFAULT_PRICES["ethereum"]!!
            val usdtPrice = priceService.getCachedPrice("tether") ?: CryptoPriceService.DEFAULT_PRICES["tether"]!!
            
            _allCryptocurrencies.value = listOf(
                CryptoCurrency(
                    id = "efranc",
                    symbol = "eXAF",
                    name = "eFranc",
                    balance = prefs.getFloat("efranc_balance", 250000.0f).toDouble(),
                    priceUsd = efrancPrice.priceUsd,
                    priceEur = efrancPrice.priceEur,
                    change24h = efrancPrice.change24h,
                    iconResId = R.drawable.ic_franc
                ),
                CryptoCurrency(
                    id = "enaira",
                    symbol = "eNGN",
                    name = "eNaira",
                    balance = prefs.getFloat("enaira_balance", 500000.0f).toDouble(),
                    priceUsd = enairaPrice.priceUsd,
                    priceEur = enairaPrice.priceEur,
                    change24h = enairaPrice.change24h,
                    iconResId = R.drawable.ic_naira
                ),
                CryptoCurrency(
                    id = "bitcoin",
                    symbol = "BTC",
                    name = "Bitcoin",
                    balance = prefs.getFloat("btc_balance", 1.0f).toDouble(),
                    priceUsd = btcPrice.priceUsd,
                    priceEur = btcPrice.priceEur,
                    change24h = btcPrice.change24h,
                    iconResId = R.drawable.ic_bitcoin
                ),
                CryptoCurrency(
                    id = "ethereum",
                    symbol = "ETH",
                    name = "Ethereum",
                    balance = prefs.getFloat("eth_balance", 5.0f).toDouble(),
                    priceUsd = ethPrice.priceUsd,
                    priceEur = ethPrice.priceEur,
                    change24h = ethPrice.change24h,
                    iconResId = R.drawable.ic_ethereum
                ),
                CryptoCurrency(
                    id = "tether",
                    symbol = "USDT",
                    name = "Tether USD",
                    balance = prefs.getFloat("usdt_balance", 1200.0f).toDouble(),
                    priceUsd = usdtPrice.priceUsd,
                    priceEur = usdtPrice.priceEur,
                    change24h = usdtPrice.change24h,
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
                ),
                CryptoCurrency(
                    id = "usd-coin",
                    symbol = "USDC",
                    name = "USD Coin",
                    balance = prefs.getFloat("usdc_balance", 1000.0f).toDouble(),
                    priceUsd = 1.0,
                    priceEur = 0.91,
                    change24h = 0.0,
                    iconResId = R.drawable.usdc
                ),
                CryptoCurrency(
                    id = "solana",
                    symbol = "SOL",
                    name = "Solana",
                    balance = prefs.getFloat("sol_balance", 10.0f).toDouble(),
                    priceUsd = 100.0, // Will be updated from API
                    priceEur = 91.0,  // Will be updated from API
                    change24h = 0.0,
                    iconResId = R.drawable.sol
                )
            )
            Log.d("WalletViewModel", "Loaded BTC balance: ${_allCryptocurrencies.value.find { it.symbol == "BTC" }?.balance}")
            
            // Trigger price update in background
            viewModelScope.launch {
                updateCryptoPrices()
            }
        } else {
            Log.d("WalletViewModel", "No saved balances, loading demo cryptocurrencies")
            loadDemoCryptocurrencies()
        }
    }
    
    private fun saveCryptocurrencies() {
        val cryptos = _allCryptocurrencies.value
        prefs.edit().apply {
            cryptos.forEach { crypto ->
                when (crypto.symbol) {
                    "eXAF" -> putFloat("efranc_balance", crypto.balance.toFloat())
                    "eNGN" -> putFloat("enaira_balance", crypto.balance.toFloat())
                    "BTC" -> putFloat("btc_balance", crypto.balance.toFloat())
                    "ETH" -> putFloat("eth_balance", crypto.balance.toFloat())
                    "USDT" -> putFloat("usdt_balance", crypto.balance.toFloat())
                    "SUB" -> putFloat("sub_balance", crypto.balance.toFloat())
                    "USDC" -> putFloat("usdc_balance", crypto.balance.toFloat())
                    "SOL" -> putFloat("sol_balance", crypto.balance.toFloat())
                }
            }
            apply()
        }
        Log.d("WalletViewModel", "Saved crypto balances")
    }
    
    fun loadDemoCryptocurrencies() {
        // Only generate new balances if the list is empty (first load or after reset)
        if (_allCryptocurrencies.value.isNotEmpty()) {
            Log.d("WalletViewModel", "Cryptocurrencies already loaded, skipping regeneration")
            return
        }
        
        // Get cached prices or use defaults
        val efrancPrice = priceService.getCachedPrice("efranc") ?: CryptoPriceService.DEFAULT_PRICES["efranc"]!!
        val enairaPrice = priceService.getCachedPrice("enaira") ?: CryptoPriceService.DEFAULT_PRICES["enaira"]!!
        val btcPrice = priceService.getCachedPrice("bitcoin") ?: CryptoPriceService.DEFAULT_PRICES["bitcoin"]!!
        val ethPrice = priceService.getCachedPrice("ethereum") ?: CryptoPriceService.DEFAULT_PRICES["ethereum"]!!
        val usdtPrice = priceService.getCachedPrice("tether") ?: CryptoPriceService.DEFAULT_PRICES["tether"]!!
        
        // Generate slightly randomized balances for more realistic appearance
        val efrancBalance = (250000.0 + kotlin.random.Random.nextDouble(-100000.0, 500000.0)).coerceAtLeast(25000.0)
        val enairaBalance = (500000.0 + kotlin.random.Random.nextDouble(-200000.0, 1000000.0)).coerceAtLeast(50000.0)
        val btcBalance = (1.0 + kotlin.random.Random.nextDouble(-0.8, 2.0)).coerceAtLeast(0.1)
        val ethBalance = (5.0 + kotlin.random.Random.nextDouble(-3.0, 10.0)).coerceAtLeast(0.1)
        val usdtBalance = (1200.0 + kotlin.random.Random.nextDouble(-800.0, 3000.0)).coerceAtLeast(50.0)
        val subBalance = (200.0 + kotlin.random.Random.nextDouble(-150.0, 500.0)).coerceAtLeast(10.0)
        
        _allCryptocurrencies.value = listOf(
            CryptoCurrency(
                id = "efranc",
                symbol = "eXAF",
                name = "eFranc",
                balance = kotlin.math.round(efrancBalance), // Round to whole numbers for Franc
                priceUsd = efrancPrice.priceUsd,
                priceEur = efrancPrice.priceEur,
                change24h = efrancPrice.change24h,
                iconResId = R.drawable.ic_franc
            ),
            CryptoCurrency(
                id = "enaira",
                symbol = "eNGN",
                name = "eNaira",
                balance = kotlin.math.round(enairaBalance), // Round to whole numbers for Naira
                priceUsd = enairaPrice.priceUsd,
                priceEur = enairaPrice.priceEur,
                change24h = enairaPrice.change24h,
                iconResId = R.drawable.ic_naira
            ),
            CryptoCurrency(
                id = "bitcoin",
                symbol = "BTC",
                name = "Bitcoin",
                balance = kotlin.math.round(btcBalance * 100) / 100.0, // Round to 2 decimals
                priceUsd = btcPrice.priceUsd,
                priceEur = btcPrice.priceEur,
                change24h = btcPrice.change24h,
                iconResId = R.drawable.ic_bitcoin
            ),
            CryptoCurrency(
                id = "ethereum",
                symbol = "ETH", 
                name = "Ethereum",
                balance = kotlin.math.round(ethBalance * 100) / 100.0,
                priceUsd = ethPrice.priceUsd,
                priceEur = ethPrice.priceEur,
                change24h = ethPrice.change24h,
                iconResId = R.drawable.ic_ethereum
            ),
            CryptoCurrency(
                id = "tether",
                symbol = "USDT",
                name = "Tether USD",
                balance = kotlin.math.round(usdtBalance * 100) / 100.0,
                priceUsd = usdtPrice.priceUsd,
                priceEur = usdtPrice.priceEur,
                change24h = usdtPrice.change24h,
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
            ),
            CryptoCurrency(
                id = "usd-coin",
                symbol = "USDC",
                name = "USD Coin",
                balance = 1000.0,
                priceUsd = 1.0,
                priceEur = 0.91,
                change24h = 0.0,
                iconResId = R.drawable.usdc
            ),
            CryptoCurrency(
                id = "solana",
                symbol = "SOL",
                name = "Solana",
                balance = 10.0,
                priceUsd = 100.0, // Will be updated from API
                priceEur = 91.0,  // Will be updated from API
                change24h = 0.0,
                iconResId = R.drawable.sol
            )
        )
        
        saveCryptocurrencies() // Save after generating new balances
        
        // Trigger price update in background
        viewModelScope.launch {
            updateCryptoPrices()
        }
    }
    
    fun updateCryptoBalance(cryptoId: String, newBalance: Double) {
        val oldBalance = _allCryptocurrencies.value.find { it.id == cryptoId }?.balance ?: 0.0
        Log.d("WalletViewModel", "Updating crypto balance for $cryptoId: $oldBalance -> $newBalance")
        
        _allCryptocurrencies.value = _allCryptocurrencies.value.map { crypto ->
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
        
        val currentCryptos = _allCryptocurrencies.value.toMutableList()
        val existingCrypto = currentCryptos.find { it.symbol == cryptoSymbol }
        
        return if (existingCrypto != null) {
            // Add to existing balance
            val oldBalance = existingCrypto.balance
            val newBalance = oldBalance + amount
            Log.d("WalletViewModel", "Adding $amount $cryptoSymbol to existing balance: $oldBalance + $amount = $newBalance")
            
            val updatedCrypto = existingCrypto.copy(balance = newBalance)
            _allCryptocurrencies.value = currentCryptos.map { crypto ->
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
    
    fun getIdentityManager() = repository.getIdentityManager()
    
    suspend fun testOfflineTransfer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Find a Unicity token to test with
            val testToken = tokens.value.firstOrNull { it.type == "Unicity Token" }
                ?: return@withContext Result.failure(Exception("No Unicity tokens available for testing"))
            
            // Generate a test recipient identity
            val sdkService = getSdkService()
            var recipientIdentity: String? = null
            
            sdkService.generateIdentity { result ->
                result.fold(
                    onSuccess = { identity -> recipientIdentity = identity },
                    onFailure = { error -> throw error }
                )
            }
            
            // Wait for identity generation
            delay(1000)
            
            if (recipientIdentity == null) {
                return@withContext Result.failure(Exception("Failed to generate recipient identity"))
            }
            
            // Get recipient address from identity
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            val identityData = objectMapper.readTree(recipientIdentity)
            val recipientSecret = identityData.get("secret").asText().toByteArray()
            val recipientNonce = identityData.get("nonce").asText().toByteArray()
            
            // Parse token data to get tokenId and tokenType
            val tokenData = objectMapper.readTree(testToken.jsonData ?: "{}")
            val genesis = tokenData.get("genesis")
            val genesisData = genesis.get("data")
            val tokenIdHex = genesisData.get("tokenId").asText()
            val tokenTypeHex = genesisData.get("tokenType").asText()
            
            // Create recipient's predicate to get correct address
            val recipientSigningService = com.unicity.sdk.shared.signing.SigningService.createFromSecret(recipientSecret, recipientNonce).get()
            val tokenId = com.unicity.sdk.token.TokenId.create(hexStringToByteArray(tokenIdHex))
            val tokenType = com.unicity.sdk.token.TokenType.create(hexStringToByteArray(tokenTypeHex))
            
            val recipientPredicate = com.unicity.sdk.predicate.MaskedPredicate.create(
                tokenId,
                tokenType,
                recipientSigningService,
                com.unicity.sdk.shared.hash.HashAlgorithm.SHA256,
                recipientNonce
            ).get()
            
            val recipientAddress = com.unicity.sdk.address.DirectAddress.create(recipientPredicate.reference).get().toString()
            
            // Get sender identity
            val senderIdentity = getIdentityManager().getCurrentIdentity()
                ?: return@withContext Result.failure(Exception("No wallet identity found"))
            
            // Create offline transfer package
            val transferResult = sdkService.createOfflineTransferPackage(
                senderIdentity.toJson(),
                recipientAddress,
                testToken.jsonData ?: "{}"
            )
            
            var offlinePackage: String? = null
            transferResult.fold(
                onSuccess = { pkg -> offlinePackage = pkg },
                onFailure = { error -> return@withContext Result.failure(error) }
            )
            
            if (offlinePackage == null) {
                return@withContext Result.failure(Exception("Failed to create offline package"))
            }
            
            // Complete the transfer (simulating receiver side)
            val completeResult = sdkService.completeOfflineTransfer(
                recipientIdentity!!,
                offlinePackage!!
            )
            
            completeResult.fold(
                onSuccess = { receivedToken ->
                    // Remove the token from sender's wallet
                    removeToken(testToken.id)
                    
                    return@withContext Result.success(
                        "Offline transfer test successful!\n\n" +
                        "Token '${testToken.name}' was transferred offline and received successfully.\n" +
                        "Token has been removed from your wallet to simulate the transfer."
                    )
                },
                onFailure = { error ->
                    return@withContext Result.failure(
                        Exception("Failed to complete offline transfer: ${error.message}")
                    )
                }
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    override fun onCleared() {
        super.onCleared()
        priceUpdateJob?.cancel()
        repository.destroy()
    }
    
    private fun startPriceUpdates() {
        priceUpdateJob?.cancel()
        priceUpdateJob = viewModelScope.launch {
            while (true) {
                updateCryptoPrices()
                delay(60000) // Update every minute
            }
        }
    }
    
    private suspend fun updateCryptoPrices() {
        try {
            val prices = priceService.fetchPrices()
            val currentCryptos = _allCryptocurrencies.value
            
            if (currentCryptos.isNotEmpty()) {
                _allCryptocurrencies.value = currentCryptos.map { crypto ->
                    val priceData = prices[crypto.id]
                    if (priceData != null) {
                        crypto.copy(
                            priceUsd = priceData.priceUsd,
                            priceEur = priceData.priceEur,
                            change24h = priceData.change24h
                        )
                    } else {
                        crypto
                    }
                }
                Log.d("WalletViewModel", "Updated crypto prices from API/cache")
            }
        } catch (e: Exception) {
            Log.e("WalletViewModel", "Error updating prices: ${e.message}")
        }
    }
    
    fun refreshPrices() {
        viewModelScope.launch {
            try {
                val prices = priceService.fetchPrices(forceRefresh = true)
                val currentCryptos = _allCryptocurrencies.value
                
                if (currentCryptos.isNotEmpty()) {
                    _allCryptocurrencies.value = currentCryptos.map { crypto ->
                        val priceData = prices[crypto.id]
                        if (priceData != null) {
                            crypto.copy(
                                priceUsd = priceData.priceUsd,
                                priceEur = priceData.priceEur,
                                change24h = priceData.change24h
                            )
                        } else {
                            crypto
                        }
                    }
                    Log.d("WalletViewModel", "Force refreshed crypto prices")
                }
            } catch (e: Exception) {
                Log.e("WalletViewModel", "Error refreshing prices: ${e.message}")
            }
        }
    }
}