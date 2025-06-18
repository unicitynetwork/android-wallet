# Unicity NFC Wallet Demo

A fully functional Android wallet app for the Unicity Protocol, demonstrating real cryptographic token transfers via NFC using Android Host Card Emulation (HCE). Users can mint, store, and transfer genuine Unicity tokens between devices with a simple tap.

## 🚀 Current Implementation

**Token Type**: Real Unicity Protocol tokens with cryptographic signatures  
**Transfer Method**: NFC-only direct transfer using Android HCE  
**SDK Integration**: Full Unicity State Transition SDK with blockchain commitments  
**Transfer Speed**: 1-60 seconds depending on token size  
**User Experience**: True "single tap" - no pairing, no secondary connections

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

2. **Open in Android Studio**
   - Import the project
   - Sync Gradle dependencies

3. **Build and install**
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Test on two NFC devices**
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
- **DirectNfcClient**: Handles outgoing token transfers
- **WalletRepository**: Manages token storage and Unicity SDK integration
- **UnicitySdkService**: WebView-based bridge to Unicity State Transition SDK

## 📡 NFC Transfer Flow

### Sending a Token
1. User taps "Send Token" and expands token card
2. User taps sending device to receiving device
3. `DirectNfcClient` establishes NFC connection using IsoDep
4. Token data is chunked and sent via APDU commands
5. Transfer completes with success confirmation

### Receiving a Token
1. `HostCardEmulatorService` activates when NFC field detected
2. Service receives APDU commands and reconstructs token data
3. Progress broadcasts update UI in real-time
4. Completed token is saved to wallet
5. Success confirmation sent back to sender

### Technical Details
- **Protocol**: Custom APDU commands over NFC-A/ISO 14443 Type A
- **Chunk Size**: ~250 bytes per APDU (limited by NFC specs)
- **Buffer Limit**: ~37KB total transfer size (Android HCE limitation)
- **Timeout**: 30 seconds per transfer attempt

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
- **"Tag was lost"**: Devices moved apart during transfer - keep steady contact
- **"Failed to send"**: Retry with slower, more deliberate tap
- **App crashes**: Check logs for specific errors

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
- **StateTransitionClient**: Blockchain interaction
- **InclusionProof**: Proof of blockchain commitment

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
│   ├── data/           # Models and repositories
│   ├── nfc/            # NFC transfer implementation
│   ├── sdk/            # Unicity SDK integration
│   ├── ui/             # Activities and adapters
│   ├── utils/          # Utility classes
│   └── viewmodel/      # MVVM ViewModels
├── assets/
│   ├── bridge.html     # WebView bridge HTML
│   ├── unicity-sdk.js  # Unicity SDK bundle
│   └── unicity-wrapper.js # SDK JavaScript wrapper
└── res/
    ├── drawable/       # Icons and graphics
    ├── layout/         # XML layouts
    ├── values/         # Colors, strings, styles
    └── xml/            # App configuration
```

### Key Files
- `HostCardEmulatorService.kt`: NFC receiving logic
- `DirectNfcClient.kt`: NFC sending logic  
- `MainActivity.kt`: Main wallet interface
- `ReceiveActivity.kt`: Transfer receiving UI
- `UnicitySdkService.kt`: SDK WebView bridge
- `unicity-wrapper.js`: SDK JavaScript interface
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
- **WebView bridge**: Enables TypeScript SDK usage in Android app
- **NFC-only approach**: Bluetooth pairing proved unreliable on modern Android
- **Direct HCE**: Eliminates need for backend servers during transfer
- **Chunked transfers**: Works within NFC APDU size limitations
- **Material Design**: Provides modern, accessible user interface

### Limitations
- **Transfer size**: 64KB maximum due to Android HCE buffer limits
- **NFC only**: No fallback for large files or long-distance transfers
- **Android only**: iOS has different NFC capabilities
- **Physical proximity**: Devices must touch during entire transfer

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

**Version**: 2.0.0  
**Last Updated**: June 2025  
**License**: MIT