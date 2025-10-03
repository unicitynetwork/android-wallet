package org.unicitylabs.wallet.token

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.InputStream

/**
 * Data class representing a token or coin definition in the registry
 */
data class TokenDefinition(
    val network: String,
    val assetKind: String, // "fungible" or "non-fungible"
    val name: String,
    val symbol: String? = null,
    val description: String,
    val icon: String? = null,
    val id: String // Hex string: tokenType for NFTs, coinId for fungible
)

/**
 * Registry for Unicity token types and coin IDs
 * Loads metadata from bundled unicity-ids.testnet.json
 */
class UnicityTokenRegistry private constructor(context: Context) {

    companion object {
        private const val REGISTRY_FILE = "unicity-ids.testnet.json"

        @Volatile
        private var INSTANCE: UnicityTokenRegistry? = null

        fun getInstance(context: Context): UnicityTokenRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnicityTokenRegistry(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val tokenDefinitions: List<TokenDefinition>
    private val definitionsById: Map<String, TokenDefinition>

    init {
        val mapper = jacksonObjectMapper()
        val inputStream: InputStream = context.assets.open(REGISTRY_FILE)
        tokenDefinitions = mapper.readValue(inputStream)

        // Index by ID for fast lookup
        definitionsById = tokenDefinitions.associateBy { it.id }
    }

    /**
     * Get token type definition by its hex ID
     */
    fun getTokenType(tokenTypeHex: String): TokenDefinition? {
        return definitionsById[tokenTypeHex]
    }

    /**
     * Get coin definition by its hex ID
     */
    fun getCoinDefinition(coinIdHex: String): TokenDefinition? {
        return definitionsById[coinIdHex]
    }

    /**
     * Get all fungible token definitions
     */
    fun getFungibleTokens(): List<TokenDefinition> {
        return tokenDefinitions.filter { it.assetKind == "fungible" }
    }

    /**
     * Get all non-fungible token definitions
     */
    fun getNonFungibleTokens(): List<TokenDefinition> {
        return tokenDefinitions.filter { it.assetKind == "non-fungible" }
    }

    /**
     * Get all token definitions
     */
    fun getAllDefinitions(): List<TokenDefinition> {
        return tokenDefinitions
    }
}
