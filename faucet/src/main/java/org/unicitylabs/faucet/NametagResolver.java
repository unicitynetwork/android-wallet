package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves nametag to Nostr public key using Unicity nametag service
 */
public class NametagResolver {

    private static final String NAMETAG_SERVICE_URL = "https://nametag-test.unicity.network";

    private final OkHttpClient httpClient;
    private final ObjectMapper jsonMapper;

    public NametagResolver() {
        this.httpClient = new OkHttpClient();
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Resolve a nametag to a Nostr public key
     *
     * @param nametag The nametag to resolve (e.g., "alice")
     * @return CompletableFuture with the Nostr public key (hex)
     */
    public CompletableFuture<String> resolveNametag(String nametag) {
        CompletableFuture<String> future = new CompletableFuture<>();

        String url = NAMETAG_SERVICE_URL + "/resolve/" + nametag;
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        System.out.println("🔍 Resolving nametag: " + nametag);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.err.println("❌ Failed to resolve nametag: " + e.getMessage());
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        System.err.println("❌ Nametag not found: " + nametag);
                        future.completeExceptionally(
                            new RuntimeException("Nametag not found: " + nametag)
                        );
                        return;
                    }

                    String responseBody = response.body().string();
                    JsonNode json = jsonMapper.readTree(responseBody);

                    // Extract Nostr public key from response
                    // Expected format: { "nostrPubKey": "hex..." } or { "publicKey": "hex..." }
                    String nostrPubKey = null;
                    if (json.has("nostrPubKey")) {
                        nostrPubKey = json.get("nostrPubKey").asText();
                    } else if (json.has("publicKey")) {
                        nostrPubKey = json.get("publicKey").asText();
                    } else if (json.has("pubkey")) {
                        nostrPubKey = json.get("pubkey").asText();
                    }

                    if (nostrPubKey == null || nostrPubKey.isEmpty()) {
                        System.err.println("❌ No Nostr public key in response");
                        future.completeExceptionally(
                            new RuntimeException("No Nostr public key in nametag response")
                        );
                        return;
                    }

                    System.out.println("✅ Resolved nametag '" + nametag + "' to: " + nostrPubKey.substring(0, 8) + "...");
                    future.complete(nostrPubKey);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }
}
