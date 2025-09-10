package com.unicity.nfcwalletdemo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Instrumented test for token minting functionality.
 * This test runs on an Android device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class TokenMintInstrumentedTest {
    
    companion object {
        private const val TAG = "TokenMintTest"
    }
    
    private lateinit var sdkService: UnicitySdkService
    
    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        sdkService = UnicitySdkService(context)
        
        // Wait for SDK to initialize
        Thread.sleep(2000)
    }
    
    @Test
    fun testTokenMinting() = runTest {
        // Generate identity
        Log.d(TAG, "Generating identity...")
        val identityResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.generateIdentity { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Identity generation should succeed", identityResult.isSuccess)
        
        val identityResponse = identityResult.getOrThrow()
        val identityJson = JSONObject(identityResponse)
        assertEquals("success", identityJson.getString("status"))
        
        val identity = identityJson.getString("data")
        Log.d(TAG, "Generated identity: $identity")
        
        // Mint token
        Log.d(TAG, "Minting token...")
        val tokenData = """{"amount":100,"data":"Test token from instrumented test","stateData":"Initial state"}"""
        
        val mintResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.mintToken(identity, tokenData) { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Token minting should succeed", mintResult.isSuccess)
        
        val mintResponse = mintResult.getOrThrow()
        val mintJson = JSONObject(mintResponse)
        assertEquals("success", mintJson.getString("status"))
        
        val tokenDataResult = mintJson.getString("data")
        assertNotNull("Token data should not be null", tokenDataResult)
        
        Log.d(TAG, "✅ TOKEN MINT TEST PASSED!")
        Log.d(TAG, "Token data: $tokenDataResult")
    }
    
    @Test
    fun testTokenTransfer() = runTest {
        // First create two identities
        val aliceIdentity = generateIdentity()
        val bobIdentity = generateIdentity()
        
        // Alice mints a token
        val tokenData = """{"amount":50,"data":"Transfer test token","stateData":"Initial"}"""
        val mintedToken = mintToken(aliceIdentity, tokenData)
        
        // Create transfer from Alice to Bob
        val transferResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.createTransfer(aliceIdentity, bobIdentity, mintedToken) { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Transfer creation should succeed", transferResult.isSuccess)
        
        val transferData = transferResult.getOrThrow()
        val transferJson = JSONObject(transferData)
        assertEquals("success", transferJson.getString("status"))
        
        // Bob completes the transfer
        val completeResult = suspendCoroutine<Result<String>> { cont ->
            sdkService.finishTransfer(bobIdentity, transferJson.getString("data")) { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Transfer completion should succeed", completeResult.isSuccess)
        
        Log.d(TAG, "✅ TOKEN TRANSFER TEST PASSED!")
    }
    
    private suspend fun generateIdentity(): String {
        val result = suspendCoroutine<Result<String>> { cont ->
            sdkService.generateIdentity { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Identity generation should succeed", result.isSuccess)
        val response = JSONObject(result.getOrThrow())
        assertEquals("success", response.getString("status"))
        return response.getString("data")
    }
    
    private suspend fun mintToken(identity: String, tokenData: String): String {
        val result = suspendCoroutine<Result<String>> { cont ->
            sdkService.mintToken(identity, tokenData) { result ->
                cont.resume(result)
            }
        }
        
        assertTrue("Token minting should succeed", result.isSuccess)
        val response = JSONObject(result.getOrThrow())
        assertEquals("success", response.getString("status"))
        return response.getString("data")
    }
}