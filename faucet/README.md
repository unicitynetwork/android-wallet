# Unicity Token Faucet

A standalone CLI application for minting Unicity tokens and sending them via Nostr to nametag recipients.

## Overview

This faucet application:
1. Loads all supported coins dynamically from the Unicity registry
2. Mints tokens with proper proxy address targeting
3. Transfers tokens to recipient's nametag proxy address (supports phone numbers as nametags)
4. Uses privacy-preserving SHA-256 hashed nametags for Nostr lookups
5. Sends complete transfer package (source token + transfer transaction) via Nostr
6. Recipient wallet finalizes the transfer with cryptographic verification

The wallet app receives and finalizes these token transfers automatically through proper Unicity Protocol semantics.

### Privacy-Preserving Nametag System
- **Hashed Storage**: All nametags are SHA-256 hashed before Nostr queries
- **Phone Number Support**: Send tokens to phone numbers (e.g., +14155552671)
- **E.164 Normalization**: Phone numbers are normalized to ensure consistent hashing
- **Privacy by Default**: Raw nametags are never exposed on the network

## Configuration

Edit `src/main/resources/faucet-config.json`:

```json
{
  "registryUrl": "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/refs/heads/main/unicity-ids.testnet.json",
  "nostrRelay": "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080",
  "aggregatorUrl": "https://goggregator-test.unicity.network",
  "faucetMnemonic": "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
  "defaultAmount": 1000,
  "defaultCoin": "solana"
}
```

### Configuration Options

- **registryUrl**: URL to unicity-ids registry JSON (defines all coins and token types dynamically)
- **nostrRelay**: WebSocket URL of the Nostr relay for P2P messaging
- **aggregatorUrl**: URL of the Unicity aggregator service
- **faucetMnemonic**: BIP-39 mnemonic phrase for faucet identity - **KEEP SECURE!**
- **defaultAmount**: Default token amount if not specified via CLI
- **defaultCoin**: Default coin name (e.g., "solana", "bitcoin")

## Usage

### Build the Project

```bash
cd faucet
./gradlew build
```

### Run the Faucet

```bash
# Default coin (Solana) with default amount
./gradlew run --args="--nametag=alice"

# Send to a phone number (automatically normalized and hashed)
./gradlew run --args="--nametag=+14155552671"
./gradlew run --args="--nametag=4155552671"  # Works without country code

# Specify amount (uses proper decimals automatically)
./gradlew run --args="--nametag=alice --amount=1.5"

# Specify coin
./gradlew run --args="--nametag=alice --amount=0.01 --coin=bitcoin"
./gradlew run --args="--nametag=alice --amount=0.5 --coin=ethereum"
./gradlew run --args="--nametag=alice --amount=100 --coin=tether"
./gradlew run --args="--nametag=alice --amount=50 --coin=usd-coin"

# Send Bitcoin to a phone number
./gradlew run --args="--nametag=+14155552671 --amount=0.001 --coin=bitcoin"

# Force refresh registry from GitHub
./gradlew run --args="--nametag=alice --refresh"
```

### CLI Options

```
Usage: faucet [-hV] -n=<nametag> [-a=<amount>] [-c=<coin>] [--refresh] [--config=<configPath>]

Options:
  -n, --nametag=<nametag>   Recipient's nametag (required)
  -a, --amount=<amount>     Token amount in human-readable units (e.g., 0.05)
  -c, --coin=<coin>         Coin to mint (e.g., 'bitcoin', 'ethereum', 'solana')
      --refresh             Force refresh registry from GitHub (ignores cache)
      --config=<path>       Path to config file
  -h, --help                Show this help message and exit
  -V, --version             Print version information and exit
```

## Supported Coins

All coins are loaded dynamically from the registry. Current coins:

| Coin | Symbol | Decimals | CoinGecko ID |
|------|--------|----------|--------------|
| solana | SOL | 9 | solana |
| bitcoin | BTC | 8 | bitcoin |
| ethereum | ETH | 18 | ethereum |
| tether | USDT | 6 | tether |
| usd-coin | USDC | 6 | usd-coin |

To add new coins, update the [unicity-ids.testnet.json](https://github.com/unicitynetwork/unicity-ids/blob/main/unicity-ids.testnet.json) registry.

## How It Works

### 1. Registry Loading
- Fetches coin definitions from GitHub
- Caches locally in `~/.unicity/registry-cache.json`
- Cache valid for 24 hours
- Use `--refresh` to force update

### 2. Nametag Resolution
- Queries Nostr relay: `{"kinds": [30078], "#t": ["nametag"]}`
- Gets recipient's Nostr public key from binding
- Creates proxy address: `ProxyAddress.create(TokenId.fromNameTag(nametag))`

### 3. Token Minting
- Uses token type from registry (non-fungible "unicity")
- Uses coin ID from registry
- Applies correct decimals (e.g., 1.5 SOL → 1,500,000,000 units)
- Mints to faucet's address first

### 4. Token Transfer to Proxy Address
- Creates `TransferCommitment` to proxy address
- Submits to aggregator
- Waits for inclusion proof
- Creates transfer transaction

### 5. Nostr Message Delivery
- Encrypts transfer package with NIP-04
- Sends to recipient's Nostr pubkey
- Format: `token_transfer:{"sourceToken":"...","transferTx":"..."}`
- Wallet receives, verifies, and finalizes

## Proper Transfer Flow

Unlike simple token sending, this implements the full Unicity Protocol:

1. **Faucet**: Mint → Transfer to ProxyAddress → Send transfer package via Nostr
2. **Wallet**: Receive → Check proxy address → Load nametag token → Create recipient predicate with transfer's salt → Finalize with SDK → Save

This ensures:
- ✅ Cryptographic ownership verification
- ✅ Proxy address resolution with nametag tokens
- ✅ Proper state transitions
- ✅ Verifiable inclusion proofs

## Registry Cache

**Location:** `~/.unicity/registry-cache.json`

**Clear cache:**
```bash
rm ~/.unicity/registry-cache.json
# Or use --refresh flag
```

## Testing

Run E2E test:
```bash
./gradlew test --tests "FaucetE2ETest.testCompleteTokenTransferFlow"
```

## Dependencies

- Unicity Java SDK 1.2+ (state transitions, proxy addressing)
- Jackson (JSON/CBOR serialization)
- OkHttp (WebSocket for Nostr)
- BouncyCastle (Schnorr signatures)
- Picocli (CLI)
- BitcoinJ (BIP-39 mnemonic)

## License

MIT License
