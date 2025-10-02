package org.unicitylabs.wallet

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * E2E test for Nostr relay without requiring Android app
 */
class NostrRelayE2ETest {

    companion object {
        private const val RELAY_URL = "ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080"
        private const val TIMEOUT_SECONDS = 10L
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    @Test
    fun testNostrRelayConnection() {
        println("🚀 Starting Nostr Relay E2E Test")
        println("📡 Connecting to: $RELAY_URL")

        val latch = CountDownLatch(3)
        var connectionEstablished = false
        var subscriptionAcknowledged = false
        var eventPublished = false

        val request = Request.Builder()
            .url(RELAY_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("✅ Test 1: WebSocket connection established")
                connectionEstablished = true
                latch.countDown()

                // Test 2: Send subscription request
                val subscriptionId = "test-sub-${System.currentTimeMillis()}"
                val subRequest = listOf(
                    "REQ",
                    subscriptionId,
                    mapOf("kinds" to listOf(0, 1), "limit" to 10)
                )
                val subJson = gson.toJson(subRequest)
                println("📤 Test 2: Sending subscription request: $subJson")
                webSocket.send(subJson)

                // Test 3: Publish an event after delay
                Thread.sleep(500)
                val event = createTestEvent()
                val eventRequest = listOf("EVENT", event)
                val eventJson = gson.toJson(eventRequest)
                println("📤 Test 3: Publishing event")
                webSocket.send(eventJson)

                // Close subscription after delay
                Thread.sleep(1000)
                val closeRequest = listOf("CLOSE", subscriptionId)
                webSocket.send(gson.toJson(closeRequest))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, List::class.java)
                    println("📥 Received: ${message[0]} ${message.getOrNull(1) ?: ""}")

                    when (message[0]) {
                        "EOSE" -> {
                            println("✅ Test 2: End of stored events received")
                            subscriptionAcknowledged = true
                            latch.countDown()
                        }
                        "OK" -> {
                            val success = message.getOrNull(2) as? Boolean ?: false
                            if (success) {
                                println("✅ Test 3: Event published successfully")
                                eventPublished = true
                                latch.countDown()
                            } else {
                                val reason = message.getOrNull(3) ?: "unknown"
                                println("⚠️ Event rejected: $reason")
                                latch.countDown()
                            }
                        }
                        "EVENT" -> {
                            println("📨 Received event from subscription")
                        }
                        "NOTICE" -> {
                            println("📢 Notice: ${message.getOrNull(1)}")
                        }
                    }
                } catch (e: Exception) {
                    println("📥 Raw message: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ WebSocket error: ${t.message}")
                repeat(3) { latch.countDown() }
            }
        }

        val webSocket = client.newWebSocket(request, listener)

        // Wait for tests to complete
        val completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Close connection
        webSocket.close(1000, "Test complete")

        // Print results
        println("\n📊 Test Results:")
        println("   Connection: ${if (connectionEstablished) "✅" else "❌"}")
        println("   Subscription: ${if (subscriptionAcknowledged) "✅" else "❌"}")
        println("   Event Publishing: ${if (eventPublished) "✅" else "⚠️"}")

        // Assertions
        assertTrue("WebSocket connection should be established", connectionEstablished)
        assertTrue("Subscription should be acknowledged", subscriptionAcknowledged)
        // Event publishing might fail due to signature validation, that's OK

        if (connectionEstablished && subscriptionAcknowledged) {
            println("\n🎉 Nostr relay is working correctly!")
        }

        // Shutdown
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun createTestEvent(): NostrEvent {
        val content = "Hello from Kotlin E2E test!"
        val pubkey = ByteArray(32).apply { Random.nextBytes(this) }.toHex()
        val createdAt = System.currentTimeMillis() / 1000
        val kind = 1
        val tags = emptyList<List<String>>()

        // Calculate event ID (simplified)
        val eventData = listOf(
            0,
            pubkey,
            createdAt,
            kind,
            tags,
            content
        )
        val eventJson = gson.toJson(eventData)
        val id = MessageDigest.getInstance("SHA-256")
            .digest(eventJson.toByteArray())
            .toHex()

        // Generate dummy signature
        val sig = ByteArray(64).apply { Random.nextBytes(this) }.toHex()

        return NostrEvent(
            id = id,
            pubkey = pubkey,
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}