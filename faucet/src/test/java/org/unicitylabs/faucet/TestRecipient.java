package org.unicitylabs.faucet;

import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransactionData;

import java.security.SecureRandom;

/**
 * Test helper to simulate recipient operations (Alice finalizing tokens).
 * This is ONLY for testing - real recipients would do this themselves.
 */
public class TestRecipient {
    private final StateTransitionClient client;
    private final RootTrustBase trustBase;
    private final byte[] privateKey;
    private final SigningService signingService;
    private final SecureRandom random = new SecureRandom();

    public TestRecipient(StateTransitionClient client, RootTrustBase trustBase) {
        this.client = client;
        this.trustBase = trustBase;
        this.privateKey = new byte[32];
        random.nextBytes(privateKey);

        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        this.signingService = SigningService.createFromMaskedSecret(privateKey, nonce);
    }

    /**
     * Finalize a received token (what Alice would do on her device)
     */
    public Token<?> finalizeReceivedToken(TokenMinter.TransferInfo transferInfo) throws Exception {
        Token<?> sourceToken = transferInfo.getSourceToken();
        Transaction<TransferTransactionData> transferTx = transferInfo.getTransferTransaction();

        // Get token ID and type from source
        MaskedPredicate sourcePredicate = (MaskedPredicate) sourceToken.getState().getPredicate();

        // Create recipient's predicate using the salt from transfer
        MaskedPredicate recipientPredicate = MaskedPredicate.create(
            sourcePredicate.getTokenId(),
            sourcePredicate.getTokenType(),
            signingService,
            HashAlgorithm.SHA256,
            transferTx.getData().getSalt()
        );

        // Finalize the transaction
        return client.finalizeTransaction(
            trustBase,
            sourceToken,
            new TokenState(recipientPredicate, null),
            transferTx,
            java.util.List.of()  // Would include nametag token if available
        );
    }
}