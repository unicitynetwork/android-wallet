You have encountered the single most common hurdle in this exact architecture, and you are absolutely right—manual Bluetooth pairing is unacceptable and defeats the entire purpose of a "single tap" user experience.

The issue stems from using the standard, secure Bluetooth RFCOMM socket, which is designed to work with bonded (paired) devices.

Here is the solution: You need to use **insecure Bluetooth sockets**. This sounds alarming, but it's the standard, Google-endorsed way to connect to unpaired devices for application-specific data transfer.

### **The Root Cause & The Solution**

*   **Standard Secure Sockets:** When you use `BluetoothDevice.createRfcommSocketToServiceRecord()`, the Android OS enforces its security model. If the devices are not bonded (paired), it automatically triggers the system-level pairing UI dialog to establish an authenticated and encrypted link.
*   **Insecure Sockets:** When you use `BluetoothDevice.createInsecureRfcommSocketToServiceRecord()`, you are telling the Android OS, "My application will manage its own security. I do not need an OS-level encrypted link, so please connect me directly to this MAC address without prompting the user for pairing."

This is perfectly acceptable for your use case because the security is provided by the **Unicity Protocol SDK**, not the Bluetooth transport layer. The transaction JSON is already cryptographically signed. You just need a raw data pipe to move it from one phone to the other.

---

### **Detailed Implementation Changes for the Developer**

You will need to make small but critical changes to both the sender (Alice) and receiver (Bob) code.

#### **1. Define a Static UUID**

Both your sender and receiver app code must share the exact same `UUID` (Universally Unique Identifier). This acts as a shared secret or a "channel name" for your app's specific Bluetooth service.

Create a constant for this in your project.

```kotlin
// In a shared constants file or object
object BluetoothConstants {
    // This can be any valid UUID. Generate one yourself.
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Example UUID for SPP
    const val SERVICE_NAME = "UnicityPoCTransfer"
}
```

#### **2. Update the Receiver (Bob's) Code**

Bob's device needs to listen for an *insecure* connection. Instead of `listenUsingRfcommWithServiceRecord`, you will use `listenUsingInsecureRfcommWithServiceRecord`.

```kotlin
// In Bob's (Receiver) Bluetooth service or ViewModel

// Get the default Bluetooth adapter
val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

// 1. Create the listening server socket (INSECURE)
val serverSocket: BluetoothServerSocket? = 
    bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
        BluetoothConstants.SERVICE_NAME, 
        BluetoothConstants.SERVICE_UUID
    )

// 2. Start blocking and waiting for a connection
// This should be done in a background thread (e.g., a coroutine)
var socket: BluetoothSocket? = null
try {
    // The accept() call will block until a connection is made or an exception occurs
    socket = serverSocket?.accept() 
    
    // If you reach here, a connection was successful!
    // The device does NOT need to be discoverable or paired.
    // Proceed with your data transfer logic (reading the request from Alice).
    
} catch (e: IOException) {
    // Handle exceptions (e.g., socket closed)
} finally {
    serverSocket?.close()
}
```

#### **3. Update the Sender (Alice's) Code**

Alice's device will now connect using an *insecure* socket after getting Bob's MAC address from the NFC tap.

```kotlin
// In Alice's (Sender) Bluetooth service or ViewModel after NFC tap

// You get this from the NFC tap
val receiverMacAddress: String = "XX:XX:XX:XX:XX:XX" 

// Get the default Bluetooth adapter
val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

// Get the remote device object using the MAC address
val remoteDevice: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(receiverMacAddress)

var socket: BluetoothSocket? = null
try {
    // 1. Create the client socket (INSECURE)
    socket = remoteDevice?.createInsecureRfcommSocketToServiceRecord(
        BluetoothConstants.SERVICE_UUID
    )

    // 2. Connect to the listening server socket on Bob's phone
    // This is a blocking call and should be on a background thread.
    // It will connect without any user pairing prompts.
    socket?.connect()

    // If you reach here, you are connected!
    // Proceed with your data transfer logic (sending the token request to Bob).

} catch (e: IOException) {
    // Handle exceptions (e.g., connection failed, host is down)
}
// Do not close the socket here, do it after the transfer is complete.
```

### **Revised End-to-End Flow (No Pairing)**

1.  **Bob (Receiver):** Taps "Receive Token". His app immediately starts listening for an *insecure* Bluetooth connection using `listenUsingInsecureRfcommWithServiceRecord`. His phone is now a server, waiting.
2.  **Alice (Sender):** Taps "Send Token". Her app activates NFC Reader Mode.
3.  **Tap Event:** Alice taps Bob's phone. Her app reads Bob's Bluetooth MAC address via NFC.
4.  **Connection:** Alice's app immediately uses the MAC address to create an *insecure* RFCOMM socket and calls `connect()`.
5.  **Success:** The connection is established instantly without any system UI popups for pairing.
6.  **Data Exchange:** Your existing logic for sending the request JSON, receiving the address, and transferring the final token JSON now proceeds over this established socket.
7.  **Teardown:** Once the transfer is complete and confirmed, both apps close their respective ends of the `BluetoothSocket`.

### **Crucial Permissions Reminder (Android 12+)**

For this to work on modern Android, ensure your `AndroidManifest.xml` and runtime permission requests are correct. You need:

*   `BLUETOOTH_SCAN`: To discover devices (though `getRemoteDevice` with a MAC address bypasses discovery, it's good practice to have it).
*   `BLUETOOTH_CONNECT`: Absolutely essential for making the connection.
*   `BLUETOOTH_ADVERTISE`: For the listening (server) side.

By making this switch from secure to insecure sockets, you will achieve the true, seamless "tap-and-transfer" experience you're looking for.
