package org.unicitylabs.wallet.transfer

import android.util.Log
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenType
import org.unicitylabs.sdk.token.fungible.CoinId
import org.unicitylabs.sdk.token.fungible.TokenCoinData
import java.math.BigInteger
import java.util.UUID

/**
 * Calculates optimal token selection and splitting strategy to minimize the number of splits
 * required to transfer a specific amount.
 */
class TokenSplitCalculator {

    companion object {
        private const val TAG = "TokenSplitCalculator"
    }

    /**
     * Represents a token split plan
     */
    data class SplitPlan(
        val tokensToTransferDirectly: List<Token<*>>,    // Tokens to send as-is
        val tokenToSplit: Token<*>? = null,               // Token that needs splitting
        val splitAmount: BigInteger? = null,           // Amount to split off for transfer
        val remainderAmount: BigInteger? = null,       // Amount that stays with sender
        val totalTransferAmount: BigInteger,           // Total amount being transferred
        val coinId: CoinId                             // The coin type being transferred
    ) {
        val requiresSplit: Boolean = tokenToSplit != null

        fun describe(): String {
            val builder = StringBuilder()
            builder.append("Transfer Plan:\n")
            builder.append("- Total amount to transfer: $totalTransferAmount\n")

            if (tokensToTransferDirectly.isNotEmpty()) {
                builder.append("- Tokens to transfer directly: ${tokensToTransferDirectly.size}\n")
                tokensToTransferDirectly.forEach { token ->
                    val amount = token.getCoins().map { it.coins[coinId] }.orElse(null) ?: BigInteger.ZERO
                    builder.append("  * Token ${token.id.toHexString().take(8)}...: $amount\n")
                }
            }

            if (requiresSplit) {
                builder.append("- Token to split: ${tokenToSplit?.id?.toHexString()?.take(8)}...\n")
                builder.append("  * Split into: $splitAmount (transfer) + $remainderAmount (keep)\n")
            }

            return builder.toString()
        }
    }

