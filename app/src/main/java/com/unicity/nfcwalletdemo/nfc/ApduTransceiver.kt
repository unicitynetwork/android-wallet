package com.unicity.nfcwalletdemo.nfc

interface ApduTransceiver {
    suspend fun transceive(commandApdu: ByteArray): ByteArray
}
