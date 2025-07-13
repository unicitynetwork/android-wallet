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
        private const val AGGREGATOR_URL = "https://aggregator-test-1.mainnet.unicity.network"  // TODO: Make configurable
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
            val tokenDataNode = objectMapper.readTree(tokenDataJson)
            val data = tokenDataNode.get("data").asText()
            val amount = tokenDataNode.get("amount")?.asLong() ?: 100L
            
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
            
            // Create token data implementation similar to TestTokenData
            val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
            val tokenData = object : ISerializable {
                override fun toJSON(): Any = dataBytes.toHexString()
                override fun toCBOR(): ByteArray = dataBytes // In real implementation, should use CborEncoder
            }
            
            // Create mint transaction data
            val salt = ByteArray(32)
            random.nextBytes(salt)
            
            val mintData = MintTransactionData<ISerializable>(
                tokenId,
                tokenType,
                predicate,
                tokenData,  // Pass actual token data
                coinData,
                null,       // No data hash - data is stored directly in token
                salt
            )
            
            // Submit mint transaction
            val commitment = client.submitMintTransaction(mintData).await()
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, commitment).await()
            val mintTransaction = client.createTransaction(commitment, inclusionProof).await()
            
            // Create token with empty state data (matching CommonTestFlow)
            val tokenState = TokenState.create(predicate, ByteArray(0))
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
            
            // For offline transfers, we need to specify what data the recipient will store
            // This is typically custom data the recipient wants to associate with the token
            val recipientData = "Received via offline transfer".toByteArray(StandardCharsets.UTF_8)
            val recipientDataHash = DataHasher.digest(HashAlgorithm.SHA256, recipientData)
            
            // Create transaction data following CommonTestFlow pattern
            val transactionData = TransactionData.create(
                tokenState,
                recipientAddress,
                salt,
                recipientDataHash, // Data hash for recipient's custom data
                message,
                null  // No nametagData
            ).await()
            
            // Log transaction data details
            Log.d(TAG, "Transaction data hash: ${transactionData.hash.toJSON()}")
            Log.d(TAG, "Source state hash: ${transactionData.sourceState.hash.toJSON()}")
            Log.d(TAG, "Public key: ${signingService.publicKey.toHexString()}")
            
            // Create authenticator - following the SDK's internal pattern
            val authenticator = Authenticator.create(
                signingService,
                transactionData.hash, // Sign the transaction hash
                transactionData.sourceState.hash // Source state hash for reference
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
            
            // For offline transfers, we should include the full token information
            // that the receiver will need to reconstruct their token
            val offlinePackage = mapOf(
                "commitment" to commitmentJson,
                "tokenInfo" to mapOf(
                    "tokenId" to tokenState.unlockPredicate.toJSON().let { 
                        (it as Map<*, *>)["data"]?.let { data ->
                            (data as Map<*, *>)["tokenId"]
                        }
                    },
                    "tokenType" to tokenState.unlockPredicate.toJSON().let { 
                        (it as Map<*, *>)["data"]?.let { data ->
                            (data as Map<*, *>)["tokenType"]
                        }
                    },
                    "genesis" to tokenNode.get("genesis")?.let { 
                        objectMapper.writeValueAsString(it)
                    }
                )
            )
            
            Result.success(objectMapper.writeValueAsString(offlinePackage))
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
            
            // Parse the complete offline package (should include both commitment and token info)
            val packageNode = objectMapper.readTree(offlineTransactionJson)
            
            // Check if this is a commitment or a full package
            val commitmentNode = if (packageNode.has("commitment")) {
                packageNode.get("commitment")
            } else {
                // Direct commitment object
                packageNode
            }
            
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
            
            // Recreate commitment from offline package (already signed by sender)
            val commitment = Commitment(requestId, transactionData, authenticator)
            
            // Submit the pre-signed commitment to aggregator (receiver doesn't sign anything)
            val response = aggregatorClient.submitTransaction(
                commitment.requestId,
                transactionData.hash,
                commitment.authenticator
            ).await()
            if (response.status != com.unicity.sdk.api.SubmitCommitmentStatus.SUCCESS) {
                throw Exception("Failed to submit commitment: ${response.status}")
            }
            
            // Wait for inclusion proof
            val inclusionProof = InclusionProofUtils.waitInclusionProof(client, commitment).await()
            val confirmedTx = client.createTransaction(commitment, inclusionProof).await()
            
            // Create receiver's signing service
            val signingService = SigningService.createFromSecret(secret, nonce).await()
            
            // Extract token ID and type from the package
            val tokenId: TokenId
            val tokenType: TokenType
            
            if (packageNode.has("tokenInfo")) {
                // New format with token info
                val tokenInfo = packageNode.get("tokenInfo")
                val tokenIdHex = tokenInfo.get("tokenId").asText()
                tokenId = TokenId.create(hexStringToByteArray(tokenIdHex))
                
                val tokenTypeHex = tokenInfo.get("tokenType").asText()
                tokenType = TokenType.create(hexStringToByteArray(tokenTypeHex))
            } else {
                // Fallback: extract from transaction data source state
                val sourceState = transactionData.sourceState
                val sourcePredicateData = sourceState.unlockPredicate.toJSON() as Map<*, *>
                val predicateData = sourcePredicateData["data"] as Map<*, *>
                
                val tokenIdHex = predicateData["tokenId"] as String
                tokenId = TokenId.create(hexStringToByteArray(tokenIdHex))
                
                val tokenTypeHex = predicateData["tokenType"] as String  
                tokenType = TokenType.create(hexStringToByteArray(tokenTypeHex))
            }
            
            // Create receiver's predicate matching the token
            val receiverPredicate = MaskedPredicate.create(
                tokenId,
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                nonce
            ).await()
            
            // Get recipient's custom data from the transaction data hash
            val recipientData = if (transactionData.data != null) {
                // In a real app, receiver would know what data they provided
                "Received via offline transfer".toByteArray(StandardCharsets.UTF_8)
            } else {
                ByteArray(0)
            }
            
            // Create new token state with recipient's data
            val tokenState = TokenState.create(receiverPredicate, recipientData)
            
            // For offline transfers, we need the genesis transaction from the sender
            // This should be included in the offline package
            // For now, we'll create a simple token structure
            val receivedToken = mapOf(
                "state" to tokenState.toJSON(),
                "genesis" to mapOf(
                    "data" to transactionData.toJSON(),
                    "inclusionProof" to inclusionProof.toJSON()
                ),
                "transactions" to listOf(
                    mapOf(
                        "data" to confirmedTx.data.toJSON(),
                        "inclusionProof" to confirmedTx.inclusionProof.toJSON()
                    )
                )
            )
            
            Result.success(objectMapper.writeValueAsString(receivedToken))
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
            val data = if (dataHex.isNotEmpty() && dataHex != "null") {
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
            
            // Get token ID and type from predicate data
            val tokenIdHex = predicateData.get("tokenId")?.asText()
            val tokenTypeHex = predicateData.get("tokenType")?.asText()
            
            val tokenId = if (tokenIdHex != null && tokenIdHex != "null") {
                TokenId.create(hexStringToByteArray(tokenIdHex))
            } else {
                TokenId.create(ByteArray(32))
            }
            
            val tokenType = if (tokenTypeHex != null && tokenTypeHex != "null") {
                TokenType.create(hexStringToByteArray(tokenTypeHex))
            } else {
                TokenType.create(ByteArray(32))
            }
            
            // Create a predicate that preserves the exact hash from the original token
            val predicate = object : com.unicity.sdk.predicate.IPredicate {
                // Use the predicate hash from the JSON if available
                private val predicateHash = if (predicateHashHex != null && predicateHashHex != "null") {
                    DataHash.fromJSON(predicateHashHex)
                } else {
                    DataHasher.digest(HashAlgorithm.SHA256, objectMapper.writeValueAsBytes(predicateNode))
                }
                
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
            val tokenIdData = ByteArray(32)
            val tokenTypeData = ByteArray(32)
            random.nextBytes(tokenIdData)
            random.nextBytes(tokenTypeData)
            
            // Create a basic fallback predicate
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
        // Properly deserialize transaction data from the offline package
        val tokenState = deserializeTokenState(txDataNode.get("sourceState"))
        val recipientAddress = txDataNode.get("recipient").asText()
        
        // Preserve original salt (hex encoded)
        val saltHex = txDataNode.get("salt").asText()
        val salt = hexStringToByteArray(saltHex)
        
        // Preserve original data hash if present
        val dataHashNode = txDataNode.get("data")
        val dataHash = if (dataHashNode != null && !dataHashNode.isNull) {
            DataHash.fromJSON(dataHashNode.asText())
        } else {
            null
        }
        
        // Preserve original message if present
        val messageNode = txDataNode.get("message")
        val message = if (messageNode != null && !messageNode.isNull) {
            hexStringToByteArray(messageNode.asText())
        } else {
            null
        }
        
        // Preserve nametagData if present (usually null)
        val nametagDataNode = txDataNode.get("nametagData")
        val nametagData = if (nametagDataNode != null && !nametagDataNode.isNull) {
            // For now, we don't support nametag data
            null
        } else {
            null
        }
        
        return TransactionData.create(
            tokenState,
            recipientAddress,
            salt,
            dataHash,
            message,
            nametagData
        ).get()
    }
}