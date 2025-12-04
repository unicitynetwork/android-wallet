package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.unicitylabs.nostr.client.NostrClient;
import org.unicitylabs.nostr.crypto.NostrKeyManager;
import org.unicitylabs.nostr.crypto.SchnorrSigner;
import org.unicitylabs.nostr.protocol.Event;
import org.unicitylabs.nostr.protocol.EventKinds;
import org.unicitylabs.nostr.protocol.Filter;

import org.apache.commons.codec.binary.Hex;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;

import static org.junit.Assert.*;

/**
 * Test concurrent faucet requests to verify the shared NostrClient
 * properly handles multiple simultaneous token transfers.
 *
 * This test:
 * 1. Creates multiple test recipients with unique nametags
 * 2. Publishes nametag bindings for each recipient
 * 3. Sends concurrent faucet requests
 * 4. Verifies all recipients receive their tokens
 *
 * NOTE: This is an E2E test that requires:
 * - AGGREGATOR_API_KEY environment variable
 * - faucet-config.json with valid configuration
 * - Access to aggregator and Nostr relay
 *
 * Run with: ./gradlew test --tests "ConcurrentFaucetTest" -DAGGREGATOR_API_KEY=your-key
 */
public class ConcurrentFaucetTest {

    private static final String NOSTR_RELAY = "wss://nostr-relay.testnet.unicity.network";
    private static final int NUM_CONCURRENT_REQUESTS = 4;
    private static final long TIMEOUT_SECONDS = 120;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private FaucetService faucetService;
    private List<TestRecipientInfo> recipients = new ArrayList<>();
    private NostrClient publisherClient;

    static class TestRecipientInfo {
        final String nametag;
        final byte[] privateKey;
        final String pubkeyHex;
        NostrClient client;
        final List<Event> receivedTokens = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch tokenLatch = new CountDownLatch(1);

        TestRecipientInfo(String nametag, byte[] privateKey, String pubkeyHex) {
            this.nametag = nametag;
            this.privateKey = privateKey;
            this.pubkeyHex = pubkeyHex;
        }
    }

    @Before
    public void setUp() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Concurrent Faucet Test - " + NUM_CONCURRENT_REQUESTS + " simultaneous requests  ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // Skip if API key not configured
        String apiKey = System.getenv("AGGREGATOR_API_KEY");
        Assume.assumeTrue("AGGREGATOR_API_KEY not set, skipping E2E test", apiKey != null && !apiKey.isEmpty());

        // Initialize faucet service
        FaucetConfig config = FaucetConfig.load();
        faucetService = new FaucetService(config, "faucet-data");
        System.out.println("✅ FaucetService initialized with shared NostrClient");

        // Create publisher client for nametag bindings
        byte[] publisherKey = new byte[32];
        random.nextBytes(publisherKey);
        NostrKeyManager publisherKeyManager = NostrKeyManager.fromPrivateKey(publisherKey);
        publisherClient = new NostrClient(publisherKeyManager);
        publisherClient.connect(NOSTR_RELAY).join();
        System.out.println("✅ Publisher client connected");

        // Create test recipients
        for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
            byte[] privateKey = new byte[32];
            random.nextBytes(privateKey);
            byte[] pubKeyBytes = SchnorrSigner.getPublicKey(privateKey);
            String pubkeyHex = Hex.encodeHexString(pubKeyBytes);
            String nametag = "concurrent-test-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);

            TestRecipientInfo recipient = new TestRecipientInfo(nametag, privateKey, pubkeyHex);
            recipients.add(recipient);

