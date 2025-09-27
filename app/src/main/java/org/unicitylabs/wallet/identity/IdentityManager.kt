package org.unicitylabs.wallet.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenType
import java.security.SecureRandom
import org.unicitylabs.wallet.data.model.UserIdentity
import org.unicitylabs.wallet.utils.WalletConstants
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IdentityManager(private val context: Context) {
    
    companion object {
        private const val TAG = "IdentityManager"
        private const val KEYSTORE_ALIAS = "UnicityWalletIdentity"
        private const val SHARED_PREFS = "identity_prefs"
        private const val KEY_ENCRYPTED_SEED = "encrypted_seed"
        private const val KEY_SEED_IV = "seed_iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WORD_COUNT = 12 // Using 12-word seed phrase
        private const val CURVE_NAME = "secp256k1" // Same curve as used by SigningService
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
    
    /**
     * Derives a complete identity from a BIP-39 seed
     * Uses the seed to generate:
     * 1. Private key (first 32 bytes of seed)
     * 2. Nonce (next 32 bytes or SHA-256 of seed)
     * 3. Public key (derived from private key using secp256k1)
     * 4. Address (derived from unmasked predicate with testnet token type)
     */
    private fun deriveIdentityFromSeed(seed: ByteArray): UserIdentity {
        // Use first 32 bytes of seed as secret (private key)
        val secret = seed.take(32).toByteArray()
        
        // Use next 32 bytes as nonce (or derive it)
        val nonce = if (seed.size >= 64) {
            seed.slice(32..63).toByteArray()
        } else {
            // If seed is shorter, hash it to get nonce
            MessageDigest.getInstance("SHA-256").digest(seed)
        }
        
        // Derive public key from private key using secp256k1
        val publicKey = derivePublicKey(secret)
        
        // Generate address from unmasked predicate with testnet token type
        val address = deriveAddress(secret, nonce)
        
        Log.d(TAG, "Identity derived - Public key: ${bytesToHex(publicKey)}, Address: $address")
        
        return UserIdentity(
            privateKey = bytesToHex(secret),
            nonce = bytesToHex(nonce),
            publicKey = bytesToHex(publicKey),
            address = address
        )
    }
    
    /**
     * Derives the public key from a private key using secp256k1 curve
     * This matches what SigningService does internally
     */
    private fun derivePublicKey(privateKeyBytes: ByteArray): ByteArray {
        try {
            // Get secp256k1 curve parameters
            val ecSpec = ECNamedCurveTable.getParameterSpec(CURVE_NAME)
            val domainParams = ECDomainParameters(
                ecSpec.curve,
                ecSpec.g,
                ecSpec.n,
                ecSpec.h
            )
            
            // Create private key parameters
            val privateKey = ECPrivateKeyParameters(
                BigInteger(1, privateKeyBytes),
                domainParams
            )
            
            // Calculate public key point
            val publicKeyPoint = FixedPointCombMultiplier()
                .multiply(domainParams.g, privateKey.d)
                .normalize()
            
            // Return compressed public key (33 bytes)
            return publicKeyPoint.getEncoded(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving public key", e)
            // Return empty array on error
            return ByteArray(33)
        }
    }
    
    /**
     * Derives a Unicity address from the private key
     * Uses an unmasked predicate which allows tokens (including nametags) to proxy to this address
     */
    private fun deriveAddress(secret: ByteArray, nonce: ByteArray): String {
        return try {
            // Create signing service with secret
            val signingService = SigningService.createFromSecret(secret)
            
            // Use the currently active chain's token type and generate a token ID
            val tokenType = TokenType(hexToBytes(WalletConstants.UNICITY_TOKEN_TYPE))
            val tokenId = TokenId(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })

            // Create an unmasked predicate with salt (using nonce as salt)
            val predicate = UnmaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce  // Use nonce as salt for the unmasked predicate
            )
            
            // Get the address from the predicate reference
            val address = predicate.getReference().toAddress()
            
            // Return the address string representation
            address.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving address", e)
            // Return a hash of the public key as fallback
            val publicKey = derivePublicKey(secret)
            val addressHash = MessageDigest.getInstance("SHA-256").digest(publicKey)
            bytesToHex(addressHash.take(20).toByteArray())
        }
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
    
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    private data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray
    )
}