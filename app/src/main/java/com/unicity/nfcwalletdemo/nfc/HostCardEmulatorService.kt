package com.unicity.nfcwalletdemo.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.unicity.nfcwalletdemo.sdk.UnicityJavaSdkService

class HostCardEmulatorService() : HostApduService() {

    private lateinit var hceLogic: HostCardEmulatorLogic

    override fun onCreate() {
        super.onCreate()
        // Initialize HostCardEmulatorLogic here, passing the application context
        hceLogic = HostCardEmulatorLogic(applicationContext, UnicityJavaSdkService())
        Log.d(TAG, "HostCardEmulatorService created and initialized HostCardEmulatorLogic")
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        // Delegate to the HostCardEmulatorLogic
        return hceLogic.processCommandApdu(commandApdu, extras)
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
        // Optionally, you might want to reset state in hceLogic here if needed
    }

    companion object {
        private const val TAG = "HostCardEmulatorService"

        // Expose static methods from HostCardEmulatorLogic if they are still needed externally
        fun getGeneratedReceiverIdentity(): Map<String, String>? {
            return HostCardEmulatorLogic.getGeneratedReceiverIdentity()
        }
    }
}
