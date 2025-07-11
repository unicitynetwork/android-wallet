# Unicity NFC Wallet Demo

A fully functional Android wallet app for the Unicity Protocol, demonstrating real cryptographic token transfers via NFC using Android Host Card Emulation (HCE). Users can mint, store, and transfer genuine Unicity tokens between devices with a simple tap.

## 🚀 Current Implementation

**Token Type**: Real Unicity Protocol tokens with cryptographic signatures  
**Transfer Method**: Hybrid NFC + Bluetooth LE mesh for unlimited token sizes  
**SDK Integration**: Unicity State Transition SDK v1.4.7 with offline transfer support  
**Transfer Protocol**: NFC handshake for secure discovery, Bluetooth for data transfer  
**Transfer Speed**: 1-2 seconds NFC handshake + ~50-100 KB/s Bluetooth transfer  
**User Experience**: Quick NFC tap to initiate, automatic Bluetooth transfer

## ✨ Features

- **Real Unicity Tokens**: Mint genuine cryptographic tokens on the Unicity Protocol
- **Direct NFC Transfer**: Tap-to-send tokens between Android devices
- **Blockchain Integration**: Tokens are committed to the Unicity test network
- **Modern UI**: Material Design with Unicity branding
- **Real-time Progress**: Transfer progress indicators on both devices
- **Token Management**: Expandable cards with detailed token information
- **Custom Token Minting**: Create tokens with custom names, amounts, and data

## 📱 Requirements

- **Android 7.0+** (API level 24+)
- **NFC-enabled device**
- **Host Card Emulation support** (most modern Android devices)
- **Internet connection** (for blockchain commitments)

## 🛠 Setup

### Quick Start

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd nfc-wallet-demo
   ```

2. **Install Java 17** (required for Android development)
   ```bash
   brew install openjdk@17  # macOS
   # or use your system's package manager
   ```

3. **Open in Android Studio**
   - Import the project
   - Sync Gradle dependencies
   - Ensure Java 17 is selected in project structure

4. **Build and install**
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # macOS
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Test on two NFC devices**
   - Install on both devices
   - Enable NFC in device settings
   - Open app on both devices
   - Tap one device to the other to transfer tokens

## 🏗 Architecture

### MVVM Pattern
```
UI Layer (Activities/Fragments)
    ↓
ViewModel Layer (State Management)
    ↓
Repository Layer (Data Access)
    ↓
