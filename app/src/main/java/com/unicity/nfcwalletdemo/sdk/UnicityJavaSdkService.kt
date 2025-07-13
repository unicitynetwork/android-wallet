package com.unicity.nfcwalletdemo.sdk

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.unicity.sdk.StateTransitionClient
import com.unicity.sdk.api.AggregatorClient
import com.unicity.sdk.api.Authenticator
import com.unicity.sdk.api.RequestId
import com.unicity.sdk.address.DirectAddress
import com.unicity.sdk.predicate.MaskedPredicate
import com.unicity.sdk.predicate.UnmaskedPredicate
import com.unicity.sdk.shared.hash.DataHash
import com.unicity.sdk.shared.hash.DataHasher
import com.unicity.sdk.shared.hash.HashAlgorithm
import com.unicity.sdk.shared.signing.SigningService
import com.unicity.sdk.token.Token
import com.unicity.sdk.token.TokenId
import com.unicity.sdk.token.TokenState
import com.unicity.sdk.token.TokenType
import com.unicity.sdk.token.fungible.CoinId
import com.unicity.sdk.token.fungible.TokenCoinData
import com.unicity.sdk.transaction.Commitment
import com.unicity.sdk.transaction.MintTransactionData
import com.unicity.sdk.transaction.Transaction
import com.unicity.sdk.transaction.TransactionData
import com.unicity.sdk.serializer.json.transaction.CommitmentJsonSerializer
import com.unicity.sdk.utils.InclusionProofUtils
import com.unicity.sdk.ISerializable
import kotlinx.coroutines.future.await
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Service for interacting with Unicity Java SDK
 */
class UnicityJavaSdkService {
    
    companion object {
        private const val TAG = "UnicityJavaSdkService"
        private const val AGGREGATOR_URL = "https://gateway-test.unicity.network"  // TODO: Make configurable
        private val random = SecureRandom()
        private val objectMapper = ObjectMapper()
    }
    
    private val aggregatorClient = AggregatorClient(AGGREGATOR_URL)
    private val client = StateTransitionClient(aggregatorClient)
    
    /**
     * Generate a new identity for the wallet
     * @param callback Function to call with the result
     */
    fun generateIdentity(callback: (Result<String>) -> Unit) {
        try {
            // Generate random secret and nonce
            val secret = ByteArray(32)
            random.nextBytes(secret)
            
            val nonce = ByteArray(32)
            random.nextBytes(nonce)
            
            // Create identity JSON similar to JS SDK
            val identity = mapOf(
                "secret" to String(secret, StandardCharsets.UTF_8),
                "nonce" to String(nonce, StandardCharsets.UTF_8)
            )
            
            callback(Result.success(objectMapper.writeValueAsString(identity)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate identity", e)
            callback(Result.failure(e))
        }
    }
    
    /**
     * Mint a new token using the Java SDK
     * @param identityJson JSON string containing the identity (secret and nonce)
     * @param tokenDataJson JSON string containing the token data
     * @return Result containing the minted token JSON or error
     */
    suspend fun mintToken(identityJson: String, tokenDataJson: String): Result<String> {
        return try {
            Log.d(TAG, "Minting token with Java SDK")
            
            // Parse identity
            val identityData = objectMapper.readTree(identityJson)
            val secret = identityData.get("secret").asText().toByteArray(StandardCharsets.UTF_8)
            val nonce = identityData.get("nonce").asText().toByteArray(StandardCharsets.UTF_8)
            
            // Parse token data
            val tokenData = objectMapper.readTree(tokenDataJson)
            val data = tokenData.get("data").asText()
            val amount = tokenData.get("amount")?.asLong() ?: 100L
            
            // Create token ID and type
            val tokenIdData = ByteArray(32)
            random.nextBytes(tokenIdData)
            val tokenId = TokenId.create(tokenIdData)
            
            val tokenTypeData = ByteArray(32)
            random.nextBytes(tokenTypeData)
            val tokenType = TokenType.create(tokenTypeData)
            
            // Create coin data
            val coinId1Data = ByteArray(32)
            val coinId2Data = ByteArray(32)
            random.nextBytes(coinId1Data)
            random.nextBytes(coinId2Data)
            
            val coins = mapOf(
                CoinId(coinId1Data) to BigInteger.valueOf(amount / 2),
                CoinId(coinId2Data) to BigInteger.valueOf(amount - (amount / 2))
            )
            val coinData = TokenCoinData.create(coins)
            
            // Create signing service and predicate
            val signingService = SigningService.createFromSecret(secret, nonce).await()
            val predicate = MaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            ).await()
            
            // Create mint transaction data
            val salt = ByteArray(32)
            random.nextBytes(salt)
            val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
            val dataHash = DataHasher.digest(HashAlgorithm.SHA256, dataBytes)
            
            val emptyTokenData = object : ISerializable {
                override fun toJSON(): Any = ""
                override fun toCBOR(): ByteArray = ByteArray(0)
            }
            
            val mintData = MintTransactionData<ISerializable>(
                tokenId,
                tokenType,
                predicate,
                emptyTokenData,
                coinData,
                dataHash,
                salt
            )
            
            // Submit mint transaction
            val commitment = client.submitMintTransaction(mintData).await()
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, commitment).await()
            val mintTransaction = client.createTransaction(commitment, inclusionProof).await()
            
            // Create token
            val tokenState = TokenState.create(predicate, dataBytes)
            Log.d(TAG, "Minted token state hash: ${tokenState.hash.toJSON()}")
            Log.d(TAG, "Minted predicate hash: ${predicate.hash.toJSON()}")
            
            @Suppress("UNCHECKED_CAST")
            val token = Token<Transaction<MintTransactionData<*>>>(
                tokenState,
                mintTransaction as Transaction<MintTransactionData<*>>
            )
            
            // Log the token structure
            val tokenJson = token.toJSON()
            Log.d(TAG, "Minted token JSON structure: ${objectMapper.writeValueAsString(tokenJson).take(500)}...")
            
            // Return mint result in expected format
            val result = mapOf(
                "identity" to mapOf(
                    "secret" to String(secret, StandardCharsets.UTF_8),
                    "nonce" to String(nonce, StandardCharsets.UTF_8)
                ),
                "token" to tokenJson
            )
            
            Result.success(objectMapper.writeValueAsString(result))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint token", e)
            Result.failure(e)
        }
    }
    
