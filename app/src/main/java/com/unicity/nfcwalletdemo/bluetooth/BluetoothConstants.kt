package com.unicity.nfcwalletdemo.bluetooth

import java.util.UUID

object BluetoothConstants {
    // Unique UUID for our service - this helps with discovery
    val SERVICE_UUID: UUID = UUID.fromString("f3b45678-1234-5678-9abc-def012345678")
    const val SERVICE_NAME = "UnicityWalletTransfer"
    
    // Connection timeout in milliseconds
    const val CONNECTION_TIMEOUT = 10000L
    
    // Buffer size for data transfer
    const val BUFFER_SIZE = 4096
}