    /**
     * Calculates the optimal token selection and splitting strategy
     *
     * @param availableTokens List of available tokens with the target coin
     * @param targetAmount Amount to transfer
     * @param coinId The coin type to transfer
     * @return SplitPlan describing the optimal strategy, or null if transfer is not possible
     */
    fun calculateOptimalSplit(
        availableTokens: List<Token<*>>,
        targetAmount: BigInteger,
        coinId: CoinId
    ): SplitPlan? {

        Log.d(TAG, "=== calculateOptimalSplit ===")
        Log.d(TAG, "Target amount: $targetAmount")
        Log.d(TAG, "CoinId: ${coinId.toString()}")
        Log.d(TAG, "Available tokens count: ${availableTokens.size}")

        if (targetAmount <= BigInteger.ZERO) {
            Log.e(TAG, "Invalid target amount: $targetAmount")
            return null
        }

        // Extract tokens with the specified coin and their amounts
        val tokenAmounts = availableTokens.mapNotNull { token ->
            val coins = token.getCoins()
            Log.d(TAG, "Checking token ${token.id.toHexString().take(8)}... - has coins: ${coins.isPresent}")

            if (coins.isPresent) {
                val coinData = coins.get()
                Log.d(TAG, "Token coins: ${coinData.coins.keys.map { it.toString() }}")

                val amount = coinData.coins[coinId]
                Log.d(TAG, "Amount for our coinId: $amount")

                if (amount != null && amount > BigInteger.ZERO) {
                    TokenWithAmount(token, amount)
                } else {
                    Log.d(TAG, "Token doesn't have our coin or amount is zero")
                    null
                }
            } else {
                Log.d(TAG, "Token has no coins at all")
                null
            }
        }.sortedBy { it.amount } // Sort by amount ascending

        Log.d(TAG, "Found ${tokenAmounts.size} tokens with the target coin")

        if (tokenAmounts.isEmpty()) {
            Log.e(TAG, "No tokens found with coin: $coinId")
            return null
        }

        // Calculate total available amount
        val totalAvailable = tokenAmounts.fold(BigInteger.ZERO) { acc, ta -> acc + ta.amount }
        if (totalAvailable < targetAmount) {
            Log.e(TAG, "Insufficient funds. Available: $totalAvailable, Required: $targetAmount")
            return null
        }

        // Strategy 1: Try to find exact match (no split needed)
        val exactMatch = tokenAmounts.find { it.amount == targetAmount }
        if (exactMatch != null) {
            Log.d(TAG, "Found exact match token")
            return SplitPlan(
                tokensToTransferDirectly = listOf(exactMatch.token),
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        }

        // Strategy 2: Try to find combination that sums to exact amount (no split needed)
        val exactCombination = findExactCombination(tokenAmounts, targetAmount)
        if (exactCombination != null) {
            Log.d(TAG, "Found exact combination of ${exactCombination.size} tokens")
            return SplitPlan(
                tokensToTransferDirectly = exactCombination.map { it.token },
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        }

        // Strategy 3: Find optimal split (minimize number of operations)
        return findOptimalSplitStrategy(tokenAmounts, targetAmount, coinId)
    }

    /**
     * Finds a combination of tokens that sum exactly to the target amount
     */
    private fun findExactCombination(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger
    ): List<TokenWithAmount>? {
        // Use dynamic programming for subset sum problem
        // For simplicity and performance, limit search to combinations of up to 5 tokens
        val maxCombinationSize = minOf(5, tokens.size)

        for (size in 1..maxCombinationSize) {
            val combination = findCombinationOfSize(tokens, targetAmount, size)
            if (combination != null) {
                return combination
            }
        }
        return null
    }

    /**
     * Finds combination of specific size that sums to target
     */
    private fun findCombinationOfSize(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger,
        size: Int
    ): List<TokenWithAmount>? {
        if (size == 1) {
            return tokens.find { it.amount == targetAmount }?.let { listOf(it) }
        }

        // Generate combinations recursively
        fun combinations(
            list: List<TokenWithAmount>,
            k: Int,
            start: Int = 0,
            current: List<TokenWithAmount> = emptyList()
        ): Sequence<List<TokenWithAmount>> = sequence {
            if (k == 0) {
                yield(current)
            } else {
                for (i in start until list.size) {
                    yieldAll(combinations(list, k - 1, i + 1, current + list[i]))
                }
            }
        }

        return combinations(tokens, size)
            .find { combo -> combo.fold(BigInteger.ZERO) { acc, ta -> acc + ta.amount } == targetAmount }
    }

    /**
     * Finds the optimal strategy when splitting is required
     */
    private fun findOptimalSplitStrategy(
        tokens: List<TokenWithAmount>,
        targetAmount: BigInteger,
        coinId: CoinId
    ): SplitPlan {
        // Find tokens less than target amount (can be transferred directly)
        val smallerTokens = tokens.filter { it.amount < targetAmount }
        val largerTokens = tokens.filter { it.amount > targetAmount }

        // Calculate how much we can transfer directly
        val directTransferAmount = smallerTokens.fold(BigInteger.ZERO) { acc, ta -> acc + ta.amount }
        val remainingNeeded = targetAmount - directTransferAmount

        // Find the best token to split (prefer smallest token that's large enough)
        val tokenToSplit = largerTokens.minByOrNull { it.amount }
            ?: tokens.maxByOrNull { it.amount }!! // Use largest if no larger tokens

        return if (remainingNeeded > BigInteger.ZERO && tokenToSplit.amount >= remainingNeeded) {
            // Split the selected token
            SplitPlan(
                tokensToTransferDirectly = smallerTokens.map { it.token },
                tokenToSplit = tokenToSplit.token,
                splitAmount = remainingNeeded,
                remainderAmount = tokenToSplit.amount - remainingNeeded,
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        } else {
            // Edge case: need to split without using smaller tokens
            val singleTokenToSplit = tokens.find { it.amount >= targetAmount }!!
            SplitPlan(
                tokensToTransferDirectly = emptyList(),
                tokenToSplit = singleTokenToSplit.token,
                splitAmount = targetAmount,
                remainderAmount = singleTokenToSplit.amount - targetAmount,
                totalTransferAmount = targetAmount,
                coinId = coinId
            )
        }
    }

    /**
     * Helper class to pair token with its amount for a specific coin
     */
    private data class TokenWithAmount(
        val token: Token<*>,
        val amount: BigInteger
    )
}

// Extension function to convert TokenId to hex string
fun TokenId.toHexString(): String {
    return this.bytes.joinToString("") { "%02x".format(it) }
}