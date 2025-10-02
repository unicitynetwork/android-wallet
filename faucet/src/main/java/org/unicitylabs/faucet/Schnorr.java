package org.unicitylabs.faucet;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * BIP-340 Schnorr signatures using BouncyCastle (pure Java, no JNI)
 */
public class Schnorr {

    private static final ECNamedCurveParameterSpec CURVE_PARAMS = ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final BigInteger N = CURVE_PARAMS.getN();
    private static final ECPoint G = CURVE_PARAMS.getG();

    /**
     * Derive x-only public key from private key (32 bytes)
     * BIP-340: Returns x-coordinate of P where P.y is even
     */
    public static byte[] getPublicKey(byte[] privateKey) {
        BigInteger d = new BigInteger(1, privateKey);
        ECPoint P = G.multiply(d).normalize();

        // BIP-340: x-only pubkey is x-coordinate of P (implicitly even y)
        byte[] x = P.getAffineXCoord().toBigInteger().toByteArray();
        return padTo32Bytes(x);
    }

    /**
     * Sign a message using BIP-340 Schnorr
     * @param message 32-byte message to sign
     * @param privateKey 32-byte private key
     * @return 64-byte signature (R.x || s)
     */
    public static byte[] sign(byte[] message, byte[] privateKey) throws Exception {
        if (message.length != 32) {
            throw new IllegalArgumentException("Message must be 32 bytes");
        }
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("Private key must be 32 bytes");
        }

        BigInteger d = new BigInteger(1, privateKey);
        ECPoint P = G.multiply(d).normalize();

        // BIP-340: If P.y is odd, use negated private key
        if (P.getAffineYCoord().toBigInteger().testBit(0)) {
            d = N.subtract(d);
            P = G.multiply(d).normalize();
        }

        // Get x-only public key (P now has even y)
        byte[] px = padTo32Bytes(P.getAffineXCoord().toBigInteger().toByteArray());

        // Generate nonce k (deterministic from adjusted private key d and message)
        byte[] d_bytes = padTo32Bytes(d.toByteArray());
        byte[] k_bytes = taggedHash("BIP0340/nonce", concat(d_bytes, px, message));
        BigInteger k = new BigInteger(1, k_bytes).mod(N);

        if (k.equals(BigInteger.ZERO)) {
            throw new RuntimeException("Invalid k");
        }

        // Compute R = k*G
        ECPoint R = G.multiply(k).normalize();

        // Get R.x as 32 bytes (before potentially negating k)
        byte[] rx = padTo32Bytes(R.getAffineXCoord().toBigInteger().toByteArray());

        // BIP-340: If R.y is odd (has_even_y returns false), negate k
        // Note: We keep the same R.x coordinate, k is adjusted for the signature calculation
        if (R.getAffineYCoord().toBigInteger().testBit(0)) {
            k = N.subtract(k);
        }

        // Compute challenge e = tagged_hash("BIP0340/challenge", R.x || P.x || message)
        byte[] e_bytes = taggedHash("BIP0340/challenge", concat(rx, px, message));
        BigInteger e = new BigInteger(1, e_bytes).mod(N);

        // Compute s = (k + e*d) mod n
        BigInteger s = k.add(e.multiply(d)).mod(N);

        // Return signature = R.x || s (64 bytes)
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        byte[] s_bytes = padTo32Bytes(s.toByteArray());
        System.arraycopy(s_bytes, 0, sig, 32, 32);

        return sig;
    }

    /**
     * Tagged hash as specified in BIP-340
     */
    private static byte[] taggedHash(String tag, byte[] msg) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] tagHash = sha256.digest(tag.getBytes());
        sha256.reset();
        sha256.update(tagHash);
        sha256.update(tagHash);
        sha256.update(msg);
        return sha256.digest();
    }

    /**
     * Pad or trim byte array to exactly 32 bytes
     */
    private static byte[] padTo32Bytes(byte[] bytes) {
        if (bytes.length == 32) {
            return bytes;
        }
        if (bytes.length > 32) {
            // Remove leading zeros
            return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        }
        // Pad with leading zeros
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return padded;
    }

    /**
     * Concatenate byte arrays
     */
    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] arr : arrays) {
            totalLength += arr.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }
}
