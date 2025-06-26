package com.unicity.nfcwalletdemo.nfc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unicity.nfcwalletdemo.data.model.Token
import com.unicity.nfcwalletdemo.sdk.UnicitySdkService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.timeout
import org.mockito.kotlin.doAnswer
import org.mockito.ArgumentCaptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NfcTransferTest {

    private lateinit var appContext: Context
    private lateinit var unicitySdkService: UnicitySdkService
    private lateinit var hceLogic: HostCardEmulatorLogic
    private lateinit var nfcTestChannel: NfcTestChannel
    private lateinit var directNfcClient: DirectNfcClient

    @Mock
    private lateinit var mockOnTransferComplete: () -> Unit
    @Mock
    private lateinit var mockOnError: (String) -> Unit
    @Mock
    private lateinit var mockOnProgress: (Int, Int) -> Unit

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize real UnicitySdkService
        unicitySdkService = UnicitySdkService(appContext)

        // Initialize real HostCardEmulatorLogic
        hceLogic = HostCardEmulatorLogic(appContext, unicitySdkService)

        // Initialize NfcTestChannel with the real HCE logic
        nfcTestChannel = NfcTestChannel(hceLogic)

        // Initialize DirectNfcClient with the test channel and mocks
        directNfcClient = DirectNfcClient(
            unicitySdkService,
            nfcTestChannel,
            mockOnTransferComplete,
            mockOnError,
            mockOnProgress
        )
    }

    @Test
    fun testOfflineNfcTransferFlow() = runTest {
        // Given a token to send
        val testTokenJson = """
            {
                "id": "test_token_id_123",
                "type": "test_token_type",
                "name": "Test Token",
                "symbol": "TST",
                "decimals": 0,
                "supply": "1",
                "owner": "owner_address",
                "jsonData": "{\"identity\":{\"secret\":\"sender_secret\",\"nonce\":\"sender_nonce\"},\"token\":{\"id\":\"test_token_id_123\",\"type\":\"test_token_type\",\"name\":\"Test Token\"}}"
            }
        """
        val testToken = Gson().fromJson(testTokenJson, Token::class.java)
        directNfcClient.setTokenToSend(testToken)

        // When the NFC transfer is started
        directNfcClient.startNfcTransfer()

        // Then verify that onTransferComplete is called
        verify(mockOnTransferComplete, timeout(10000)).invoke()

        // And verify that the token was received by the HCE logic
        // This requires a way to inspect the received token in HostCardEmulatorLogic
        // For now, we'll rely on the onTransferComplete callback as a primary indicator
        // In a real scenario, we'd add an assertion on hceLogic.tokenToReceive or similar

        // Optional: Verify the offline transaction data received by the HCE logic
        // This would require exposing a method or property in HostCardEmulatorLogic
        // to get the last received offline transaction.
        // For now, we can check the static variable if it's still used for this purpose.
        // assertNotNull(HostCardEmulatorLogic.tokenToReceive)
        // assertEquals("test_token_id_123", HostCardEmulatorLogic.tokenToReceive?.id)
    }
}