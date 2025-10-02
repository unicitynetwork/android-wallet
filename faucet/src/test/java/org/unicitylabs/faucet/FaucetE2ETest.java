package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.JsonNode;
import org.unicitylabs.sdk.transaction.Transaction;
import org.unicitylabs.sdk.transaction.TransferTransactionData;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.commons.codec.binary.Hex;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * E2E test for the faucet:
 * 1. Mint a unique nametag for Alice
 * 2. Mint a solana-test token
 * 3. Send token to Alice via Nostr
 * 4. Verify Alice receives the token
 *
 * NOTE: Uses simplified message format without real Nostr signatures for testing.
 * The wallet's NostrP2PService will process the token_transfer message.
 */
public class FaucetE2ETest {

    private static final String AGGREGATOR_URL = "https://goggregator-test.unicity.network";
    private static final String NOSTR_RELAY = "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080";
    private static final long TIMEOUT_SECONDS = 60L;
    private static final int KIND_TOKEN_TRANSFER = 31113; // Custom Unicity event kind

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout - keep connection open indefinitely
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Send ping every 20 seconds to keep alive
        .retryOnConnectionFailure(true) // Auto-retry on connection failures
        .build();

    private byte[] faucetPrivateKey;
    private byte[] alicePrivateKey;
    private String aliceNostrPubKey;
    private String aliceNametag;
    private WebSocket aliceWebSocket;
    private final List<String> aliceReceivedMessages = new ArrayList<>();
    private final CountDownLatch aliceTokenReceivedLatch = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  Faucet E2E Test - Starting Setup           ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // Generate faucet private key
        faucetPrivateKey = new byte[32];
        new SecureRandom().nextBytes(faucetPrivateKey);
        System.out.println("✅ Generated faucet private key");

        // Generate Alice's private key and derive Nostr public key properly
        alicePrivateKey = new byte[32];
        new SecureRandom().nextBytes(alicePrivateKey);
        byte[] alicePubKeyBytes = Schnorr.getPublicKey(alicePrivateKey); // Derive from private key
        aliceNostrPubKey = Hex.encodeHexString(alicePubKeyBytes);
        System.out.println("✅ Alice's Nostr pubkey: " + aliceNostrPubKey.substring(0, 16) + "...");

