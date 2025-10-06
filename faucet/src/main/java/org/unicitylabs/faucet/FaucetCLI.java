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
        description = "Token amount in human-readable units (e.g., 0.05 for SOL, uses default from config if not specified)"
    )
    private Double amount;

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

        // Determine user-friendly amount (e.g., "5" or "0.05" for SOL)
        double userAmount = (amount != null) ? amount : config.defaultAmount;

        // Load token registry to get decimals
        UnicityTokenRegistry registry = UnicityTokenRegistry.getInstance();
        int decimals = registry.getDecimals(config.coinId);

        // Convert user amount to smallest units: userAmount * 10^decimals
        long tokenAmount = (long) (userAmount * Math.pow(10, decimals));

        System.out.println("💰 Minting tokens:");
        System.out.println("   User amount: " + userAmount);
        System.out.println("   Decimals: " + decimals);
        System.out.println("   Actual amount (smallest units): " + tokenAmount);
        System.out.println();

        // Derive private key from mnemonic
        byte[] faucetPrivateKey = mnemonicToPrivateKey(config.faucetMnemonic);

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

        // Step 2: Mint token to faucet
        System.out.println();
        TokenMinter minter = new TokenMinter(config.aggregatorUrl, faucetPrivateKey);
        var mintedToken = minter.mintToken(
            config.tokenType,
            config.coinId,
            tokenAmount
        ).join();

        // Step 3: Create ProxyAddress from nametag (deterministic from nametag string)
        System.out.println();
        System.out.println("🔍 Creating proxy address from nametag...");

        org.unicitylabs.sdk.token.TokenId nametagTokenId = org.unicitylabs.sdk.token.TokenId.fromNameTag(nametag);
        org.unicitylabs.sdk.address.ProxyAddress recipientProxyAddress = org.unicitylabs.sdk.address.ProxyAddress.create(nametagTokenId);

        System.out.println("✅ Proxy address: " + recipientProxyAddress.getAddress());

        // Step 4: Transfer token to the proxy address
        TokenMinter.TransferInfo transferInfo = minter.transferToProxyAddress(
            mintedToken,
            recipientProxyAddress
        ).join();

        // Step 5: Serialize both source token and transfer transaction
        String sourceTokenJson = minter.serializeToken(transferInfo.getSourceToken());
        String transferTxJson = minter.serializeTransaction(transferInfo.getTransferTransaction());

        // Step 6: Create transfer package
        String transferPackage = createTransferPackage(sourceTokenJson, transferTxJson);

        // Step 7: Send via Nostr to recipient's Nostr pubkey
        System.out.println();
        System.out.println("📨 Sending token transfer package to " + nametag + " via Nostr...");
        System.out.println("   Nostr pubkey: " + recipientPubKey.substring(0, 16) + "...");
        System.out.println("   Proxy address: " + recipientProxyAddress.getAddress());

        NostrClient nostrClient = new NostrClient(config.nostrRelay, faucetPrivateKey);
        nostrClient.sendEncryptedMessage(recipientPubKey, transferPackage).join();

        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  ✅ Token sent successfully!         ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
        System.out.println("📊 Summary:");
        System.out.println("   Recipient: " + nametag);
        System.out.println("   Amount: " + userAmount + " (= " + tokenAmount + " smallest units)");
        UnicityTokenRegistry.CoinDefinition coinDef = registry.getCoinDefinition(config.coinId);
        if (coinDef != null) {
            System.out.println("   Coin: " + coinDef.name + " (" + coinDef.symbol + ")");
        }
        System.out.println("   Delivery: Nostr relay");
        System.out.println();

        // Clean shutdown
        System.exit(0);
        return 0;
    }

    /**
     * Create a transfer package with source token and transfer transaction
     * Format: token_transfer:{"sourceToken":"...","transferTx":"..."}
     */
    private String createTransferPackage(String sourceTokenJson, String transferTxJson) {
        // Create JSON payload with both source token and transfer transaction
        String payload = String.format(
            "{\"sourceToken\":%s,\"transferTx\":%s}",
            sourceTokenJson,
            transferTxJson
        );
        return "token_transfer:" + payload;
    }

    /**
     * Derive private key from BIP-39 mnemonic phrase
     */
    private byte[] mnemonicToPrivateKey(String mnemonic) throws Exception {
        // Convert mnemonic to seed using BIP-39 (BitcoinJ)
        byte[] seed = org.bitcoinj.crypto.MnemonicCode.toSeed(
            java.util.Arrays.asList(mnemonic.split(" ")),
            "" // No passphrase
        );

        // Use first 32 bytes of seed as private key
        byte[] privateKey = new byte[32];
        System.arraycopy(seed, 0, privateKey, 0, 32);
        return privateKey;
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