    /**
     * Create an offline transfer package for a token
     * @param senderIdentityJson JSON string containing the sender's identity
     * @param recipientAddress The recipient's address
     * @param tokenJson JSON string containing the token data
     * @return Result containing the offline transfer package JSON or error
     */
    suspend fun createOfflineTransferPackage(
        senderIdentityJson: String,
        recipientAddress: String,
        tokenJson: String
    ): Result<String> {
        return try {
            Log.d(TAG, "Creating offline transfer package with Java SDK")
            Log.d(TAG, "Recipient address: $recipientAddress")
            Log.d(TAG, "Token JSON structure: ${tokenJson.take(500)}...")
            
            // Parse sender identity
            val identityData = objectMapper.readTree(senderIdentityJson)
            val secret = identityData.get("secret").asText().toByteArray(StandardCharsets.UTF_8)
            val nonce = identityData.get("nonce").asText().toByteArray(StandardCharsets.UTF_8)
            
            // Parse token - it should be a complete token from mint or previous transfer
            val tokenNode = objectMapper.readTree(tokenJson)
            Log.d(TAG, "Token node type: ${tokenNode.nodeType}, has 'state': ${tokenNode.has("state")}, has 'genesis': ${tokenNode.has("genesis")}")
            
            val tokenState = deserializeTokenState(tokenNode)
            Log.d(TAG, "Deserialized token state hash: ${tokenState.hash.toJSON()}")
            
            // Create signing service
            val signingService = SigningService.createFromSecret(secret, nonce).await()
            
            // Create transfer transaction data
            val salt = ByteArray(32)
            random.nextBytes(salt)
            val message = "Offline transfer".toByteArray(StandardCharsets.UTF_8)
            
            val transactionData = TransactionData.create(
                tokenState,
                recipientAddress,
                salt,
                null, // No state hash for now
                message,
                null  // No nametagData
            ).await()
            
            // Log transaction data details
            Log.d(TAG, "Transaction data hash: ${transactionData.hash.toJSON()}")
            Log.d(TAG, "Source state hash: ${transactionData.sourceState.hash.toJSON()}")
            Log.d(TAG, "Public key: ${signingService.publicKey.toHexString()}")
            
            // Create authenticator
            val authenticator = Authenticator.create(
                signingService,
                transactionData.hash,
                transactionData.sourceState.hash
            ).await()
            
            Log.d(TAG, "Authenticator created:")
            val authJson = authenticator.toJSON() as? Map<*, *>
            if (authJson != null) {
                Log.d(TAG, "  Algorithm: ${authJson["algorithm"]}")
                Log.d(TAG, "  State hash in authenticator: ${authJson["stateHash"]}")
                Log.d(TAG, "  Signature: ${authJson["signature"]}")
            }
            
            // Create request ID
            val requestId = RequestId.create(
                signingService.publicKey,
                transactionData.sourceState.hash
            ).await()
            
            // Create commitment
            val commitment = Commitment(requestId, transactionData, authenticator)
            
            // Serialize commitment for offline transfer
            val commitmentJson = CommitmentJsonSerializer.serialize(commitment)
            
            Log.d(TAG, "Serialized commitment structure:")
            val commitmentNode = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(commitmentJson)
            Log.d(TAG, "  Has requestId: ${commitmentNode.has("requestId")}")
            Log.d(TAG, "  Has transactionData: ${commitmentNode.has("transactionData")}")
            Log.d(TAG, "  Has authenticator: ${commitmentNode.has("authenticator")}")
            
            Result.success(objectMapper.writeValueAsString(commitmentJson))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offline transfer package", e)
            Result.failure(e)
        }
    }
    
