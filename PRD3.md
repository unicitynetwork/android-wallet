You are absolutely correct. This is an excellent and crucial update based on real-world Android development. Your analysis is spot on and identifies the exact limitations of modern Android that make the initial "insecure socket" approach fail.

*   **MAC Address Privacy:** Since Android 6, apps cannot programmatically access the Bluetooth MAC address of nearby devices to protect user privacy. It will always return the dummy address `02:00:00:00:00:00`.
*   **Unreliable Discovery:** Classic Bluetooth Discovery is slow, battery-intensive, and not designed for the instantaneous connection we need.

This means we must pivot to the modern, correct solution for this problem. Your `HYBRID_SOLUTION.md` is heading in the right direction. Let's formalize it into a new, actionable specification.

---

### **Revised Specification: The Modern Android Solution (NFC + Bluetooth Low Energy)**

The key is to abandon classic Bluetooth sockets and embrace **Bluetooth Low Energy (BLE)**. BLE is designed for exactly this scenario: short-burst, low-power data transfers between unpaired devices.

#### **Why This Approach Works and Solves All Problems**

1.  **Solves the MAC Address Problem:** With BLE, we don't need a MAC address. The receiver **advertises** a unique service, and the sender **scans** for that specific advertisement. The advertisement itself is the "address."
2.  **Solves the Unreliable Discovery Problem:** BLE scanning is vastly more reliable, faster, and more power-efficient than classic discovery. We can perform a highly targeted scan for just our app's unique service, making the connection nearly instant.
3.  **Achieves the True Single-Tap UX:** The user experience remains identical to the original goal. The user taps, and the connection happens seamlessly in the background without any pairing prompts or device pickers.
4.  **Maintains Security:** The Unicity SDK still handles all cryptographic signing of the transaction. BLE is merely the transport pipe for this already-secure data.

---

### **Detailed Technical Implementation (NFC + BLE)**

This is a complete replacement for the Bluetooth Classic implementation.

**Core Concept:**
*   **Receiver (Bob):** Acts as a BLE **Peripheral** and sets up a **GATT Server**. It will **advertise** a unique service when it's ready to receive.
*   **Sender (Alice):** Acts as a BLE **Central** and is the **GATT Client**. It will **scan** for the specific service advertisement after the NFC tap.

**1. Define a Shared, Unique UUID**

This is the most critical piece. Both apps must use the exact same set of UUIDs. Generate your own for your app.

```kotlin
// In a shared constants file
object BleConstants {
    // A unique UUID for your app's service
    val SERVICE_UUID: UUID = UUID.fromString("your-unique-service-uuid-here")
    
    // A UUID for the characteristic Alice WRITES to and Bob READS from
    val CHARACTERISTIC_REQUEST_UUID: UUID = UUID.fromString("your-unique-request-uuid-here")

    // A UUID for the characteristic Bob WRITES to and Alice SUBSCRIBES to for notifications
    val CHARACTERISTIC_RESPONSE_UUID: UUID = UUID.fromString("your-unique-response-uuid-here")
}
```

