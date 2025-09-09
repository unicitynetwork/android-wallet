package com.unicity.nfcwalletdemo.di

import com.unicity.nfcwalletdemo.utils.WalletConstants
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.StateTransitionClient

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
}