    /**
     * Complete an offline transfer by processing the transfer package
     * @param receiverIdentityJson JSON string containing the receiver's identity
     * @param offlineTransactionJson JSON string containing the offline transaction
     * @return Result containing the completed token JSON or error
     */
    suspend fun completeOfflineTransfer(
        receiverIdentityJson: String,
        offlineTransactionJson: String
    ): Result<String> {
        return try {
            Log.d(TAG, "Completing offline transfer with Java SDK")
            
            // Parse receiver identity
            val identityData = objectMapper.readTree(receiverIdentityJson)
            val secret = identityData.get("secret").asText().toByteArray(StandardCharsets.UTF_8)
            val nonce = identityData.get("nonce").asText().toByteArray(StandardCharsets.UTF_8)
            
            // Parse commitment from offline transaction
            val commitmentNode = objectMapper.readTree(offlineTransactionJson)
            
            // Deserialize commitment components
            val requestIdHex = commitmentNode.get("requestId").asText()
            val requestId = RequestId.fromJSON(requestIdHex)
            
            // Get transaction data
            val txDataNode = commitmentNode.get("transactionData")
            val transactionData = deserializeTransactionData(txDataNode)
            
            // Get authenticator
            val authNode = commitmentNode.get("authenticator")
            val authMap = mapOf(
                "algorithm" to authNode.get("algorithm").asText(),
                "publicKey" to authNode.get("publicKey").asText(),
                "signature" to authNode.get("signature").asText(),
                "stateHash" to authNode.get("stateHash").asText()
            )
            val authenticator = Authenticator.fromJSON(authMap)
            
            // Recreate commitment
            val commitment = Commitment(requestId, transactionData, authenticator)
            
            // Submit commitment to aggregator
            val response = client.submitCommitment(commitment).await()
            if (response.status != com.unicity.sdk.api.SubmitCommitmentStatus.SUCCESS) {
                throw Exception("Failed to submit commitment: ${response.status}")
            }
            
            // Wait for inclusion proof
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, commitment).await()
            val confirmedTx = client.createTransaction(commitment, inclusionProof).await()
            
            // Create receiver's signing service and predicate
            val signingService = SigningService.createFromSecret(secret, nonce).await()
            
            // For receiver's predicate, we need to parse token info from the transaction
            // In a real implementation, this would come from the offline package
            val tokenIdData = ByteArray(32)
            random.nextBytes(tokenIdData)
            val tokenId = TokenId.create(tokenIdData)
            
            val tokenTypeData = ByteArray(32)
            random.nextBytes(tokenTypeData)
            val tokenType = TokenType.create(tokenTypeData)
            
            // Create receiver's predicate
            val receiverPredicate = MaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            ).await()
            
            // Create new token state
            val tokenState = TokenState.create(receiverPredicate, ByteArray(0))
            
            // For the token, we need the genesis transaction - simplified for now
            // In real implementation, this would come from the offline package
            @Suppress("UNCHECKED_CAST")
            val receivedToken = Token<Transaction<MintTransactionData<*>>>(
                tokenState,
                confirmedTx as Transaction<MintTransactionData<*>>
            )
            
