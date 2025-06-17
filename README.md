# Unicity NFC Wallet Demo

A proof-of-concept Android app demonstrating direct NFC token transfers using Android Host Card Emulation (HCE). Users can securely transfer tokens between devices with a simple tap.

## 🚀 Current Implementation

**Transfer Method**: NFC-only direct transfer using Android HCE  
**Supported Token Sizes**: 2KB to 64KB  
**Transfer Speed**: 1-60 seconds depending on token size  
**User Experience**: True "single tap" - no pairing, no secondary connections

## ✨ Features

- **Direct NFC Transfer**: Tap-to-send tokens between Android devices
- **Modern UI**: Material Design with Unicity branding
- **Real-time Progress**: Transfer progress indicators on both devices
- **Demo Tokens**: Pre-loaded tokens (2KB, 4KB, 8KB, 16KB, 32KB, 64KB)
- **Token Management**: Expandable cards with detailed token information

## 📱 Requirements

- **Android 7.0+** (API level 24+)
- **NFC-enabled device**
- **Host Card Emulation support** (most modern Android devices)

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
- **WalletRepository**: Manages token storage and demo data

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
2. **Send**: Device A - tap token, select "Send Token"  
3. **Transfer**: Tap Device A to Device B (back-to-back)
4. **Verify**: Check token appears in Device B's wallet
5. **Reverse**: Test transfer from Device B back to Device A

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

## 🏭 Production Checklist

### Security
- [ ] Implement token encryption for sensitive data
- [ ] Add digital signatures for token authenticity
- [ ] Secure storage for private keys
- [ ] Input validation and sanitization

### Integration
- [ ] Replace demo tokens with real Unicity SDK integration
- [ ] Implement server-side token validation
- [ ] Add user authentication
- [ ] Connect to production Unicity network

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
│   ├── ui/             # Activities and adapters
│   ├── utils/          # Utility classes
│   └── viewmodel/      # MVVM ViewModels
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

**Version**: 1.0.0  
**Last Updated**: June 2024  
**License**: MIT