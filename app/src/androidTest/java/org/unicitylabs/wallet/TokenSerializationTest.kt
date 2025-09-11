package org.unicitylabs.wallet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Instrumented test for token serialization/deserialization functionality.
 * Tests that tokens can be serialized to .txf files and deserialized back correctly.
 */
@RunWith(AndroidJUnit4::class)
class TokenSerializationTest {
    
    companion object {
        private const val TAG = "TokenSerializationTest"
    }
    
    private lateinit var sdkService: UnicityJavaSdkService
    private lateinit var context: android.content.Context
    private val objectMapper = ObjectMapper()
    
    @Before
    fun setup() = runTest {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize SDK on main thread
        withContext(Dispatchers.Main) {
            sdkService = UnicityJavaSdkService.getInstance()
        }
        
        // Wait for SDK to initialize
        Thread.sleep(5000)
    }
    
    @Test
    fun testTokenSerializationAndDeserialization() = runTest {
        // Step 1: Generate identity and mint a token
        Log.d(TAG, "Generating identity...")
        val random = SecureRandom()
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        random.nextBytes(secret)
        random.nextBytes(nonce)
        
        Log.d(TAG, "Identity generated with secret and nonce")
        
        // Step 2: Mint a token with specific data
        Log.d(TAG, "Minting token...")
        val tokenAmount = 42L
        val tokenData = "Test serialization token"
        
        val mintResult = try {
            val token = sdkService.mintToken(tokenAmount, tokenData, secret, nonce)
            if (token != null) {
                val tokenJson = JSONObject()
                tokenJson.put("token", JSONObject(objectMapper.writeValueAsString(token)))
                Result.success(tokenJson.toString())
            } else {
                Result.failure<String>(Exception("Token minting failed"))
            }
        } catch (e: Exception) {
            Result.failure<String>(e)
        }
        
        assertTrue("Token minting should succeed", mintResult.isSuccess)
        
        // The response is already the token data, not wrapped
        val mintResultStr = mintResult.getOrThrow()
        assertNotNull("Token data should not be null", mintResultStr)
        Log.d(TAG, "Raw mint result: $mintResultStr")
        
        // The mint result contains the full response including token, identity, etc.
        // We need to extract just the token part
        val mintResponse = JSONObject(mintResultStr)
        val originalTokenJson = mintResponse.getJSONObject("token").toString()
        Log.d(TAG, "Original token JSON: $originalTokenJson")
        
        // Step 3: Save token to a .txf file
        val tempDir = context.cacheDir
        val txfFile = File(tempDir, "test_token_${System.currentTimeMillis()}.txf")
        
        try {
            // Write the token JSON to the file
            txfFile.writeText(originalTokenJson)
            assertTrue("TXF file should exist", txfFile.exists())
            Log.d(TAG, "Token saved to: ${txfFile.absolutePath}")
            
            // Step 4: Read the contents back from the file
            val tokenStringFromFile = txfFile.readText()
            assertEquals("File contents should match original", originalTokenJson, tokenStringFromFile)
            
            // Step 5: Deserialize the token using SDK
            Log.d(TAG, "Deserializing token from file...")
            // TODO: Rewrite for Java SDK
            val deserializeResult = Result.failure<String>(NotImplementedError("deserializeToken not available in Java SDK"))
            /*
            val deserializeResult = suspendCoroutine<Result<String>> { cont ->
                // TODO: deserializeToken not available in Java SDK
                // Need to use UnicityObjectMapper.JSON.readValue() instead
                // sdkService.deserializeToken(tokenStringFromFile) { result ->
                    cont.resume(result)
                }
            }
            */
            
            if (!deserializeResult.isSuccess) {
                Log.e(TAG, "Deserialization failed: ${deserializeResult.exceptionOrNull()?.message}")
                deserializeResult.exceptionOrNull()?.printStackTrace()
            }
            assertTrue("Token deserialization should succeed", deserializeResult.isSuccess)
            
            // The response is already the deserialized data
            val deserializeResultStr = deserializeResult.getOrThrow()
            Log.d(TAG, "Raw deserialize result: $deserializeResultStr")
            
            // Extract the token from the response
            val deserializeResponse = JSONObject(deserializeResultStr)
            val deserializedTokenJson = deserializeResponse.getJSONObject("token").toString()
            Log.d(TAG, "Deserialized token JSON: $deserializedTokenJson")
            
            // Step 6: Verify the deserialized token matches the original
            val originalToken = JSONObject(originalTokenJson)
            val deserializedToken = JSONObject(deserializedTokenJson)
            
            // Compare key fields
            assertEquals("Token IDs should match", 
                originalToken.optString("id"), 
                deserializedToken.optString("id"))
            
            assertEquals("Token types should match", 
                originalToken.optString("type"), 
                deserializedToken.optString("type"))
            
            assertEquals("Token data should match", 
                originalToken.optString("data"), 
                deserializedToken.optString("data"))
            
            // Compare state if present
            if (originalToken.has("state") && deserializedToken.has("state")) {
                val originalState = originalToken.getJSONObject("state")
                val deserializedState = deserializedToken.getJSONObject("state")
                
                assertEquals("State data should match",
                    originalState.optString("data"),
                    deserializedState.optString("data"))
            }
            
            // Compare genesis transaction if present
            if (originalToken.has("genesis") && deserializedToken.has("genesis")) {
                val originalGenesis = originalToken.getJSONObject("genesis")
                val deserializedGenesis = deserializedToken.getJSONObject("genesis")
                
                assertEquals("Genesis data recipient should match",
                    originalGenesis.optJSONObject("data")?.optString("recipient"),
                    deserializedGenesis.optJSONObject("data")?.optString("recipient"))
            }
            
            Log.d(TAG, "✅ TOKEN SERIALIZATION/DESERIALIZATION TEST PASSED!")
            
        } finally {
            // Cleanup: delete the temporary file
            if (txfFile.exists()) {
                txfFile.delete()
                Log.d(TAG, "Cleaned up temporary file")
            }
        }
    }
    
