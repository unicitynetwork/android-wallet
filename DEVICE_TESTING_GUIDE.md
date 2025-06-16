# Testing on Real Android Devices

This guide provides detailed instructions for testing the Unicity NFC Wallet Demo on physical Android devices.

## Prerequisites

### Device Requirements
- **Two Android phones** with:
  - NFC support (Android 4.4 KitKat or higher)
  - Bluetooth support
  - Android 7.0 (API 24) or higher
- USB cables for both devices
- A computer with Android Studio or ADB installed

### Preparing Your Devices

#### 1. Enable Developer Mode
On each device:
1. Go to **Settings > About phone**
2. Find **Build number** and tap it 7 times
3. You'll see "You are now a developer!"

#### 2. Enable Developer Options
1. Go to **Settings > System > Developer options**
2. Enable the following:
   - ✅ **USB debugging**
   - ✅ **Install via USB**
   - ✅ **Disable permission monitoring** (optional, helps with testing)

#### 3. Enable Required Features
1. Go to **Settings**
2. Enable:
   - ✅ **NFC** (usually in Connected devices or Wireless & networks)
   - ✅ **Bluetooth**
   - ✅ **Location** (required for Bluetooth on some Android versions)

## Installation Methods

### Method 1: Using Android Studio

1. **Connect first device** via USB
2. Open the project in Android Studio
3. Select your device from the dropdown
4. Click **Run** (▶️) or press `Shift + F10`
5. **Disconnect first device**, connect second device
6. Repeat steps 3-4

### Method 2: Using ADB Command Line

1. **Build the APK** (if not already built):
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on first device**:
   ```bash
   # Check device is connected
   adb devices
   
   # Install the app
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Install on second device**:
   ```bash
   # Disconnect first device, connect second
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Method 3: Direct APK Transfer

1. **Copy APK to devices**:
   - Connect device via USB
   - Copy `app-debug.apk` to Downloads folder
   - Or share via email/cloud storage

2. **Install from device**:
   - Open file manager
   - Navigate to APK location
   - Tap to install
   - Enable "Install from unknown sources" if prompted

## Testing Procedures

### Initial Setup

1. **Launch app on both devices**
2. **Grant permissions** when prompted:
   - Bluetooth permissions (Connect, Scan, Advertise)
   - Location permission (if requested)
3. **Verify initial state**:
   - Both devices show "My Wallet" screen
   - At least one device has a token (Community Coin)

### Test 1: Basic Token Transfer

**Device A (Sender - Has Token):**
1. Tap on "Community Coin" in the wallet
2. You'll see "Ready to Send Community Coin. Tap the recipient's phone."
3. Keep this screen open

**Device B (Receiver):**
1. Tap "Receive Token" button
2. You'll see "Ready to receive. Ask the sender to tap your phone."
3. Keep this screen open

**Perform Transfer:**
1. Hold devices back-to-back
2. You should hear/feel NFC detection (beep or vibration)
3. Watch status updates on both screens:
   - Sender: "Connecting..." → "Waiting for address..." → "Success!"
   - Receiver: "Connection request..." → "Generating address..." → "Success!"
4. Verify token moved from Device A to Device B

### Test 2: Reverse Transfer

Now test sending the token back:
1. Device B (now has token) → Send Token
2. Device A → Receive Token
3. Tap devices together
4. Verify token returns to Device A

### Test 3: Error Scenarios

#### NFC Disabled Test
1. Disable NFC on one device
2. Try to transfer
3. Verify error dialog appears

#### Bluetooth Disabled Test
1. Enable NFC, disable Bluetooth
2. Try to transfer
3. Verify "Bluetooth Required" dialog

#### Interrupted Transfer
1. Start a transfer
2. Separate devices quickly after NFC tap
3. Verify appropriate error handling

### Test 4: Permission Handling

1. **Revoke Bluetooth permission**:
   - Settings > Apps > NFC Wallet Demo > Permissions
   - Deny Bluetooth permission
