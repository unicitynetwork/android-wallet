package com.unicity.nfcwalletdemo.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.Wallet
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import com.unicity.nfcwalletdemo.sdk.UnicityIdentity
import com.unicity.nfcwalletdemo.sdk.UnicityTokenData
import com.unicity.nfcwalletdemo.sdk.UnicityMintResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

class WalletRepository(context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val unicitySdkService = UnicitySdkService(context)
    
    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()
    
    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    companion object {
        private const val TAG = "WalletRepository"
    }
    
    init {
        loadWallet()
    }
    
    private fun loadWallet() {
        val walletJson = sharedPreferences.getString("wallet", null)
        if (walletJson != null) {
            val wallet = gson.fromJson(walletJson, Wallet::class.java)
            _wallet.value = wallet
            _tokens.value = wallet.tokens
        } else {
            // Create new wallet if none exists
            createNewWallet()
        }
    }
    
    private fun createNewWallet() {
        val baseTime = System.currentTimeMillis()
        val newWallet = Wallet(
            id = UUID.randomUUID().toString(),
            name = "My Wallet",
            address = "unicity_wallet_${baseTime}",
            tokens = emptyList() // Start with empty tokens, will be populated async
        )
        saveWallet(newWallet)
    }
    
    private fun createDemoTokens(baseTime: Long): List<Token> {
        val tokenSizes = listOf(
            2 * 1024,  // 2KB
            4 * 1024,  // 4KB
            8 * 1024,  // 8KB
            16 * 1024, // 16KB
            32 * 1024, // 32KB
            64 * 1024  // 64KB
        )
        
        return tokenSizes.mapIndexed { index, sizeBytes ->
            val sizeKB = sizeBytes / 1024
            Token(
                name = "${sizeKB}KB Token",
                type = "Demo Token",
                timestamp = baseTime + index,
                unicityAddress = "unicity_${sizeKB}kb_${UUID.randomUUID().toString().take(8)}",
                jsonData = generateTokenData(sizeBytes),
                sizeBytes = sizeBytes
            )
        }
    }
    
    private fun generateTokenData(targetSizeBytes: Int): String {
        // Generate JSON data that approximates the target size
        val baseJson = """
        {
            "version": "1.0",
            "type": "demo_token",
            "metadata": {
                "description": "Demo token for testing transfer speeds",
                "features": ["transferable", "divisible", "mintable"]
            },
            "data": "
        """.trimIndent()
        
        val suffix = """
        "
        }
        """.trimIndent()
        
        // Calculate how much padding we need
        val baseSize = baseJson.length + suffix.length
        val paddingSize = maxOf(0, targetSizeBytes - baseSize)
        
        // Generate padding data (base64-like string)
        val padding = StringBuilder()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        repeat(paddingSize) {
            padding.append(chars.random())
        }
        
        return baseJson + padding.toString() + suffix
    }
    
    private fun saveWallet(wallet: Wallet) {
        _wallet.value = wallet
        _tokens.value = wallet.tokens
        val walletJson = gson.toJson(wallet)
        sharedPreferences.edit().putString("wallet", walletJson).apply()
    }
    
    fun addToken(token: Token) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens + token
        )
        saveWallet(updatedWallet)
    }
    
    fun removeToken(tokenId: String) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens.filter { it.id != tokenId }
        )
        saveWallet(updatedWallet)
    }
    
    fun getToken(tokenId: String): Token? {
        return _tokens.value.find { it.id == tokenId }
    }
    
    fun generateNewAddress(): String {
        // In real implementation, this would call Unicity SDK
        return "unicity_addr_${UUID.randomUUID().toString().take(8)}"
    }
    
    suspend fun refreshTokens() {
        // Add a small delay to show refresh animation
        delay(300)
        // Force reload from storage
        loadWallet()
    }
    
    fun clearWalletAndCreateDemo() {
        // Clear existing wallet data
        sharedPreferences.edit().clear().apply()
        // Create new wallet with demo tokens
        createNewWallet()
    }
    
    suspend fun mintNewToken(name: String, data: String, amount: Long = 100): Result<Token> {
        _isLoading.value = true
        return try {
            // Generate identity for the token
            val identity = generateIdentity()
            
            // Create token data
            val tokenData = UnicityTokenData(data, amount)
            
            // Mint the token using Unicity SDK
            val mintResult = mintToken(identity, tokenData)
            
            // Create Token object with Unicity data
            val token = Token(
                name = name,
                type = "Unicity Token",
                unicityAddress = identity.secret.take(16), // Use part of secret as address
                jsonData = mintResult.toJson(),
                sizeBytes = mintResult.toJson().length
            )
            
            // Add to wallet
            addToken(token)
            
            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }
    
    suspend fun createSampleTokens() {
        _isLoading.value = true
        try {
            val tokenSizes = listOf("2KB", "4KB", "8KB", "16KB", "32KB", "64KB")
            val tokenAmounts = listOf(100L, 250L, 500L, 750L, 1000L, 1500L)
            
            tokenSizes.forEachIndexed { index, sizeLabel ->
                val result = mintNewToken(
                    name = "$sizeLabel Token",
                    data = "Sample token data for $sizeLabel testing with amount ${tokenAmounts[index]}",
                    amount = tokenAmounts[index]
                )
                
                if (result.isFailure) {
                    Log.e(TAG, "Failed to create $sizeLabel token: ${result.exceptionOrNull()}")
                }
                
                // Add small delay between tokens to avoid overwhelming the SDK
                delay(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sample tokens", e)
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun generateIdentity(): UnicityIdentity = suspendCancellableCoroutine { continuation ->
        unicitySdkService.generateIdentity { result ->
            result.onSuccess { identityJson ->
                try {
                    val identity = UnicityIdentity.fromJson(identityJson)
                    continuation.resume(identity)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse identity", e)
                    continuation.resume(UnicityIdentity("error_secret", "error_nonce"))
                }
            }
            result.onFailure { error ->
                Log.e(TAG, "Failed to generate identity", error)
                continuation.resume(UnicityIdentity("fallback_secret", "fallback_nonce"))
            }
        }
    }
    
    private suspend fun mintToken(identity: UnicityIdentity, tokenData: UnicityTokenData): UnicityMintResult = 
        suspendCancellableCoroutine { continuation ->
            unicitySdkService.mintToken(identity.toJson(), tokenData.toJson()) { result ->
                result.onSuccess { mintResultJson ->
                    try {
                        val mintResult = UnicityMintResult.fromJson(mintResultJson)
                        continuation.resume(mintResult)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse mint result", e)
                        // Create fallback result
                        val fallback = UnicityMintResult(mapOf("error" to "parse_failed"), identity)
                        continuation.resume(fallback)
                    }
                }
                result.onFailure { error ->
                    Log.e(TAG, "Failed to mint token", error)
                    // Create fallback result
                    val fallback = UnicityMintResult(mapOf("error" to error.message), identity)
                    continuation.resume(fallback)
                }
            }
        }
    
    fun getSdkService() = unicitySdkService
    
    fun destroy() {
        unicitySdkService.destroy()
    }
}