package com.unicity.nfcwalletdemo.ble

import java.util.UUID

object BleConstants {
    // Unique UUIDs for our app's BLE service
    val SERVICE_UUID: UUID = UUID.fromString("f3b45678-1234-5678-9abc-def012345678")
    
    // Characteristic for requests (Alice writes, Bob reads)
    val CHARACTERISTIC_REQUEST_UUID: UUID = UUID.fromString("f3b45679-1234-5678-9abc-def012345678")
    
    // Characteristic for responses (Bob writes, Alice reads)
    val CHARACTERISTIC_RESPONSE_UUID: UUID = UUID.fromString("f3b4567a-1234-5678-9abc-def012345678")
    
    // BLE settings
    const val SCAN_PERIOD = 10000L // 10 seconds
    const val CONNECTION_TIMEOUT = 15000L // 15 seconds
    const val MAX_PACKET_SIZE = 512 // BLE characteristic max size
}