package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.commons.codec.binary.Hex;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nostr client for sending token transfer messages via relay
 */
public class NostrClient {

    private static final int KIND_TOKEN_TRANSFER = 31113; // Custom Unicity event kind for token transfers
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;

    private final String relayUrl;
    private final byte[] privateKey;
    private final String publicKeyHex;
    private final OkHttpClient httpClient;
    private final ObjectMapper jsonMapper;

    public NostrClient(String relayUrl, byte[] privateKey) throws Exception {
        this.relayUrl = relayUrl;
        this.privateKey = privateKey;

        // Derive x-only public key using BIP-340 Schnorr
        byte[] pubKeyBytes = Schnorr.getPublicKey(privateKey);
        this.publicKeyHex = Hex.encodeHexString(pubKeyBytes);

        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Send encrypted message to a recipient via Nostr relay
     *
     * @param recipientPubKey Recipient's public key (hex)
     * @param message Message content (will be encrypted)
     * @return CompletableFuture that completes when message is sent
     */
    public CompletableFuture<NostrConnection> sendEncryptedMessage(String recipientPubKey, String message) {
        CompletableFuture<NostrConnection> future = new CompletableFuture<>();

        Request request = new Request.Builder()
            .url(relayUrl)
            .build();

        WebSocketListener listener = new WebSocketListener() {
            private WebSocket ws;

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                this.ws = webSocket;
                System.out.println("✅ Connected to Nostr relay: " + relayUrl);

                try {
                    // Create and send encrypted event
                    NostrEvent event = createEncryptedEvent(recipientPubKey, message);
                    List<Object> eventRequest = Arrays.asList("EVENT", event);
                    String json = jsonMapper.writeValueAsString(eventRequest);

                    System.out.println("📤 Sending encrypted message to: " + recipientPubKey.substring(0, 8) + "...");
                    System.out.println("🔍 Event details:");
                    System.out.println("   ID: " + event.id.substring(0, 16) + "...");
                    System.out.println("   From (pubkey): " + event.pubkey.substring(0, 16) + "...");
                    System.out.println("   To (p tag): " + recipientPubKey.substring(0, 16) + "...");
                    System.out.println("   Kind: " + event.kind);
                    System.out.println("   Tags: " + event.tags);
                    ws.send(json);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    ws.close(1000, "Error");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    List<?> message = jsonMapper.readValue(text, List.class);
                    String messageType = (String) message.get(0);

                    if ("OK".equals(messageType)) {
                        String eventId = message.size() > 1 ? (String) message.get(1) : "unknown";
                        boolean success = message.size() > 2 && (Boolean) message.get(2);
                        String statusMessage = message.size() > 3 ? (String) message.get(3) : "";

                        System.out.println("📨 Relay OK response:");
                        System.out.println("   Event ID: " + eventId);
                        System.out.println("   Success: " + success);
                        System.out.println("   Message: " + statusMessage);

                        if (success) {
                            System.out.println("✅ Message delivered successfully");
                            future.complete(new NostrConnection(ws));
                            // Don't close immediately - let the connection stay open
                            // The caller can close it when appropriate
                        } else {
                            System.err.println("❌ Message rejected: " + statusMessage);
                            future.completeExceptionally(new RuntimeException("Message rejected: " + statusMessage));
                            ws.close(1000, "Failed");
                        }
                    } else if ("NOTICE".equals(messageType)) {
                        System.out.println("📢 Relay notice: " + message.get(1));
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    webSocket.close(1000, "Error");
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("❌ WebSocket error: " + t.getMessage());
                future.completeExceptionally(t);
            }
        };

        httpClient.newWebSocket(request, listener);
        return future;
    }

    /**
     * Create a Nostr event with encrypted content using proper BIP-340 Schnorr signatures
     */
    private NostrEvent createEncryptedEvent(String recipientPubKey, String content) throws Exception {
        long createdAt = System.currentTimeMillis() / 1000;

        // Simple hex encoding as "encryption" (in production, use proper NIP-04 encryption)
        String encryptedContent = Hex.encodeHexString(content.getBytes());

        // Create tags (p tag for recipient)
        List<List<String>> tags = new ArrayList<>();
        tags.add(Arrays.asList("p", recipientPubKey));

        // Create event data for ID calculation (canonical JSON array)
        List<Object> eventData = Arrays.asList(
            0,
            publicKeyHex,
            createdAt,
            KIND_TOKEN_TRANSFER,
            tags,
            encryptedContent
        );

        String eventJson = jsonMapper.writeValueAsString(eventData);

        // Calculate event ID (SHA-256 of canonical JSON)
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] eventIdBytes = digest.digest(eventJson.getBytes());
        String eventId = Hex.encodeHexString(eventIdBytes);

        // Sign with BIP-340 Schnorr signature (proper Nostr signing)
        byte[] signature = Schnorr.sign(eventIdBytes, privateKey);
        String signatureHex = Hex.encodeHexString(signature);

        return new NostrEvent(eventId, publicKeyHex, createdAt, KIND_TOKEN_TRANSFER,
                             tags, encryptedContent, signatureHex);
    }

    /**
     * Nostr WebSocket connection handle
     */
    public static class NostrConnection {
        private final WebSocket webSocket;

        public NostrConnection(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        public void close() {
            if (webSocket != null) {
                webSocket.close(1000, "Normal closure");
            }
        }
    }

    /**
     * Nostr event structure
     */
    public static class NostrEvent {
        public String id;
        public String pubkey;
        public long created_at;
        public int kind;
        public List<List<String>> tags;
        public String content;
        public String sig;

        public NostrEvent() {}

        public NostrEvent(String id, String pubkey, long created_at, int kind,
                         List<List<String>> tags, String content, String sig) {
            this.id = id;
            this.pubkey = pubkey;
            this.created_at = created_at;
            this.kind = kind;
            this.tags = tags;
            this.content = content;
            this.sig = sig;
        }
    }
}
