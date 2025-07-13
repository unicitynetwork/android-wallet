package com.unicity.nfcwalletdemo.sdk

import com.fasterxml.jackson.databind.ObjectMapper
import com.unicity.sdk.address.DirectAddress
import com.unicity.sdk.predicate.MaskedPredicate
import com.unicity.sdk.shared.hash.HashAlgorithm
import com.unicity.sdk.shared.signing.SigningService
import com.unicity.sdk.token.TokenId
import com.unicity.sdk.token.TokenType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

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

        // Calculate the recipient address from receiver's identity
        // We need to derive the address that matches the receiver's predicate
        val receiverIdData = objectMapper.readTree(receiverIdentity!!)
        val receiverSecret = receiverIdData.get("secret").asText().toByteArray(StandardCharsets.UTF_8)
        val receiverNonce = receiverIdData.get("nonce").asText().toByteArray(StandardCharsets.UTF_8)
        
        // Get token info from minted token to create matching predicate
        val mintedToken = objectMapper.readTree(tokenJson!!)
        
        // In the Java SDK, tokenId and tokenType are in genesis.data
        val genesis = mintedToken.get("genesis")
        val genesisData = genesis.get("data")
        
        val tokenIdHex = genesisData.get("tokenId").asText()
        val tokenTypeHex = genesisData.get("tokenType").asText()
        
        // Create receiver's signing service and predicate to get the correct address
        val receiverSigningService = SigningService.createFromSecret(receiverSecret, receiverNonce).get()
        val tokenId = TokenId.create(hexStringToByteArray(tokenIdHex))
        val tokenType = TokenType.create(hexStringToByteArray(tokenTypeHex))
        
        val receiverPredicate = MaskedPredicate.create(
            tokenId,
            tokenType,
            receiverSigningService,
            HashAlgorithm.SHA256,
            receiverNonce
        ).get()
        
        val recipientAddress = DirectAddress.create(receiverPredicate.reference).get().toString()

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
                if (error.message?.contains("AUTHENTICATOR_VERIFICATION_FAILED") == true) {
                    println("\nLikely issue: Authenticator signature validation failed at aggregator")
                    println("This suggests the authenticator is not signing the correct data")
                }
                
                // Fail the test
                fail("Transfer failed: ${error.message}")
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