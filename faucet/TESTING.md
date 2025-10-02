# Faucet E2E Testing Guide

## Overview

This document describes how to test the complete token transfer flow from the faucet to the wallet app via Nostr.

## Test Components

### 1. NametagMinter (Test Helper)
- **Location**: `src/test/java/org/unicitylabs/faucet/NametagMinter.java`
- **Purpose**: Mints nametag tokens for test users
- **Key Features**:
  - Generates unique token IDs and types
  - Creates masked predicates for ownership
  - Submits to aggregator and waits for inclusion proof
  - Returns fully constructed nametag token

### 2. FaucetE2ETest (E2E Test)
- **Location**: `src/test/java/org/unicitylabs/faucet/FaucetE2ETest.java`
- **Purpose**: Complete end-to-end test of token transfer flow
- **Test Flow**:
  1. **Setup**: Generate unique nametag for Alice (e.g., `alice-test-abc12345`)
  2. **Mint Nametag**: Mint nametag token for Alice with her Nostr pubkey
  3. **Connect to Nostr**: Alice connects to relay and subscribes to messages
  4. **Mint Token**: Faucet mints a `solana-test` token (1000 amount)
  5. **Send via Nostr**: Faucet sends token to Alice via encrypted Nostr message
  6. **Verify**: Alice receives `token_transfer:` message with token JSON

## Why the Test is @Ignored

The E2E test requires the **secp256k1 native JNI library** for Schnorr signatures (BIP-340) used by Nostr. This library is:
- ✅ Available in Android environments
- ❌ Not available in JVM unit test environments

### Error Message:
```
java.lang.IllegalStateException: Could not load native Secp256k1 JNI library.
Have you added the JNI dependency?
```

This is the same issue encountered in the wallet app's `NostrMessagingE2ETest`.

## Manual Testing Instructions

### Prerequisites
1. **Wallet App** running on Android device/emulator
2. **Faucet** built and ready (`./gradlew build`)
3. **Network access** to:
   - Aggregator: `https://goggregator-test.unicity.network`
   - Nostr Relay: `ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080`

### Step 1: Register Nametag in Wallet App
1. Open wallet app
2. Go to Profile
3. Register a nametag (e.g., "alice")
4. Enable "Available as Agent" toggle
5. Note down the nametag (without @unicity suffix)

### Step 2: Run the Faucet
```bash
cd faucet

# Send 1000 solana-test tokens to alice
./gradlew run --args="--nametag=alice --amount=1000"

# Or use the mint task
./gradlew mint --args="--nametag=alice --amount=1000"
```

### Step 3: Verify in Wallet App
1. Check wallet balance
2. Look for new token in token list
3. Verify token details:
   - **Coin**: solana-test
   - **Amount**: 1000
   - **Status**: Confirmed

### Expected Output (Faucet)
```
╔══════════════════════════════════════╗
║   Unicity Token Faucet v1.0.0        ║
╚══════════════════════════════════════╝

✅ Configuration loaded
   Relay: ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080
   Aggregator: https://goggregator-test.unicity.network
   Coin ID: solana-test

🔍 Resolving nametag: alice
✅ Resolved nametag 'alice' to: 3a4b5c...

🔨 Minting token...
   Coin: solana-test
   Amount: 1000
📡 Submitting commitment to aggregator...
✅ Commitment submitted successfully!
⏳ Waiting for inclusion proof...
✅ Inclusion proof received!
✅ Token minted successfully!

📨 Sending token to alice via Nostr...
✅ Connected to Nostr relay
📤 Sending encrypted message to: 3a4b5c...
✅ Message delivered successfully

╔══════════════════════════════════════╗
║  ✅ Token sent successfully!         ║
╚══════════════════════════════════════╝

📊 Summary:
   Recipient: alice
   Amount: 1000 solana-test
   Delivery: Nostr relay
```

### Expected Output (Wallet App)
- **Notification**: "New token received"
- **Token appears** in wallet list
- **Token details** show correct amount and coin ID

## Configuration for Testing

### Update Faucet Config
Edit `faucet/src/main/resources/faucet-config.json`:

