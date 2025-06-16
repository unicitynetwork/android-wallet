# Hybrid NFC + Bluetooth Solution Analysis

## Current Issues

1. **Android blocks MAC address access** - Always returns `02:00:00:00:00:00`
2. **Bluetooth discovery is unreliable** - Often finds 0 devices even when discoverable
3. **No pairing requirement** conflicts with Android's security model

## Why The Current Approach Fails

The PRD2.md spec assumes we can:
1. Exchange MAC addresses via NFC ❌ (Android blocks this)
2. Connect directly to MAC address ❌ (Need discovery first)
3. Use insecure sockets without any prior setup ✅ (This works)

But Android's Bluetooth stack requires EITHER:
- Devices are already paired (we want to avoid this)
- Devices discover each other (unreliable)

## Alternative Solutions

### Option 1: Direct NFC Transfer (Simple but Slow)
- Transfer tokens directly via NFC
- Pro: Works reliably, no Bluetooth needed
- Con: Very slow for large tokens (7-14 minutes for 1MB)

### Option 2: WiFi Direct
- Use WiFi Direct for high-speed transfer after NFC handshake
- Pro: Fast, no pairing needed
- Con: Different API, requires WiFi Direct support

### Option 3: Bluetooth with Manual Connection
- Show discovered device name to user
- User manually selects correct device
- Pro: Works with current architecture
- Con: Not "single tap" experience

### Option 4: BLE Advertisement + GATT
- Use Bluetooth Low Energy advertising
- Receiver advertises service, sender scans
- Pro: More reliable than classic Bluetooth discovery
- Con: Requires BLE support, different API

## Recommendation

Given the constraints and the requirement for "single tap" experience without pairing, the most practical solution is:

1. **For small tokens (<100KB)**: Use direct NFC transfer
2. **For large tokens**: Accept that user needs to either:
   - Pre-pair devices (one time)
   - Use manual device selection
   - Wait for slow NFC transfer

The "zero-pairing insecure Bluetooth" approach simply doesn't work reliably on modern Android due to security restrictions.