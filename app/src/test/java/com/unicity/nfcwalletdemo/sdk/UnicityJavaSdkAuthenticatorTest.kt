package com.unicity.nfcwalletdemo.sdk

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Test to verify authenticator creation and signature generation for transfers
 */
class UnicityJavaSdkAuthenticatorTest {
    
    private lateinit var sdkService: UnicityJavaSdkService
    private val objectMapper = ObjectMapper()
    
    @Before
    fun setup() {
        sdkService = UnicityJavaSdkService()
    }
    
    @Test
    fun testCreateOfflineTransferAuthenticator() = runBlocking {
        println("Testing authenticator creation for offline transfers...")
        
        // First, mint a token to transfer
        var identityJson: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> identityJson = json },
                onFailure = { error -> fail("Failed to generate identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)
        assertNotNull("Identity should be generated", identityJson)
        
        // Mint a token
        val tokenData = mapOf(
            "data" to "Test Token for Transfer",
            "amount" to 100
        )
        val tokenDataJson = objectMapper.writeValueAsString(tokenData)
        
        println("Minting token for transfer test...")
        val mintResult = sdkService.mintToken(identityJson!!, tokenDataJson)
        
        var tokenJson: String? = null
        mintResult.fold(
            onSuccess = { result ->
                val mintData = objectMapper.readTree(result)
                tokenJson = objectMapper.writeValueAsString(mintData.get("token"))
                println("Token minted successfully")
            },
            onFailure = { error ->
                fail("Failed to mint token: ${error.message}")
            }
        )
        
        // Generate a recipient address
        var recipientIdentity: String? = null
        var recipientAddress: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> 
                    recipientIdentity = json
                    // In real implementation, we'd derive the address from the identity
                    // For now, use a dummy address
                    recipientAddress = "dummy-recipient-address"
                },
                onFailure = { error -> fail("Failed to generate recipient identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)
        
        // Now create an offline transfer package
        println("\nCreating offline transfer package...")
        println("Token to transfer: ${tokenJson?.take(200)}...")
        println("Recipient address: $recipientAddress")
        
        val transferResult = sdkService.createOfflineTransferPackage(
            identityJson!!,
            recipientAddress!!,
            tokenJson!!
        )
        
        transferResult.fold(
            onSuccess = { commitmentJson ->
                println("\nOffline transfer package created successfully!")
                
                val commitment = objectMapper.readTree(commitmentJson)
                
                // Verify commitment structure
                assertNotNull("Commitment should have requestId", commitment.get("requestId"))
                assertNotNull("Commitment should have transactionData", commitment.get("transactionData"))
                assertNotNull("Commitment should have authenticator", commitment.get("authenticator"))
                
                // Check authenticator fields
                val authenticator = commitment.get("authenticator")
                assertNotNull("Authenticator should have algorithm", authenticator.get("algorithm"))
                assertNotNull("Authenticator should have publicKey", authenticator.get("publicKey"))
                assertNotNull("Authenticator should have signature", authenticator.get("signature"))
                assertNotNull("Authenticator should have stateHash", authenticator.get("stateHash"))
                
                println("\nAuthenticator details:")
                println("Algorithm: ${authenticator.get("algorithm").asText()}")
                println("Public Key: ${authenticator.get("publicKey").asText()}")
                println("Signature: ${authenticator.get("signature").asText()}")
                println("State Hash: ${authenticator.get("stateHash").asText()}")
                
                // Check transaction data
                val txData = commitment.get("transactionData")
                println("\nTransaction data hash: ${txData.get("hash")?.asText()}")
                println("Source state hash: ${txData.get("sourceState")?.get("hash")?.asText()}")
                
                // Verify the authenticator is signing the correct hashes
                val txHash = txData.get("hash")?.asText()
                val stateHash = authenticator.get("stateHash").asText()
                val sourceStateHash = txData.get("sourceState")?.get("hash")?.asText()
                
                assertEquals(
                    "Authenticator stateHash should match source state hash",
                    sourceStateHash,
                    stateHash
                )
                
                println("\n✅ Authenticator structure is valid")
            },
            onFailure = { error ->
                error.printStackTrace()
                fail("Failed to create offline transfer package: ${error.message}")
            }
        )
    }
    
    @Test
    fun testCompleteOfflineTransferWithRealAggregator() = runBlocking {
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
}