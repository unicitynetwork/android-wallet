package com.unicity.nfcwalletdemo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Instrumented test for token serialization/deserialization functionality.
 * Tests that tokens can be serialized to .txf files and deserialized back correctly.
 */
@RunWith(AndroidJUnit4::class)
class TokenSerializationTest {
    
    companion object {
        private const val TAG = "TokenSerializationTest"
    }
    
    private lateinit var sdkService: UnicitySdkService
    private lateinit var context: android.content.Context
    
    @Before
    fun setup() = runTest {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize SDK on main thread
        withContext(Dispatchers.Main) {
            sdkService = UnicitySdkService(context)
        }
        
        // Wait for SDK to initialize
        Thread.sleep(5000)
    }
    
    @Test
    fun testTokenSerializationAndDeserialization() = runTest {
        // Step 1: Generate identity and mint a token
        Log.d(TAG, "Generating identity...")
        val identityResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.generateIdentity { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Identity generation should succeed", identityResult.isSuccess)
        val identityResponseStr = identityResult.getOrThrow()
        Log.d(TAG, "Identity response: $identityResponseStr")
        
        // The response might already be the identity JSON directly
        val identity = identityResponseStr
        
        // Step 2: Mint a token with specific data
        Log.d(TAG, "Minting token...")
        val tokenData = """{"amount":42,"data":"Test serialization token","stateData":"Serialization test state"}"""
        
        val mintResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.mintToken(identity, tokenData) { result ->
                cont.resume(result)
            }
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
            val deserializeResult = suspendCoroutine<Result<String>> { cont ->
                sdkService.deserializeToken(tokenStringFromFile) { result ->
                    cont.resume(result)
                }
            }
            
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
        val identityResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.generateIdentity { result ->
                cont.resume(result)
            }
        }
        
        val identity = identityResult.getOrThrow()
        Log.d(TAG, "Identity (cycle test): $identity")
        
        // Mint a token
        val tokenData = """{"amount":100,"data":"Multi-cycle test","stateData":"Cycle test state"}"""
        val mintResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.mintToken(identity, tokenData) { result ->
                cont.resume(result)
            }
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
                val deserializeResult = suspendCoroutine<Result<String>> { cont ->
                    sdkService.deserializeToken(fromFile) { result ->
                        cont.resume(result)
                    }
                }
                
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