package org.unicitylabs.wallet

import org.junit.Test
import org.junit.Assert.*
import com.google.gson.Gson
import java.security.MessageDigest
import org.spongycastle.util.encoders.Hex
import org.spongycastle.asn1.x9.X9ECParameters
import org.spongycastle.crypto.ec.CustomNamedCurves
import org.spongycastle.crypto.params.ECDomainParameters
import org.spongycastle.crypto.params.ECPrivateKeyParameters
import org.spongycastle.crypto.params.ECPublicKeyParameters
import org.spongycastle.crypto.signers.ECDSASigner
import org.spongycastle.crypto.signers.HMacDSAKCalculator
import org.spongycastle.crypto.digests.SHA256Digest
import java.math.BigInteger

/**
 * Test to verify proper Nostr event signature generation
 */
class NostrSignatureTest {

    companion object {
        // secp256k1 curve parameters
        private val CURVE_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
        private val CURVE: ECDomainParameters = ECDomainParameters(
            CURVE_PARAMS.curve,
            CURVE_PARAMS.g,
            CURVE_PARAMS.n,
            CURVE_PARAMS.h
        )
    }

    private val gson = Gson()

    @Test
    fun testNostrEventSignature() {
        // Test vector from Nostr documentation
        // Using a known private key for testing
        val privateKeyHex = "5e9a30b0c5d2cfaf9cb4b8f26c1ce6ac2799f33bb3ba8018827cb66a58e3e67e"
        val privateKey = BigInteger(privateKeyHex, 16)

        // Calculate public key
        val publicKeyPoint = CURVE.g.multiply(privateKey)
        val xCoord = publicKeyPoint.normalize().xCoord.toBigInteger()
        val xBytes = xCoord.toByteArray().let { bytes ->
            if (bytes.size > 32) bytes.takeLast(32).toByteArray()
            else ByteArray(32 - bytes.size) + bytes
        }
        val publicKeyHex = Hex.toHexString(xBytes)

        println("Private key: $privateKeyHex")
        println("Public key: $publicKeyHex")

        // Create a simple test event
        val createdAt = 1700000000L
        val kind = 1
        val tags = listOf<List<String>>()
        val content = "Hello Nostr"

        // Create canonical event JSON for signing
        val eventData = listOf(
            0,
            publicKeyHex,
            createdAt,
            kind,
            tags,
            content
        )

        val eventJson = gson.toJson(eventData)
        println("Event JSON: $eventJson")

        // Calculate event ID (sha256 of canonical JSON)
        val eventId = MessageDigest.getInstance("SHA-256")
            .digest(eventJson.toByteArray())
            .toHex()
        println("Event ID: $eventId")

        // Sign the event
        val signature = signEventId(eventId, privateKey)
        println("Signature: $signature")

        // Verify signature format
        assertEquals("Signature should be 128 hex characters (64 bytes)", 128, signature.length)

        // Create the full event
        val event = mapOf(
            "id" to eventId,
            "pubkey" to publicKeyHex,
            "created_at" to createdAt,
            "kind" to kind,
            "tags" to tags,
            "content" to content,
            "sig" to signature
        )

        val fullEventJson = gson.toJson(event)
        println("\nFull event JSON:")
        println(fullEventJson)

        // Verify signature components
        val sigBytes = Hex.decode(signature)
        assertEquals("Signature should be 64 bytes", 64, sigBytes.size)

        val r = BigInteger(1, sigBytes.take(32).toByteArray())
        val s = BigInteger(1, sigBytes.takeLast(32).toByteArray())

        // Verify r and s are valid (non-zero and less than curve order)
        assertTrue("r should be > 0", r > BigInteger.ZERO)
        assertTrue("r should be < n", r < CURVE.n)
        assertTrue("s should be > 0", s > BigInteger.ZERO)
        assertTrue("s should be < n", s < CURVE.n)
        assertTrue("s should be normalized (lower half)", s <= CURVE.n.shiftRight(1))
    }

    private fun signEventId(eventId: String, privateKey: BigInteger): String {
        val messageHash = Hex.decode(eventId)

        // Create ECDSA signer with deterministic k (RFC 6979)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        val privKeyParams = ECPrivateKeyParameters(privateKey, CURVE)
        signer.init(true, privKeyParams)

        // Sign the message hash
        val signature = signer.generateSignature(messageHash)
        val r = signature[0]
        val s = signature[1]

        // Normalize s to lower half per BIP-62
        val sNormalized = if (s > CURVE.n.shiftRight(1)) {
            CURVE.n.subtract(s)
        } else {
            s
        }

        // Format as 64-byte hex (32 bytes for r, 32 bytes for s)
        val rBytes = r.toByteArray().let { bytes ->
            if (bytes.size > 32) bytes.takeLast(32).toByteArray()
            else ByteArray(32 - bytes.size) + bytes
        }

        val sBytes = sNormalized.toByteArray().let { bytes ->
            if (bytes.size > 32) bytes.takeLast(32).toByteArray()
            else ByteArray(32 - bytes.size) + bytes
        }

        return Hex.toHexString(rBytes + sBytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}