Data Layer (Models/Storage)
```

### Key Components

- **MainActivity**: Token list and wallet management
- **ReceiveActivity**: Handles incoming token transfers via HCE
- **HostCardEmulatorService**: NFC HCE service for receiving tokens
- **HybridNfcBluetoothClient**: Orchestrates hybrid NFC + Bluetooth transfers
- **BluetoothMeshTransferService**: Handles Bluetooth LE mesh data transfer
- **DirectNfcClient**: Legacy NFC-only transfer implementation
- **WalletRepository**: Manages token storage and Unicity SDK integration
- **UnicitySdkService**: WebView-based bridge to Unicity State Transition SDK

## 📡 Transfer Flow

### Hybrid NFC + Bluetooth Transfer Protocol
The app now implements a hybrid approach that combines NFC for secure peer discovery with Bluetooth LE mesh networking for high-bandwidth data transfer. This eliminates the 64KB NFC payload limitation while maintaining security and ease of use.

For detailed technical documentation, see [HYBRID_TRANSFER_IMPLEMENTATION.md](HYBRID_TRANSFER_IMPLEMENTATION.md).

### Legacy NFC-Only Transfer Protocol
The previous NFC-only implementation is still available but limited to tokens under 64KB:

#### Sending a Token
1. User taps "Send Token" and expands token card
2. User taps sending device to receiving device
3. `DirectNfcClient` establishes NFC connection and waits for stabilization
4. **Handshake Phase**:
   - Sender requests receiver to generate a new cryptographic address
   - Receiver generates identity (secret + nonce) and derives address
   - Receiver sends address back to sender
5. **Transfer Phase**:
   - Sender creates offline transaction using receiver's address
   - Transaction package is chunked and sent via APDU commands
   - Receiver saves the offline transaction and broadcasts to UI
6. **Completion Phase**:
   - Receiver processes offline transaction with SDK
   - Token is added to receiver's wallet
   - Success confirmation shown on both devices

#### Receiving a Token
1. `HostCardEmulatorService` activates when NFC field detected
2. **Address Generation**:
   - Receives token transfer request with token metadata
   - Generates new cryptographic identity via SDK
   - Returns receiver address to sender
3. **Transaction Reception**:
   - Receives offline transaction chunks
   - Reconstructs complete transaction package
   - Saves to SharedPreferences for persistence
4. **Processing**:
   - `ReceiveActivity` catches broadcast or finds saved transfer
   - Completes offline transfer using SDK
   - Updates wallet with new token

### Technical Details
- **Protocol**: Custom APDU commands over NFC-A/ISO 14443 Type A
- **Commands**:
  - `CMD_SELECT_AID (0xA4)`: Initial application selection
  - `CMD_REQUEST_RECEIVER_ADDRESS (0x05)`: Request receiver to generate address
  - `CMD_GET_RECEIVER_ADDRESS (0x06)`: Query for generated address
  - `CMD_SEND_OFFLINE_TRANSACTION (0x07)`: Send transaction chunks
  - `CMD_TEST_PING (0x08)`: Test mode for debugging
- **Chunk Size**: 200 bytes per APDU (conservative for stability)
- **Buffer Limit**: ~36KB total transfer size (Android HCE limitation)
- **Timeouts**: 
  - 30 seconds for address generation
  - Connection stabilization delays between operations
- **Persistence**: Transfers saved to SharedPreferences to survive app restarts

## 🧪 Testing

### Emulator Testing (UI Only)
```bash
# Start Android emulator
emulator -avd <your-avd-name>

