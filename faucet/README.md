# Unicity Token Faucet

A standalone CLI application for minting Unicity tokens and sending them via Nostr to nametag recipients.

## Overview

This faucet application:
1. Mints a Unicity token with configurable coin type and amount
2. Submits the token to the Unicity aggregator
3. Resolves the recipient's nametag to a Nostr public key
4. Sends the token via encrypted Nostr message to the recipient

The wallet app will receive and process these tokens automatically through its Nostr P2P messaging system.

## Configuration

Edit `src/main/resources/faucet-config.json`:

```json
{
  "tokenType": "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509",
  "coinId": "dee5f8ce778562eec90e9c38a91296a023210ccc76ff4c29d527ac3eb64ade93",
  "nostrRelay": "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080",
  "aggregatorUrl": "https://goggregator-test.unicity.network",
  "faucetMnemonic": "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
  "defaultAmount": 1000
}
```

### Configuration Options

- **tokenType**: Fixed token type hex (32 bytes) for Unicity testnet NFT container
- **coinId**: Fixed coin ID hex (32 bytes) for fungible tokens (e.g., Solana testnet)
- **nostrRelay**: WebSocket URL of the Nostr relay for P2P messaging
- **aggregatorUrl**: URL of the Unicity aggregator service
- **faucetMnemonic**: BIP-39 mnemonic phrase for faucet identity - **KEEP SECURE!** (Default: "abandon abandon..." for testnet)
- **defaultAmount**: Default token amount if not specified via CLI

See [unicity-ids.testnet.json](https://github.com/unicitynetwork/unicity-ids/blob/main/unicity-ids.testnet.json) for the token registry with metadata.

## Usage

### Build the Project

```bash
cd faucet
./gradlew build
```

### Run the Faucet

Send a token to a nametag recipient:

```bash
# Using default amount from config
./gradlew run --args="--nametag=alice"

# Specify custom amount
./gradlew run --args="--nametag=alice --amount=500"

# Using the 'mint' task
./gradlew mint --args="--nametag=bob --amount=1000"
```

### CLI Options

```
Usage: faucet [-hV] -n=<nametag> [-a=<amount>] [-c=<configPath>]

Options:
  -n, --nametag=<nametag>   Recipient's nametag (e.g., 'alice')
  -a, --amount=<amount>     Token amount (uses default from config if not specified)
  -c, --config=<configPath> Path to config file (default: faucet-config.json in resources)
  -h, --help               Show this help message and exit
  -V, --version            Print version information and exit
```

## How It Works

### 1. Nametag Resolution
- Queries the Nostr relay for the recipient's nametag binding
- Uses filter: `{"kinds": [30078], "#t": ["nametag"], "limit": 1}`
- Retrieves the recipient's Nostr public key from the binding event

### 2. Token Minting
- Creates a Unicity token using the Java State Transition SDK
- Token contains:
  - Fixed token type (f8aa1383... - Unicity NFT container)
  - Fixed coin ID (dee5f8ce... - Solana testnet)
  - Amount specified via CLI or config
  - Masked predicate for ownership

### 3. Token Transfer
- Transfers the minted token to the recipient's nametag
- Creates a transfer transaction commitment
- Submits to aggregator and waits for inclusion proof

### 4. Nostr Message Delivery
- Creates an encrypted Nostr event (kind 31113 - token transfer)
- Uses simple hex encoding for message content
- Signs the event with Schnorr signature (BIP-340)
- Sends format: `token_transfer:{tokenJson}`
- The wallet app receives and processes the token automatically

## Testing with Wallet App

### Prerequisites
1. Wallet app must be running with Nostr P2P service started
2. Wallet user must have minted a nametag
3. Nametag binding must be published to the Nostr relay
4. Both faucet and wallet must use the same Nostr relay

### Test Flow

1. **Mint nametag in wallet app**:
   - Open wallet app
   - Go to Profile → Nametag section
   - Tap "Mint Nametag" and enter a unique name (e.g., "alice-test-123")
   - Wait for minting and Nostr binding to complete

2. **Run the faucet**:
   ```bash
   cd faucet
   ./gradlew run --args="--nametag=alice-test-123 --amount=1000"
   ```

3. **Verify in wallet app**:
   - Token should appear in Tokens tab immediately
   - Should display as "1000 SOL" with Solana icon
   - New tokens appear at the top of the list

## Message Format

The faucet sends tokens in the following format via Nostr:

```
token_transfer:{tokenJson}
```

Where `{tokenJson}` is the serialized Unicity token from the SDK's `CommitmentJsonSerializer`.

The wallet app's `NostrP2PService` recognizes this format and automatically:
1. Extracts the token JSON
2. Deserializes the token
3. Saves it to the wallet database
4. Notifies the user

## Security Considerations

1. **Private Key Security**: The `faucetPrivateKey` in the config is sensitive. In production:
   - Use environment variables
   - Use secure key management
   - Never commit real keys to version control

2. **Message Encryption**: Currently uses simple hex encoding. For production:
   - Implement proper NIP-04 encryption
   - Use ECDH for shared secret derivation
   - Add message authentication

3. **Rate Limiting**: Consider adding:
   - Rate limits per nametag
   - Maximum amount limits
   - Authentication/authorization

## Dependencies

- Unicity Java SDK 1.2.0 (token minting, state transitions)
- Jackson (JSON serialization)
- OkHttp (WebSocket for Nostr relay)
- Secp256k1 (Schnorr signatures)
- Picocli (CLI argument parsing)

## Troubleshooting

### "Nametag not found in relay"
- Ensure the nametag is minted in the wallet app (Profile → Nametag)
- Check that the Nostr service started successfully after minting
- Wait 2-3 seconds after minting for the binding to publish
- Verify nametag spelling matches exactly

### "Message rejected"
- Check Nostr relay is running and accessible
- Verify signature is valid (check private key)
- Ensure recipient public key is correct

### "Failed to submit token"
- Verify aggregator URL is correct and accessible
- Check network connectivity
- Review aggregator logs for errors

### "Connection timeout"
- Increase timeout in `NostrClient` (default: 10 seconds)
- Check Nostr relay is accepting connections
- Verify firewall rules allow WebSocket connections

## Development

### Add New Coin Types

Edit `faucet-config.json`:
```json
{
  "coinId": "bitcoin-test",
  "defaultAmount": 50000000
}
```

### Custom Token Data

Modify `TokenMinter.mintToken()` to include additional fields:
```java
Map<String, Object> tokenData = new HashMap<>();
tokenData.put("coinId", coinId);
tokenData.put("amount", amount);
tokenData.put("customField", "customValue"); // Add custom fields
```

### Logging

Adjust logging levels in `src/main/resources/logback.xml`:
```xml
<logger name="org.unicitylabs.sdk" level="DEBUG"/>
```

## License

MIT License - see LICENSE file for details
