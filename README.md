# Unicity NFC Wallet Demo

A Proof-of-Concept Android application demonstrating NFC-based token transfers using the Unicity Protocol.

## Overview

This Android app showcases a secure "tap-to-send" experience for transferring digital assets managed by the Unicity Protocol. The app uses NFC for initial handshake and Bluetooth for secure data transfer.

## Features

- **Wallet Management**: View and manage digital tokens
- **NFC Tap-to-Send**: Transfer tokens by simply tapping phones
- **Secure Bluetooth Transfer**: Two-way communication for token exchange
- **MVVM Architecture**: Clean, maintainable code structure
- **Material Design UI**: Modern, intuitive user interface

## Technical Architecture

### Key Technologies
- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel)
- **Communication**:
  - NFC (Near Field Communication) for initial handshake
  - Bluetooth for secure data transfer
- **UI**: Material Design Components

### Project Structure
```
app/
├── src/main/java/com/unicity/nfcwalletdemo/
│   ├── ui/
│   │   ├── wallet/      # Main wallet screen
│   │   ├── send/        # Send token flow
│   │   └── receive/     # Receive token flow
│   ├── data/
│   │   ├── model/       # Data models
│   │   └── repository/  # Data management
│   ├── viewmodel/       # ViewModels for UI state
│   ├── nfc/            # NFC functionality
│   ├── bluetooth/      # Bluetooth communication
│   └── utils/          # Utility classes
└── src/main/res/       # Resources (layouts, values, etc.)
```

## Requirements

- Android Studio Arctic Fox or later
- Android SDK 24 or higher
- Two NFC-enabled Android devices for testing
- Bluetooth support

## Setup Instructions

1. **Clone the repository**
   ```bash
   git clone [repository-url]
   cd nfc-wallet-demo
   ```

2. **Open in Android Studio**
   - Open Android Studio
   - Select "Open an existing project"
   - Navigate to the project directory

3. **Build the project**
   - Let Android Studio sync the project
   - Build the project using Build → Make Project

4. **Install on devices**
   - Connect two NFC-enabled Android devices
   - Run the app on both devices

## Usage

### Sending a Token
1. Open the app on both devices
2. On the sending device:
   - Tap on a token or press "Send Token"
   - When prompted, tap the receiving device
3. The transfer will complete automatically

### Receiving a Token
1. On the receiving device:
   - Press "Receive Token"
   - Wait for the sender to tap
2. The token will appear in your wallet

## Transfer Flow

1. **NFC Handshake**: Receiver broadcasts Bluetooth MAC address via NFC
2. **Bluetooth Connection**: Sender connects to receiver's Bluetooth
3. **Address Exchange**: Receiver generates unique Unicity address
4. **Token Transfer**: Sender transfers token JSON to receiver
5. **Confirmation**: Both devices update their wallet states

## Permissions

The app requires the following permissions:
- NFC access
- Bluetooth access (BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
- Location access (for Bluetooth discovery on older Android versions)

## Development Notes

### Unicity SDK Integration
The app currently uses mock implementations for Unicity SDK functions. To integrate the actual SDK:

1. Add the SDK dependency to `app/build.gradle.kts`
2. Update `WalletRepository.kt` to use actual SDK methods:
   - Replace `generateNewAddress()` with SDK's address generation
   - Implement proper wallet management using SDK
3. Update `BluetoothClient.kt` to use SDK for token signing

### Testing
- Unit tests are located in `app/src/test/`
- Instrumented tests are in `app/src/androidTest/`
- Run tests using Android Studio or `./gradlew test`

### Known Limitations
- Currently uses mock Unicity SDK functions
- Bluetooth MAC address access may be restricted on newer Android versions
- Token persistence is handled via SharedPreferences (consider using Room DB)

## Testing

### Emulator Testing
See [TESTING_GUIDE.md](TESTING_GUIDE.md) for:
- Setting up Android Studio emulator
- Testing the UI without physical devices
- Understanding emulator limitations

### Real Device Testing
See [DEVICE_TESTING_GUIDE.md](DEVICE_TESTING_GUIDE.md) for:
- Detailed device setup instructions
- Step-by-step testing procedures
- Debugging and troubleshooting
- Performance testing guidelines

## Troubleshooting

### NFC Not Working
- Ensure NFC is enabled in device settings
- Check that both devices support NFC
- Hold devices back-to-back firmly

### Bluetooth Connection Failed
- Ensure Bluetooth is enabled on both devices
- Grant all required permissions
- Try disabling and re-enabling Bluetooth

### Transfer Failed
- Check that both devices have the app in the correct state
- Ensure stable Bluetooth connection
- Review logcat for detailed error messages

## Quick Start

See [QUICK_START.md](QUICK_START.md) for a 5-minute guide to get the app running on your phones.

## Production Readiness

This is a proof-of-concept. See [PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md) for detailed requirements before production deployment.

## Contributing

We welcome contributions! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

[License information to be added]