# Install and run
./gradlew installDebug
adb shell am start -n com.unicity.nfcwalletdemo/.ui.wallet.MainActivity
```

**Note**: NFC transfers cannot be tested in emulator - UI functionality only.

### Real Device Testing

#### Requirements
- 2 physical Android devices with NFC
- Both devices have NFC enabled in Settings
- App installed on both devices

#### Test Procedure
1. **Setup**: Open app on both devices
2. **Mint**: Use menu → "Mint a Token" to create a real Unicity token
3. **Send**: Device A - tap token, select "Send Token"  
4. **Transfer**: Tap Device A to Device B (back-to-back)
5. **Verify**: Check token appears in Device B's wallet
6. **Reverse**: Test transfer from Device B back to Device A

#### Common Issues
- **"Tag was lost"**: Devices moved apart during transfer - keep steady contact for entire duration
- **"NFC connection lost"**: Connection interrupted - retry with phones held more firmly together
- **"Failed to get receiver address"**: Handshake failed - ensure receiver app is open and active
- **"State data is not part of transaction"**: SDK processing error - check token format and retry
- **App crashes**: Check logs for specific errors
- **Transfer succeeds but token doesn't appear**: Check receiver's wallet after a few seconds (processing delay)

## 📊 Token Size Performance

| Token Size | Transfer Time | Chunks | Notes |
|------------|---------------|---------|-------|
| 2KB        | ~1-2 seconds  | ~8     | Very fast |
| 4KB        | ~2-4 seconds  | ~16    | Fast |
| 8KB        | ~4-8 seconds  | ~32    | Good |
| 16KB       | ~8-15 seconds | ~64    | Acceptable |
| 32KB       | ~15-30 seconds| ~128   | Slow |
| 64KB       | ~30-60 seconds| ~256   | Very slow |

## 🔐 Unicity SDK Integration

The app uses the Unicity State Transition SDK to create real cryptographic tokens:

### Token Minting Process
1. **Generate Identity**: Creates cryptographic key pair (secret + nonce)
2. **Submit to Network**: Sends mint transaction to Unicity aggregator
3. **Wait for Proof**: Receives blockchain inclusion proof
4. **Create Token**: Constructs complete token with transaction history

### Key SDK Features Used
- **SigningService**: Cryptographic signatures for token ownership
- **MaskedPredicate**: Privacy-preserving ownership predicates
- **StateTransitionClient**: Blockchain interaction (no separate offline client)
- **InclusionProof**: Proof of blockchain commitment with direct verification
- **Commitment**: Standard commitment class for offline transfers
- **CommitmentJsonSerializer**: JSON serialization for offline transfer packages
- **TokenFactory**: Token reconstruction from JSON data
- **Identity Generation**: Create new cryptographic identities for receiving tokens

## 🏭 Production Checklist

### Security
- [x] Digital signatures via Unicity SDK
- [x] Cryptographic token authentication
- [ ] Secure storage for private keys
- [ ] Input validation and sanitization

### Integration
- [x] Real Unicity SDK integration
- [x] Connection to Unicity test network
- [ ] Production network credentials
- [ ] User authentication system

### Testing
- [ ] Comprehensive device compatibility testing
- [ ] Edge case handling (interrupted transfers, etc.)
- [ ] Performance testing with various token sizes
- [ ] Security penetration testing

### Release
- [ ] Code signing with production certificates
- [ ] ProGuard/R8 code obfuscation
- [ ] Remove debug logging
- [ ] Play Store compliance review

## 🔧 Development

### Project Structure
```
app/src/main/
├── java/com/unicity/nfcwalletdemo/
│   ├── data/                    # Data layer
│   │   ├── api/                 # API interfaces
│   │   │   └── CryptoPriceApi.kt
│   │   ├── model/               # Data models
│   │   │   ├── Token.kt         # Unicity token model
│   │   │   ├── TransferRequest.kt
│   │   │   ├── TransferResponse.kt
│   │   │   └── Wallet.kt
│   │   ├── repository/          # Data repositories
│   │   │   └── WalletRepository.kt
│   │   └── service/             # Services
│   │       └── CryptoPriceService.kt
│   ├── model/                   # Domain models
│   │   └── CryptoCurrency.kt
│   ├── nfc/                     # NFC implementation
│   │   ├── ApduTransceiver.kt   # APDU command interface
│   │   ├── BluetoothHandshake.kt # Bluetooth handshake data classes
│   │   ├── DirectNfcClient.kt   # Legacy NFC-only sender
│   │   ├── HostCardEmulatorLogic.kt # Receiver logic
│   │   ├── HostCardEmulatorService.kt # HCE service
│   │   ├── HybridNfcBluetoothClient.kt # Hybrid transfer orchestrator
│   │   ├── NfcTestChannel.kt    # Test mode support
│   │   └── RealNfcTransceiver.kt # NFC transceiver
│   ├── bluetooth/               # Bluetooth implementation
│   │   └── BluetoothMeshTransferService.kt # BLE mesh transfer
│   ├── sdk/                     # Unicity SDK integration
│   │   ├── UnicitySdkService.kt # WebView bridge service
│   │   └── UnicityTokenData.kt  # SDK data models
│   ├── ui/                      # UI layer
│   │   ├── receive/
│   │   │   └── ReceiveActivity.kt # Token receiving UI
│   │   ├── send/
│   │   │   └── SendActivity.kt  # Legacy send activity
│   │   └── wallet/
│   │       ├── AssetDialogAdapter.kt
│   │       ├── CryptoAdapter.kt
│   │       ├── MainActivity.kt  # Main wallet UI
│   │       └── TokenAdapter.kt  # Token list adapter
│   ├── utils/                   # Utilities
│   │   └── PermissionUtils.kt
│   └── viewmodel/               # ViewModels
│       ├── ReceiveViewModel.kt
│       ├── SendViewModel.kt
│       └── WalletViewModel.kt
├── assets/
│   ├── bridge.html              # WebView bridge HTML
│   ├── unicity-sdk.js           # Bundled Unicity SDK v1.4.7
│   └── unicity-wrapper.js       # JavaScript wrapper (updated for SDK v1.4.7)
└── res/
    ├── drawable/                # Icons and graphics
    ├── layout/                  # XML layouts
    │   ├── activity_main.xml
    │   ├── activity_receive.xml
    │   ├── dialog_mint_token.xml
    │   ├── item_crypto.xml
    │   └── item_token.xml
    ├── values/                  # Resources
    │   ├── colors.xml
    │   ├── strings.xml
    │   ├── styles.xml
    │   └── themes.xml
    └── xml/                     # Configuration
        └── apduservice.xml      # HCE service config
