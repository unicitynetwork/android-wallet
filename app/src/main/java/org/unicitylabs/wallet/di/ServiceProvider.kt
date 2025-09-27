package org.unicitylabs.wallet.di

import org.unicitylabs.wallet.utils.WalletConstants
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.signing.SigningService

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
     * Creates a RootTrustBase for token verification.
     * In production, this should be fetched from the network or stored securely.
     * For now, we'll use a test trust base.
     */
    fun getRootTrustBase(): RootTrustBase {
        // For development/testing - in production this should come from the network
        // or be stored securely with the app
        val testSigningService = SigningService(SigningService.generatePrivateKey())
        return RootTrustBase(
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
    }
}