**2. Receiver (Bob's) Flow: The Peripheral / GATT Server**

When Bob taps "Receive Token":

1.  **Start NFC HCE:** The app activates Host Card Emulation. The *only* purpose of the HCE service is to respond to a tap, signaling "I'm ready." It does **not** need to send any data.
2.  **Start BLE Advertising:** Simultaneously, the app starts advertising its `SERVICE_UUID`.
    *   Use `BluetoothLeAdvertiser`.
    *   Create `AdvertiseSettings` (e.g., `ADVERTISE_MODE_LOW_LATENCY`).
    *   Create `AdvertiseData`, making sure to include your `SERVICE_UUID`.
3.  **Setup GATT Server:**
    *   Create a `BluetoothGattServer` using `bluetoothManager.openGattServer()`.
    *   Define a `BluetoothGattService` with your `SERVICE_UUID`.
    *   Add two `BluetoothGattCharacteristic`s to this service:
        *   `CHARACTERISTIC_REQUEST_UUID`: Properties `WRITE`.
        *   `CHARACTERISTIC_RESPONSE_UUID`: Properties `NOTIFY` and `READ`.
    *   Implement the `BluetoothGattServerCallback` to handle events:
        *   `onConnectionStateChange`: To know when Alice has connected.
        *   `onCharacteristicWriteRequest`: This is triggered when Alice sends data (the token request or the final token JSON). Bob reads the data here.
        *   `onNotificationSent`: To confirm that a notification was successfully sent to Alice.

**3. Sender (Alice's) Flow: The Central / GATT Client**

When Alice taps "Send Token":

1.  **Start NFC Reader Mode:** The app waits for a tap.
2.  **On NFC Tap:** The moment the tap is detected, the app knows Bob is ready and advertising.
3.  **Start BLE Scan:**
    *   Immediately stop NFC Reader Mode and start a BLE scan using `BluetoothLeScanner`.
    *   **Crucially, use a `ScanFilter` to only look for devices advertising your `SERVICE_UUID`.** This makes the scan extremely fast and efficient.
    *   `val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()`
4.  **Connect to GATT Server:**
    *   In the `ScanCallback`'s `onScanResult`, you have found Bob's device.
    *   Stop the scan immediately.
    *   Call `device.connectGatt()` to connect to Bob's GATT server.
5.  **Implement `BluetoothGattCallback`:** This is where the two-way communication happens.
    *   `onConnectionStateChange`: When the connection is successful (`STATE_CONNECTED`), call `gatt.discoverServices()`.
    *   `onServicesDiscovered`: The services and characteristics are now known.
        *   Find `CHARACTERISTIC_RESPONSE_UUID` and call `gatt.setCharacteristicNotification(characteristic, true)` to subscribe to updates from Bob.
        *   Find `CHARACTERISTIC_REQUEST_UUID`. You will use this to write data.
    *   `onCharacteristicChanged`: This is the "inbox." It's triggered when Bob sends data (the Unicity address or the final confirmation).
    *   `onCharacteristicWrite`: Confirms your data was successfully sent to Bob.

### **Revised End-to-End Workflow**

1.  **Bob (Receiver):** Taps "Receive". App starts NFC HCE and starts **advertising** its BLE Service UUID. Status: "Ready to receive..."
2.  **Alice (Sender):** Taps "Send". App starts NFC Reader Mode. Status: "Ready to send. Tap recipient's phone."
3.  **The Tap:** Alice taps Bob's phone.
    *   Alice's app gets the NFC signal, stops NFC, and immediately starts a targeted **BLE scan** for `SERVICE_UUID`.
4.  **Connection (100-500ms):**
    *   Alice's scan finds Bob's advertisement.
    *   Alice stops scanning and calls `connectGatt`.
    *   A connection is established. **No pairing prompt.** Both apps update status to "Connected."
5.  **Dialog (Data Exchange):**
    *   Alice discovers services, subscribes to notifications, and then **writes** the token request `{ "action": "request_unicity_address", ... }` to `CHARACTERISTIC_REQUEST_UUID`.
    *   Bob's `onCharacteristicWriteRequest` is triggered. He reads the request, generates the Unicity address.
    *   Bob updates the value of `CHARACTERISTIC_RESPONSE_UUID` with the address and calls `notifyCharacteristicChanged`.
    *   Alice's `onCharacteristicChanged` is triggered. She receives the address.
    *   Alice calls the Unicity SDK, gets the final token JSON, and **writes** it to `CHARACTERISTIC_REQUEST_UUID`.
    *   Bob's `onCharacteristicWriteRequest` is triggered again. He saves the token and updates his wallet.
    *   Bob sends a final confirmation `{ "status": "transfer_complete" }` via another notification on `CHARACTERISTIC_RESPONSE_UUID`.
6.  **Teardown:**
    *   Upon receiving the final confirmation, Alice's app shows "Success!" and calls `gatt.close()`.
    *   Bob's `onConnectionStateChange` shows a disconnection. His app shows "Success!" and stops advertising.

This NFC + BLE architecture is the industry-standard way to solve this problem on modern mobile operating systems and will give you the robust, seamless, single-tap experience you require.