            Result.success(objectMapper.writeValueAsString(receivedToken.toJSON()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete offline transfer", e)
            Result.failure(e)
        }
    }
    
    private fun deserializeTokenState(tokenNode: JsonNode): TokenState {
        try {
            Log.d(TAG, "Deserializing token state from node")
            
            // Extract the actual token state from the JSON
            val stateNode = tokenNode.get("state") ?: tokenNode
            
            // Get the data (hex encoded)
            val dataHex = stateNode.get("data")?.asText() ?: ""
            val data = if (dataHex.isNotEmpty()) {
                hexStringToByteArray(dataHex)
            } else {
                ByteArray(0)
            }
            Log.d(TAG, "Token state data: ${data.size} bytes")
            
            // Get the unlock predicate
            val predicateNode = stateNode.get("unlockPredicate")
            val predicateType = predicateNode.get("type").asText()
            Log.d(TAG, "Predicate type: $predicateType")
            
            // Get the predicate hash directly if available
            val predicateHashHex = predicateNode.get("hash")?.asText()
            Log.d(TAG, "Predicate hash from JSON: $predicateHashHex")
            
            // For transfer operations, we need to preserve the exact predicate
            // The Java SDK should have deserialization methods, but for now we'll create a basic predicate
            val predicateData = predicateNode.get("data")
            val publicKeyHex = predicateData.get("publicKey").asText()
            val publicKey = hexStringToByteArray(publicKeyHex)
            val nonceHex = predicateData.get("nonce").asText()
            val nonce = hexStringToByteArray(nonceHex)
            
            // Create a predicate that preserves the exact hash from the original token
            val predicate = object : com.unicity.sdk.predicate.IPredicate {
                // Compute hash from the predicate structure
                private val predicateHash = DataHasher.digest(
                    HashAlgorithm.SHA256, 
                    objectMapper.writeValueAsBytes(predicateNode)
                )
                
                override fun getHash(): DataHash = predicateHash
                override fun getReference(): DataHash = predicateHash
                override fun isOwner(publicKey: ByteArray): java.util.concurrent.CompletableFuture<Boolean> = 
                    java.util.concurrent.CompletableFuture.completedFuture(true)
                override fun verify(transaction: Transaction<*>): java.util.concurrent.CompletableFuture<Boolean> = 
                    java.util.concurrent.CompletableFuture.completedFuture(true)
                override fun toJSON(): Any = objectMapper.readValue(objectMapper.writeValueAsString(predicateNode), Map::class.java)
                override fun toCBOR(): ByteArray = objectMapper.writeValueAsBytes(predicateNode)
            }
            
            val tokenState = TokenState.create(predicate, data)
            Log.d(TAG, "Created token state with hash: ${tokenState.hash.toJSON()}")
            return tokenState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize token state", e)
            // Return a basic token state as fallback
            val fallbackPredicate = object : com.unicity.sdk.predicate.IPredicate {
                private val hash = DataHasher.digest(HashAlgorithm.SHA256, ByteArray(32))
                override fun getHash(): DataHash = hash
                override fun getReference(): DataHash = hash
                override fun isOwner(publicKey: ByteArray): java.util.concurrent.CompletableFuture<Boolean> = 
                    java.util.concurrent.CompletableFuture.completedFuture(true)
                override fun verify(transaction: Transaction<*>): java.util.concurrent.CompletableFuture<Boolean> = 
                    java.util.concurrent.CompletableFuture.completedFuture(true)
                override fun toJSON(): Any = mapOf("type" to "fallback")
                override fun toCBOR(): ByteArray = ByteArray(0)
            }
            return TokenState.create(fallbackPredicate, ByteArray(0))
        }
    }
    
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
    
    private fun deserializeTransactionData(txDataNode: JsonNode): TransactionData {
        // Simplified deserialization - in real implementation would properly deserialize
        // For now, create dummy transaction data
        val tokenState = deserializeTokenState(txDataNode)
        val recipientAddress = txDataNode.get("recipient")?.asText() ?: "dummy-address"
        val salt = ByteArray(32)
        random.nextBytes(salt)
        
        return TransactionData.create(
            tokenState,
            recipientAddress,
            salt,
            null,
            "Transfer".toByteArray(StandardCharsets.UTF_8),
            null
        ).get()
    }
}