# Quick Start Guide - Testing on Your Phone

## 5-Minute Setup

### What You Need
- 2 Android phones with NFC
- USB cable
- Computer with ADB installed

### Quick Install

1. **Download the APK**:
   ```bash
   # If you have the project
   cd nfc-wallet-demo
   ./gradlew assembleDebug
   
   # APK location: app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Enable on Your Phone**:
   - Settings → About → Tap "Build number" 7 times
   - Settings → Developer options → Enable "USB debugging"
   - Settings → Enable NFC and Bluetooth

3. **Install via USB**:
   ```bash
   # Connect phone 1
   adb install app/build/outputs/apk/debug/app-debug.apk
   
   # Connect phone 2
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Quick Test

**Phone 1**: Open app → Tap "Community Coin"  
**Phone 2**: Open app → Tap "Receive Token"  
**Action**: Hold phones back-to-back  
**Result**: Token transfers! ✨

### Alternative: Install Without Computer

1. Upload `app-debug.apk` to Google Drive
2. On each phone:
   - Download APK from Drive
   - Open Downloads → Tap APK
   - Enable "Unknown sources" if asked
   - Install

### Troubleshooting

**"No NFC service"**: Your phone might not support NFC  
**"Connection failed"**: Make sure Bluetooth is ON  
**"Permission denied"**: Grant all permissions when asked

### That's It! 🎉

You're ready to demo NFC token transfers. For detailed testing, see [DEVICE_TESTING_GUIDE.md](DEVICE_TESTING_GUIDE.md)