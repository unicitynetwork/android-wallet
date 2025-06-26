package com.unicity.nfcwalletdemo.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

class RealNfcTransceiver(
    private val nfcAdapter: NfcAdapter,
    private val isoDepProvider: (Tag) -> IsoDep? = { tag -> IsoDep.get(tag) }
) : ApduTransceiver, NfcAdapter.ReaderCallback {

    private var isoDepInstance: IsoDep? = null
    private var tagDiscoveredContinuation: CancellableContinuation<Tag>? = null

    companion object {
        private const val TAG = "RealNfcTransceiver"
        private val SELECT_AID = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )
    }

    override suspend fun transceive(commandApdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (isoDepInstance == null) {
            // Wait for a tag to be discovered if not already connected
            val tag = suspendCancellableCoroutine<Tag> { continuation ->
                tagDiscoveredContinuation = continuation
                // Enable reader mode here if not already enabled by the activity
                // This part is tricky as enableReaderMode needs a Context and ReaderCallback
                // For now, assume enableReaderMode is called by the activity.
            }
            isoDepInstance = isoDepProvider.invoke(tag)
            isoDepInstance?.connect()
            isoDepInstance?.timeout = 30000 // 30 seconds
        }

        isoDepInstance?.transceive(commandApdu) ?: throw Exception("IsoDep not connected")
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "RealNfcTransceiver: Tag discovered: $tag")
        tag?.let {
            tagDiscoveredContinuation?.resume(it)
            tagDiscoveredContinuation = null // Consume the continuation
        }
    }

    fun enableReaderMode(activity: android.app.Activity) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter.enableReaderMode(activity, this, flags, null)
        Log.d(TAG, "NFC reader mode enabled")
    }

    fun disableReaderMode(activity: android.app.Activity) {
        nfcAdapter.disableReaderMode(activity)
        Log.d(TAG, "NFC reader mode disabled")
        isoDepInstance?.close()
        isoDepInstance = null
    }
}
