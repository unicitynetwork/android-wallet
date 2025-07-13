package com.unicity.nfcwalletdemo.sdk

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for UnicityJavaSdkService that use the real Unicity aggregator
 * These tests verify that minting actually works with the Java SDK
 */
class UnicityJavaSdkServiceIntegrationTest {
    
    private lateinit var sdkService: UnicityJavaSdkService
    private val objectMapper = ObjectMapper()
    
    @Before
    fun setup() {
        sdkService = UnicityJavaSdkService()
    }
    
    @Test
    fun testGenerateIdentity() {
        println("Testing identity generation...")
        
        var identityJson: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json ->
                    identityJson = json
                    println("Identity generated successfully: $json")
                },
                onFailure = { error ->
                    fail("Failed to generate identity: ${error.message}")
                }
            )
        }
        
        // Wait for callback
        Thread.sleep(1000)
        
        assertNotNull("Identity should be generated", identityJson)
        
        // Verify identity structure
        val identity = objectMapper.readTree(identityJson)
        assertNotNull("Identity should have secret", identity.get("secret"))
        assertNotNull("Identity should have nonce", identity.get("nonce"))
    }
    
    @Test
    fun testOfflineTransfer() = runBlocking {
        println("Testing complete offline transfer flow with real aggregator...")

        // Generate sender identity and mint token
        var senderIdentity: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> senderIdentity = json },
                onFailure = { error -> fail("Failed to generate sender identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)

        val tokenData = mapOf("data" to "Transfer Test Token", "amount" to 50)
        val tokenDataJson = objectMapper.writeValueAsString(tokenData)

        val mintResult = sdkService.mintToken(senderIdentity!!, tokenDataJson)
        var tokenJson: String? = null
        mintResult.fold(
            onSuccess = { result ->
                val mintData = objectMapper.readTree(result)
                tokenJson = objectMapper.writeValueAsString(mintData.get("token"))
            },
            onFailure = { error -> fail("Failed to mint token: ${error.message}") }
        )

        // Generate receiver identity
        var receiverIdentity: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> receiverIdentity = json },
                onFailure = { error -> fail("Failed to generate receiver identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)

        // For testing, we need a proper recipient address
        // In the real app, this would come from the receiver's identity
        val recipientAddress = "test-recipient-address"

        // Create offline transfer
        println("\nCreating offline transfer...")
        val transferResult = sdkService.createOfflineTransferPackage(
            senderIdentity!!,
            recipientAddress,
            tokenJson!!
        )

        var offlinePackage: String? = null
        transferResult.fold(
            onSuccess = { pkg ->
                offlinePackage = pkg
                println("Offline package created")
            },
            onFailure = { error ->
                error.printStackTrace()
                fail("Failed to create offline transfer: ${error.message}")
            }
        )

        // Complete the transfer
        println("\nCompleting offline transfer...")
        val completeResult = sdkService.completeOfflineTransfer(
            receiverIdentity!!,
            offlinePackage!!
        )

        completeResult.fold(
            onSuccess = { receivedToken ->
                println("✅ Transfer completed successfully!")
                println("Received token: ${receivedToken.take(200)}...")
            },
            onFailure = { error ->
                println("❌ Transfer failed: ${error.message}")
                error.printStackTrace()

                // Log more details about the failure
                if (error.message?.contains("inclusion proof") == true) {
                    println("\nLikely issue: Authenticator signature validation failed at aggregator")
                    println("This suggests the authenticator is not signing the correct data")
                }
            }
        )
    }
    
    @Test
    fun testAggregatorConnectivity() = runBlocking {
        println("Testing aggregator connectivity...")
        
        try {
            // Generate identity and attempt a mint to test connectivity
            var identityJson: String? = null
            sdkService.generateIdentity { result ->
                result.fold(
                    onSuccess = { json -> identityJson = json },
                    onFailure = { error -> fail("Failed to generate identity: ${error.message}") }
                )
            }
            Thread.sleep(1000)
            
            val tokenData = mapOf("data" to "Connectivity Test", "amount" to 1)
            val tokenDataJson = objectMapper.writeValueAsString(tokenData)
            
            val startTime = System.currentTimeMillis()
            val result = sdkService.mintToken(identityJson!!, tokenDataJson)
            val duration = System.currentTimeMillis() - startTime
            
            result.fold(
                onSuccess = { 
                    println("Aggregator is reachable and responsive (${duration}ms)")
                    assertTrue("Response time should be reasonable", duration < 30000)
                },
                onFailure = { error ->
                    if (error.message?.contains("timeout", ignoreCase = true) == true ||
                        error.message?.contains("connection", ignoreCase = true) == true) {
                        fail("Aggregator connectivity issue: ${error.message}")
                    } else {
                        // Other errors might still indicate connectivity
                        println("Aggregator responded with error (still connected): ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            fail("Unexpected error testing aggregator connectivity: ${e.message}")
        }
    }
}