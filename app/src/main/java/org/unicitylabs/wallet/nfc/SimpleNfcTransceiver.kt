package org.unicitylabs.wallet.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simplified NFC transceiver that connects to tag on first transceive
 */
class SimpleNfcTransceiver(
    private val nfcAdapter: NfcAdapter
) : ApduTransceiver, NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "SimpleNfcTransceiver"
        private const val TAG_WAIT_TIMEOUT_MS = 5000L
    }

    private var currentTag: Tag? = null
    private var isoDep: IsoDep? = null
    private val tagDiscovered = AtomicBoolean(false)

    override suspend fun transceive(commandApdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        // Wait for tag to be discovered if not already connected
        if (isoDep == null || !isoDep!!.isConnected) {
            Log.d(TAG, "Waiting for NFC tag to be discovered...")
            
            withTimeout(TAG_WAIT_TIMEOUT_MS) {
                while (!tagDiscovered.get()) {
                    delay(50) // Check every 50ms
                }
            }
            
            // Tag discovered, wait a bit for connection to stabilize
            delay(200) // Increased delay for stability
        }
        
        val iso = isoDep
        if (iso != null && iso.isConnected) {
            try {
                Log.d(TAG, "Transceiving ${commandApdu.size} bytes")
                return@withContext iso.transceive(commandApdu)
            } catch (e: Exception) {
                Log.e(TAG, "Transceive failed", e)
                isoDep = null
                tagDiscovered.set(false)
                throw Exception("Lost connection to tag. Please tap again.")
            }
        } else {
            throw Exception("Failed to connect to NFC tag. Please tap phones together.")
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "Tag discovered: $tag")
        
        tag?.let {
            currentTag = it
            try {
                isoDep?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
            
            isoDep = IsoDep.get(tag)?.apply {
                try {
                    connect()
                    timeout = 30000 // 30 seconds
                    Log.d(TAG, "Connected to tag successfully")
                    tagDiscovered.set(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to tag", e)
                    isoDep = null
                    tagDiscovered.set(false)
                }
            }
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
        
        // Clean up
        try {
            isoDep?.close()
        } catch (e: Exception) {
            // Ignore
        }
        isoDep = null
        currentTag = null
        tagDiscovered.set(false)
    }
}