# Unicity Nostr SDK

Java SDK for Nostr protocol integration with Unicity blockchain applications.

## Features

- **Token Transfers**: Send and receive Unicity tokens via Nostr
- **Nametag Bindings**: Map Unicity nametags to Nostr public keys
- **Encrypted Messaging**: NIP-04 encrypted direct messages with compression
- **Location Broadcasting**: Agent location discovery for P2P networks
- **Profile Management**: Standard Nostr profiles
- **Multi-Relay Support**: Connect to multiple Nostr relays simultaneously
- **Pure Java**: No JNI dependencies, works on Android and JVM

## Requirements

- Java 11 or higher
- Android API 31+ (for Android applications)

## Installation

### Gradle

```gradle
dependencies {
    implementation("org.unicitylabs:unicity-nostr-sdk:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>org.unicitylabs</groupId>
    <artifactId>unicity-nostr-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### Initialize Client

```java
// Create key manager from private key
byte[] privateKey = ...; // 32-byte private key
NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(privateKey);

// Create client
NostrClient client = new NostrClient(keyManager);

// Connect to relays
client.connect("wss://relay.example.com");
```

### Send Encrypted Message

```java
String recipientPubkey = "...";
String message = "Hello, Nostr!";

client.publishEncryptedMessage(recipientPubkey, message)
    .thenAccept(eventId -> {
        System.out.println("Message sent: " + eventId);
    });
```

### Subscribe to Events

```java
Filter filter = Filter.builder()
    .kinds(EventKinds.ENCRYPTED_DM)
    .pTags(keyManager.getPublicKeyHex())
    .build();

client.subscribe(filter, new NostrEventListener() {
    @Override
    public void onEvent(Event event) {
        System.out.println("Received event: " + event.id);
    }
});
```

### Token Transfers

```java
// Send token to recipient's nametag
String recipientNametag = "alice@unicity";
String tokenJson = ...; // Unicity SDK token JSON

client.sendTokenTransfer(recipientNametag, tokenJson)
    .thenAccept(eventId -> {
        System.out.println("Token sent: " + eventId);
    });
```

### Nametag Bindings

```java
// Publish nametag binding
client.publishNametagBinding("alice@unicity", "0x123...")
    .thenAccept(success -> {
        System.out.println("Binding published: " + success);
    });

// Query pubkey by nametag
client.queryPubkeyByNametag("alice@unicity")
    .thenAccept(pubkey -> {
        System.out.println("Found pubkey: " + pubkey);
    });
```

## Architecture

The SDK is organized into several packages:

- `org.unicitylabs.nostr.client` - Main client and relay management
- `org.unicitylabs.nostr.protocol` - Nostr protocol structures (Event, Filter)
- `org.unicitylabs.nostr.crypto` - Cryptographic operations (Schnorr, NIP-04)
- `org.unicitylabs.nostr.nametag` - Nametag binding protocol
- `org.unicitylabs.nostr.token` - Token transfer protocol
- `org.unicitylabs.nostr.util` - Utility classes

## Documentation

Full API documentation is available at [docs link].

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

MIT License - see [LICENSE](LICENSE) for details.
