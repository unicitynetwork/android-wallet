package com.unicity.nfcwalletdemo.nametag

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.unicity.nfcwalletdemo.utils.WalletConstants
import com.unicity.nfcwalletdemo.identity.IdentityManager
import com.unicity.sdk.StateTransitionClient
import com.unicity.sdk.address.DirectAddress
import com.unicity.sdk.address.ProxyAddress
import com.unicity.sdk.api.AggregatorClient
import com.unicity.sdk.transaction.MintCommitment
import com.unicity.sdk.api.SubmitCommitmentStatus
import com.unicity.sdk.hash.HashAlgorithm
import com.unicity.sdk.predicate.MaskedPredicate
import com.unicity.sdk.signing.SigningService
import com.unicity.sdk.token.NameTagTokenState
import com.unicity.sdk.token.Token
import com.unicity.sdk.token.TokenId
import com.unicity.sdk.token.TokenType
import com.unicity.sdk.transaction.NametagMintTransactionData
import com.unicity.sdk.transaction.MintTransactionReason
import com.unicity.sdk.util.InclusionProofUtils
import com.unicity.sdk.serializer.UnicityObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class NametagService(private val context: Context) {
    
    private val identityManager = IdentityManager(context)
    
    companion object {
        private const val TAG = "NametagService"
        private const val NAMETAG_FILE_PREFIX = "nametag_"
        private const val NAMETAG_FILE_SUFFIX = ".json"
    }
    
    private val aggregatorClient = AggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL)
    private val stateTransitionClient = StateTransitionClient(aggregatorClient)
    
    /**
     * Mints a nametag for the given string if not already minted
     * @param nametagString The raw nametag string (e.g., "john", NOT "john@unicity")
     *                      The @unicity suffix is only used for display in the wallet app
     * @param ownerAddress The address that will own the nametag
     * @return The minted nametag token or null if minting fails
     */
    suspend fun mintNametag(
        nametagString: String,
        ownerAddress: DirectAddress
    ): Token<*>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Minting nametag: $nametagString (raw, without @unicity)")
            
            // Check if nametag already exists locally
            val existingNametag = loadNametag(nametagString)
            if (existingNametag != null) {
                Log.d(TAG, "Nametag already exists locally: $nametagString")
                return@withContext existingNametag
            }
            
            // Generate cryptographic materials for the nametag
            val nonce = ByteArray(32).apply { 
                SecureRandom().nextBytes(this)
            }
            val salt = ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            }
            
            // Get the identity from IdentityManager
            val identity = identityManager.getCurrentIdentity()
                ?: throw IllegalStateException("No wallet identity found")
            
            // Convert hex strings to byte arrays
            val secret = hexToBytes(identity.secret)
            val identityNonce = hexToBytes(identity.nonce)
            
            // Create signing service with identity credentials
            val signingService = SigningService.createFromSecret(secret, nonce)
            
            // Create predicate for the nametag
            val nametagPredicate = MaskedPredicate.create(
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )
            
            // Create token type for the nametag
            val nametagTokenType = TokenType(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })
            
            // Get the nametag address
            val nametagAddress = nametagPredicate.getReference(nametagTokenType).toAddress()
            
            // Create mint commitment for the nametag
            val mintTransactionData: NametagMintTransactionData<MintTransactionReason> = NametagMintTransactionData(
                    nametagString,
                    nametagTokenType,
                    ByteArray(10), // Token data (can be customized)
                    null, // No coin data for nametag
                    nametagAddress,
                    salt,
                    ownerAddress
                )
            
            val mintCommitment: MintCommitment<NametagMintTransactionData<MintTransactionReason>> = MintCommitment.create(mintTransactionData)
            
            // Submit the mint commitment
            val submitResponse = stateTransitionClient.submitCommitment(mintCommitment).await()
            
            if (submitResponse.status != SubmitCommitmentStatus.SUCCESS) {
                Log.e(TAG, "Failed to submit nametag mint commitment: ${submitResponse.status}")
                return@withContext null
            }
            
            Log.d(TAG, "Nametag mint commitment submitted successfully")
            
            // Wait for inclusion proof
            val inclusionProof = InclusionProofUtils.waitInclusionProof(
                stateTransitionClient,
                mintCommitment
            ).await()
            
            // Create the genesis transaction
            val genesisTransaction = mintCommitment.toTransaction(inclusionProof)
            
            // Create the nametag token
            val nametagToken = Token(
                NameTagTokenState(nametagPredicate, ownerAddress),
                genesisTransaction
            )
            
            // Save the nametag to persistent storage
            saveNametag(nametagString, nametagToken, nonce)
            
            Log.d(TAG, "Nametag minted and saved successfully: $nametagString")
            return@withContext nametagToken
            
        } catch (e: Exception) {
            Log.e(TAG, "Error minting nametag: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Imports an existing nametag from JSON data
     * @param nametagString The nametag string
     * @param jsonData The JSON representation of the nametag token
     * @param nonce The nonce used for the nametag predicate
     * @return The imported nametag token or null if import fails
     */
    suspend fun importNametag(
        nametagString: String,
        jsonData: String,
        nonce: ByteArray
    ): Token<*>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Importing nametag: $nametagString")
            
            // Parse the token from JSON
            val token = UnicityObjectMapper.JSON.readValue(jsonData, Token::class.java)
            
            // Save the imported nametag
            saveNametag(nametagString, token, nonce)
            
            Log.d(TAG, "Nametag imported successfully: $nametagString")
            return@withContext token
            
        } catch (e: Exception) {
            Log.e(TAG, "Error importing nametag: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Loads a nametag from persistent storage
     * @param nametagString The nametag string
     * @return The loaded nametag token or null if not found
     */
    suspend fun loadNametag(nametagString: String): Token<*>? = withContext(Dispatchers.IO) {
        try {
            val file = getNametagFile(nametagString)
            if (!file.exists()) {
                return@withContext null
            }
            
            val jsonData = file.readText()
            val nametagData = UnicityObjectMapper.JSON.readTree(jsonData)
            
            // Extract token data
            val tokenJson = nametagData.get("token").toString()
            val token = UnicityObjectMapper.JSON.readValue(tokenJson, Token::class.java)
            
            Log.d(TAG, "Nametag loaded from storage: $nametagString")
            return@withContext token
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading nametag: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Gets the proxy address for a nametag
     * @param nametagToken The nametag token
     * @return The proxy address that can be used to receive tokens
     */
    fun getProxyAddress(nametagToken: Token<*>): ProxyAddress {
        return ProxyAddress.create(nametagToken.id)
    }
    
    /**
     * Checks if a nametag exists locally
     * @param nametagString The nametag string
     * @return true if the nametag exists locally
     */
    suspend fun hasNametag(nametagString: String): Boolean = withContext(Dispatchers.IO) {
        getNametagFile(nametagString).exists()
    }
    
    /**
     * Deletes a nametag from local storage
     * @param nametagString The nametag string
     * @return true if deletion was successful
     */
    suspend fun deleteNametag(nametagString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getNametagFile(nametagString)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting nametag: ${e.message}", e)
            false
        }
    }
    
    /**
     * Exports a nametag as JSON in .txf format
     * @param nametagString The nametag string
     * @return The JSON representation of the nametag suitable for .txf file or null if not found
     */
    suspend fun exportNametag(nametagString: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = getNametagFile(nametagString)
            if (!file.exists()) {
                return@withContext null
            }
            
            // Return the raw JSON data which is already in .txf format
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting nametag: ${e.message}", e)
            null
        }
    }
    
    private fun saveNametag(nametagString: String, token: Token<*>, nonce: ByteArray) {
        try {
            val file = getNametagFile(nametagString)
            
            // Create a .txf-compatible JSON structure that includes both token and nonce
            // This format can be exported/imported as a .txf file
            val nametagData = mapOf(
                "nametag" to nametagString,
                "token" to UnicityObjectMapper.JSON.writeValueAsString(token),
                "nonce" to nonce.encodeToString(),
                "timestamp" to System.currentTimeMillis(),
                "format" to "txf",
                "version" to "1.0"
            )
            
            val jsonData = UnicityObjectMapper.JSON.writeValueAsString(nametagData)
            file.writeText(jsonData)
            
            Log.d(TAG, "Nametag saved to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving nametag: ${e.message}", e)
            throw e
        }
    }
    
    private fun getNametagFile(nametagString: String): File {
        val nametagDir = File(context.filesDir, "nametags")
        if (!nametagDir.exists()) {
            nametagDir.mkdirs()
        }
        
        val filename = "$NAMETAG_FILE_PREFIX${nametagString.hashCode()}$NAMETAG_FILE_SUFFIX"
        return File(nametagDir, filename)
    }
    
    private fun ByteArray.encodeToString(): String {
        return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    }
    
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}