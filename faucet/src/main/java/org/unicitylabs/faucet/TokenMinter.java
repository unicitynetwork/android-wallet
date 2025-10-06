package org.unicitylabs.faucet;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.ProxyAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicateReference;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.MintCommitment;
import org.unicitylabs.sdk.transaction.MintTransactionData;
import org.unicitylabs.sdk.transaction.MintTransactionReason;
import org.unicitylabs.sdk.transaction.TransferCommitment;
import org.unicitylabs.sdk.transaction.TransferTransactionData;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for minting Unicity tokens using the Java SDK
 */
public class TokenMinter {

    private final StateTransitionClient client;
    private final SigningService signingService;
    private final RootTrustBase trustBase;
    private final SecureRandom random;

    public RootTrustBase getTrustBase() {
        return trustBase;
    }

    public TokenMinter(String aggregatorUrl, byte[] privateKey) throws Exception {
        this.random = new SecureRandom();

        // Create signing service with nonce for masked secret
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        this.signingService = SigningService.createFromMaskedSecret(privateKey, nonce);

        // Load trust base from testnet config
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("trustbase-testnet.json");
            if (inputStream == null) {
                throw new RuntimeException("trustbase-testnet.json not found in resources");
            }
            String json = new String(inputStream.readAllBytes());
            this.trustBase = UnicityObjectMapper.JSON.readValue(json, RootTrustBase.class);
            System.out.println("✅ Loaded RootTrustBase from trustbase-testnet.json");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load trustbase-testnet.json", e);
        }

