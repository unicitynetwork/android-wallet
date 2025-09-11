package org.unicitylabs.wallet.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.model.TokenStatus
import org.unicitylabs.wallet.data.model.Wallet
import org.unicitylabs.wallet.identity.IdentityManager
import org.unicitylabs.wallet.sdk.UnicityIdentity
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import org.unicitylabs.wallet.sdk.UnicityMintResult
import org.unicitylabs.wallet.sdk.UnicityTokenData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import java.util.UUID

class WalletRepository(context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val unicitySdkService = UnicityJavaSdkService()
    private val identityManager = IdentityManager(context)
    
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
        Log.d(TAG, "=== addToken called ===")
        Log.d(TAG, "Token to add: ${token.name}, type: ${token.type}")
        
        val currentWallet = _wallet.value
        if (currentWallet == null) {
            Log.e(TAG, "Current wallet is null! Cannot add token")
            return
        }
        
        Log.d(TAG, "Current tokens count: ${currentWallet.tokens.size}")
        
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens + token
        )
        
        Log.d(TAG, "Updated tokens count: ${updatedWallet.tokens.size}")
        
        saveWallet(updatedWallet)
        
        Log.d(TAG, "Token added and wallet saved")
    }
    
    fun removeToken(tokenId: String) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens.filter { it.id != tokenId }
        )
        saveWallet(updatedWallet)
    }
    
    fun updateToken(token: Token) {
        val currentWallet = _wallet.value ?: return
        val updatedWallet = currentWallet.copy(
            tokens = currentWallet.tokens.map { if (it.id == token.id) token else it }
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
        // Note: We do NOT clear the BIP-39 identity here
        // The identity persists across wallet resets for recovery purposes
        // If you need to clear identity, use identityManager.clearIdentity() separately
        
        // Create new empty wallet
        createNewWallet()
    }
    
    suspend fun mintNewToken(name: String, data: String, amount: Long = 100): Result<Token> {
        Log.d(TAG, "=== mintNewToken started ===")
        Log.d(TAG, "Minting token: name=$name, data=$data, amount=$amount")
        
        _isLoading.value = true
        return try {
            // Generate identity for the token
            Log.d(TAG, "Generating identity...")
            val identity = generateIdentity()
            Log.d(TAG, "Identity generated: ${identity.secret.take(8)}...")
            
            // Create token data
            val tokenData = UnicityTokenData(data, amount)
            
            // Mint the token using Unicity SDK
            Log.d(TAG, "Calling SDK mintToken...")
            val mintResult = mintToken(identity, tokenData)
            Log.d(TAG, "Mint result received")
            
            // Extract the complete token JSON from the mint result
            // The JS SDK returns a finalized, self-contained token that includes:
            // - Token state with unlock predicate
            // - Genesis (mint) transaction with inclusion proof
            // - Complete transaction history
            // This is the same format used for .txf file exports
            val mintResultObj = gson.fromJson(mintResult.toJson(), Map::class.java)
            val tokenJson = gson.toJson(mintResultObj["token"])
            
            // Check if this is a pending token
            val status = if (mintResultObj["status"] == "pending") {
                TokenStatus.PENDING
            } else {
                TokenStatus.CONFIRMED
            }
            
            // Create Token object with the complete, self-contained token data
            val token = Token(
                name = name,
                type = "Unicity Token",
                unicityAddress = identity.secret.take(16), // Use part of secret as address
                jsonData = tokenJson, // Store the complete token in .txf format
                sizeBytes = tokenJson.length,
                status = status
            )
            
            // Only add to wallet if minting was successful
            Log.d(TAG, "Adding token to wallet...")
            addToken(token)
            
            Log.d(TAG, "Token minting completed successfully")
            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }
    
    
    private suspend fun generateIdentity(): UnicityIdentity {
        // Check if we already have an identity
        val existingIdentity = identityManager.getCurrentIdentity()
        if (existingIdentity != null) {
            // Return the existing BIP-39 derived identity
            return UnicityIdentity(existingIdentity.secret, existingIdentity.nonce)
        }
        
        // Generate a new BIP-39 identity if none exists
        val (identity, _) = identityManager.generateNewIdentity()
        return UnicityIdentity(identity.secret, identity.nonce)
    }
    
    private suspend fun mintToken(identity: UnicityIdentity, tokenData: UnicityTokenData): UnicityMintResult {
        val token = unicitySdkService.mintToken(
            tokenData.amount,
            tokenData.data,
            identity.secret.toByteArray(),
            identity.nonce.toByteArray()
        )
        
        return if (token != null) {
            try {
                // Convert token to JSON for storage using UnicityObjectMapper
                val tokenJson = UnicityObjectMapper.JSON.writeValueAsString(token)
                UnicityMintResult.success(tokenJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize minted token", e)
                throw Exception("Failed to serialize minted token: ${e.message}", e)
            }
        } else {
            Log.e(TAG, "Failed to mint token")
            throw Exception("Failed to mint token")
        }
    }
    
    fun getSdkService() = unicitySdkService
    
    fun getIdentityManager() = identityManager
    
    fun destroy() {
        // No cleanup needed for Java SDK service
    }
}