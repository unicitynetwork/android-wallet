package org.unicitylabs.faucet;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * CLI application for minting Unicity tokens and sending them via Nostr to a nametag recipient
 *
 * Usage:
 *   ./gradlew run --args="--nametag=alice --amount=100"
 *   ./gradlew mint --args="--nametag=alice --amount=100"
 */
@Command(
    name = "faucet",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "Mint Unicity tokens and send them via Nostr to a nametag recipient"
)
public class FaucetCLI implements Callable<Integer> {

    @Option(
        names = {"-n", "--nametag"},
        required = true,
        description = "Recipient's nametag (e.g., 'alice')"
    )
    private String nametag;

    @Option(
        names = {"-a", "--amount"},
        description = "Token amount (uses default from config if not specified)"
    )
    private Long amount;

    @Option(
        names = {"-c", "--config"},
        description = "Path to config file (default: faucet-config.json in resources)"
    )
    private String configPath;

    @Override
    public Integer call() throws Exception {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Unicity Token Faucet v1.0.0        ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        // Load configuration
        FaucetConfig config = FaucetConfig.load();
        System.out.println("✅ Configuration loaded");
        System.out.println("   Relay: " + config.nostrRelay);
        System.out.println("   Aggregator: " + config.aggregatorUrl);
        System.out.println("   Token Type: " + config.tokenType);
        System.out.println("   Coin ID: " + config.coinId);
        System.out.println();

        // Determine amount
        long tokenAmount = (amount != null) ? amount : config.defaultAmount;

        // Convert faucet private key from hex
        byte[] faucetPrivateKey = hexStringToByteArray(config.faucetPrivateKey);

        // Step 1: Resolve nametag to Nostr public key
        NametagResolver nametagResolver = new NametagResolver(config.nostrRelay, faucetPrivateKey);
        String recipientPubKey;
        try {
            recipientPubKey = nametagResolver.resolveNametag(nametag).join();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            String errorMsg = (cause != null) ? cause.getMessage() : e.getMessage();
            System.err.println("\n❌ " + errorMsg);
            System.err.println("\nMake sure the wallet user has minted the nametag and published the binding to Nostr.");
            System.exit(1);
            return 1;
        }

        // Step 2: Mint the token (includes submission to aggregator)
        TokenMinter minter = new TokenMinter(config.aggregatorUrl, faucetPrivateKey);
        var token = minter.mintToken(
            config.tokenType,
            config.coinId,
            tokenAmount
        ).join();

        // Step 3: Serialize token to JSON
        String tokenJson = minter.serializeToken(token);

        // Step 5: Send token via Nostr to recipient
        System.out.println();
        System.out.println("📨 Sending token to " + nametag + " via Nostr...");

        NostrClient nostrClient = new NostrClient(config.nostrRelay, faucetPrivateKey);

        // Create transfer message with token JSON
        String transferMessage = createTransferMessage(tokenJson, tokenAmount, config.coinId);

        // Send encrypted message via Nostr
        nostrClient.sendEncryptedMessage(recipientPubKey, transferMessage).join();

        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  ✅ Token sent successfully!         ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
        System.out.println("📊 Summary:");
        System.out.println("   Recipient: " + nametag);
        System.out.println("   Amount: " + tokenAmount + " tokens");
        System.out.println("   Delivery: Nostr relay");
        System.out.println();

        // Clean shutdown
        System.exit(0);
        return 0;
    }

    /**
     * Create a transfer message with token JSON
     */
    private String createTransferMessage(String tokenJson, long amount, String coinId) {
        // Format: token_transfer:{tokenJson}
        return "token_transfer:" + tokenJson;
    }

    /**
     * Convert hex string to byte array
     */
    private byte[] hexStringToByteArray(String hex) {
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new FaucetCLI()).execute(args);
        System.exit(exitCode);
    }
}
