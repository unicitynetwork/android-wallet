package org.unicitylabs.wallet.nostr

import android.util.Log
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.util.encoders.Hex
import org.unicitylabs.wallet.util.JsonMapper

/**
 * Manages Nostr pubkey ↔ Unicity nametag bindings using replaceable events
 * Adapted from faucet's NostrNametagBinding.java for Android wallet
 */
class NostrNametagBinding {

    companion object {
        private const val TAG = "NostrNametagBinding"

        // Kind 30078: Parameterized replaceable event for application-specific data
        const val KIND_NAMETAG_BINDING = 30078
        const val TAG_D_VALUE = "unicity-nametag"
    }

    /**
     * Create a binding event that maps a Nostr pubkey to a Unicity nametag
     * This is a replaceable event - newer events automatically replace older ones
     */
    fun createBindingEvent(
        publicKeyHex: String,
        nametagId: String,
        unicityAddress: String,
        keyManager: NostrKeyManager
    ): Event {
        val createdAt = System.currentTimeMillis() / 1000

        // Create tags for the replaceable event
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("d", TAG_D_VALUE))  // Makes it replaceable by pubkey+d
        tags.add(listOf("nametag", nametagId))  // For querying by nametag
        tags.add(listOf("t", nametagId))  // IMPORTANT: Use 't' tag which is indexed by relay
        tags.add(listOf("address", unicityAddress))  // Store Unicity address

        // Create content with binding information
        val contentData = mapOf(
            "nametag" to nametagId,
            "address" to unicityAddress,
            "verified" to System.currentTimeMillis()
        )
        val content = JsonMapper.toJson(contentData)

        // Create event data for ID calculation (canonical JSON array)
        val eventData = listOf(
            0,
            publicKeyHex,
            createdAt,
            KIND_NAMETAG_BINDING,
            tags,
            content
        )

        val eventJson = JsonMapper.toJson(eventData)

        // Calculate event ID (SHA-256 of canonical JSON)
        val digest = SHA256Digest()
        val eventJsonBytes = eventJson.toByteArray()
        digest.update(eventJsonBytes, 0, eventJsonBytes.size)
        val eventIdBytes = ByteArray(32)
        digest.doFinal(eventIdBytes, 0)
        val eventId = Hex.toHexString(eventIdBytes)

        // Sign with Schnorr signature using NostrKeyManager
        val signature = keyManager.sign(eventIdBytes)
        val signatureHex = Hex.toHexString(signature)

        return Event(
            id = eventId,
            pubkey = publicKeyHex,
            created_at = createdAt,
            kind = KIND_NAMETAG_BINDING,
            tags = tags,
            content = content,
            sig = signatureHex
        )
    }

    /**
     * Create a filter to find nametag by Nostr pubkey
     */
    fun createPubkeyToNametagFilter(nostrPubkey: String): Map<String, Any> {
        val filter = mutableMapOf<String, Any>()
        filter["kinds"] = listOf(KIND_NAMETAG_BINDING)
        filter["authors"] = listOf(nostrPubkey)
        filter["#d"] = listOf(TAG_D_VALUE)  // Use #d tag to ensure we get the nametag binding
        filter["limit"] = 1
        return filter
    }

    /**
     * Create a filter to find Nostr pubkey by nametag
     * IMPORTANT: Must use #t tag which is indexed by the relay
     */
    fun createNametagToPubkeyFilter(nametagId: String): Map<String, Any> {
        val filter = mutableMapOf<String, Any>()
        filter["kinds"] = listOf(KIND_NAMETAG_BINDING)
        filter["#t"] = listOf(nametagId)  // Use #t tag which is commonly indexed by relays
        filter["limit"] = 1
        return filter
    }

    /**
     * Parse nametag from a binding event
     */
    fun parseNametagFromEvent(event: Event): String? {
        if (event.kind != KIND_NAMETAG_BINDING) {
            return null
        }

        // Look for nametag in tags
        for (tag in event.tags) {
            if (tag.size >= 2 && tag[0] == "nametag") {
                return tag[1]
            }
        }

        // Fallback to parsing from content
        return try {
            val contentData = JsonMapper.fromJson(event.content, Map::class.java) as Map<*, *>
            contentData["nametag"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse nametag from event content", e)
            null
        }
    }

    /**
     * Parse Unicity address from a binding event
     */
    fun parseAddressFromEvent(event: Event): String? {
        if (event.kind != KIND_NAMETAG_BINDING) {
            return null
        }

        // Look for address in tags
        for (tag in event.tags) {
            if (tag.size >= 2 && tag[0] == "address") {
                return tag[1]
            }
        }

        // Fallback to parsing from content
        return try {
            val contentData = JsonMapper.fromJson(event.content, Map::class.java) as Map<*, *>
            contentData["address"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse address from event content", e)
            null
        }
    }
}
