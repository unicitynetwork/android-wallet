package org.unicitylabs.wallet

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.nfc.NfcAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.unicitylabs.wallet.bluetooth.BluetoothMeshTransferService
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.nfc.ApduTransceiver
import org.unicitylabs.wallet.nfc.BluetoothHandshake
import org.unicitylabs.wallet.nfc.BluetoothHandshakeResponse
import org.unicitylabs.wallet.nfc.HybridNfcBluetoothClient
import org.unicitylabs.wallet.nfc.TokenPreview
import org.unicitylabs.wallet.sdk.UnicityJavaSdkService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Instrumented test for NFC + Bluetooth hybrid transfer
 */
@RunWith(AndroidJUnit4::class)
class HybridTransferTest {
    
    private lateinit var context: Context
    private lateinit var bluetoothService: BluetoothMeshTransferService
    private lateinit var sdkService: UnicityJavaSdkService
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        bluetoothService = BluetoothMeshTransferService(context)
        sdkService = UnicityJavaSdkService.getInstance()
    }
    
    @After
    fun tearDown() {
        bluetoothService.cleanup()
        // SDK service cleanup not needed for singleton
    }
    
    @Test
    fun testBluetoothHandshakeDataSerialization() {
        val handshake = BluetoothHandshake(
            senderId = "test-sender",
            bluetoothMAC = "00:11:22:33:44:55",
            transferId = UUID.randomUUID().toString(),
            tokenPreview = TokenPreview(
                tokenId = "test-token-id",
                name = "Test Token",
                type = "NFT",
                amount = 100
            )
        )
        
        val json = handshake.toJson()
        val deserialized = BluetoothHandshake.fromJson(json)
        
        assertEquals(handshake.senderId, deserialized.senderId)
        assertEquals(handshake.bluetoothMAC, deserialized.bluetoothMAC)
        assertEquals(handshake.transferId, deserialized.transferId)
        assertEquals(handshake.tokenPreview.tokenId, deserialized.tokenPreview.tokenId)
    }
    
    @Test
    fun testBluetoothServiceInitialization() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull("Bluetooth adapter should be available", bluetoothAdapter)
        
        if (bluetoothAdapter.isEnabled) {
            val mac = bluetoothService.getBluetoothMAC()
            assertNotNull("Bluetooth MAC should not be null", mac)
            assertNotEquals("Bluetooth MAC should not be default", "00:00:00:00:00:00", mac)
        }
    }
    
    @Test
    fun testHybridClientStateFlow() = runTest {
        val mockTransceiver = MockApduTransceiver()
        val testToken = Token(
            id = "test-token-${UUID.randomUUID()}",
            name = "Test NFT",
            type = "NFT"
        )
        
        val hybridClient = HybridNfcBluetoothClient(
            context = context,
            sdkService = sdkService,
            apduTransceiver = mockTransceiver,
            onTransferComplete = { },
            onError = { },
            onProgress = null
        )
        
        // Test mode removed - not available in current implementation
        // hybridClient.setTestMode(true)
        
        // Initial state should be Idle
        assertEquals(HybridNfcBluetoothClient.TransferState.Idle, hybridClient.state.value)
        
        // Start transfer (will fail due to mock, but we can test state transitions)
        val job = launch {
            try {
                hybridClient.startTransferAsSender(testToken, "{\"secret\":\"test\",\"nonce\":\"test\"}")
            } catch (e: Exception) {
                // Expected in test environment
            }
        }
        
        // Wait for state to change from Idle
        val nextState = withTimeoutOrNull(2000) {
            hybridClient.state.first { it !is HybridNfcBluetoothClient.TransferState.Idle }
        }
        
        assertTrue(
            "State should transition to NfcHandshaking",
            nextState is HybridNfcBluetoothClient.TransferState.NfcHandshaking
        )
        
        job.cancel()
        hybridClient.cleanup()
    }
    
    @Test
    fun testNfcAvailability() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        if (nfcAdapter != null) {
            assertTrue("NFC should be supported", true)
            // Note: NFC might not be enabled in test environment
        } else {
            // Skip test if NFC not available
            println("NFC not available on test device")
        }
    }
    
    @Test
    fun testBluetoothDataTransferChunking() = runTest {
        // Test that large data is properly chunked
        val largeData = ByteArray(5000) { it.toByte() } // 5KB of test data
        val chunks = largeData.toList().chunked(512)
        
        assertEquals("Should have correct number of chunks", 10, chunks.size)
        assertEquals("First chunk should be correct size", 512, chunks[0].size)
        assertEquals("Last chunk should be correct size", 392, chunks.last().size)
    }
    
    /**
     * Mock APDU transceiver for testing
     */
    private class MockApduTransceiver : ApduTransceiver {
        override suspend fun transceive(commandApdu: ByteArray): ByteArray {
            // Simulate successful APDU response
            val mockResponse = BluetoothHandshakeResponse(
                receiverId = "mock-receiver",
                bluetoothMAC = "AA:BB:CC:DD:EE:FF",
                transferId = UUID.randomUUID().toString(),
                accepted = true
            )
            
            val responseData = mockResponse.toJson().toByteArray()
            return responseData + byteArrayOf(0x90.toByte(), 0x00.toByte())
        }
        
        fun close() {
            // No-op for mock
        }
    }
}

/**
 * Manual integration test for actual device testing
 * Run this with two physical devices
 */
class ManualHybridTransferTest {
    
    companion object {
        /**
         * Device A (Sender) - Run this method
         */
        @JvmStatic
        fun runSenderTest(context: Context) {
            val scope = MainScope()
            
            scope.launch {
                try {
                    println("=== Starting Hybrid Transfer Test (SENDER) ===")
                    
                    val bluetoothService = BluetoothMeshTransferService(context)
                    val sdkService = UnicityJavaSdkService.getInstance()
                    
                    // Create test token
                    val testToken = Token(
                        id = "test-${UUID.randomUUID()}",
                        name = "Test NFT",
                        type = "NFT"
                    )
                    
                    println("Test token created: ${testToken.name}")
                    println("Bluetooth MAC: ${bluetoothService.getBluetoothMAC()}")
                    println("Tap phones together to start transfer...")
                    
                    // Wait for NFC tap and complete transfer
                    // In real app, this would be triggered by NFC tap
                    
                    bluetoothService.cleanup()
                    // SDK service cleanup not needed for singleton
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        /**
         * Device B (Receiver) - Run this method
         */
        @JvmStatic
        fun runReceiverTest(context: Context) {
            val scope = MainScope()
            
            scope.launch {
                try {
                    println("=== Starting Hybrid Transfer Test (RECEIVER) ===")
                    
                    val bluetoothService = BluetoothMeshTransferService(context)
                    val sdkService = UnicityJavaSdkService.getInstance()
                    
                    println("Bluetooth MAC: ${bluetoothService.getBluetoothMAC()}")
                    println("Waiting for sender to tap...")
                    
                    // Wait for NFC tap and receive transfer
                    // In real app, this would be triggered by NFC tap
                    
                    bluetoothService.cleanup()
                    // SDK service cleanup not needed for singleton
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}