    @Test
    fun testMultipleTokenSerializationCycle() = runTest {
        // Test serializing and deserializing multiple times to ensure consistency
        val random = SecureRandom()
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        random.nextBytes(secret)
        random.nextBytes(nonce)
        
        Log.d(TAG, "Identity (cycle test) generated")
        
        // Mint a token
        val tokenAmount = 100L
        val tokenData = "Multi-cycle test"
        val mintResult = try {
            val token = sdkService.mintToken(tokenAmount, tokenData, secret, nonce)
            if (token != null) {
                val tokenJson = JSONObject()
                tokenJson.put("token", JSONObject(objectMapper.writeValueAsString(token)))
                Result.success(tokenJson.toString())
            } else {
                Result.failure<String>(Exception("Token minting failed"))
            }
        } catch (e: Exception) {
            Result.failure<String>(e)
        }
        
        val mintResultStr = mintResult.getOrThrow()
        val mintResponse = JSONObject(mintResultStr)
        var currentTokenJson = mintResponse.getJSONObject("token").toString()
        
        // Perform multiple serialization/deserialization cycles
        repeat(3) { cycle ->
            Log.d(TAG, "Serialization cycle ${cycle + 1}")
            
            // Save to file
            val txfFile = File(context.cacheDir, "cycle_test_${cycle}.txf")
            
            try {
                txfFile.writeText(currentTokenJson)
                
                // Read back
                val fromFile = txfFile.readText()
                
                // Deserialize
                // TODO: deserializeToken not available in Java SDK
                // Need to use UnicityObjectMapper.JSON.readValue() instead
                val deserializeResult = Result.failure<String>(NotImplementedError("deserializeToken not available in Java SDK"))
                
                if (!deserializeResult.isSuccess) {
                    Log.e(TAG, "Deserialization failed in cycle ${cycle + 1}: ${deserializeResult.exceptionOrNull()?.message}")
                    deserializeResult.exceptionOrNull()?.printStackTrace()
                }
                assertTrue("Deserialization should succeed in cycle ${cycle + 1}", 
                    deserializeResult.isSuccess)
                
                val deserializeResultStr = deserializeResult.getOrThrow()
                val deserializeResponse = JSONObject(deserializeResultStr)
                val newTokenJson = deserializeResponse.getJSONObject("token").toString()
                
                // Verify key fields remain consistent
                val originalToken = JSONObject(currentTokenJson)
                val newToken = JSONObject(newTokenJson)
                
                assertEquals("Token ID should remain consistent", 
                    originalToken.optString("id"), 
                    newToken.optString("id"))
                
                // Update for next cycle
                currentTokenJson = newTokenJson
                
            } finally {
                txfFile.delete()
            }
        }
        
        Log.d(TAG, "✅ MULTIPLE SERIALIZATION CYCLES TEST PASSED!")
    }
}