package com.unicity.nfcwalletdemo.nfc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ApplicationProvider
import android.os.Handler
import android.os.Looper

@RunWith(AndroidJUnit4::class)
class NfcTransferTest {

    private lateinit var appContext: Context
    private lateinit var unicitySdkService: UnicitySdkService
    private lateinit var hceLogic: HostCardEmulatorLogic
    private lateinit var nfcTestChannel: NfcTestChannel
    private lateinit var directNfcClient: DirectNfcClient
    
    // Test state tracking
    private var transferCompleted = false
    private var transferError: String? = null
    private val progressUpdates = mutableListOf<Pair<Int, Int>>()
    private lateinit var transferCompleteLatch: CountDownLatch

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()

        // Initialize SDK service and HCE logic on main thread
        val initLatch = CountDownLatch(1)
        var initError: Exception? = null
        
        Handler(Looper.getMainLooper()).post {
            try {
                // Initialize real UnicitySdkService
                unicitySdkService = UnicitySdkService(appContext)
                
                // Wait a bit for WebView to initialize
                Thread.sleep(2000)

                // Initialize real HostCardEmulatorLogic
                hceLogic = HostCardEmulatorLogic(appContext, unicitySdkService)

                // Initialize NfcTestChannel with the real HCE logic
                nfcTestChannel = NfcTestChannel(hceLogic)
            } catch (e: Exception) {
                initError = e
            } finally {
                initLatch.countDown()
            }
        }
        
        // Wait for initialization
        assertTrue("Initialization should complete", initLatch.await(5, TimeUnit.SECONDS))
        initError?.let { throw it }
        
        // Reset test state
        transferCompleted = false
        transferError = null
        progressUpdates.clear()
        transferCompleteLatch = CountDownLatch(1)

        // Initialize DirectNfcClient with test callbacks
        directNfcClient = DirectNfcClient(
            unicitySdkService,
            nfcTestChannel,
            onTransferComplete = {
                transferCompleted = true
                transferCompleteLatch.countDown()
            },
            onError = { error ->
                transferError = error
                transferCompleteLatch.countDown()
            },
            onProgress = { current, total ->
                progressUpdates.add(current to total)
            }
        )
    }

    @Test
    fun testOfflineNfcTransferFlow() {
        // Given a token to send
        val testToken = createTestTokenWithUnicityData()
        directNfcClient.setTokenToSend(testToken)

        // When the NFC transfer is started
        directNfcClient.startNfcTransfer()

        // Then wait for the transfer to complete
        val completed = transferCompleteLatch.await(15, TimeUnit.SECONDS)
        
        // Verify the transfer completed successfully
        assertTrue("Transfer should complete within timeout", completed)
        assertTrue("Transfer should complete successfully, but got error: $transferError", transferCompleted)
        assertNull("Transfer should not have errors", transferError)

        // Verify the receiver generated an address
        assertNotNull("Receiver should generate address", HostCardEmulatorLogic.getGeneratedReceiverIdentity())
    }
    
    @Test
    fun testNfcTransferWithProgress() {
        // Given a large token that will be chunked
        val largeToken = createLargeTestToken()
        directNfcClient.setTokenToSend(largeToken)
        
        // Reset latch for this test
        transferCompleteLatch = CountDownLatch(1)
        transferCompleted = false
        transferError = null
        progressUpdates.clear()
        
        // When the transfer starts
        directNfcClient.startNfcTransfer()
        
        // Then wait for completion
        val completed = transferCompleteLatch.await(10, TimeUnit.SECONDS)
        
        // Verify transfer completed
        assertTrue("Transfer should complete", completed)
        assertTrue("Transfer should succeed", transferCompleted)
        
        // Verify progress callbacks were received
        assertTrue("Should receive progress updates", progressUpdates.isNotEmpty())
    }
    
    @Test
    fun testNfcHandshakeSteps() {
        // Given a token with proper Unicity data
        val token = createTestTokenWithUnicityData()
        directNfcClient.setTokenToSend(token)
        
        // Reset test state
        transferCompleteLatch = CountDownLatch(1)
        transferCompleted = false
        transferError = null
        
        // When transfer starts
        directNfcClient.startNfcTransfer()
        
        // Then verify the handshake completes
        val completed = transferCompleteLatch.await(10, TimeUnit.SECONDS)
        
        // Verify completion
        assertTrue("Handshake should complete", completed)
        assertNull("Should not have handshake errors", transferError)
        assertTrue("Should complete successfully", transferCompleted)
    }
    
    private fun createTestTokenWithUnicityData(): Token {
        // Create a token with proper Unicity SDK data structure
        val tokenData = mapOf(
            "identity" to mapOf(
                "secret" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "nonce" to "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
            ),
            "token" to mapOf(
                "id" to "deadbeefcafe",
                "type" to "unicitytoken",
                "version" to 1,
                "genesis" to mapOf(
                    "data" to mapOf(
                        "tokenId" to "deadbeefcafe",
                        "tokenType" to "unicitytoken"
                    )
                ),
                "state" to mapOf(
                    "unlockPredicate" to mapOf(
                        "type" to "MaskedPredicate"
                    ),
                    "data" to "0x00"
                ),
                "transactions" to emptyList<Any>()
            )
        )
        
        return Token(
            id = "test_unicity_token",
            name = "Test Unicity Token",
            type = "unicity",
            jsonData = Gson().toJson(tokenData),
            sizeBytes = Gson().toJson(tokenData).length
        )
    }
    
    private fun createLargeTestToken(): Token {
        // Create a token with large data to test chunking
        val largeData = "x".repeat(5000) // 5KB of data
        val tokenData = mapOf(
            "identity" to mapOf(
                "secret" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "nonce" to "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
            ),
            "token" to mapOf(
                "id" to "largeid",
                "type" to "largetype",
                "largeField" to largeData
            )
        )
        
        return Token(
            id = "large_token",
            name = "Large Test Token",
            type = "unicity",
            jsonData = Gson().toJson(tokenData),
            sizeBytes = Gson().toJson(tokenData).length
        )
    }
    
    private fun assertNull(message: String, value: Any?) {
        if (value != null) {
            throw AssertionError("$message: expected null but was $value")
        }
    }
}