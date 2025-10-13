package org.unicitylabs.faucet;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Random;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test that Nostr relay accepts messages up to 1MB
 */
public class NostrLargeMessageTest {
    private static final Logger log = LoggerFactory.getLogger(NostrLargeMessageTest.class);

    private static final String RELAY_URL = "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080";

    @Test
    public void testSend300KBMessage() throws Exception {
        log.info("=== Testing 300KB message transfer via Nostr ===");

        // Generate test private key
        byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);

        // Initialize Nostr client
        NostrClient nostrClient = new NostrClient(RELAY_URL, privateKey);

        // Use dummy recipient pubkey (we're just testing relay limit)
        String recipientPubkey = "a".repeat(64);

        // Create 300KB payload (larger than old 256KB limit)
        String largeContent = generateRandomContent(300 * 1024); // 300KB
        String message = "test_transfer:" + largeContent;

        log.info("Message size: {} bytes ({} KB)", message.length(), message.length() / 1024);
        log.info("Sending to relay...");

        // Send via Nostr and wait for confirmation
        try {
            NostrClient.NostrConnection connection = nostrClient.sendEncryptedMessage(recipientPubkey, message)
                .get(10, java.util.concurrent.TimeUnit.SECONDS);

            log.info("✅ SUCCESS: 300KB message accepted by relay!");
            log.info("Relay now supports 1MB limit (previously 256KB)");

            connection.close();
            assertTrue("Relay accepted 300KB message", true);

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("message too large")) {
                log.error("❌ FAILED: Relay still rejecting with 'message too large'");
                log.error("Relay limit: {}", e.getMessage());
                fail("Relay rejected 300KB message - limit not updated: " + e.getMessage());
            } else {
                log.error("❌ FAILED with error: {}", e.getMessage());
                throw e;
            }
        }
    }

    @Test
    public void testSend480KBMessage() throws Exception {
        log.info("=== Testing 480KB message transfer via Nostr ===");

        byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);

        NostrClient nostrClient = new NostrClient(RELAY_URL, privateKey);
        String recipientPubkey = "c".repeat(64);

        // 480KB content → ~960KB hex-encoded → just under 1MB
        String largeContent = generateRandomContent(480 * 1024);
        String message = "test_transfer:" + largeContent;

        log.info("Message size: {} bytes ({} KB)", message.length(), message.length() / 1024);
        log.info("After hex encoding: ~{} KB", (message.length() * 2) / 1024);
        log.info("Sending to relay...");

        try {
            NostrClient.NostrConnection connection = nostrClient.sendEncryptedMessage(recipientPubkey, message)
                .get(15, java.util.concurrent.TimeUnit.SECONDS);

            log.info("✅ SUCCESS: 480KB message accepted (960KB hex-encoded)!");
            log.info("Relay confirmed working at 1MB limit");
            connection.close();
            assertTrue("Relay accepted 480KB message", true);

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("message too large")) {
                log.error("❌ FAILED: Relay rejected 480KB message");
                log.error("Relay limit: {}", e.getMessage());
                fail("Relay rejected 480KB message - 1MB limit issue: " + e.getMessage());
            } else {
                log.error("❌ FAILED with error: {}", e.getMessage());
                throw e;
            }
        }
    }

    private String generateRandomContent(int sizeBytes) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(sizeBytes);

        // Fill with random printable ASCII for realistic test
        for (int i = 0; i < sizeBytes; i++) {
            sb.append((char) (random.nextInt(94) + 33)); // ASCII 33-126
        }

        return sb.toString();
    }
}
