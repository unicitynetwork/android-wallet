# Testing Guide - Android Studio Emulator

This guide explains how to test the Unicity NFC Wallet Demo UI in the Android Studio emulator.

## Prerequisites

- Android Studio installed (Arctic Fox or later)
- Project opened in Android Studio
- Gradle sync completed successfully

## Setting Up the Emulator

### 1. Create a Virtual Device

1. Open Android Studio with the project
2. Click on **Device Manager** icon in the toolbar (or go to **Tools > Device Manager**)
3. Click **Create Device** button
4. Select a device profile:
   - Recommended: **Pixel 4** or **Pixel 5** (these have NFC support)
   - Click **Next**
5. Select System Image:
   - Choose **API 30** or higher (Android 11+)
   - If not downloaded, click the download link
   - Click **Next**
6. Configure AVD:
   - Name: `NFC_Wallet_Demo_Device`
   - Click **Show Advanced Settings**
   - Ensure **NFC** is enabled (if available)
   - Click **Finish**

### 2. Launch the Emulator

1. In Device Manager, click the **Play** button next to your created device
2. Wait for the emulator to fully boot

## Running the App

### Method 1: Using the Run Button
1. Select your emulator from the device dropdown in the toolbar
2. Click the green **Run** button (▶️) or press `Shift + F10`
3. Wait for the app to build and install

### Method 2: Using Gradle
1. Open Terminal in Android Studio
2. Run: `./gradlew installDebug`
3. Launch the app from the emulator's app drawer

## Testing the UI

### Main Wallet Screen
When the app launches, you'll see:
- **"My Wallet" title** at the top
- **Token list** showing "Community Coin" (if any tokens exist)
- **Two buttons** at the bottom:
  - Send Token (left)
  - Receive Token (right)

### Testing Send Flow
1. **Click "Send Token"** or tap on a token
2. You'll see the Send screen with:
   - NFC icon animation
   - Status message: "Ready to Send Community Coin. Tap the recipient's phone."
   - Cancel button at bottom
3. **Note**: NFC tap won't work in emulator, but you can observe the UI

### Testing Receive Flow
1. **Click "Receive Token"**
2. You'll see the Receive screen with:
   - Pulsing NFC icon
   - Status message: "Ready to receive. Ask the sender to tap your phone."
   - Cancel button at bottom

### Testing Permissions
When first launching:
1. The app will request **Bluetooth permissions**
2. Click **Allow** to grant permissions
3. If you deny, you'll see a permission dialog explaining why they're needed

### Testing Error States
To see error dialogs:
1. **Disable Bluetooth** in emulator settings
2. Try to send/receive - you'll see "Bluetooth Required" dialog
3. **Disable NFC** (if supported in emulator)
4. Try to send/receive - you'll see "NFC Required" dialog

## Emulator Limitations

### What Works
- ✅ All UI screens and navigation
- ✅ Permission requests and dialogs
- ✅ Token list display
- ✅ Animation and status messages
- ✅ Error handling dialogs

### What Doesn't Work
- ❌ Actual NFC communication (no NFC hardware)
- ❌ Bluetooth device discovery (limited in emulator)
- ❌ Token transfers between devices

## UI Testing Checklist

- [ ] App launches without crashes
- [ ] Main wallet screen displays correctly
- [ ] "Send Token" button navigates to send screen
- [ ] "Receive Token" button navigates to receive screen
- [ ] Cancel buttons return to main screen
- [ ] Permission dialogs appear when needed
- [ ] Error dialogs show for disabled features
- [ ] All text is readable and properly aligned
- [ ] Icons and animations display correctly

## Screenshots

To capture screenshots:
1. Click the **Camera** icon in the emulator toolbar
2. Screenshots are saved to your desktop by default

## Advanced Testing

### Testing Multiple Emulators
To simulate two devices (without actual transfer):
1. Create two AVDs with different names
2. Launch both emulators
3. Install the app on both: `adb -s emulator-5554 install app-debug.apk`
4. Test UI flows on both simultaneously

### Using Layout Inspector
1. With app running, go to **View > Tool Windows > Layout Inspector**
2. Select your app process
3. Inspect view hierarchy and properties

### Testing Different Screen Sizes
1. Create AVDs with different screen sizes:
   - Small phone (5.0")
   - Large phone (6.7")
   - Tablet (10.1")
2. Verify UI adapts correctly

## Troubleshooting

### App Won't Install
- Clean project: `./gradlew clean`
- Rebuild: `./gradlew assembleDebug`
- Check emulator has enough storage

### Emulator is Slow
- Increase RAM in AVD settings
- Enable hardware acceleration (HAXM/KVM)
- Close other applications

### UI Not Updating
- Click "Cold Boot Now" in AVD Manager
- Wipe emulator data and restart

## Next Steps

For full functionality testing:
1. Install on two physical Android devices with NFC
2. Enable Developer Mode and USB debugging
3. Follow the main README for device testing instructions