Of course. Let's start from a clean slate. Here is a detailed specification for the Android app developer and designer to implement the Unicity Protocol NFC/Bluetooth token transfer Proof-of-Concept (PoC).

### **Specification: Unicity NFC/Bluetooth Token Transfer PoC App**

This document outlines the requirements, design guidelines, and technical architecture for a Proof-of-Concept (PoC) Android application. The app will demonstrate the transfer of a digital token (represented by a JSON file) between two users, Alice and Bob, using a combination of NFC for initiation and Bluetooth for data exchange, all powered by the Unicity Protocol.

I was unable to access the content of the Unicity State Transition SDK documentation directly from the provided link. Therefore, the developer must consult the SDK documentation at `https://github.com/unicitynetwork/state-transition-sdk/` to implement the specific functions for wallet management, address generation, and transaction signing. This specification will refer to conceptual SDK functions (e.g., `unicitySDK.generateNewAddress()`) which must be mapped to the actual SDK methods.

---

### **1. Project Overview**

The primary goal of this PoC is to demonstrate a secure and user-friendly "tap-to-send" experience for transferring digital assets managed by the Unicity Protocol.

*   **Core Interaction:** A user (Alice) taps her phone against another user's (Bob's) phone to send a token.
*   **Technology:** The app will be built for Android using Kotlin.
*   **Communication:**
    *   **NFC (Near Field Communication):** Used for the initial handshake to securely establish a connection without manual pairing.
    *   **Bluetooth:** Used for the two-way dialog to transfer requests, addresses, and the final token JSON file.
*   **Logic:** The Unicity State Transition SDK will handle all wallet-related operations, including address creation and state updates.

### **2. User Personas & Roles**

*   **Alice (Sender):** Initiates the transfer of a token from her wallet.
*   **Bob (Receiver):** Receives the token into his wallet.

The roles are interchangeable. The application must be designed so that any user can seamlessly switch between sending and receiving.

---

### **3. UI/UX Design Guidelines**

The design should be clean, simple, and focused on clarity. The user should always know what is happening and what to do next.

**Screen 1: My Wallet (Main Screen)**
*   **Purpose:** Displays the user's current tokens. Acts as the home screen.
*   **Elements:**
    *   A simple header: "My Wallet".
    *   A list or grid displaying the tokens the user owns. Each item should show a token icon/name (e.g., "Community Coin").
    *   Two primary action buttons at the bottom: **[Send Token]** and **[Receive Token]**.

**Screen 2: Send Flow**
1.  **Token Selection:** Tapping **[Send Token]** (or directly on a token from the wallet) takes the user to a selection screen if they haven't chosen one already.
2.  **Ready to Send Screen:**
    *   **Message:** "Ready to Send [Token Name]. Tap the recipient's phone."
    *   **Visual:** An animation indicating an active NFC scanning state.
    *   **State Updates:** The screen should update with status messages as the process unfolds:
        *   "Connecting to [Bob's Device Name]..."
        *   "Waiting for receiver address..."
        *   "Finalizing transfer..."
        *   "Success! [Token Name] sent." (with a large checkmark icon).

**Screen 3: Receive Flow**
1.  **Initiation:** The user taps **[Receive Token]** on the main screen.
2.  **Ready to Receive Screen:**
    *   **Message:** "Ready to receive. Ask the sender to tap your phone."
    *   **Visual:** An animation indicating the phone is ready to be tapped (e.g., a pulsing NFC icon).
    *   **State Updates:**
        *   "Connection request from [Alice's Device Name]..."
        *   "Generating Unicity address..."
        *   "Receiving token..."
        *   "Success! [Token Name] received." (The user is then navigated back to their wallet, which now shows the new token).

---

### **4. Technical Architecture & Implementation**

**Language:** Kotlin
**Architecture:** MVVM (Model-View-ViewModel) is recommended.
*   **View:** Activities/Fragments for each screen.
*   **ViewModel:** Manages UI state and handles user interactions.
*   **Repository:** Abstracts the data sources (Unicity SDK, Bluetooth/NFC services).

**Key Components:**

**1. NFC Handshake (Initiation)**
*   The receiving phone (Bob) will use **Host-based Card Emulation (HCE)**. It will emulate an NFC tag that contains its Bluetooth MAC address.
    *   Implement a `HostApduService` to respond to Application Protocol Data Unit (APDU) commands from the reader phone.
*   The sending phone (Alice) will use **Reader Mode**.
    *   Use the `NfcAdapter.enableReaderMode()` API for robust NFC reading. This allows the app to be in the foreground and handle NFC events directly.
