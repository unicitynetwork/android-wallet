package org.unicitylabs.wallet.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.repository.WalletRepository
import org.unicitylabs.wallet.data.service.CryptoPriceService
import org.unicitylabs.wallet.model.CryptoCurrency

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WalletRepository.getInstance(application)
    private val prefs = application.getSharedPreferences("crypto_balances", android.content.Context.MODE_PRIVATE)
    private val priceService = CryptoPriceService(application)
    private var priceUpdateJob: Job? = null
    
    val tokens: StateFlow<List<Token>> = repository.tokens
    val isLoading: StateFlow<Boolean> = repository.isLoading

    /**
     * Gets all tokens for a specific coinId
     * Used for selecting which token to send when user picks an aggregated asset
     */
    fun getTokensByCoinId(coinId: String): List<Token> {
        return tokens.value.filter { it.coinId == coinId }
    }

    // Aggregated assets by coinId for Assets tab with price data
    val aggregatedAssets: StateFlow<List<org.unicitylabs.wallet.model.AggregatedAsset>> = repository.tokens
        .map { tokenList ->
            // Filter tokens that have coins
            val tokensWithCoins = tokenList.filter { it.coinId != null && it.amount != null }

            // Get token registry for decimals lookup
            val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(getApplication())

            // Group by coinId and aggregate
            tokensWithCoins
                .groupBy { it.coinId!! }
                .map { (coinId, tokensForCoin) ->
                    val symbol = tokensForCoin.first().symbol ?: "UNKNOWN"

                    // Get coin definition from registry to retrieve decimals
                    val coinDef = registry.getCoinDefinition(coinId)
                    val decimals = coinDef?.decimals ?: 8 // Default to 8 if not specified

                    // Use coin name from registry directly as CoinGecko API ID
                    // Registry names are already in correct format (lowercase with hyphens)
                    val apiId = coinDef?.name ?: symbol.lowercase()

                    // Get price from service (cached or default)
                    val priceData = priceService.getCachedPrice(apiId)
                        ?: org.unicitylabs.wallet.data.service.CryptoPriceService.CryptoPriceData(0.0, 0.0, 0.0)

                    org.unicitylabs.wallet.model.AggregatedAsset(
                        coinId = coinId,
                        symbol = symbol,
                        name = coinDef?.name ?: tokensForCoin.firstOrNull()?.name,
                        totalAmount = tokensForCoin.sumOf { it.amount ?: 0L },
                        decimals = decimals,
                        tokenCount = tokensForCoin.size,
                        iconUrl = coinDef?.getIconUrl() ?: tokensForCoin.first().iconUrl,
                        priceUsd = priceData.priceUsd,
                        priceEur = priceData.priceEur,
                        change24h = priceData.change24h
                    )
                }
                .sortedByDescending { it.getTotalFiatValue("USD") } // Sort by USD value descending
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _selectedToken = MutableStateFlow<Token?>(null)
    val selectedToken: StateFlow<Token?> = _selectedToken.asStateFlow()
    
    private val _mintResult = MutableStateFlow<Result<Token>?>(null)
    val mintResult: StateFlow<Result<Token>?> = _mintResult.asStateFlow()
    
    private val _allCryptocurrencies = MutableStateFlow<List<CryptoCurrency>>(emptyList())

    // Aggregated token balances from Tokens tab
    private val aggregatedTokenBalances: StateFlow<List<CryptoCurrency>> = tokens
        .map { tokenList ->
            // Group tokens by coinId and sum amounts
            val registry = org.unicitylabs.wallet.token.UnicityTokenRegistry.getInstance(application)
            tokenList
                .filter { it.coinId != null && it.amount != null }
                .groupBy { it.coinId!! }
                .mapNotNull { (coinId, tokensForCoin) ->
                    val coinDef = registry.getCoinDefinition(coinId)
                    if (coinDef != null) {
                        val totalBalance = tokensForCoin.sumOf { it.amount ?: 0L }.toDouble()
                        CryptoCurrency(
                            id = coinId,
                            symbol = coinDef.symbol ?: coinId.take(4),
                            name = coinDef.name,
                            balance = totalBalance,
                            priceUsd = 0.0, // TODO: Get real price
                            priceEur = 0.0,
                            change24h = 0.0,
                            iconResId = R.drawable.unicity_logo,
                            isDemo = false,
                            iconUrl = coinDef.icon
                        )
                    } else null
                }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    // Public cryptocurrencies flow that combines demo cryptos + aggregated tokens
    val cryptocurrencies: StateFlow<List<CryptoCurrency>> = kotlinx.coroutines.flow.combine(
        _allCryptocurrencies,
        aggregatedTokenBalances
    ) { demoCryptos, tokenCryptos ->
        (tokenCryptos + demoCryptos).filter { it.balance > 0.00000001 }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    init {
        Log.d("WalletViewModel", "ViewModel initialized")
        // Initialize with empty cryptocurrency list - only real Unicity tokens will be shown
        _allCryptocurrencies.value = emptyList()
        // Start price updates for bridged coins (BTC, ETH, SOL, etc.)
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

    fun deleteCrypto(cryptoId: String) {
        Log.d("WalletViewModel", "Deleting crypto: $cryptoId")

        _allCryptocurrencies.value = _allCryptocurrencies.value.filter { crypto ->
            crypto.id != cryptoId
        }

        saveCryptocurrencies() // Save after deletion
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
            
            // Create a test identity for recipient
            val recipientSecretString = "test-recipient-${System.currentTimeMillis()}"
            val recipientNonceBytes = ByteArray(32).apply {
                java.security.SecureRandom().nextBytes(this)
            }
            val recipientIdentity = mapOf(
                "secret" to recipientSecretString,
                "nonce" to recipientNonceBytes.toHexString()
            )
            val recipientIdentityJson = com.google.gson.Gson().toJson(recipientIdentity)
            
            // Get recipient address from identity
            val recipientSecret = recipientSecretString.toByteArray()
            val recipientNonce = hexStringToByteArray(recipientNonceBytes.toHexString())
            
            // Parse token data to get tokenId and tokenType
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            val tokenData = objectMapper.readTree(testToken.jsonData ?: "{}")
            val genesis = tokenData.get("genesis")
            val genesisData = genesis?.get("data")
            val tokenIdHex = genesisData?.get("tokenId")?.asText() ?: ""
            val tokenTypeHex = genesisData?.get("tokenType")?.asText() ?: ""
            
            // Create recipient's predicate to get correct address
            val recipientSigningService = org.unicitylabs.sdk.signing.SigningService.createFromMaskedSecret(recipientSecret, recipientNonce)
            val tokenType = org.unicitylabs.sdk.token.TokenType(hexStringToByteArray(tokenTypeHex))
            val tokenId = org.unicitylabs.sdk.token.TokenId(hexStringToByteArray(tokenIdHex))

            // Create salt for UnmaskedPredicate
            val salt = ByteArray(32)
            java.security.SecureRandom().nextBytes(salt)

            val recipientPredicate = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate.create(
                tokenId,
                tokenType,
                recipientSigningService,
                org.unicitylabs.sdk.hash.HashAlgorithm.SHA256,
                salt
            )
            
            val recipientAddress = recipientPredicate.getReference().toAddress().toString()
            
            // Get sender identity
            val senderIdentity = getIdentityManager().getCurrentIdentity()
                ?: return@withContext Result.failure(Exception("No wallet identity found"))
            
            // Create offline transfer package
            val offlinePackage = sdkService.createOfflineTransfer(
                testToken.jsonData ?: "{}",
                recipientAddress,
                null, // Use full amount
                senderIdentity.privateKey.toByteArray(),
                senderIdentity.nonce.toByteArray()
            )
            
            if (offlinePackage == null) {
                return@withContext Result.failure(Exception("Failed to create offline package"))
            }
            
            // Complete the transfer (simulating receiver side)
            val receivedToken = sdkService.completeOfflineTransfer(
                offlinePackage,
                recipientSecret,
                recipientNonce
            )
            
            if (receivedToken != null) {
                // Remove the token from sender's wallet
                removeToken(testToken.id)
                
                Result.success(
                    "Offline transfer test successful!\n\n" +
                    "Token '${testToken.name}' was transferred offline and received successfully.\n" +
                    "Token has been removed from your wallet to simulate the transfer."
                )
            } else {
                Result.failure(
                    Exception("Failed to complete offline transfer")
                )
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
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