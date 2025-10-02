package org.unicitylabs.wallet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.unicitylabs.wallet.sdk.UnicitySdkService
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Simple test to verify SDK initialization and basic functionality
 */
@RunWith(AndroidJUnit4::class)
class SimpleSdkTest {
    
    companion object {
        private const val TAG = "SimpleSdkTest"
    }
    
    @Test
    fun testSdkInitialization() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize SDK on main thread
        val sdkService = withContext(Dispatchers.Main) {
            UnicitySdkService(context)
        }
        
        // Wait for SDK to initialize
        Thread.sleep(5000)
        
        // Try to generate identity
        Log.d(TAG, "Attempting to generate identity...")
        val result = suspendCoroutine<Result<String>> { cont ->
            sdkService.generateIdentity { result ->
                Log.d(TAG, "generateIdentity callback received")
                cont.resume(result)
            }
        }
        
        if (result.isSuccess) {
            val response = result.getOrThrow()
            Log.d(TAG, "Success response: $response")
            
            // Try to parse it as JSON
            try {
                val json = org.json.JSONObject(response)
                Log.d(TAG, "Parsed JSON successfully")
                Log.d(TAG, "Keys: ${json.keys().asSequence().toList()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse as JSON: ${e.message}")
                Log.d(TAG, "Raw response: $response")
            }
        } else {
            val error = result.exceptionOrNull()
            Log.e(TAG, "Failed with error: ${error?.message}")
            fail("SDK call failed: ${error?.message}")
        }
    }
}