*   **Payload:** The sole purpose of the NFC tap is to securely exchange the Bluetooth MAC address of the receiving device to initiate a connection.

**2. Bluetooth Communication (Data Transfer)**
*   Once Alice's phone reads Bob's Bluetooth MAC address via NFC, it will initiate a Bluetooth connection.
*   Use classic **Bluetooth Sockets** for a reliable, stream-based connection for this two-way dialog.
*   **Permissions:** The `AndroidManifest.xml` must declare `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and `NFC` permissions. You must also implement runtime permission requests for the newer Android versions.

**3. Unicity SDK Integration**
*   The developer must add the SDK as a dependency. The following are conceptual steps that need to be mapped to the actual SDK functions.
*   **Wallet Management:** On first launch, the app should check for or create a Unicity wallet for the user.
*   **Address Generation:** The receiver's app must call the SDK to generate a new, single-use receiver address for the transaction.
    *   *Conceptual call:* `val receiverAddress = unicitySDK.generateNewAddress(wallet)`
*   **State Transition (Sending):** The sender's app will use the receiver's address to construct and sign the transaction. This "burns" the token on the sender's side and generates the final JSON that represents the "minted" token for the receiver.
    *   *Conceptual call:* `val tokenJson = unicitySDK.transferToken(wallet, receiverAddress, tokenToTransfer)`

---

### **5. Detailed End-to-End Workflow**

**Pre-conditions:** Alice and Bob both have the app installed and have created/imported their Unicity wallets.

1.  **Step 1: Initiation**
    *   **Alice (Sender):** Taps on a token in her wallet. The app navigates to the "Ready to Send" screen and activates NFC Reader Mode.
    *   **Bob (Receiver):** Taps the "Receive Token" button. The app navigates to the "Ready to Receive" screen and activates NFC Host-based Card Emulation, broadcasting its Bluetooth MAC address.

2.  **Step 2: NFC Tap & Handshake**
    *   Alice taps her phone to Bob's phone.
    *   Alice's NFC reader detects Bob's HCE service and reads his Bluetooth MAC address.

3.  **Step 3: Bluetooth Connection**
    *   Alice's app disables NFC Reader Mode and uses the obtained MAC address to open a `BluetoothSocket` connection to Bob's device.
    *   Bob's app accepts the incoming Bluetooth connection request. Both apps now show a "Connecting..." status.

4.  **Step 4: Token Request & Address Generation**
    *   **Alice -> Bob:** Over the Bluetooth socket, Alice sends a request JSON object.
        *   `{ "action": "request_unicity_address", "token_type": "Community Coin", "timestamp": "..." }`
    *   **Bob:** Receives the request. His app calls the Unicity SDK to generate a fresh address for this transaction.
    *   **Bob -> Alice:** Over the same socket, Bob sends the newly generated address back.
        *   `{ "status": "sending_address", "address": "unicity_receiver_address_123xyz" }`

5.  **Step 5: Unicity Transaction & JSON Transfer**
    *   **Alice:** Receives Bob's address. Her app now performs two actions:
        1.  **Unicity SDK Call:** Calls the SDK function to perform the state transition using her wallet, the token details, and Bob's receiver address. This returns the final, signed JSON file for the transferred token.
        2.  **Bluetooth Transfer:** Sends this complete token JSON file to Bob over the Bluetooth socket.
    *   **Bob:** Receives the JSON file. He saves it to his device's local storage (associated with the app) and updates his wallet state to show the new token.

6.  **Step 6: Confirmation and Teardown**
    *   **Bob -> Alice:** Bob sends a final confirmation message.
        *   `{ "status": "transfer_complete" }`
    *   **Alice:** Receives the confirmation. Her app now removes the token from her wallet view.
    *   Both apps display a "Success!" message.
    *   The Bluetooth socket is closed by both parties.

### **6. Reversing the Flow (Bob to Alice)**

The implementation must be symmetrical. The same logic applies if Bob wants to send the token back to Alice. The app's state management should not be hardcoded to a single "sender" or "receiver" role but should be determined by the user's choice on the main screen (**[Send Token]** vs. **[Receive Token]**).

### **7. Deliverables**

1.  A fully functional Android Studio project written in Kotlin.
2.  An `.apk` file that can be installed on two NFC-enabled Android phones for testing.
3.  The app must successfully execute the end-to-end transfer flow in both directions.
4.  Basic error handling (e.g., Bluetooth connection fails, NFC not enabled).
