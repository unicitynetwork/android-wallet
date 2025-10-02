package org.unicitylabs.wallet

import fr.acinq.secp256k1.Secp256k1
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.util.encoders.Hex
import org.unicitylabs.wallet.nostr.Event
import org.unicitylabs.wallet.nostr.NostrNametagBinding
import org.unicitylabs.wallet.util.JsonMapper
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E2E test for Nostr nametag binding functionality
 * Tests publishing and querying nametag bindings on the Nostr relay
 *
 * NOTE: This test requires native secp256k1 library which is not available in JVM unit tests.
 * It should be run as an Android instrumented test instead.
 */
@Ignore("Requires native secp256k1 library - run as Android instrumented test instead")
class NostrNametagBindingTest {

    companion object {
        private const val RELAY_URL = "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        private const val TIMEOUT_SECONDS = 15L
    }

    private val secp256k1 = Secp256k1.get()
    private val client = OkHttpClient.Builder()
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class TestClient(
        val tag: String,
        val privateKey: ByteArray,
        val publicKey: String,
        var webSocket: WebSocket? = null,
        val receivedEvents: MutableList<Event> = mutableListOf(),
        val latch: CountDownLatch = CountDownLatch(1)
    )

    @Test
    fun testNametagBindingFlow() {
        println("🚀 Starting Nostr Nametag Binding E2E Test")
        println("📡 Relay URL: $RELAY_URL")

        // Create test client
        val alice = createTestClient("alice")

        // Connect to relay
        connectClient(alice)
        Thread.sleep(1000)

        // Test 1: Publish nametag binding
        println("\n📤 Test 1: Publishing nametag binding")
        val nametagId = "alice"
        val unicityAddress = "0x1234567890abcdef" // Mock address

        val bindingEvent = createNametagBinding(alice, nametagId, unicityAddress)
        publishEvent(alice, bindingEvent)

        // Wait for event to be published
        Thread.sleep(2000)

        // Test 2: Query nametag by pubkey
        println("\n🔍 Test 2: Query nametag by pubkey")
        val foundNametag = queryNametagByPubkey(alice, alice.publicKey)
        println("   Found nametag: $foundNametag")
        assertEquals("Should find the nametag", nametagId, foundNametag)

        // Test 3: Query pubkey by nametag
        println("\n🔍 Test 3: Query pubkey by nametag")
        val foundPubkey = queryPubkeyByNametag(alice, nametagId)
        println("   Found pubkey: ${foundPubkey?.take(16)}...")
        assertEquals("Should find the pubkey", alice.publicKey, foundPubkey)

        // Test 4: Query non-existent nametag
        println("\n🔍 Test 4: Query non-existent nametag")
        val notFound = queryPubkeyByNametag(alice, "nonexistent")
        assertNull("Should not find non-existent nametag", notFound)

        // Close connection
        alice.webSocket?.close(1000, "Test complete")

        println("\n🎉 Nametag binding test completed successfully!")

        // Shutdown
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun createTestClient(tag: String): TestClient {
        // Generate real secp256k1 key pair
        val privateKeyBytes = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())

        // Get x-only public key (Schnorr uses x-only pubkeys)
        val publicKeyBytes = secp256k1.pubkeyCreate(privateKeyBytes)
        val publicKeyHex = Hex.toHexString(publicKeyBytes).substring(2) // Remove compression prefix

        println("🔑 Generated keypair for $tag:")
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
                println("✅ ${client.tag}: Connected to relay")
                client.webSocket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JsonMapper.fromJson<List<*>>(text)
                    when (message[0]) {
                        "EVENT" -> {
                            val eventData = message[2] as Map<*, *>
                            val event = Event(
                                id = eventData["id"] as String,
                                pubkey = eventData["pubkey"] as String,
                                created_at = (eventData["created_at"] as Number).toLong(),
                                kind = (eventData["kind"] as Number).toInt(),
                                tags = (eventData["tags"] as List<*>).map { it as List<String> },
                                content = eventData["content"] as String,
                                sig = eventData["sig"] as String
                            )
                            client.receivedEvents.add(event)
                            client.latch.countDown()
                        }
                        "EOSE" -> {
                            println("✅ ${client.tag}: End of stored events")
                            client.latch.countDown()
                        }
                        "OK" -> {
                            val eventId = message[1]
                            val success = message.getOrNull(2) as? Boolean ?: false
                            if (success) {
                                println("✅ ${client.tag}: Event published: $eventId")
                            } else {
                                println("⚠️ ${client.tag}: Event rejected: ${message.getOrNull(3)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("❌ Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ ${client.tag}: Error: ${t.message}")
            }
        }

        client.webSocket = this.client.newWebSocket(request, listener)
    }

    private fun createNametagBinding(client: TestClient, nametagId: String, unicityAddress: String): Event {
        val createdAt = System.currentTimeMillis() / 1000

        val tags = listOf(
            listOf("d", NostrNametagBinding.TAG_D_VALUE),
            listOf("nametag", nametagId),
            listOf("t", nametagId),  // IMPORTANT: Use 't' tag for indexing
            listOf("address", unicityAddress)
        )

        val content = JsonMapper.toJson(mapOf(
            "nametag" to nametagId,
            "address" to unicityAddress,
            "verified" to System.currentTimeMillis()
        ))

        val eventData = listOf(
            0,
            client.publicKey,
            createdAt,
            NostrNametagBinding.KIND_NAMETAG_BINDING,
            tags,
            content
        )

        val eventJson = JsonMapper.toJson(eventData)
        val digest = SHA256Digest()
        val eventJsonBytes = eventJson.toByteArray()
        digest.update(eventJsonBytes, 0, eventJsonBytes.size)
        val eventIdBytes = ByteArray(32)
        digest.doFinal(eventIdBytes, 0)
        val eventId = Hex.toHexString(eventIdBytes)

        // Sign with Schnorr
        val signature = secp256k1.signSchnorr(eventIdBytes, client.privateKey, null)
        val signatureHex = Hex.toHexString(signature)

        return Event(
            id = eventId,
            pubkey = client.publicKey,
            created_at = createdAt,
            kind = NostrNametagBinding.KIND_NAMETAG_BINDING,
            tags = tags,
            content = content,
            sig = signatureHex
        )
    }

    private fun publishEvent(client: TestClient, event: Event) {
        val eventRequest = listOf("EVENT", event)
        val json = JsonMapper.toJson(eventRequest)
        client.webSocket?.send(json)
    }

    private fun queryNametagByPubkey(client: TestClient, nostrPubkey: String): String? {
        val subscriptionId = "query-nametag-${System.currentTimeMillis()}"

        val filter = mapOf(
            "kinds" to listOf(NostrNametagBinding.KIND_NAMETAG_BINDING),
            "authors" to listOf(nostrPubkey),
            "#d" to listOf(NostrNametagBinding.TAG_D_VALUE),
            "limit" to 1
        )

        val reqMessage = listOf("REQ", subscriptionId, filter)
        val json = JsonMapper.toJson(reqMessage)

        client.receivedEvents.clear()
        val latch = CountDownLatch(1)
        client.webSocket?.send(json)

        // Wait for response
        latch.await(5, TimeUnit.SECONDS)

        // Close subscription
        val closeMessage = listOf("CLOSE", subscriptionId)
        client.webSocket?.send(JsonMapper.toJson(closeMessage))

        // Parse nametag from received event
        val event = client.receivedEvents.firstOrNull()
        return event?.tags?.firstOrNull { it[0] == "nametag" }?.get(1)
    }

    private fun queryPubkeyByNametag(client: TestClient, nametagId: String): String? {
        val subscriptionId = "query-pubkey-${System.currentTimeMillis()}"

        val filter = mapOf(
            "kinds" to listOf(NostrNametagBinding.KIND_NAMETAG_BINDING),
            "#t" to listOf(nametagId),  // IMPORTANT: Use #t tag
            "limit" to 1
        )

        val reqMessage = listOf("REQ", subscriptionId, filter)
        val json = JsonMapper.toJson(reqMessage)

        client.receivedEvents.clear()
        val latch = CountDownLatch(1)
        client.webSocket?.send(json)

        // Wait for response
        latch.await(5, TimeUnit.SECONDS)

        // Close subscription
        val closeMessage = listOf("CLOSE", subscriptionId)
        client.webSocket?.send(JsonMapper.toJson(closeMessage))

        // Return pubkey from received event
        return client.receivedEvents.firstOrNull()?.pubkey
    }
}