```

### Key Files

#### NFC Transfer Core
- `DirectNfcClient.kt`: Handles sending tokens via NFC
  - Implements offline transfer handshake protocol
  - Manages connection stability and retries
  - Chunks data into APDU commands
- `HostCardEmulatorLogic.kt`: Processes incoming NFC commands
  - Generates receiver addresses on demand
  - Handles chunked data reception
  - Saves transfers to SharedPreferences
- `HostCardEmulatorService.kt`: Android HCE service wrapper
- `ApduTransceiver.kt`: Interface for APDU communication
- `RealNfcTransceiver.kt`: IsoDep NFC implementation

#### UI Components
- `MainActivity.kt`: Main wallet interface
  - Token list with expandable cards
  - Settings menu with test options
  - NFC transfer initiation
- `ReceiveActivity.kt`: Token receiving UI
  - Shows transfer progress
  - Handles offline transfer processing
  - Manages success/error states
- `TokenAdapter.kt`: RecyclerView adapter for token list

#### SDK Integration
- `UnicitySdkService.kt`: WebView-based SDK bridge
  - Manages JavaScript interface
  - Handles SDK method calls
  - Processes offline transfers
- `unicity-wrapper.js`: JavaScript wrapper for SDK
- `bridge.html`: WebView container for SDK

#### Data Layer
- `WalletRepository.kt`: Token storage and SDK operations
- `Token.kt`: Unicity token data model
- `apduservice.xml`: HCE service configuration

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test

# Install debug APK
./gradlew installDebug
```

## 📝 Notes

### Design Decisions
- **Real Unicity tokens**: Full SDK integration for genuine blockchain tokens
- **Offline transfer protocol**: Receiver generates unique address for each transfer
- **WebView bridge**: Enables TypeScript SDK usage in Android app
- **NFC-only approach**: Bluetooth pairing proved unreliable on modern Android
- **Direct HCE**: Eliminates need for backend servers during transfer
- **Chunked transfers**: Works within NFC APDU size limitations (200 bytes/chunk)
- **Connection stability**: Added delays between operations to prevent "tag lost" errors
- **Persistence layer**: SharedPreferences ensure transfers survive app lifecycle changes
- **Material Design**: Provides modern, accessible user interface

### Limitations
- **Android only**: iOS has different NFC/Bluetooth capabilities
- **Bluetooth availability**: Requires Bluetooth LE support
- **Background transfers**: May be interrupted by battery optimization
- **MAC address privacy**: Android 6.0+ uses randomized MACs

---

## 🚦 Getting Started

1. **Install the app** on two NFC-enabled Android devices
2. **Open the app** - it starts with an empty wallet
3. **Mint a token** using the menu (⋮) → "Mint a Token"
   - Enter a name (e.g., "My First Token")
   - Set amount (default: 100)
   - Add custom data (optional)
4. **Wait for minting** - includes blockchain commitment (~2-5 seconds)
5. **Transfer tokens** by tapping devices together
6. **Reset wallet** using menu → "Reset Wallet" if needed

## 📦 Dependencies

### Android Dependencies
- Android Gradle Plugin: 8.11.0
- Kotlin: 1.9.0
- Gradle: 8.13
- Target SDK: 34 (Android 14)
- Min SDK: 24 (Android 7.0)

### JavaScript Dependencies
- @unicitylabs/state-transition-sdk: 1.4.7-rc.7cec668
- @unicitylabs/commons: 2.4.0-rc.24d6a7c
- Node.js: 20.14.0 (managed by Gradle)
- Webpack: 5.99.9

**Version**: 2.0.0  
**Last Updated**: July 2025  
**License**: MIT