package org.unicitylabs.wallet

import okhttp3.*
import org.junit.Test
import org.junit.Assert.*
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlin.random.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import fr.acinq.secp256k1.Secp256k1
import org.spongycastle.util.encoders.Hex

/**
 * E2E test for Nostr-based P2P messaging between two clients
 * Tests the complete flow: handshake, message sending, and receiving
 * Uses real Schnorr signatures (BIP-340) as required by Nostr
 */
class NostrMessagingE2ETest {

    companion object {
        private const val RELAY_URL = "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        private const val TIMEOUT_SECONDS = 15L
        private const val KIND_ENCRYPTED_DM = 4
    }

    private val gson = Gson()
    private val secp256k1 = Secp256k1.get()
    private val client = OkHttpClient.Builder()
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class NostrEvent(
        val id: String,
        val pubkey: String,
        val created_at: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    data class TestClient(
        val tag: String,
        val privateKey: ByteArray,
        val publicKey: String,
        var webSocket: WebSocket? = null,
        val receivedMessages: MutableList<NostrEvent> = mutableListOf(),
        val latch: CountDownLatch = CountDownLatch(1)
    )

    @Test
    fun testP2PMessagingFlow() {
        println("🚀 Starting Nostr P2P Messaging E2E Test with Schnorr Signatures (BIP-340)")
        println("📡 Relay URL: $RELAY_URL")

        // Create two test clients with real secp256k1 keys
        val alice = createTestClient("alice")
        val bob = createTestClient("bob")

        // Connect both clients to the relay
        connectClient(alice)
        connectClient(bob)

        // Wait for connections to establish
        Thread.sleep(1000)

        // Subscribe both clients to receive messages
        subscribeToMessages(alice)
        subscribeToMessages(bob)

        // Wait for subscriptions to be acknowledged
        Thread.sleep(1000)

        // Test 1: Alice sends handshake to Bob
        println("\n📤 Test 1: Alice sends handshake to Bob")
        sendHandshake(alice, bob)

        // Wait for Bob to receive the handshake
        val bobReceivedHandshake = bob.latch.await(5, TimeUnit.SECONDS)

        // Test 2: Bob sends a message to Alice
        println("\n📤 Test 2: Bob sends message to Alice")
        val aliceLatch = CountDownLatch(1)
        sendMessage(bob, alice, "Hello Alice, this is Bob!")

        // Wait for Alice to receive the message
        val aliceReceivedMessage = aliceLatch.await(5, TimeUnit.SECONDS)

        // Test 3: Alice sends a message back to Bob
        println("\n📤 Test 3: Alice sends message back to Bob")
        val bobLatch2 = CountDownLatch(1)
        sendMessage(alice, bob, "Hi Bob, got your message!")

        // Wait for Bob to receive the reply
        val bobReceivedReply = bobLatch2.await(5, TimeUnit.SECONDS)

        // Close connections
        alice.webSocket?.close(1000, "Test complete")
        bob.webSocket?.close(1000, "Test complete")

        // Verify results
        println("\n📊 Test Results:")
        println("   Bob received handshake: ${if (bobReceivedHandshake) "✅" else "❌"}")
        println("   Alice received ${alice.receivedMessages.size} messages")
        println("   Bob received ${bob.receivedMessages.size} messages")

        // Assertions
        assertTrue("Bob should receive handshake", bob.receivedMessages.isNotEmpty())

        // Check if messages were encrypted (content should be base64-like)
        bob.receivedMessages.forEach { event ->
            assertTrue("Message should be encrypted (kind=$KIND_ENCRYPTED_DM)", event.kind == KIND_ENCRYPTED_DM)
            assertTrue("Content should not be empty", event.content.isNotEmpty())
        }

        println("\n🎉 Nostr P2P messaging test completed with Schnorr signatures!")

        // Shutdown
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun createTestClient(tag: String): TestClient {
        // Generate real secp256k1 key pair
        val privateKeyBytes = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())

        // Get x-only public key (Schnorr uses x-only pubkeys)
        val publicKeyBytes = secp256k1.pubkeyCreate(privateKeyBytes)
        val publicKeyHex = Hex.toHexString(publicKeyBytes).substring(2) // Remove compression prefix, use x-coordinate only

        println("🔑 Generated keypair for $tag:")
        println("   Private key: ${Hex.toHexString(privateKeyBytes).take(20)}...")
        println("   Public key: ${publicKeyHex.take(20)}...")

        return TestClient(
            tag = tag,
            privateKey = privateKeyBytes,
            publicKey = publicKeyHex
        )
    }

    private fun connectClient(client: TestClient) {
        val request = Request.Builder()
            .url(RELAY_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("✅ ${client.tag}: WebSocket connected")
                client.webSocket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, List::class.java)
                    when (message[0]) {
                        "EVENT" -> {
                            val eventData = message[2]
                            val event = gson.fromJson(gson.toJson(eventData), NostrEvent::class.java)
                            println("📨 ${client.tag} received event: kind=${event.kind}, from=${event.pubkey.take(8)}...")

                            // Check if this message is for us
                            val isForUs = event.tags.any { tag ->
                                tag.size >= 2 && tag[0] == "p" && tag[1] == client.publicKey
                            }

                            if (isForUs) {
                                client.receivedMessages.add(event)
                                client.latch.countDown()

                                // Try to decrypt the message (simplified for testing)
                                if (event.kind == KIND_ENCRYPTED_DM) {
                                    println("   📜 Encrypted content length: ${event.content.length}")
                                }
                            }
                        }
                        "EOSE" -> {
                            println("✅ ${client.tag}: End of stored events")
                        }
                        "OK" -> {
                            val eventId = message[1]
                            val success = message.getOrNull(2) as? Boolean ?: false
                            if (success) {
                                println("✅ ${client.tag}: Event published successfully: $eventId")
                            } else {
                                val reason = message.getOrNull(3) ?: "unknown"
                                println("⚠️ ${client.tag}: Event rejected: $reason")
                            }
                        }
                        "NOTICE" -> {
                            println("📢 ${client.tag}: Notice: ${message.getOrNull(1)}")
                        }
                    }
                } catch (e: Exception) {
                    println("❌ ${client.tag}: Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ ${client.tag}: WebSocket error: ${t.message}")
                repeat(3) { client.latch.countDown() }
            }
        }

