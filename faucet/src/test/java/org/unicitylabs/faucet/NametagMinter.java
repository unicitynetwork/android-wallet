package org.unicitylabs.faucet;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.DirectAddress;
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
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.transaction.MintCommitment;
import org.unicitylabs.sdk.transaction.MintTransactionReason;
import org.unicitylabs.sdk.transaction.NametagMintTransactionData;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

/**
 * Service for minting nametag tokens for testing
 */
public class NametagMinter {

    private final StateTransitionClient client;
    private final RootTrustBase trustBase;
    private final SecureRandom random;

    public NametagMinter(String aggregatorUrl) {
        this.random = new SecureRandom();

        // Load trust base from testnet config
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("trustbase-testnet.json");
            if (inputStream == null) {
                throw new RuntimeException("trustbase-testnet.json not found in test resources");
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
     * Mint a nametag for a user
     *
     * @param nametag The nametag string (e.g., "alice-test-12345")
     * @param ownerPrivateKey Owner's private key
     * @param nostrPubKey Owner's Nostr public key (hex)
     * @return Minted nametag token
     */
    public CompletableFuture<Token<NametagMintTransactionData<MintTransactionReason>>> mintNametag(
        String nametag,
        byte[] ownerPrivateKey,
        String nostrPubKey
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("🏷️  Minting nametag: " + nametag);

                // Create signing service with nonce
                byte[] nonce = new byte[32];
                random.nextBytes(nonce);
                SigningService signingService = SigningService.createFromMaskedSecret(ownerPrivateKey, nonce);

                // Generate random token type
                byte[] tokenTypeData = new byte[32];
                random.nextBytes(tokenTypeData);
                TokenType tokenType = new TokenType(tokenTypeData);

                // Create address for nametag
                DirectAddress nametagAddress = MaskedPredicateReference.create(
                    tokenType,
                    signingService,
                    HashAlgorithm.SHA256,
                    nonce
                ).toAddress();

                // Create salt
                byte[] salt = new byte[32];
                random.nextBytes(salt);

                // Create metadata binding Nostr pubkey to this nametag
                var metadata = new java.util.HashMap<String, Object>();
                metadata.put("nostrPubkey", nostrPubKey);
                metadata.put("nametag", nametag);
                metadata.put("version", "1.0");
                byte[] tokenData = UnicityObjectMapper.JSON.writeValueAsBytes(metadata);

                // Create nametag with standard constructor (no tokenData in simplified version)
                // For now, using standard constructor - in production, would need custom implementation
                NametagMintTransactionData<MintTransactionReason> nametagData = new NametagMintTransactionData<>(
                    nametag,        // nametag string
                    tokenType,      // token type
                    nametagAddress, // nametag address
                    salt,           // salt
                    nametagAddress  // owner address (same as nametag address)
                );

                // TODO: In production, extend NametagMintTransactionData to include tokenData
                // or use a registry service for Nostr<->Nametag binding

                // Create mint commitment
                MintCommitment<NametagMintTransactionData<MintTransactionReason>> commitment =
                    MintCommitment.create(nametagData);

                // Submit commitment
                System.out.println("📡 Submitting nametag commitment...");
                SubmitCommitmentResponse response = client.submitCommitment(commitment).join();

                if (response.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                    throw new RuntimeException("Failed to submit nametag: " + response.getStatus());
                }

                System.out.println("✅ Nametag commitment submitted!");

                // Wait for inclusion proof
                System.out.println("⏳ Waiting for inclusion proof...");
                var inclusionProof = InclusionProofUtils.waitInclusionProof(
                    client,
                    trustBase,
                    commitment
                ).join();

                if (inclusionProof == null) {
                    throw new RuntimeException("Failed to get inclusion proof for nametag");
                }

                System.out.println("✅ Inclusion proof received!");

                // Create token using Token.create() with proper predicate
                // Following SDK TokenUtils.mintNametagToken pattern
                Token<NametagMintTransactionData<MintTransactionReason>> token = Token.create(
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

                System.out.println("✅ Nametag minted successfully: " + nametag);

                return token;
            } catch (Exception e) {
                System.err.println("❌ Error minting nametag: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to mint nametag", e);
            }
        });
    }

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
}
