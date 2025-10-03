package org.unicitylabs.faucet;

import java.util.concurrent.CompletableFuture;

/**
 * Resolves nametag to Nostr public key by querying the Nostr relay
 * Uses the nametag binding system we implemented
 */
public class NametagResolver {

    private final String relayUrl;
    private final byte[] faucetPrivateKey;

    public NametagResolver(String relayUrl, byte[] faucetPrivateKey) {
        this.relayUrl = relayUrl;
        this.faucetPrivateKey = faucetPrivateKey;
    }

    /**
     * Resolve a nametag to a Nostr public key by querying the Nostr relay
     *
     * @param nametag The nametag to resolve (e.g., "alice-test-abc123")
     * @return CompletableFuture with the Nostr public key (hex)
     */
    public CompletableFuture<String> resolveNametag(String nametag) {
        System.out.println("🔍 Resolving nametag via Nostr relay: " + nametag);

        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            // Use NostrClient to query the nametag binding
            NostrClient nostrClient = new NostrClient(relayUrl, faucetPrivateKey);

            return nostrClient.queryPubkeyByNametag(relayUrl, nametag)
                .thenApply(pubkey -> {
                    if (pubkey == null) {
                        throw new RuntimeException("Nametag not found: " + nametag);
                    }
                    System.out.println("✅ Resolved nametag '" + nametag + "' to: " + pubkey.substring(0, 16) + "...");
                    return pubkey;
                });
        } catch (Exception e) {
            future.completeExceptionally(e);
            return future;
        }
    }
}
