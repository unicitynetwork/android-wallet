package com.unicity.nfcwalletdemo.sdk

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.unicity.sdk.StateTransitionClient
import com.unicity.sdk.api.AggregatorClient
import com.unicity.sdk.api.SubmitCommitmentStatus
import com.unicity.sdk.address.Address
import com.unicity.sdk.address.DirectAddress
import com.unicity.sdk.address.ProxyAddress
import com.unicity.sdk.predicate.MaskedPredicate
import com.unicity.sdk.predicate.UnmaskedPredicate
import com.unicity.sdk.hash.DataHash
import com.unicity.sdk.hash.DataHasher
import com.unicity.sdk.hash.HashAlgorithm
import com.unicity.sdk.signing.SigningService
import com.unicity.sdk.token.Token
import com.unicity.sdk.token.TokenId
import com.unicity.sdk.token.TokenState
import com.unicity.sdk.token.TokenType
import com.unicity.sdk.token.fungible.CoinId
import com.unicity.sdk.token.fungible.TokenCoinData
import com.unicity.sdk.transaction.MintCommitment
import com.unicity.sdk.transaction.MintTransactionData
import com.unicity.sdk.transaction.MintTransactionReason
import com.unicity.sdk.transaction.TransferCommitment
import com.unicity.sdk.transaction.Transaction
import com.unicity.sdk.transaction.TransferTransactionData
import com.unicity.sdk.util.InclusionProofUtils
import com.unicity.sdk.serializer.UnicityObjectMapper
import com.unicity.nfcwalletdemo.utils.WalletConstants
import kotlinx.coroutines.future.await
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * Service for interacting with the Unicity Java SDK 1.1.
 * This service provides coroutine-based wrappers around the Java SDK's CompletableFuture APIs.
 */
class UnicityJavaSdkService {
    companion object {
        private const val TAG = "UnicityJavaSdkService"
        
        @Volatile
        private var instance: UnicityJavaSdkService? = null
        
        fun getInstance(): UnicityJavaSdkService {
            return instance ?: synchronized(this) {
                instance ?: UnicityJavaSdkService().also { instance = it }
            }
        }
    }
    
