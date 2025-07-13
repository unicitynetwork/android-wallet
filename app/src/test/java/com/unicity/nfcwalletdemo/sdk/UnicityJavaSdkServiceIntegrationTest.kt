package com.unicity.nfcwalletdemo.sdk

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

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
    fun testMintTokenWithRealAggregator() = runBlocking {
        println("Testing real token minting with Unicity aggregator...")
        println("Using aggregator URL: https://aggregator-test-1.mainnet.unicity.network")
        
        // First generate an identity
        var identityJson: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> identityJson = json },
                onFailure = { error -> fail("Failed to generate identity: ${error.message}") }
            )
        }
        
        // Wait for identity generation
        Thread.sleep(1000)
        assertNotNull("Identity should be generated", identityJson)
        
        // Create token data
        val tokenData = mapOf(
            "data" to "Integration Test Token",
            "amount" to 100
        )
        val tokenDataJson = objectMapper.writeValueAsString(tokenData)
        
        println("Attempting to mint token...")
        println("Identity: $identityJson")
        println("Token data: $tokenDataJson")
        
        // Mint the token
        val startTime = System.currentTimeMillis()
        val result = sdkService.mintToken(identityJson!!, tokenDataJson)
        
        result.fold(
            onSuccess = { mintResultJson ->
                val duration = System.currentTimeMillis() - startTime
                println("Token minted successfully in ${duration}ms!")
                println("Mint result: $mintResultJson")
                
                // Verify the result structure
                val mintResult = objectMapper.readTree(mintResultJson)
                assertNotNull("Result should have identity", mintResult.get("identity"))
                assertNotNull("Result should have token", mintResult.get("token"))
                
                // Verify token structure
                val token = mintResult.get("token")
                assertNotNull("Token should exist", token)
                
                // Log token details
                println("Token structure: ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(token)}")
            },
            onFailure = { error ->
                val duration = System.currentTimeMillis() - startTime
                println("Minting failed after ${duration}ms")
                error.printStackTrace()
                fail("Failed to mint token: ${error.message}\n${error.stackTraceToString()}")
            }
        )
    }
    
    @Test
    fun testMintMultipleTokens() = runBlocking {
        println("Testing multiple token minting to ensure consistency...")
        
        // Generate identity once
        var identityJson: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> identityJson = json },
                onFailure = { error -> fail("Failed to generate identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)
        assertNotNull("Identity should be generated", identityJson)
        
        // Mint 3 tokens
        val tokenNames = listOf("Token 1", "Token 2", "Token 3")
        val results = mutableListOf<String>()
        
        for (name in tokenNames) {
            println("\nMinting $name...")
            
            val tokenData = mapOf(
                "data" to name,
                "amount" to 50
            )
            val tokenDataJson = objectMapper.writeValueAsString(tokenData)
            
            val result = sdkService.mintToken(identityJson!!, tokenDataJson)
            result.fold(
                onSuccess = { mintResultJson ->
                    println("$name minted successfully!")
                    results.add(mintResultJson)
                },
                onFailure = { error ->
                    error.printStackTrace()
                    fail("Failed to mint $name: ${error.message}")
                }
            )
            
            // Small delay between mints
            Thread.sleep(2000)
        }
        
        assertEquals("All 3 tokens should be minted", 3, results.size)
        println("\nSuccessfully minted ${results.size} tokens!")
    }
    
    @Test
    fun testMintTokenWithLargeData() = runBlocking {
        println("Testing token minting with large data payload...")
        
        // Generate identity
        var identityJson: String? = null
        sdkService.generateIdentity { result ->
            result.fold(
                onSuccess = { json -> identityJson = json },
                onFailure = { error -> fail("Failed to generate identity: ${error.message}") }
            )
        }
        Thread.sleep(1000)
        assertNotNull("Identity should be generated", identityJson)
        
        // Create token with large data
        val largeData = "Large Data ".repeat(100) // ~1KB of data
        val tokenData = mapOf(
            "data" to largeData,
            "amount" to 1000
        )
        val tokenDataJson = objectMapper.writeValueAsString(tokenData)
        
        println("Token data size: ${tokenDataJson.length} bytes")
        
        val result = sdkService.mintToken(identityJson!!, tokenDataJson)
        result.fold(
            onSuccess = { mintResultJson ->
                println("Large token minted successfully!")
                assertNotNull("Result should not be null", mintResultJson)
            },
            onFailure = { error ->
                error.printStackTrace()
                fail("Failed to mint large token: ${error.message}")
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