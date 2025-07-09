package com.unicity.nfcwalletdemo.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.unicity.nfcwalletdemo.data.model.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IdentityManager(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_ALIAS = "UnicityWalletIdentity"
        private const val SHARED_PREFS = "identity_prefs"
        private const val KEY_ENCRYPTED_SEED = "encrypted_seed"
        private const val KEY_SEED_IV = "seed_iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WORD_COUNT = 12 // Using 12-word seed phrase
    }
    
    private val sharedPrefs = context.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    
    /**
     * Generates a new BIP-39 seed phrase and derives identity from it
     */
    suspend fun generateNewIdentity(): Pair<UserIdentity, List<String>> = withContext(Dispatchers.IO) {
        // Generate 12-word mnemonic
        val mnemonicCode = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_12)
        val words = mnemonicCode.words.map { it.concatToString() } // Convert CharArray to String
        
        // Derive seed from mnemonic
        val seed = mnemonicCode.toSeed()
        
        // Create identity from seed
        val identity = deriveIdentityFromSeed(seed)
        
        // Encrypt and store the mnemonic
        storeSeedPhrase(words)
        
        Pair(identity, words)
    }
    
    /**
     * Restores identity from a seed phrase
     */
    suspend fun restoreFromSeedPhrase(words: List<String>): UserIdentity = withContext(Dispatchers.IO) {
        // Validate word count
        require(words.size == WORD_COUNT) { "Invalid seed phrase length. Expected $WORD_COUNT words." }
        
        // Create mnemonic from words - join words into a phrase
        val phrase = words.joinToString(" ")
        val mnemonicCode = Mnemonics.MnemonicCode(phrase)
        
        // Derive seed
        val seed = mnemonicCode.toSeed()
        
        // Create identity from seed
        val identity = deriveIdentityFromSeed(seed)
        
        // Store the new seed phrase
        storeSeedPhrase(words)
        
        identity
    }
    
    /**
     * Gets the current identity if it exists
     */
    suspend fun getCurrentIdentity(): UserIdentity? = withContext(Dispatchers.IO) {
        val seedPhrase = getStoredSeedPhrase()
        if (seedPhrase != null) {
            restoreFromSeedPhrase(seedPhrase)
        } else {
            null
        }
    }
    
    /**
     * Gets the stored seed phrase (requires authentication)
     */
    suspend fun getSeedPhrase(): List<String>? = withContext(Dispatchers.IO) {
        getStoredSeedPhrase()
    }
    
    /**
     * Checks if an identity exists
     */
    fun hasIdentity(): Boolean {
        return sharedPrefs.contains(KEY_ENCRYPTED_SEED)
    }
    
    /**
     * Clears the stored identity (use with caution!)
     */
    suspend fun clearIdentity() = withContext(Dispatchers.IO) {
        sharedPrefs.edit().clear().apply()
        // Note: We don't delete the keystore key as it might be used elsewhere
    }
    
    private fun deriveIdentityFromSeed(seed: ByteArray): UserIdentity {
        // Use first 32 bytes of seed as secret
        val secret = seed.take(32).toByteArray()
        
        // Use next 32 bytes as nonce (or derive it)
        val nonce = if (seed.size >= 64) {
            seed.slice(32..63).toByteArray()
        } else {
            // If seed is shorter, hash it to get nonce
            MessageDigest.getInstance("SHA-256").digest(seed)
        }
        
        return UserIdentity(
            secret = bytesToHex(secret),
            nonce = bytesToHex(nonce)
        )
    }
    
    private fun storeSeedPhrase(words: List<String>) {
        val seedPhrase = words.joinToString(" ")
        val encryptedData = encrypt(seedPhrase.toByteArray())
        
        sharedPrefs.edit()
            .putString(KEY_ENCRYPTED_SEED, Base64.encodeToString(encryptedData.ciphertext, Base64.DEFAULT))
            .putString(KEY_SEED_IV, Base64.encodeToString(encryptedData.iv, Base64.DEFAULT))
            .apply()
    }
    
    private fun getStoredSeedPhrase(): List<String>? {
        val encryptedSeed = sharedPrefs.getString(KEY_ENCRYPTED_SEED, null) ?: return null
        val iv = sharedPrefs.getString(KEY_SEED_IV, null) ?: return null
        
        val ciphertext = Base64.decode(encryptedSeed, Base64.DEFAULT)
        val ivBytes = Base64.decode(iv, Base64.DEFAULT)
        
        val decrypted = decrypt(ciphertext, ivBytes)
        return String(decrypted).split(" ")
    }
    
    private fun getOrCreateSecretKey(): SecretKey {
        return if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }
    
    private fun encrypt(data: ByteArray): EncryptedData {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val ciphertext = cipher.doFinal(data)
        val iv = cipher.iv
        
        return EncryptedData(ciphertext, iv)
    }
    
    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        return cipher.doFinal(ciphertext)
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray
    )
}