    private val client: StateTransitionClient by lazy {
        val aggregatorClient = AggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL)
        StateTransitionClient(aggregatorClient)
    }
    
    private val objectMapper = ObjectMapper()
    
    /**
     * Mint a new token with the specified amount and data.
     * @param amount The token amount in wei (must be positive)
     * @param data Custom data to include with the token (e.g., currency type)
     * @param secret The wallet's secret key for signing
     * @param nonce The wallet's nonce for key derivation
     * @return The minted token or null if minting fails
     */
    suspend fun mintToken(
        amount: Long,
        data: String,
        secret: ByteArray,
        nonce: ByteArray
    ): Token<*>? {
        return try {
            Log.d(TAG, "Minting token with amount: $amount, data: $data")
            
            // Create token identifiers
            val random = SecureRandom()
            val tokenIdData = ByteArray(32)
            random.nextBytes(tokenIdData)
            val tokenId = TokenId(tokenIdData)
            
            val tokenTypeData = ByteArray(32)
            random.nextBytes(tokenTypeData)
            val tokenType = TokenType(tokenTypeData)
            
            // Create coin data - split amount into two coins
            val coinId1Data = ByteArray(32)
            val coinId2Data = ByteArray(32)
            random.nextBytes(coinId1Data)
            random.nextBytes(coinId2Data)
            
            val coins = mapOf(
                CoinId(coinId1Data) to BigInteger.valueOf(amount / 2),
                CoinId(coinId2Data) to BigInteger.valueOf(amount - (amount / 2))
            )
            val coinData = TokenCoinData(coins)
            
            // Create signing service and predicate (SDK 1.1 - no tokenId/tokenType in create)
            val signingService = SigningService.createFromSecret(secret, nonce)
            val predicate = MaskedPredicate.create(
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )
            
            // Get recipient address from predicate reference for this token type
            val recipientAddress = predicate.getReference(tokenType).toAddress()
            
            // Create token data as byte array
            val tokenDataMap = mapOf(
                "data" to data,
                "amount" to amount
            )
            val tokenDataBytes = objectMapper.writeValueAsBytes(tokenDataMap)
            
            // Create salt for transaction
            val salt = ByteArray(32)
            random.nextBytes(salt)
            
            // Create mint transaction data (SDK 1.1 signature)
            val mintData = MintTransactionData<MintTransactionReason>(
                tokenId,
                tokenType,
                tokenDataBytes,
                coinData,
                recipientAddress,
                salt,
                null,  // No data hash - data is stored directly
                null   // No reason
            )
            
            // Create mint commitment
            val commitment = MintCommitment.create(mintData)
            
            // Submit commitment to network
            val submitResponse = client.submitCommitment(commitment).await()
            
            if (submitResponse.status != SubmitCommitmentStatus.SUCCESS) {
                Log.e(TAG, "Failed to submit mint commitment: ${submitResponse.status}")
                return null
            }
            
            Log.d(TAG, "Mint commitment submitted successfully")
            
            // Wait for inclusion proof
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, commitment).await()
            
            // Create transaction from commitment and proof
            val transaction = commitment.toTransaction(inclusionProof)
            
            // Create token state
            val tokenState = TokenState(predicate, ByteArray(0))
            
            // Create and return the token
            val token = Token(tokenState, transaction)
            
            Log.d(TAG, "Successfully minted token")
            token
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            null
        }
    }
    
    /**
     * Create an offline transfer package that can be shared via NFC.
     * This serializes a transfer commitment that the recipient can later submit.
     * @param tokenJson The serialized token to transfer
     * @param recipientAddress The recipient's address  
     * @param amount The amount to transfer (optional, defaults to full token amount)
     * @param senderSecret The sender's secret key
     * @param senderNonce The sender's nonce
     * @return The serialized commitment package or null if creation fails
     */
    suspend fun createOfflineTransfer(
        tokenJson: String,
        recipientAddress: String,
        amount: Long? = null,
        senderSecret: ByteArray,
        senderNonce: ByteArray
    ): String? {
        return try {
            Log.d(TAG, "Creating offline transfer to $recipientAddress")
            
            // Parse the token JSON to extract necessary data
            val tokenNode = objectMapper.readTree(tokenJson)
            
            // Create signing service for sender
            val signingService = SigningService.createFromSecret(senderSecret, senderNonce)
            
            // Generate salt for the transfer
            val salt = ByteArray(32)
            SecureRandom().nextBytes(salt)
            
            // Create offline transfer package structure
            // This includes all data needed for recipient to complete the transfer
            val offlinePackage = mapOf(
                "type" to "offline_transfer",
                "version" to "1.1",
                "sender" to mapOf(
                    "address" to tokenNode.path("state").path("address").asText(),
                    "publicKey" to Base64.getEncoder().encodeToString(signingService.publicKey)
                ),
                "recipient" to recipientAddress,
                "token" to tokenJson,
                "commitment" to mapOf(
                    "salt" to Base64.getEncoder().encodeToString(salt),
                    "timestamp" to System.currentTimeMillis(),
                    "amount" to (amount ?: tokenNode.path("amount").asLong(0))
                ),
                "network" to "test"
            )
            
            // Serialize the package
            val packageJson = objectMapper.writeValueAsString(offlinePackage)
            
            Log.d(TAG, "Created offline transfer package (${packageJson.length} bytes)")
            packageJson
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offline transfer", e)
            null
        }
    }
    
    /**
     * Complete an offline transfer by processing the package from sender.
     * This would typically submit the transfer commitment to the network.
     * @param offlinePackage The serialized package from the sender
     * @param recipientSecret The recipient's secret key
     * @param recipientNonce The recipient's nonce
     * @return The completed token or null if completion fails
     */
    suspend fun completeOfflineTransfer(
        offlinePackage: String,
        recipientSecret: ByteArray,
        recipientNonce: ByteArray
    ): Token<*>? {
        return try {
            Log.d(TAG, "Completing offline transfer")
            
            // Parse the offline package
            val packageNode = objectMapper.readTree(offlinePackage)
            
            // Verify package version
            val version = packageNode.path("version").asText()
            if (version != "1.1") {
                Log.e(TAG, "Unsupported package version: $version")
                return null
            }
            
            // Extract token and transfer data
            val tokenJson = packageNode.path("token").toString()
            val recipientAddress = packageNode.path("recipient").asText()
            
            // Create recipient's signing service and predicate
            val recipientSigningService = SigningService.createFromSecret(recipientSecret, recipientNonce)
            
            // In a real implementation, this would:
            // 1. Deserialize the sender's token
            // 2. Create and submit a transfer commitment
            // 3. Wait for inclusion proof
            // 4. Finalize the transaction with recipient's predicate
            // 5. Return the received token
            
            // For now, return null as full implementation requires more complex token deserialization
            Log.w(TAG, "Offline transfer completion not fully implemented in SDK 1.1")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete offline transfer", e)
            null
        }
    }
    
    /**
     * Serialize a token to JSON for storage or transfer.
     */
    fun serializeToken(token: Token<*>?): String? {
        if (token == null) {
            return null
        }
        return try {
            // Use UnicityObjectMapper for proper serialization
            UnicityObjectMapper.JSON.writeValueAsString(token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize token", e)
            null
        }
    }
    
    /**
     * Deserialize a token from JSON.
     * Note: This is complex in SDK 1.1 as it requires proper transaction reconstruction.
     */
    fun deserializeToken(tokenJson: String): Token<*>? {
        return try {
            // Token deserialization is complex and requires proper type handling
            // This would need to reconstruct the full token with its transaction history
            Log.w(TAG, "Token deserialization not fully implemented for SDK 1.1")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize token", e)
            null
        }
    }
    
    // Utility functions
    
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
}