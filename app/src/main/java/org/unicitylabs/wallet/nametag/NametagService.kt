package org.unicitylabs.wallet.nametag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.unicitylabs.wallet.di.ServiceProvider
import org.unicitylabs.wallet.identity.IdentityManager
import org.unicitylabs.wallet.utils.WalletConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.address.ProxyAddress
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenState
import org.unicitylabs.sdk.token.TokenType
import org.unicitylabs.sdk.transaction.MintCommitment
import org.unicitylabs.sdk.transaction.MintTransactionReason
import org.unicitylabs.sdk.transaction.NametagMintTransactionData
import org.unicitylabs.sdk.util.InclusionProofUtils
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.verification.VerificationException
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class NametagService(
    private val context: Context,
    private val stateTransitionClient: StateTransitionClient = ServiceProvider.stateTransitionClient,
    private val rootTrustBase: RootTrustBase = ServiceProvider.getRootTrustBase()
) {
    
    private val identityManager = IdentityManager(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    companion object {
        private const val TAG = "NametagService"
        private const val NAMETAG_FILE_PREFIX = "nametag_"
        private const val NAMETAG_FILE_SUFFIX = ".json"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    /**
     * Checks if the device has an active network connection
     */
    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
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
            
            // Check network connectivity first
            if (!isNetworkAvailable()) {
                Log.e(TAG, "No network connection available")
                throw IllegalStateException("No network connection. Please check your internet connection.")
            }
            
            
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
            
            // Get the identity from IdentityManager
            val identity = identityManager.getCurrentIdentity()
                ?: throw IllegalStateException("No wallet identity found")
            
            // Convert hex strings to byte arrays
            val secret = hexToBytes(identity.privateKey)
            
            // Create signing service with identity credentials
            val signingService = SigningService.createFromMaskedSecret(secret, nonce)
            
            
            // Create token id and type for the nametag
            val nametagTokenId = TokenId(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })
            val nametagTokenType = TokenType(ByteArray(32).apply {
                SecureRandom().nextBytes(this)
            })

            // Create predicate for the nametag (SDK 1.2 requires tokenId and tokenType)
            val nametagPredicate = MaskedPredicate.create(
                nametagTokenId,
                nametagTokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )

            // Get the nametag address
            val nametagAddress = nametagPredicate.getReference().toAddress()

            // Submit the mint commitment with retry logic
            var submitResponse: org.unicitylabs.sdk.api.SubmitCommitmentResponse? = null
            var lastException: Exception? = null
            var mintCommitment: MintCommitment<NametagMintTransactionData<MintTransactionReason>>? = null

            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                try {
                    // Generate new salt for each attempt to avoid REQUEST_ID_EXISTS
                    // Salt is required (cannot be null) - use random bytes for uniqueness
                    val salt = ByteArray(32).apply {
                        SecureRandom().nextBytes(this)
                    }

                    val mintTransactionData: NametagMintTransactionData<MintTransactionReason> = NametagMintTransactionData(
                        nametagString,
                        nametagTokenType,
                        nametagAddress,
                        salt, // Salt parameter - can be null or random bytes
                        ownerAddress
                    )

                    mintCommitment = MintCommitment.create(mintTransactionData)

                    Log.d(TAG, "Submitting mint commitment (attempt $attempt/$MAX_RETRY_ATTEMPTS)")
                    submitResponse = stateTransitionClient.submitCommitment(mintCommitment!!).await()
                    
                    if (submitResponse.status == SubmitCommitmentStatus.SUCCESS) {
                        Log.d(TAG, "Mint commitment submitted successfully on attempt $attempt")
                        break
                    } else {
                        Log.w(TAG, "Mint commitment failed with status: ${submitResponse.status} (attempt $attempt)")
                        if (attempt < MAX_RETRY_ATTEMPTS) {
                            delay(RETRY_DELAY_MS * attempt) // Exponential backoff
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error submitting mint commitment (attempt $attempt): ${e.message}", e)
                    lastException = e
                    
                    // Check if it's a network error and retry
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.d(TAG, "Retrying after ${RETRY_DELAY_MS * attempt}ms...")
                        delay(RETRY_DELAY_MS * attempt) // Exponential backoff
                        
                        // Re-test network and DNS before retry
                        if (!isNetworkAvailable()) {
                            Log.e(TAG, "Network lost during retry")
                            throw IllegalStateException("Network connection lost. Please check your internet connection.")
                        }
                    }
                }
            }
            
            if (submitResponse?.status != SubmitCommitmentStatus.SUCCESS) {
                val errorMsg = lastException?.message ?: "Unknown error"
                Log.e(TAG, "Failed to submit nametag mint commitment after $MAX_RETRY_ATTEMPTS attempts: $errorMsg")
                throw lastException ?: IllegalStateException("Failed to mint nametag after $MAX_RETRY_ATTEMPTS attempts")
            }
            
            Log.d(TAG, "Nametag mint commitment submitted successfully")
            
            // Wait for inclusion proof with timeout
            val inclusionProof = try {
                withContext(Dispatchers.IO) {
                    InclusionProofUtils.waitInclusionProof(
                        stateTransitionClient,
                        rootTrustBase,
                        mintCommitment!!
                    ).get(30, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get inclusion proof: ${e.message}", e)
                throw IllegalStateException("Failed to get inclusion proof: ${e.message}")
            }

            // Create the genesis transaction
            val genesisTransaction = mintCommitment!!.toTransaction(inclusionProof)

            // Create the nametag token
            // Nametag tokens are proxy tokens that use the nametag predicate
            val trustBase = ServiceProvider.getRootTrustBase()

            val nametagToken = try {
                Token.create(
                    trustBase,
                    TokenState(nametagPredicate, null), // tokenData should be null for nametags
                    genesisTransaction
                )
            } catch (e: VerificationException) {
                // Log the detailed verification error
                Log.e(TAG, "Token verification failed: ${e.message}")
                Log.e(TAG, "VerificationResult: ${e.verificationResult}")
                Log.e(TAG, "Full exception: ", e)
                throw e
            }
            
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
            
            // Check if jsonData is the wrapper format or direct token
            val token = try {
                // First try to parse as wrapper format (from export)
                val wrapper = UnicityObjectMapper.JSON.readTree(jsonData)
                if (wrapper.has("token")) {
                    // It's a wrapper, extract the token
                    val tokenJson = wrapper.get("token").asText()
                    UnicityObjectMapper.JSON.readValue(tokenJson, Token::class.java)
                } else {
                    // It's a direct token JSON
                    UnicityObjectMapper.JSON.readValue(jsonData, Token::class.java)
                }
            } catch (e: Exception) {
                // If all else fails, try direct parse
                UnicityObjectMapper.JSON.readValue(jsonData, Token::class.java)
            }
            
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
            
            // Extract token data - it's stored as a string, not an object
            val tokenJson = nametagData.get("token").asText()
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