2. **Launch app and try transfer**
3. **Verify permission request** appears

## Debugging Tips

### Enable Detailed Logging

1. **View logs in Android Studio**:
   ```bash
   adb logcat -s NFCWalletDemo
   ```

2. **Filter by components**:
   ```bash
   # NFC logs
   adb logcat -s HostCardEmulatorService,NfcReaderCallback
   
   # Bluetooth logs
   adb logcat -s BluetoothServer,BluetoothClient
   ```

### Common Issues and Solutions

#### "NFC Service Not Found"
- Ensure NFC is supported: Settings > About phone > check NFC presence
- Try toggling NFC off and on
- Restart the device

#### "Bluetooth Connection Failed"
- Ensure devices are not already paired
- Clear Bluetooth cache: Settings > Apps > Bluetooth > Storage > Clear Cache
- Try: `adb shell pm clear com.android.bluetooth`

#### "Transfer Starts but Fails"
- Check both devices have granted all permissions
- Ensure Bluetooth is not connected to other devices
- Try increasing device proximity during NFC tap

### Performance Testing

1. **Monitor transfer time**:
   - Note timestamp when tapping
   - Note completion time
   - Should complete within 2-5 seconds

2. **Test multiple transfers**:
   - Perform 10 consecutive transfers
   - Check for consistency
   - Monitor for memory leaks

## Advanced Testing

### Testing with Multiple Tokens

1. **Modify WalletRepository** to create multiple tokens
2. Test selecting different tokens to send
3. Verify correct token is transferred

### Network Conditions

1. **Airplane mode test**:
   - Enable airplane mode
   - Enable NFC and Bluetooth manually
   - Verify transfer still works (it should!)

### Battery and Performance

1. **Low battery test**:
   - Test with battery < 15%
   - Verify NFC/Bluetooth still function

2. **Background apps**:
   - Run multiple apps
   - Test transfer performance

## Test Report Template

```
Device A: [Model, Android Version]
Device B: [Model, Android Version]

Test Results:
- [ ] App installs successfully on both devices
- [ ] Permissions granted properly
- [ ] NFC detection works reliably
- [ ] Bluetooth connection establishes
- [ ] Token transfers from A to B
- [ ] Token transfers from B to A
- [ ] Error dialogs appear for disabled features
- [ ] Transfer completes within 5 seconds
- [ ] App remains stable after multiple transfers

Issues Found:
1. [Description, steps to reproduce]
2. [Description, steps to reproduce]

Notes:
[Any additional observations]
```

## Security Testing

### Important Checks

1. **Token Uniqueness**:
   - Transfer same token multiple times
   - Verify no duplication occurs

2. **Connection Security**:
   - Try to intercept Bluetooth communication
   - Verify only intended devices connect

3. **App Sandboxing**:
   - Check token data is not accessible to other apps
   - Verify SharedPreferences are private

## Continuous Testing

### After Code Changes

1. **Uninstall old version**:
   ```bash
   adb uninstall com.unicity.nfcwalletdemo
   ```

2. **Install new version**
3. **Run regression tests**:
   - Basic transfer
   - Error scenarios
   - Permission handling

### Release Testing

1. **Build release APK**:
   ```bash
   ./gradlew assembleRelease
   ```

2. **Sign APK** (for production)
3. **Test upgrade path**:
   - Install old version
   - Upgrade to new version
   - Verify data persistence

## Troubleshooting Commands

```bash
# Check if app is installed
adb shell pm list packages | grep unicity

# Clear app data
adb shell pm clear com.unicity.nfcwalletdemo

# Check NFC status
adb shell settings get global nfc_on

# Check Bluetooth status
adb shell settings get global bluetooth_on

# Capture bug report
adb bugreport

# Record screen during testing
adb shell screenrecord /sdcard/nfc_test.mp4
```

## Next Steps

After successful device testing:
1. Document any device-specific issues
2. Test on more device models
3. Perform user acceptance testing
4. Gather performance metrics
5. Prepare for production deployment