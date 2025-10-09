package org.unicitylabs.wallet.transfer

import android.util.Log
import kotlinx.coroutines.future.await
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.Address
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicate
import org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenState
import org.unicitylabs.sdk.token.fungible.CoinId
import org.unicitylabs.sdk.token.fungible.TokenCoinData
import org.unicitylabs.sdk.transaction.split.TokenSplitBuilder
import org.unicitylabs.sdk.util.InclusionProofUtils
import org.unicitylabs.wallet.data.repository.WalletRepository
import java.math.BigInteger
import java.util.UUID

/**
 * Executes token split operations using the Unicity SDK
 */
class TokenSplitExecutor(
    private val client: StateTransitionClient,
    private val trustBase: RootTrustBase,
    private val walletRepository: WalletRepository
) {

    companion object {
        private const val TAG = "TokenSplitExecutor"
    }

    data class SplitExecutionResult(
        val tokensForRecipient: List<Token<*>>,   // Tokens to send to recipient
        val tokensKeptBySender: List<Token<*>>,   // New tokens kept by sender (from splits)
        val burnedTokens: List<Token<*>>          // Original tokens that were burned
    )

    /**
     * Executes a token split plan
     *
     * @param plan The split plan to execute
     * @param recipientAddress Address of the recipient (can be ProxyAddress with nametag)
     * @param signingService Signing service for the sender's wallet
     * @param secret The wallet secret (needed for masked predicates)
     * @return Result containing the new tokens created from the split
     */
    suspend fun executeSplitPlan(
        plan: TokenSplitCalculator.SplitPlan,
        recipientAddress: Address,
        signingService: SigningService,
        secret: ByteArray
    ): SplitExecutionResult {
        Log.d(TAG, "Executing split plan: ${plan.describe()}")

        val tokensForRecipient = mutableListOf<Token<*>>()
        val tokensKeptBySender = mutableListOf<Token<*>>()
        val burnedTokens = mutableListOf<Token<*>>()

        // Add directly transferable tokens
        tokensForRecipient.addAll(plan.tokensToTransferDirectly)

        // Execute split if required
        if (plan.requiresSplit && plan.tokenToSplit != null) {
            val splitResult = executeSingleTokenSplit(
                tokenToSplit = plan.tokenToSplit,
                splitAmount = plan.splitAmount!!,
                remainderAmount = plan.remainderAmount!!,
                coinId = plan.coinId,
                recipientAddress = recipientAddress,
                signingService = signingService,
                secret = secret
            )

            tokensForRecipient.add(splitResult.tokenForRecipient)
            tokensKeptBySender.add(splitResult.tokenForSender)
            burnedTokens.add(plan.tokenToSplit)
        }

        return SplitExecutionResult(
            tokensForRecipient = tokensForRecipient,
            tokensKeptBySender = tokensKeptBySender,
            burnedTokens = burnedTokens
        )
    }

    /**
     * Executes a single token split operation
     */
    private suspend fun executeSingleTokenSplit(
        tokenToSplit: Token<*>,
        splitAmount: BigInteger,
        remainderAmount: BigInteger,
        coinId: CoinId,
        recipientAddress: Address,
        signingService: SigningService,
        secret: ByteArray
    ): SplitTokenResult {
        Log.d(TAG, "Splitting token ${tokenToSplit.id.toHexString().take(8)}...")
        Log.d(TAG, "Split amounts: $splitAmount (recipient), $remainderAmount (sender)")

        // Create the token split builder
        val builder = TokenSplitBuilder()

        // Generate deterministic token IDs and salts (for retry safety)
        // Use token ID + amounts to ensure same split always generates same IDs
        val seedString = "${tokenToSplit.id.toHexString()}_${splitAmount}_${remainderAmount}"
        val seed = java.security.MessageDigest.getInstance("SHA-256").digest(seedString.toByteArray())

        val recipientTokenId = TokenId(seed.copyOfRange(0, 32))
        val senderTokenId = TokenId(java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_sender").toByteArray())
            .copyOfRange(0, 32))

        val recipientSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_recipient_salt").toByteArray())
        val senderSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_sender_salt").toByteArray())

        // Create token for recipient
        builder.createToken(
            recipientTokenId,
            tokenToSplit.type,
            null, // No additional data
            TokenCoinData(mapOf(coinId to splitAmount)),
            recipientAddress,
            recipientSalt,
            null // No recipient data hash
        )

        // Create token for sender (remainder)
        val senderAddress = UnmaskedPredicateReference.create(
            tokenToSplit.type,
            signingService,
            HashAlgorithm.SHA256
        ).toAddress()

        builder.createToken(
            senderTokenId,
            tokenToSplit.type,
            null, // No additional data
            TokenCoinData(mapOf(coinId to remainderAmount)),
            senderAddress,
            senderSalt,
            null // No recipient data hash
        )

        // Build the split
        val split = builder.build(tokenToSplit)

        // Step 1: Create and submit burn commitment
        // Use deterministic burn salt for retry safety
        val burnSalt = java.security.MessageDigest.getInstance("SHA-256")
            .digest((seedString + "_burn_salt").toByteArray())

        // Extract nonce if token has MaskedPredicate
        val tokenSigningService = if (tokenToSplit.state.predicate is MaskedPredicate) {
            val maskedPredicate = tokenToSplit.state.predicate as MaskedPredicate
            // Use the secret with the nonce for masked predicate
            SigningService.createFromMaskedSecret(secret, maskedPredicate.nonce)
        } else {
            signingService
        }

        val burnCommitment = split.createBurnCommitment(burnSalt, tokenSigningService)

        Log.d(TAG, "Submitting burn commitment...")
        val burnResponse = client.submitCommitment(burnCommitment).await()

        // Handle already-burned tokens (resilience for retries)
        val burnAlreadyExists = burnResponse.status == SubmitCommitmentStatus.REQUEST_ID_EXISTS
        if (burnAlreadyExists) {
            Log.w(TAG, "Token already burned (REQUEST_ID_EXISTS) - attempting recovery...")
        } else if (burnResponse.status != SubmitCommitmentStatus.SUCCESS) {
            throw Exception("Failed to burn token: ${burnResponse.status}")
        } else {
            Log.d(TAG, "Token burned successfully")
        }

        // Wait for inclusion proof (works even if already burned)
        val burnInclusionProof = InclusionProofUtils.waitInclusionProof(
            client,
            trustBase,
            burnCommitment
        ).await()

        val burnTransaction = burnCommitment.toTransaction(burnInclusionProof)
        Log.d(TAG, "Got burn transaction with proof")

        // Step 2: Create and submit mint commitments for new tokens
        val mintCommitments = split.createSplitMintCommitments(trustBase, burnTransaction)

        Log.d(TAG, "Submitting ${mintCommitments.size} mint commitments...")
        val mintedTokens = mutableListOf<MintedTokenInfo>()

        // Store recipient address string for comparison
        val recipientAddressString = recipientAddress.address

        for ((index, mintCommitment) in mintCommitments.withIndex()) {
            val response = client.submitCommitment(mintCommitment).await()

            // Handle already-minted tokens (resilience for retries)
            val mintAlreadyExists = response.status == SubmitCommitmentStatus.REQUEST_ID_EXISTS
            if (mintAlreadyExists) {
                Log.w(TAG, "Split token $index already minted (REQUEST_ID_EXISTS) - recovering...")
            } else if (response.status != SubmitCommitmentStatus.SUCCESS) {
                throw Exception("Failed to mint split token $index: ${response.status}")
            } else {
                Log.d(TAG, "Split token $index minted successfully")
            }

            // Wait for inclusion proof (works even if already minted)
            val inclusionProof = InclusionProofUtils.waitInclusionProof(
                client,
                trustBase,
                mintCommitment
            ).await()

            // Determine if this is for recipient or sender based on address
            val isForRecipient = mintCommitment.transactionData.recipient.address == recipientAddressString

            mintedTokens.add(
                MintedTokenInfo(
                    commitment = mintCommitment,
                    inclusionProof = inclusionProof,
                    isForRecipient = isForRecipient,
                    tokenId = mintCommitment.transactionData.tokenId,
                    salt = mintCommitment.transactionData.salt
                )
            )
        }

        Log.d(TAG, "All split tokens minted successfully")

        // Create Token objects for the minted tokens
        val recipientTokenInfo = mintedTokens.find { it.isForRecipient }
            ?: throw Exception("Recipient token not found in minted tokens")
        val senderTokenInfo = mintedTokens.find { !it.isForRecipient }
            ?: throw Exception("Sender token not found in minted tokens")

        // Get sender's nametag token for verification
        val senderNametagToken = getSenderNametagToken()

        // For recipient token: Create without verification (they'll verify when they receive it)
        val recipientToken = createSplitTokenDirect(recipientTokenInfo, signingService)

        // For sender token: Create WITH verification using our nametag token
        val senderToken = if (senderNametagToken != null) {
            createSplitTokenWithVerification(senderTokenInfo, signingService, senderNametagToken)
        } else {
            Log.w(TAG, "No sender nametag token found - creating without verification")
            createSplitTokenDirect(senderTokenInfo, signingService)
        }

        return SplitTokenResult(
            tokenForRecipient = recipientToken,
            tokenForSender = senderToken
        )
    }

    /**
     * Gets sender's nametag token from wallet
     */
    private fun getSenderNametagToken(): Token<*>? {
        return try {
            val nametagTokens = walletRepository.tokens.value.filter { it.type == "NAMETAG" }
            Log.d(TAG, "Found ${nametagTokens.size} nametag tokens in wallet")

            nametagTokens.firstOrNull()?.jsonData?.let { jsonData ->
                org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON.readValue(
                    jsonData,
                    org.unicitylabs.sdk.token.Token::class.java
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sender nametag token: ${e.message}")
            null
        }
    }

    /**
     * Creates a split token WITH SDK verification (for sender's own tokens)
     */
    private fun createSplitTokenWithVerification(
        mintInfo: MintedTokenInfo,
        signingService: SigningService,
        nametagToken: Token<*>
    ): Token<*> {
        val state = TokenState(
            UnmaskedPredicate.create(
                mintInfo.commitment.transactionData.tokenId,
                mintInfo.commitment.transactionData.tokenType,
                signingService,
                HashAlgorithm.SHA256,
                mintInfo.commitment.transactionData.salt
            ),
            null
        )

        return Token.create(
            trustBase,
            state,
            mintInfo.commitment.toTransaction(mintInfo.inclusionProof),
            listOf(nametagToken)
        )
    }

    /**
     * Creates a split token directly without verification (for recipient's tokens)
     * Tokens are already valid on-chain - recipient will verify when they receive it
     */
    private fun createSplitTokenDirect(
        mintInfo: MintedTokenInfo,
        signingService: SigningService
    ): Token<*> {
        val state = TokenState(
            UnmaskedPredicate.create(
                mintInfo.commitment.transactionData.tokenId,
                mintInfo.commitment.transactionData.tokenType,
                signingService,
                HashAlgorithm.SHA256,
                mintInfo.commitment.transactionData.salt
            ),
            null
        )

        val token = Token(
            state,
            mintInfo.commitment.toTransaction(mintInfo.inclusionProof),
            emptyList(),
            emptyList()
        )

        Log.d(TAG, "Split token created without verification: ${mintInfo.tokenId.toHexString().take(8)}...")
        return token
    }

    /**
     * Helper class to track minted token information
     */
    private data class MintedTokenInfo(
        val commitment: org.unicitylabs.sdk.transaction.MintCommitment<*>,
        val inclusionProof: org.unicitylabs.sdk.transaction.InclusionProof,
        val isForRecipient: Boolean,
        val tokenId: TokenId,
        val salt: ByteArray
    )

    /**
     * Result of a single token split
     */
    data class SplitTokenResult(
        val tokenForRecipient: Token<*>,
        val tokenForSender: Token<*>
    )
}