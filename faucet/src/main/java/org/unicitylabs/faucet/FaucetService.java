package org.unicitylabs.faucet;

import org.unicitylabs.faucet.db.FaucetDatabase;
import org.unicitylabs.faucet.db.FaucetRequest;
import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.token.TokenId;

import java.io.File;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for minting and distributing tokens via the faucet
 * Extracted from FaucetCLI for use in REST API
 */
public class FaucetService {

    private final FaucetConfig config;
    private final byte[] faucetPrivateKey;
    private final TokenMinter minter;
    private final NametagResolver nametagResolver;
    private final UnicityTokenRegistry registry;
    private final FaucetDatabase database;
    private final String dataDir;

    public FaucetService(FaucetConfig config, String dataDir) throws Exception {
        this.config = config;
        this.dataDir = dataDir;

        // Ensure data directory exists
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Ensure tokens directory exists
        File tokensDir = new File(dataDir + "/tokens");
        if (!tokensDir.exists()) {
            tokensDir.mkdirs();
        }

        // Initialize database
        this.database = new FaucetDatabase(dataDir);

        // Derive private key from mnemonic
        this.faucetPrivateKey = mnemonicToPrivateKey(config.faucetMnemonic);

        // Initialize token minter
        this.minter = new TokenMinter(config.aggregatorUrl, faucetPrivateKey);

        // Initialize nametag resolver
        this.nametagResolver = new NametagResolver(config.nostrRelay, faucetPrivateKey);

        // Load token registry
        this.registry = UnicityTokenRegistry.getInstance(config.registryUrl);
    }

