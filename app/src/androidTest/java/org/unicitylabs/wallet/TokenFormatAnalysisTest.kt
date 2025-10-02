package org.unicitylabs.wallet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import java.security.SecureRandom

/**
 * Test to analyze the exact format of tokens created by the SDK
 */
@RunWith(AndroidJUnit4::class)
class TokenFormatAnalysisTest {
    
    companion object {
        private const val TAG = "TokenFormatAnalysis"
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
    fun analyzeTokenFormat() = runTest {
        // Generate identity directly
        val random = SecureRandom()
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        random.nextBytes(secret)
        random.nextBytes(nonce)
        
        Log.d(TAG, "Identity generated with secret and nonce")
        
        // Mint a token
        val tokenAmount = 42L
        val tokenData = "Test token for format analysis"
        
        val mintResult = try {
            val token = sdkService.mintToken(tokenAmount, tokenData, secret, nonce)
            if (token != null) {
                // Convert token to JSON string for analysis
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
        
        val mintResultStr = mintResult.getOrThrow()
        Log.d(TAG, "Raw mint result: $mintResultStr")
        
        // Parse and analyze the token structure
        val mintResponse = JSONObject(mintResultStr)
        val tokenJson = mintResponse.getJSONObject("token")
        
        Log.d(TAG, "=== TOKEN FORMAT ANALYSIS ===")
        Log.d(TAG, "Full token JSON:")
        Log.d(TAG, tokenJson.toString(2))
        
        // List all keys
        Log.d(TAG, "\n=== TOKEN KEYS ===")
        val keys = tokenJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            Log.d(TAG, "Token has key: $key")
        }
        
        // Analyze each field
        Log.d(TAG, "\n=== FIELD ANALYSIS ===")
        
        // Version
        if (tokenJson.has("version")) {
            Log.d(TAG, "version: ${tokenJson.getString("version")}")
        }
        
        // ID
        if (tokenJson.has("id")) {
            val id = tokenJson.getString("id")
            Log.d(TAG, "id: $id")
            Log.d(TAG, "id starts with 0x: ${id.startsWith("0x")}")
        }
        
        // Type
        if (tokenJson.has("type")) {
            Log.d(TAG, "type: ${tokenJson.getString("type")}")
        }
        
        // Data
        if (tokenJson.has("data")) {
            val data = tokenJson.getString("data")
            Log.d(TAG, "data: $data")
            Log.d(TAG, "data starts with 0x: ${data.startsWith("0x")}")
        }
        
        // State
        if (tokenJson.has("state")) {
            val state = tokenJson.getJSONObject("state")
            Log.d(TAG, "\nstate object:")
            Log.d(TAG, state.toString(2))
            
            if (state.has("data")) {
                val stateData = state.get("data")
                Log.d(TAG, "state.data: $stateData (type: ${stateData.javaClass.simpleName})")
            }
            
            if (state.has("unlockPredicate")) {
                val predicate = state.getJSONObject("unlockPredicate")
                Log.d(TAG, "state.unlockPredicate: ${predicate.toString(2)}")
                
                if (predicate.has("reference")) {
                    val ref = predicate.getString("reference")
                    Log.d(TAG, "unlockPredicate.reference: $ref")
                    Log.d(TAG, "reference starts with 0x: ${ref.startsWith("0x")}")
                }
            }
        }
        
        // Genesis
        if (tokenJson.has("genesis")) {
            val genesis = tokenJson.getJSONObject("genesis")
            Log.d(TAG, "\ngenesis object:")
            Log.d(TAG, genesis.toString(2))
            
            if (genesis.has("data")) {
                val genesisData = genesis.getJSONObject("data")
                Log.d(TAG, "genesis.data: ${genesisData.toString(2)}")
                
                // Check tokenId in genesis
                if (genesisData.has("tokenId")) {
                    val tokenId = genesisData.getString("tokenId")
                    Log.d(TAG, "genesis.data.tokenId: $tokenId")
                    Log.d(TAG, "genesis tokenId starts with 0x: ${tokenId.startsWith("0x")}")
                }
                
                // Check tokenData in genesis
                if (genesisData.has("tokenData")) {
                    val tokenData = genesisData.getString("tokenData")
                    Log.d(TAG, "genesis.data.tokenData: $tokenData")
                    Log.d(TAG, "genesis tokenData starts with 0x: ${tokenData.startsWith("0x")}")
                }
            }
        }
        
        // Transactions
        if (tokenJson.has("transactions")) {
            val transactions = tokenJson.getJSONArray("transactions")
            Log.d(TAG, "\ntransactions array length: ${transactions.length()}")
            for (i in 0 until transactions.length()) {
                val tx = transactions.getJSONObject(i)
                Log.d(TAG, "transaction[$i]: ${tx.toString(2)}")
            }
        }
        
        // Coins
        if (tokenJson.has("coins")) {
            val coins = tokenJson.get("coins")
            Log.d(TAG, "\ncoins: $coins (type: ${coins.javaClass.simpleName})")
        }
        
        // Nametag tokens
        if (tokenJson.has("nametagTokens")) {
            val nametags = tokenJson.getJSONArray("nametagTokens")
            Log.d(TAG, "\nnametagTokens array length: ${nametags.length()}")
        }
        
        Log.d(TAG, "\n=== END TOKEN FORMAT ANALYSIS ===")
        
        // Now let's create a token with the exact same format and see if it can be deserialized
        val testToken = JSONObject().apply {
            if (tokenJson.has("version")) put("version", tokenJson.getString("version"))
            if (tokenJson.has("id")) put("id", tokenJson.getString("id"))
            if (tokenJson.has("type")) put("type", tokenJson.getString("type"))
            if (tokenJson.has("data")) put("data", tokenJson.getString("data"))
            if (tokenJson.has("state")) put("state", tokenJson.getJSONObject("state"))
            if (tokenJson.has("genesis")) put("genesis", tokenJson.getJSONObject("genesis"))
            if (tokenJson.has("transactions")) put("transactions", tokenJson.getJSONArray("transactions"))
            if (tokenJson.has("nametagTokens")) put("nametagTokens", tokenJson.getJSONArray("nametagTokens"))
            if (tokenJson.has("coins")) put("coins", tokenJson.get("coins"))
        }
        
        Log.d(TAG, "\nTest token created with same format")
        Log.d(TAG, "Test token JSON: ${testToken.toString(2)}")
        
        // Note: deserializeToken not available in Java SDK
        // The Java SDK uses UnicityObjectMapper.JSON.readValue() for deserialization
        Log.d(TAG, "Note: Test token created in same format")
        Log.d(TAG, "Java SDK would use UnicityObjectMapper.JSON.readValue() for deserialization")
    }
}