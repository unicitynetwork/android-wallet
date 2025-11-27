package org.unicitylabs.wallet.di

import android.content.Context
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.wallet.utils.WalletConstants
import org.unicitylabs.wallet.util.HexUtils
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
        JsonRpcAggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL, WalletConstants.UNICITY_API_KEY)
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
        return JsonRpcAggregatorClient(url)
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
                val trustBase = RootTrustBase.fromJson(json)
                cachedTrustBase = trustBase
                return trustBase
            } catch (e: Exception) {
                // Log error but continue to fallback
                e.printStackTrace()
            }
        }

        // Try to load from test resources (for unit tests)
        try {
            val inputStream = javaClass.classLoader?.getResourceAsStream("trustbase-testnet.json")
            if (inputStream != null) {
                val json = inputStream.bufferedReader().use { it.readText() }
                val trustBase = RootTrustBase.fromJson(json)
                cachedTrustBase = trustBase
                return trustBase
            }
        } catch (e: Exception) {
            // Continue to fallback
            e.printStackTrace()
        }

        // Fallback to minimal test trust base if file not found
        // Create minimal test trustbase JSON matching the actual format
        val testSigningService = SigningService(SigningService.generatePrivateKey())
        val testSigKeyHex = HexUtils.encodeHexString(testSigningService.publicKey)
        val testTrustBaseJson = """
        {
            "version": 1,
            "networkId": 0,
            "epoch": 1,
            "epochStartRound": 1,
            "rootNodes": [{
                "nodeId": "TEST_NODE",
                "sigKey": "0x${testSigKeyHex}",
                "stake": 1
            }],
            "quorumThreshold": 1,
            "stateHash": "",
            "changeRecordHash": "",
            "previousEntryHash": "",
            "signatures": {}
        }
        """.trimIndent()

        val testTrustBase = RootTrustBase.fromJson(testTrustBaseJson)
        cachedTrustBase = testTrustBase
        return testTrustBase
    }
}