    /**
     * Process a faucet request: mint tokens and send to recipient
     *
     * @param unicityId The recipient's Unicity ID (nametag)
     * @param coinName The coin name (e.g., "solana", "bitcoin")
     * @param amount The amount in user-friendly units (e.g., 0.05 SOL)
     * @return CompletableFuture with the request result
     */
    public CompletableFuture<FaucetRequestResult> processFaucetRequest(
            String unicityId, String coinName, double amount) {

        return CompletableFuture.supplyAsync(() -> {
            FaucetRequest request = null;
            try {
                // Get token type from registry
                String tokenTypeHex = registry.getUnicityTokenType();
                if (tokenTypeHex == null) {
                    throw new RuntimeException("Could not find Unicity token type in registry");
                }

                // Find the coin definition
                UnicityTokenRegistry.CoinDefinition coinDef = registry.getCoinByName(coinName);
                if (coinDef == null) {
                    throw new RuntimeException("Coin not found: " + coinName);
                }

                // Validate coin ID
                if (coinDef.id.equals(tokenTypeHex)) {
                    throw new RuntimeException("Invalid registry data: Coin ID equals Token Type");
                }

                // Calculate amount in smallest units
                int decimals = coinDef.decimals != null ? coinDef.decimals : 8;
                BigDecimal userAmountDecimal = BigDecimal.valueOf(amount);
                BigDecimal multiplier = BigDecimal.TEN.pow(decimals);
                BigDecimal tokenAmountBD = userAmountDecimal.multiply(multiplier);
                BigInteger tokenAmount = tokenAmountBD.toBigInteger();

                // Validate amount
                if (tokenAmount.compareTo(BigInteger.ZERO) <= 0) {
                    throw new RuntimeException("Invalid amount: " + amount + " " + coinDef.symbol);
                }

                // Create database record
                request = new FaucetRequest(
                        unicityId,
                        coinDef.symbol,
                        coinDef.name,
                        coinDef.id,
                        amount,
                        tokenAmount.toString(),
                        null  // Will be set after nametag resolution
                );
                long requestId = database.insertRequest(request);

                // Step 1: Resolve nametag to Nostr public key
                String recipientPubKey;
                try {
                    recipientPubKey = nametagResolver.resolveNametag(unicityId).join();
                    request.setRecipientNostrPubkey(recipientPubKey);
                    database.updateRequest(request);
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String errorMsg = (cause != null) ? cause.getMessage() : e.getMessage();
                    throw new RuntimeException("Failed to resolve nametag: " + errorMsg);
                }

                // Step 2: Mint token
                var mintedToken = minter.mintToken(tokenTypeHex, coinDef.id, tokenAmount).join();

                // Step 3: Create ProxyAddress from nametag
                TokenId nametagTokenId = TokenId.fromNameTag(unicityId);
                ProxyAddress recipientProxyAddress = ProxyAddress.create(nametagTokenId);

                // Step 4: Transfer token to proxy address
                TokenMinter.TransferInfo transferInfo = minter.transferToProxyAddress(
                        mintedToken,
                        recipientProxyAddress
                ).join();

                // Step 5: Serialize token and transaction
                String sourceTokenJson = minter.serializeToken(transferInfo.getSourceToken());
                String transferTxJson = minter.serializeTransaction(transferInfo.getTransferTransaction());

                // Step 6: Save token files
                String tokenFileName = String.format("token_%d_%s_%s.json",
                        requestId, unicityId, System.currentTimeMillis());
                String tokenFilePath = dataDir + "/tokens/" + tokenFileName;

                Map<String, String> tokenData = new HashMap<>();
                tokenData.put("sourceToken", sourceTokenJson);
                tokenData.put("transferTx", transferTxJson);

                try (FileWriter writer = new FileWriter(tokenFilePath)) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.writerWithDefaultPrettyPrinter().writeValue(writer, tokenData);
                }

                request.setTokenFilePath(tokenFilePath);
                database.updateRequest(request);

                // Step 7: Create transfer package
                String transferPackage = createTransferPackage(sourceTokenJson, transferTxJson);

                // Step 8: Send via Nostr
                NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(faucetPrivateKey);
                NostrClient nostrClient = new NostrClient(keyManager);
                nostrClient.connect(config.nostrRelay).join();
                nostrClient.sendTokenTransfer(recipientPubKey, transferPackage).join();
                nostrClient.disconnect();

                // Update status to SUCCESS
                request.setStatus("SUCCESS");
                database.updateRequest(request);

                return new FaucetRequestResult(
                        true,
                        "Token sent successfully",
                        requestId,
                        unicityId,
                        coinDef.name,
                        coinDef.symbol,
                        amount,
                        tokenAmount.toString(),
                        recipientPubKey,
                        tokenFilePath
                );

            } catch (Exception e) {
                // Update status to FAILED
                if (request != null) {
                    request.setStatus("FAILED");
                    request.setErrorMessage(e.getMessage());
                    try {
                        database.updateRequest(request);
                    } catch (Exception dbEx) {
                        System.err.println("Failed to update request status: " + dbEx.getMessage());
                    }
                }

                return new FaucetRequestResult(
                        false,
                        "Failed: " + e.getMessage(),
                        request != null ? request.getId() : null,
                        unicityId,
                        coinName,
                        null,
                        amount,
                        null,
                        null,
                        null
                );
            }
        });
    }

    /**
     * Get all supported coins from registry
     */
    public UnicityTokenRegistry.CoinDefinition[] getSupportedCoins() {
        return registry.getFungibleCoins().toArray(new UnicityTokenRegistry.CoinDefinition[0]);
    }

    /**
     * Get database instance for history queries
     */
    public FaucetDatabase getDatabase() {
        return database;
    }

    /**
     * Create transfer package (same as FaucetCLI)
     */
    private String createTransferPackage(String sourceTokenJson, String transferTxJson) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, String> payload = new HashMap<>();
        payload.put("sourceToken", sourceTokenJson);
        payload.put("transferTx", transferTxJson);
        return mapper.writeValueAsString(payload);
    }

    /**
     * Derive private key from BIP-39 mnemonic (same as FaucetCLI)
     */
    private byte[] mnemonicToPrivateKey(String mnemonic) throws Exception {
        byte[] seed = org.bitcoinj.crypto.MnemonicCode.toSeed(
                java.util.Arrays.asList(mnemonic.split(" ")),
                ""
        );
        byte[] privateKey = new byte[32];
        System.arraycopy(seed, 0, privateKey, 0, 32);
        return privateKey;
    }

    /**
     * Result of a faucet request
     */
    public static class FaucetRequestResult {
        public final boolean success;
        public final String message;
        public final Long requestId;
        public final String unicityId;
        public final String coinName;
        public final String coinSymbol;
        public final double amount;
        public final String amountInSmallestUnits;
        public final String recipientNostrPubkey;
        public final String tokenFilePath;

        public FaucetRequestResult(boolean success, String message, Long requestId,
                                   String unicityId, String coinName, String coinSymbol,
                                   double amount, String amountInSmallestUnits,
                                   String recipientNostrPubkey, String tokenFilePath) {
            this.success = success;
            this.message = message;
            this.requestId = requestId;
            this.unicityId = unicityId;
            this.coinName = coinName;
            this.coinSymbol = coinSymbol;
            this.amount = amount;
            this.amountInSmallestUnits = amountInSmallestUnits;
            this.recipientNostrPubkey = recipientNostrPubkey;
            this.tokenFilePath = tokenFilePath;
        }
    }
}