        // Initialize aggregator client
        AggregatorClient aggregatorClient = new AggregatorClient(aggregatorUrl);
        this.client = new StateTransitionClient(aggregatorClient);
    }

    /**
     * Mint a token with specified parameters
     *
     * @param tokenTypeHex Token type hex string (32 bytes)
     * @param coinIdHex Coin ID hex string (32 bytes)
     * @param amount Token amount
     * @return Minted token
     */
    public CompletableFuture<Token<MintTransactionData<MintTransactionReason>>> mintToken(
        String tokenTypeHex, String coinIdHex, long amount
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("🔨 Minting token...");
                System.out.println("   Token Type: " + tokenTypeHex);
                System.out.println("   Coin ID: " + coinIdHex);
                System.out.println("   Amount: " + amount);

                // Generate random token ID
                byte[] tokenIdData = new byte[32];
                random.nextBytes(tokenIdData);
                TokenId tokenId = new TokenId(tokenIdData);

                // Use token type from config
                byte[] tokenTypeData = hexStringToByteArray(tokenTypeHex);
                TokenType tokenType = new TokenType(tokenTypeData);

                // Use coin ID from config
                byte[] coinIdData = hexStringToByteArray(coinIdHex);

                Map<CoinId, BigInteger> coins = new HashMap<>();
                coins.put(new CoinId(coinIdData), BigInteger.valueOf(amount));
                TokenCoinData coinData = new TokenCoinData(coins);

                // Generate random nonce for masked predicate
                byte[] nonce = new byte[32];
                random.nextBytes(nonce);

                // Create address using MaskedPredicateReference (before commitment)
                var address = MaskedPredicateReference.create(
                    tokenType,
                    signingService,
                    HashAlgorithm.SHA256,
                    nonce
                ).toAddress();

                // No token data bytes - leave as null as requested
                byte[] tokenDataBytes = null;

                // Create salt
                byte[] salt = new byte[32];
                random.nextBytes(salt);

                // Create mint transaction data (following SDK TokenUtils pattern)
                MintTransactionData<MintTransactionReason> mintData = new MintTransactionData<>(
                    tokenId,
                    tokenType,
                    tokenDataBytes,
                    coinData,
                    address,
                    salt,
                    null,  // No data hash
                    null   // No reason
                );

                // Create mint commitment
                MintCommitment<MintTransactionData<MintTransactionReason>> commitment =
                    MintCommitment.create(mintData);

                // Submit commitment to aggregator
                System.out.println("📡 Submitting commitment to aggregator...");
                SubmitCommitmentResponse response = client.submitCommitment(commitment).join();

                if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                    throw new RuntimeException("Failed to submit commitment: " + response.getStatus());
                }

                System.out.println("✅ Commitment submitted successfully!");

                // Wait for inclusion proof
                System.out.println("⏳ Waiting for inclusion proof...");
                var inclusionProof = InclusionProofUtils.waitInclusionProof(
                    client,
                    trustBase,
                    commitment
                ).join();

                if (inclusionProof == null) {
                    throw new RuntimeException("Failed to get inclusion proof");
                }

                System.out.println("✅ Inclusion proof received!");

                // Create token using Token.create() with proper predicate
                // Following SDK TokenUtils.mintToken pattern - create predicate from commitment data
                Token<MintTransactionData<MintTransactionReason>> token = Token.create(
                    trustBase,
                    new TokenState(
                        MaskedPredicate.create(
                            commitment.getTransactionData().getTokenId(),
                            commitment.getTransactionData().getTokenType(),
                            signingService,
                            HashAlgorithm.SHA256,
                            nonce
                        ),
                        null
                    ),
                    commitment.toTransaction(inclusionProof)
                );

                System.out.println("✅ Token minted successfully!");
                System.out.println("   Token ID: " + bytesToHex(tokenIdData));

                return token;
            } catch (Exception e) {
                System.err.println("❌ Error minting token: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to mint token", e);
            }
        });
    }

    /**
     * Serialize token to JSON
     */
    public String serializeToken(Token<?> token) throws Exception {
        return UnicityObjectMapper.JSON.writeValueAsString(token);
    }

    /**
     * Serialize transfer transaction to JSON
     */
    public String serializeTransaction(Transaction<TransferTransactionData> tx) throws Exception {
        return UnicityObjectMapper.JSON.writeValueAsString(tx);
    }

    /**
     * Transfer token to a proxy address
     * @param sourceToken Token to transfer
     * @param recipientProxyAddress Recipient's proxy address
     * @return Transfer info containing the source token and transfer transaction
     */
    public CompletableFuture<TransferInfo> transferToProxyAddress(
        Token<?> sourceToken,
        ProxyAddress recipientProxyAddress
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("🔄 Transferring token to proxy address...");
                System.out.println("   Proxy: " + recipientProxyAddress.getAddress());

                var recipientAddress = recipientProxyAddress;

                // Generate random salt for transfer
                byte[] salt = new byte[32];
                random.nextBytes(salt);

                // Create transfer commitment
                TransferCommitment transferCommitment = TransferCommitment.create(
                    sourceToken,
                    recipientAddress,
                    salt,
                    null,  // No custom data hash
                    null,  // No additional tokens
                    signingService
                );

                System.out.println("📡 Submitting transfer commitment to aggregator...");
                SubmitCommitmentResponse response = client.submitCommitment(transferCommitment).join();

                if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                    throw new RuntimeException("Failed to submit transfer commitment: " + response.getStatus());
                }
                System.out.println("✅ Transfer commitment submitted!");

                // Wait for inclusion proof
                System.out.println("⏳ Waiting for transfer inclusion proof...");
                var inclusionProof = InclusionProofUtils.waitInclusionProof(
                    client,
                    trustBase,
                    transferCommitment
                ).join();
                System.out.println("✅ Transfer inclusion proof received!");

                // Create transfer transaction
                Transaction<TransferTransactionData> transferTransaction =
                    transferCommitment.toTransaction(inclusionProof);

                System.out.println("✅ Token transferred successfully!");

                // Return both source token and transfer transaction
                // The recipient needs both to finalize on their end
                return new TransferInfo(sourceToken, transferTransaction);
            } catch (Exception e) {
                System.err.println("❌ Error transferring token: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to transfer token", e);
            }
        });
    }

    /**
     * Helper method to convert hex string to byte array
     */
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Container for transfer information that recipient needs
     */
    public static class TransferInfo {
        private final Token<?> sourceToken;
        private final Transaction<TransferTransactionData> transferTransaction;

        public TransferInfo(Token<?> sourceToken, Transaction<TransferTransactionData> transferTransaction) {
            this.sourceToken = sourceToken;
            this.transferTransaction = transferTransaction;
        }

        public Token<?> getSourceToken() {
            return sourceToken;
        }

        public Transaction<TransferTransactionData> getTransferTransaction() {
            return transferTransaction;
        }

        /**
         * Serialize transfer info to JSON for sending to recipient
         */
        public String toJson() throws Exception {
            var data = new java.util.HashMap<String, Object>();
            data.put("sourceToken", sourceToken);
            data.put("transferTransaction", transferTransaction);
            return UnicityObjectMapper.JSON.writeValueAsString(data);
        }
    }

    /**
     * Convert hex string to byte array
     */
    private byte[] hexToBytes(String hex) {
        // Take first 32 bytes (64 hex chars)
        hex = hex.substring(0, Math.min(64, hex.length()));
        int len = hex.length();
        byte[] data = new byte[32];
        for (int i = 0; i < len && i < 64; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Convert byte array to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
