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
        val newWallet = Wallet(
            id = UUID.randomUUID().toString(),
            name = "My Wallet",
            address = "unicity_wallet_${System.currentTimeMillis()}",
            tokens = listOf(
                Token(
                    name = "Community Coin",
                    type = "Community Coin",
                    unicityAddress = "unicity_token_demo_${System.currentTimeMillis()}"
                )
            )
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
}