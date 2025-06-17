package com.unicity.nfcwalletdemo.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.data.model.Wallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.util.UUID

class WalletRepository(context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    private val _wallet = MutableStateFlow<Wallet?>(null)
    val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()
    
    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens.asStateFlow()
    
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
            tokens = createDemoTokens(baseTime)
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
}