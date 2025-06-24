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
            tokens = emptyList() // Start with empty tokens
        )
        saveWallet(newWallet)
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
    
    fun clearWallet() {
        // Clear existing wallet data
        sharedPreferences.edit().clear().apply()
        // Create new empty wallet
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
            
            // Only add to wallet if minting was successful
            addToken(token)
            
            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            Result.failure(e)
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
                        // Throw exception instead of creating fallback
                        continuation.resumeWith(Result.failure(Exception("Failed to parse mint result: ${e.message}", e)))
                    }
                }
                result.onFailure { error ->
                    Log.e(TAG, "Failed to mint token", error)
                    // Throw exception instead of creating fallback
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    
    fun getSdkService() = unicitySdkService
    
    fun destroy() {
        unicitySdkService.destroy()
    }
}