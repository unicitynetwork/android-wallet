package org.unicitylabs.wallet.nfc

interface ApduTransceiver {
    suspend fun transceive(commandApdu: ByteArray): ByteArray
}