            System.out.println("📝 Created recipient " + i + ": " + nametag + " -> " + pubkeyHex.substring(0, 16) + "...");
        }

        // Publish nametag bindings for all recipients
        System.out.println("\n📤 Publishing nametag bindings...");
        for (TestRecipientInfo recipient : recipients) {
            NostrKeyManager recipientKeyManager = NostrKeyManager.fromPrivateKey(recipient.privateKey);
            NostrClient bindingClient = new NostrClient(recipientKeyManager);
            bindingClient.connect(NOSTR_RELAY).join();
            bindingClient.publishNametagBinding(recipient.nametag, "dummy-address").join();
            Thread.sleep(100); // Small delay between bindings
            bindingClient.disconnect();
        }
        System.out.println("✅ All nametag bindings published");

        // Wait for bindings to propagate
        System.out.println("⏳ Waiting for bindings to propagate...");
        Thread.sleep(2000);

        // Connect recipient clients and subscribe to token transfers
        System.out.println("\n📡 Connecting recipient clients...");
        for (TestRecipientInfo recipient : recipients) {
            NostrKeyManager keyManager = NostrKeyManager.fromPrivateKey(recipient.privateKey);
            recipient.client = new NostrClient(keyManager);
            recipient.client.connect(NOSTR_RELAY).join();

            // Subscribe to token transfers for this recipient
            Filter filter = Filter.builder()
                    .kinds(EventKinds.TOKEN_TRANSFER)
                    .pTags(recipient.pubkeyHex)
                    .build();

            recipient.client.subscribe(filter, event -> {
                System.out.println("📥 " + recipient.nametag + " received token transfer!");
                recipient.receivedTokens.add(event);
                recipient.tokenLatch.countDown();
            });

            System.out.println("✅ " + recipient.nametag + " subscribed to token transfers");
        }
    }

    @After
    public void tearDown() {
        System.out.println("\n🧹 Cleaning up...");

        if (publisherClient != null) {
            publisherClient.disconnect();
        }

        for (TestRecipientInfo recipient : recipients) {
            if (recipient.client != null) {
                recipient.client.disconnect();
            }
        }

        if (faucetService != null) {
            faucetService.shutdown();
        }

        System.out.println("✅ Cleanup complete");
    }

    @Test
    public void testConcurrentFaucetRequests() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  Sending " + NUM_CONCURRENT_REQUESTS + " concurrent faucet requests       ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // Create executor for concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(NUM_CONCURRENT_REQUESTS);
        List<Future<FaucetService.FaucetRequestResult>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Submit all requests simultaneously
        long startTime = System.currentTimeMillis();
        for (TestRecipientInfo recipient : recipients) {
            Future<FaucetService.FaucetRequestResult> future = executor.submit(() -> {
                System.out.println("🚀 Sending faucet request for: " + recipient.nametag);
                return faucetService.processFaucetRequest(
                        recipient.nametag,
                        "solana",  // Use valid coin name from registry
                        0.001
                ).join();
            });
            futures.add(future);
        }

        System.out.println("⏳ All requests submitted, waiting for completion...\n");

        // Wait for all requests to complete
        List<FaucetService.FaucetRequestResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                FaucetService.FaucetRequestResult result = futures.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                results.add(result);
                if (result.success) {
                    successCount.incrementAndGet();
                    System.out.println("✅ Request " + i + " succeeded: " + recipients.get(i).nametag);
                } else {
                    failCount.incrementAndGet();
                    System.out.println("❌ Request " + i + " failed: " + result.message);
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
                System.out.println("❌ Request " + i + " exception: " + e.getMessage());
            }
        }

        long requestDuration = System.currentTimeMillis() - startTime;
        System.out.println("\n📊 Request phase completed in " + requestDuration + "ms");
        System.out.println("   Success: " + successCount.get() + "/" + NUM_CONCURRENT_REQUESTS);
        System.out.println("   Failed: " + failCount.get() + "/" + NUM_CONCURRENT_REQUESTS);

        executor.shutdown();

        // Wait for all recipients to receive tokens
        System.out.println("\n⏳ Waiting for all recipients to receive tokens...");
        int receivedCount = 0;
        for (TestRecipientInfo recipient : recipients) {
            boolean received = recipient.tokenLatch.await(30, TimeUnit.SECONDS);
            if (received) {
                receivedCount++;
                System.out.println("✅ " + recipient.nametag + " received token");
            } else {
                System.out.println("❌ " + recipient.nametag + " did NOT receive token (timeout)");
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;

        // Final report
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║  TEST RESULTS                                ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║  Requests sent:     " + NUM_CONCURRENT_REQUESTS + "                        ║");
        System.out.println("║  Requests success:  " + successCount.get() + "                        ║");
        System.out.println("║  Tokens received:   " + receivedCount + "                        ║");
        System.out.println("║  Total duration:    " + totalDuration + "ms                  ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        // Assert all requests succeeded and all tokens received
        assertEquals("All requests should succeed", NUM_CONCURRENT_REQUESTS, successCount.get());
        assertEquals("All tokens should be received", NUM_CONCURRENT_REQUESTS, receivedCount);

        System.out.println("\n✅ CONCURRENT TEST PASSED - All " + NUM_CONCURRENT_REQUESTS + " tokens delivered!");
    }
}
