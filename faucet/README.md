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

### Configuration Options

- **tokenType.systemId**: Unicity system ID for token type (default: 2)
- **tokenType.unitId**: Token unit ID as hex string (32 bytes)
- **coinId**: Coin identifier used in token data (e.g., "solana-test", "bitcoin-test")
- **nostrRelay**: WebSocket URL of the Nostr relay for P2P messaging
- **aggregatorUrl**: URL of the Unicity aggregator service
- **faucetPrivateKey**: Secp256k1 private key (32 bytes hex) - **KEEP SECURE!**
- **defaultAmount**: Default token amount if not specified via CLI

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

### 1. Token Minting
- Creates a Unicity token using the Java State Transition SDK
- Token contains:
  - System ID and Unit ID from config
  - Coin identifier (e.g., "solana-test")
  - Amount specified via CLI or config
  - Masked predicate for ownership

### 2. Token Submission
- Submits the minted token to the Unicity aggregator
- Aggregator validates and includes it in the blockchain

### 3. Nametag Resolution
- Queries the Unicity nametag service to resolve the recipient's nametag
- Retrieves the recipient's Nostr public key

### 4. Nostr Message Delivery
- Creates an encrypted Nostr event (kind 4 - encrypted DM)
- Signs the event with Schnorr signature (BIP-340)
- Sends the token JSON via WebSocket to the Nostr relay
- The wallet app receives and processes the token

## Testing with Wallet App

### Prerequisites
1. Wallet app must be running with Nostr P2P enabled
2. Wallet user must have a registered nametag
3. Both faucet and wallet must use the same Nostr relay

### Test Flow

1. **Register nametag in wallet app**:
   - Open wallet app
   - Go to Profile
   - Register a nametag (e.g., "alice")
   - Enable "Available as Agent" toggle

2. **Run the faucet**:
   ```bash
   ./gradlew run --args="--nametag=alice --amount=1000"
   ```

3. **Verify in wallet app**:
   - Token should appear in wallet after a few seconds
   - Check wallet balance and token list
   - Verify token details (coin type, amount)

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

### "Nametag not found"
- Ensure the nametag is registered in the wallet app
- Check the nametag service URL is accessible
- Verify nametag spelling

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
