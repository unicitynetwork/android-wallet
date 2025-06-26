package com.unicity.nfcwalletdemo.nfc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NfcTestChannel(private val hceLogic: HostCardEmulatorLogic) : ApduTransceiver {

    companion object {
        private const val TAG = "NfcTestChannel"
    }

    override suspend fun transceive(commandApdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        Log.d(TAG, "NfcTestChannel: Transceiving APDU: ${commandApdu.toHexString()}")
        // Directly call the HCE logic's processCommandApdu method
        val responseApdu = hceLogic.processCommandApdu(commandApdu, null)
        Log.d(TAG, "NfcTestChannel: Received response APDU: ${responseApdu.toHexString()}")
        responseApdu
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
