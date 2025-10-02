package org.unicitylabs.wallet.di

import android.content.Context
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.wallet.utils.WalletConstants
import java.io.InputStream

/**
 * Simple dependency injection provider for SDK services.
 * Provides singleton instances of commonly used services.
 */
object ServiceProvider {

    /**
     * Singleton instance of AggregatorClient
     */
    val aggregatorClient: AggregatorClient by lazy {
        AggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL)
    }

    /**
     * Singleton instance of StateTransitionClient
     */
    val stateTransitionClient: StateTransitionClient by lazy {
        StateTransitionClient(aggregatorClient)
    }

    /**
     * Creates a new AggregatorClient instance with a custom URL
     * Used for testing or connecting to different environments
     */
    fun createAggregatorClient(url: String): AggregatorClient {
        return AggregatorClient(url)
    }

    /**
     * Creates a new StateTransitionClient with a custom AggregatorClient
     */
    fun createStateTransitionClient(aggregatorClient: AggregatorClient): StateTransitionClient {
        return StateTransitionClient(aggregatorClient)
    }

    /**
     * Cached trustbase instance
     */
    private var cachedTrustBase: RootTrustBase? = null

    /**
     * Application context for loading assets
     */
    private var applicationContext: Context? = null

    /**
     * Initialize the ServiceProvider with application context
     */
    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Creates a RootTrustBase for token verification.
     * Loads from trustbase-testnet.json if available, otherwise creates a test trustbase.
     */
    fun getRootTrustBase(): RootTrustBase {
        // Return cached trustbase if available
        cachedTrustBase?.let { return it }

        // Try to load from assets if context is available
        applicationContext?.let { context ->
            try {
                val inputStream: InputStream = context.assets.open("trustbase-testnet.json")
                val json = inputStream.bufferedReader().use { it.readText() }
                val trustBase = UnicityObjectMapper.JSON.readValue(json, RootTrustBase::class.java)
                cachedTrustBase = trustBase
                return trustBase
            } catch (e: Exception) {
                // Log error but continue to fallback
                e.printStackTrace()
            }
        }

        // Fallback to test trust base for unit tests or if file not found
        val testSigningService = SigningService(SigningService.generatePrivateKey())
        val testTrustBase = RootTrustBase(
            0,
            0,
            0,
            0,
            setOf(
                RootTrustBase.NodeInfo(
                    "NODE",
                    testSigningService.publicKey,
                    1
                )
            ),
            1,
            ByteArray(0),
            ByteArray(0),
            null,
            emptyMap()
        )
        cachedTrustBase = testTrustBase
        return testTrustBase
    }
}