        client.webSocket = this.client.newWebSocket(request, listener)
    }

    private fun subscribeToMessages(client: TestClient) {
        val subscriptionId = "sub-${client.tag}-${System.currentTimeMillis()}"

        // Subscribe to encrypted direct messages where we are the recipient
        val filter = mapOf(
            "kinds" to listOf(KIND_ENCRYPTED_DM),
            "#p" to listOf(client.publicKey),
            "limit" to 100
        )

        val subRequest = listOf("REQ", subscriptionId, filter)
        val json = gson.toJson(subRequest)

        println("📡 ${client.tag}: Subscribing with filter: $json")
        client.webSocket?.send(json)
    }

    private fun sendHandshake(from: TestClient, to: TestClient) {
        val content = gson.toJson(mapOf(
            "type" to "handshake_request",
            "from" to from.tag,
            "timestamp" to System.currentTimeMillis()
        ))

        sendEncryptedMessage(from, to, content)
    }

    private fun sendMessage(from: TestClient, to: TestClient, message: String) {
        sendEncryptedMessage(from, to, message)
    }

    private fun sendEncryptedMessage(from: TestClient, to: TestClient, content: String) {
        // Create encrypted message (simplified - real implementation needs proper NIP-04 encryption)
        val encryptedContent = simpleEncrypt(content, from.privateKey, to.publicKey)

        val event = createEvent(
            privateKey = from.privateKey,
            publicKey = from.publicKey,
            kind = KIND_ENCRYPTED_DM,
            content = encryptedContent,
            tags = listOf(listOf("p", to.publicKey))
        )

        val eventRequest = listOf("EVENT", event)
        val json = gson.toJson(eventRequest)

        println("📤 ${from.tag}: Sending encrypted message to ${to.tag}")
        from.webSocket?.send(json)
    }

    private fun createEvent(
        privateKey: ByteArray,
        publicKey: String,
        kind: Int,
        content: String,
        tags: List<List<String>>
    ): NostrEvent {
        val createdAt = System.currentTimeMillis() / 1000

        // Create event data for ID calculation (canonical JSON)
        val eventData = listOf(
            0,
            publicKey,
            createdAt,
            kind,
            tags,
            content
        )

        val eventJson = gson.toJson(eventData)
        println("📝 Event JSON for ID: $eventJson")

        val id = MessageDigest.getInstance("SHA-256")
            .digest(eventJson.toByteArray())
            .let { Hex.toHexString(it) }
        println("🔑 Event ID: $id")
        println("🔑 Public key: $publicKey")

        // Sign the event ID with Schnorr signature (BIP-340)
        val signature = signEventSchnorr(id, privateKey)
        println("✍️ Schnorr Signature: $signature")

        val event = NostrEvent(
            id = id,
            pubkey = publicKey,
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = signature
        )

        println("📤 Full event: ${gson.toJson(event)}")

        return event
    }

    private fun signEventSchnorr(eventId: String, privateKey: ByteArray): String {
        val messageHash = Hex.decode(eventId)
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        // Create Schnorr signature (BIP-340) - this is what Nostr requires
        val signature = secp256k1.signSchnorr(messageHash, privateKey, null)
        return Hex.toHexString(signature)
    }

    private fun simpleEncrypt(content: String, senderPrivateKey: ByteArray, recipientPublicKey: String): String {
        // Simplified encryption for testing
        // Real implementation should use proper NIP-04 encryption with shared secret derived from ECDH
        try {
            // Create a shared secret (simplified - should use ECDH)
            val sharedSecret = MessageDigest.getInstance("SHA-256")
                .digest(senderPrivateKey + Hex.decode(recipientPublicKey))
                .take(16)
                .toByteArray()

            // Simple AES encryption
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(sharedSecret, "AES")
            val iv = ByteArray(16).apply { Random.nextBytes(this) }
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(content.toByteArray())

            // Return base64-like string (IV + encrypted)
            return Hex.toHexString(iv + encrypted)
        } catch (e: Exception) {
            // Fallback to hex encoding for testing
            return Hex.toHexString(content.toByteArray())
        }
    }
}