```json
{
  "tokenType": {
    "systemId": 2,
    "unitId": "0x01020304050607080102030405060708010203040506070801020304050607FF"
  },
  "coinId": "solana-test",
  "nostrRelay": "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080",
  "aggregatorUrl": "https://goggregator-test.unicity.network",
  "faucetPrivateKey": "0000000000000000000000000000000000000000000000000000000000000001",
  "defaultAmount": 1000
}
```

**Security Note**: Change `faucetPrivateKey` to a secure random value for production!

## Troubleshooting

### "Nametag not found"
- Ensure nametag is registered in wallet app
- Check spelling (case-sensitive)
- Verify nametag service is accessible

### "Message rejected" by Nostr relay
- Check Nostr relay is running
- Verify relay URL in config
- Ensure signatures are valid

### "Failed to submit commitment"
- Check aggregator URL
- Verify network connectivity
- Review aggregator logs

### Token not appearing in wallet
- Verify wallet's Nostr P2P service is running
- Check wallet is subscribed to relay
- Ensure "Available as Agent" is enabled
- Review wallet logs for errors

## Test Data

### Generated During Test
- **Alice Nametag**: `alice-test-{random-uuid}` (e.g., `alice-test-abc12345`)
- **Alice Nostr Pubkey**: Derived from test private key
- **Token ID**: Random 32 bytes
- **Token Type**: Random 32 bytes
- **Amount**: 1000 (configurable)
- **Coin**: solana-test (configurable)

### Message Format
The faucet sends tokens in this format:
```
token_transfer:{tokenJson}
```

Where `{tokenJson}` is the full Unicity token serialized by the SDK's `UnicityObjectMapper`.

## Integration with Wallet App

### Wallet's NostrP2PService
The wallet automatically:
1. **Connects** to Nostr relay on startup (if enabled)
2. **Subscribes** to encrypted DMs for user's pubkey
3. **Listens** for `token_transfer:` messages
4. **Extracts** token JSON from message
5. **Deserializes** token using `UnicityObjectMapper`
6. **Saves** token to local database
7. **Notifies** user of new token

### Message Processing
`NostrP2PService.kt` handles token transfers:
```kotlin
if (decryptedText.startsWith("token_transfer:")) {
    val tokenJson = decryptedText.substring("token_transfer:".length)
    // Deserialize and save token
    val token = UnicityObjectMapper.JSON.readValue(tokenJson, Token::class.java)
    walletRepository.saveToken(token)
}
```

## Running the Automated Test (When JNI is Available)

If you have the secp256k1 JNI library available (e.g., in an Android instrumented test):

1. **Remove `@Ignore` annotation** from `FaucetE2ETest.java`
2. **Run the test**:
   ```bash
   ./gradlew test --tests "org.unicitylabs.faucet.FaucetE2ETest"
   ```

3. **Verify output**:
   ```
   ╔══════════════════════════════════════════════╗
   ║  ✅ E2E Test Passed Successfully!           ║
   ╚══════════════════════════════════════════════╝

   📊 Test Summary:
      ✓ Nametag minted: alice-test-abc12345
      ✓ Token minted: solana-test (1000)
      ✓ Token sent via Nostr
      ✓ Alice received token
   ```

## Future Improvements

1. **Android Instrumented Test**: Port E2E test to Android instrumented test
2. **Mock Secp256k1**: Create mock for JVM testing
3. **Integration Test Environment**: Set up test environment with all services
4. **Automated Nametag Cleanup**: Clean up test nametags after tests
5. **Multi-coin Testing**: Test with different coin types
6. **Error Scenarios**: Test failure cases (invalid nametag, network errors, etc.)

## Related Files

- **Faucet CLI**: `src/main/java/org/unicitylabs/faucet/FaucetCLI.java`
- **Token Minter**: `src/main/java/org/unicitylabs/faucet/TokenMinter.java`
- **Nostr Client**: `src/main/java/org/unicitylabs/faucet/NostrClient.java`
- **Nametag Resolver**: `src/main/java/org/unicitylabs/faucet/NametagResolver.java`
- **Wallet Nostr Service**: `../app/src/main/java/org/unicitylabs/wallet/nostr/NostrP2PService.kt`

## Summary

The E2E test is comprehensive but requires native libraries. For now:
- ✅ Build and run faucet manually
- ✅ Test with real wallet app
- ✅ Verify token transfers work end-to-end
- ⏸️ Automated E2E test is @Ignored (pending JNI support)