        // Generate unique nametag for Alice
        aliceNametag = "alice-test-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("✅ Generated unique nametag: " + aliceNametag);
    }

    @After
    public void tearDown() {
        if (aliceWebSocket != null) {
            aliceWebSocket.close(1000, "Test complete");
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @Test
    public void testCompleteTokenTransferFlow() throws Exception {
        System.out.println("\n📋 Test Flow:");
        System.out.println("   1. Mint nametag for Alice");
        System.out.println("   2. Mint solana-test token");
        System.out.println("   3. Transfer token to Alice (adds transfer tx)");
        System.out.println("   4. Connect Alice to Nostr relay");
        System.out.println("   5. Send token to Alice via Nostr");
        System.out.println("   6. Verify Alice receives token");
        System.out.println("   7. Verify token has complete transaction history (mint + transfer)\n");

        // Step 1: Mint nametag for Alice
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 1: Minting nametag for Alice");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        NametagMinter nametagMinter = new NametagMinter(AGGREGATOR_URL);
        var nametagToken = nametagMinter.mintNametag(
            aliceNametag,
            alicePrivateKey,
            aliceNostrPubKey
        ).join();

        assertNotNull("Nametag token should be minted", nametagToken);
        System.out.println("✅ Nametag minted successfully!");

        // Step 2: Mint token from faucet (before connecting Alice to avoid timeout)
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 2: Minting solana-test token");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        TokenMinter tokenMinter = new TokenMinter(AGGREGATOR_URL, faucetPrivateKey);
        var token = tokenMinter.mintToken(
            2,  // systemId
            "0x01020304050607080102030405060708010203040506070801020304050607FF",  // unitId
            "solana-test",  // coinId
            1000L  // amount
        ).join();

        assertNotNull("Token should be minted", token);
        System.out.println("✅ Token minted successfully!");

        // Transfer token to Alice's nametag
        System.out.println("\n🔄 Transferring token to Alice's nametag: " + aliceNametag);
        var transferInfo = tokenMinter.transferToNametag(token, aliceNametag).join();
        assertNotNull("Transfer info should be created", transferInfo);
        System.out.println("✅ Token transferred to Alice!");

        // For testing: Alice receives and finalizes the token
        // In production, Alice would do this on her own device
        var aliceRecipient = new TestRecipient(
            new org.unicitylabs.sdk.StateTransitionClient(
                new org.unicitylabs.sdk.api.AggregatorClient(AGGREGATOR_URL)),
            tokenMinter.getTrustBase()  // Use the trustbase from tokenMinter
        );
        var finalizedToken = aliceRecipient.finalizeReceivedToken(transferInfo);
        assertNotNull("Token should be finalized", finalizedToken);

        String tokenJson = tokenMinter.serializeToken(finalizedToken);
        System.out.println("✅ Alice finalized the token on her device!");

        // Step 3: Connect Alice to Nostr relay AFTER transfer completes
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 3: Connecting Alice to Nostr relay");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        connectAliceToNostr();
        Thread.sleep(1000); // Wait for connection and subscription to be fully established

        // Step 4: Send token to Alice via Nostr
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 4: Sending token to Alice via Nostr");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⏱️  Alice is now subscribed and waiting for messages...");

        NostrClient nostrClient = new NostrClient(NOSTR_RELAY, faucetPrivateKey);
        String transferMessage = "token_transfer:" + tokenJson;
        NostrClient.NostrConnection connection = nostrClient.sendEncryptedMessage(aliceNostrPubKey, transferMessage).join();
        System.out.println("✅ Token sent via Nostr!");

        // Give the relay time to broadcast the message to Alice before closing sender's connection
        Thread.sleep(2000);
        connection.close();
        System.out.println("✅ Sender connection closed");

        // Step 6: Wait for Alice to receive the token
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 6: Waiting for Alice to receive token...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("⏱️  Waiting up to 30 seconds for EVENT message...");
        System.out.println("📡 Alice WebSocket status: " + (aliceWebSocket != null ? "CONNECTED" : "NULL"));

        boolean received = aliceTokenReceivedLatch.await(30, TimeUnit.SECONDS);

        System.out.println("\n📊 Wait result: " + (received ? "✅ RECEIVED" : "❌ TIMEOUT"));
        System.out.println("📝 Alice received " + aliceReceivedMessages.size() + " messages total");
        assertTrue("Alice should receive the token within 30 seconds", received);

        // Verify the received message contains the token
        assertFalse("Alice should have received messages", aliceReceivedMessages.isEmpty());

        String receivedTokenJson = null;
        for (String msg : aliceReceivedMessages) {
            if (msg.contains("token_transfer")) {
                System.out.println("✅ Alice received token transfer message!");
                // Extract token JSON from message (format: "token_transfer:{...}")
                receivedTokenJson = msg.substring("token_transfer:".length());
                break;
            }
        }
        assertNotNull("Alice should receive token_transfer message", receivedTokenJson);

        // Step 7: Verify token has complete transaction history
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("Step 7: Verifying token transaction history...");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Parse the received token JSON
        var receivedTokenData = jsonMapper.readValue(receivedTokenJson, java.util.Map.class);

        // Verify token version
        String version = (String) receivedTokenData.get("version");
        System.out.println("📦 Token version: " + version);
        assertEquals("Token should be version 2.0", "2.0", version);

        // Verify genesis transaction (MINT)
        var genesis = (java.util.Map<?, ?>) receivedTokenData.get("genesis");
        assertNotNull("Token should have genesis transaction", genesis);

        var genesisTxData = (java.util.Map<?, ?>) genesis.get("data");
        assertNotNull("Genesis should have transaction data", genesisTxData);

        // Verify it's a MINT transaction by checking for MINT-specific fields
        assertNotNull("Genesis should have tokenId", genesisTxData.get("tokenId"));
        assertNotNull("Genesis should have tokenType", genesisTxData.get("tokenType"));
        assertNotNull("Genesis should have coins (fungible token)", genesisTxData.get("coins"));
        System.out.println("📜 Genesis transaction: MINT (verified by structure)");

        // Verify genesis has inclusion proof
        var genesisInclusionProof = genesis.get("inclusionProof");
        assertNotNull("Genesis transaction should have inclusion proof", genesisInclusionProof);
        System.out.println("✅ Genesis (MINT) transaction is finalized with inclusion proof!");

        // Verify transfer transactions list
        var transactions = (java.util.List<?>) receivedTokenData.get("transactions");
        assertNotNull("Token should have transactions list", transactions);

        System.out.println("📜 Transfer transaction count: " + transactions.size());
        assertEquals("Token should have 1 transfer transaction", 1, transactions.size());

        // Verify the transfer transaction
        var transferTx = (java.util.Map<?, ?>) transactions.get(0);
        var transferTxData = (java.util.Map<?, ?>) transferTx.get("data");
        assertNotNull("Transfer should have transaction data", transferTxData);

        // Verify it's a TRANSFER transaction by checking for TRANSFER-specific fields
        assertNotNull("Transfer should have recipient", transferTxData.get("recipient"));
        assertNotNull("Transfer should have salt", transferTxData.get("salt"));
        System.out.println("   Transfer transaction: TRANSFER (verified by structure)");

        // Verify transfer has inclusion proof (finalized)
        var transferInclusionProof = transferTx.get("inclusionProof");
        assertNotNull("Transfer transaction should have inclusion proof", transferInclusionProof);
        System.out.println("✅ Transfer transaction is finalized with inclusion proof!");

        // Verify token state has correct owner (Alice's predicate)
        var state = (java.util.Map<?, ?>) receivedTokenData.get("state");
        var unlockPredicate = state.get("unlockPredicate");
        assertNotNull("Token should have unlock predicate", unlockPredicate);
        System.out.println("✅ Token has valid owner predicate!");

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  ✅ E2E Test Passed Successfully!           ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("\n📊 Test Summary:");
        System.out.println("   ✓ Nametag minted: " + aliceNametag);
        System.out.println("   ✓ Token minted: solana-test (1000)");
        System.out.println("   ✓ Token transferred to Alice (faucet->Alice)");
        System.out.println("   ✓ Token sent via Nostr");
        System.out.println("   ✓ Alice received token");
        System.out.println("   ✓ Token has complete history (genesis MINT + 1 transfer)");
        System.out.println("   ✓ Both transactions are finalized with inclusion proofs");
        System.out.println();
    }

    private void connectAliceToNostr() {
        Request request = new Request.Builder()
            .url(NOSTR_RELAY)
            .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                aliceWebSocket = webSocket;
                System.out.println("✅ Alice connected to Nostr relay");

                // Subscribe to messages for Alice
                try {
                    String subscriptionId = "alice-sub-" + System.currentTimeMillis();
                    var filter = new java.util.HashMap<String, Object>();
                    filter.put("kinds", Arrays.asList(KIND_TOKEN_TRANSFER)); // Token transfer events
                    filter.put("#p", Arrays.asList(aliceNostrPubKey));
                    filter.put("limit", 100);

                    List<Object> subRequest = Arrays.asList("REQ", subscriptionId, filter);
                    String json = jsonMapper.writeValueAsString(subRequest);

                    System.out.println("📡 Alice subscribing to messages...");
                    webSocket.send(json);
                } catch (Exception e) {
                    System.err.println("❌ Error subscribing: " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    long timestamp = System.currentTimeMillis();
                    System.out.println("🔍 [" + timestamp + "] Alice received raw message: " + text.substring(0, Math.min(100, text.length())) + "...");
                    List<?> message = jsonMapper.readValue(text, List.class);
                    String messageType = (String) message.get(0);
                    System.out.println("📩 [" + timestamp + "] Message type: " + messageType);

                    if ("EVENT".equals(messageType)) {
                        var eventData = message.get(2);
                        var event = jsonMapper.convertValue(eventData, JsonNode.class);

                        String content = event.get("content").asText();
                        System.out.println("📨 Alice received Nostr event with content length: " + content.length());

                        // Decode hex content (simplified - real app would decrypt)
                        try {
                            byte[] contentBytes = Hex.decodeHex(content.toCharArray());
                            String decodedContent = new String(contentBytes);
                            System.out.println("✅ Decoded content preview: " + decodedContent.substring(0, Math.min(50, decodedContent.length())) + "...");
                            aliceReceivedMessages.add(decodedContent);

                            if (decodedContent.contains("token_transfer")) {
                                System.out.println("✅ Alice received token transfer!");
                                aliceTokenReceivedLatch.countDown();
                            }
                        } catch (Exception e) {
                            // Content might not be hex, try as-is
                            System.out.println("⚠️  Content not hex, using as-is: " + e.getMessage());
                            aliceReceivedMessages.add(content);
                            if (content.contains("token_transfer")) {
                                aliceTokenReceivedLatch.countDown();
                            }
                        }
                    } else if ("EOSE".equals(messageType)) {
                        System.out.println("✅ Alice subscription confirmed (EOSE)");
                    } else if ("OK".equals(messageType)) {
                        System.out.println("✅ Alice event acknowledged");
                    } else {
                        System.out.println("ℹ️  Unknown message type: " + messageType);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Error processing message: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("❌ Alice WebSocket failure:");
                if (t != null) {
                    System.err.println("   Exception: " + t.getClass().getName());
                    System.err.println("   Message: " + t.getMessage());
                    t.printStackTrace();
                } else {
                    System.err.println("   No throwable provided");
                }
                if (response != null) {
                    System.err.println("   Response code: " + response.code());
                    System.err.println("   Response message: " + response.message());
                }
                aliceTokenReceivedLatch.countDown(); // Unblock test on failure
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                System.out.println("⚠️  Alice WebSocket closing: " + code + " - " + reason);
                webSocket.close(1000, null);
            }
        };

        httpClient.newWebSocket(request, listener);
    }
}
