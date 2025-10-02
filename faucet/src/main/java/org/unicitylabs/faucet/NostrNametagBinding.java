package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Hex;
import org.unicitylabs.faucet.NostrClient.NostrEvent;
import java.security.MessageDigest;
import java.util.*;

/**
 * Manages Nostr pubkey ↔ Unicity nametag bindings using replaceable events
 */
public class NostrNametagBinding {

    // Kind 30078: Parameterized replaceable event for application-specific data
    public static final int KIND_NAMETAG_BINDING = 30078;
    public static final String TAG_D_VALUE = "unicity-nametag";

    private final ObjectMapper jsonMapper;

    public NostrNametagBinding() {
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Create a binding event that maps a Nostr pubkey to a Unicity nametag
     * This is a replaceable event - newer events automatically replace older ones
     */
    public NostrEvent createBindingEvent(String publicKeyHex, byte[] privateKey,
                                         String nametagId, String unicityAddress) throws Exception {
        long createdAt = System.currentTimeMillis() / 1000;

        // Create tags for the replaceable event
        List<List<String>> tags = new ArrayList<>();
        tags.add(Arrays.asList("d", TAG_D_VALUE)); // Makes it replaceable by pubkey+d
        tags.add(Arrays.asList("nametag", nametagId)); // For querying by nametag
        tags.add(Arrays.asList("t", nametagId)); // Also use 't' tag which is commonly indexed
        tags.add(Arrays.asList("address", unicityAddress)); // Store Unicity address

        // Create content with binding information
        Map<String, Object> contentData = new LinkedHashMap<>();
        contentData.put("nametag", nametagId);
        contentData.put("address", unicityAddress);
        contentData.put("verified", System.currentTimeMillis());
        String content = jsonMapper.writeValueAsString(contentData);

        // Create event data for ID calculation (canonical JSON array)
        List<Object> eventData = Arrays.asList(
            0,
            publicKeyHex,
            createdAt,
            KIND_NAMETAG_BINDING,
            tags,
            content
        );

        String eventJson = jsonMapper.writeValueAsString(eventData);

        // Calculate event ID (SHA-256 of canonical JSON)
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] eventIdBytes = digest.digest(eventJson.getBytes());
        String eventId = Hex.encodeHexString(eventIdBytes);

        // Sign with BIP-340 Schnorr signature
        // privateKey is already byte[] - ensure it's 32 bytes
        byte[] privateKeyBytes = privateKey;
        if (privateKeyBytes.length == 33 && privateKeyBytes[0] == 0) {
            privateKeyBytes = Arrays.copyOfRange(privateKeyBytes, 1, 33);
        } else if (privateKeyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(privateKeyBytes, 0, padded, 32 - privateKeyBytes.length, privateKeyBytes.length);
            privateKeyBytes = padded;
        }
        byte[] signature = Schnorr.sign(eventIdBytes, privateKeyBytes);
        String signatureHex = Hex.encodeHexString(signature);

        return new NostrEvent(eventId, publicKeyHex, createdAt, KIND_NAMETAG_BINDING,
                             tags, content, signatureHex);
    }

    /**
     * Create a filter to find nametag by Nostr pubkey
     */
    public Map<String, Object> createPubkeyToNametagFilter(String nostrPubkey) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("kinds", Arrays.asList(KIND_NAMETAG_BINDING));
        filter.put("authors", Arrays.asList(nostrPubkey));

        // Use #d tag to ensure we get the nametag binding
        filter.put("#d", Arrays.asList(TAG_D_VALUE));
        filter.put("limit", 1);

        return filter;
    }

    /**
     * Create a filter to find Nostr pubkey by nametag
     */
    public Map<String, Object> createNametagToPubkeyFilter(String nametagId) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("kinds", Arrays.asList(KIND_NAMETAG_BINDING));

        // Use #t tag which is commonly indexed by relays for topics
        filter.put("#t", Arrays.asList(nametagId));

        filter.put("limit", 1);

        return filter;
    }

    /**
     * Parse nametag from a binding event
     */
    public String parseNametagFromEvent(NostrEvent event) {
        if (event == null || event.kind != KIND_NAMETAG_BINDING) {
            return null;
        }

        // Look for nametag in tags
        for (List<String> tag : event.tags) {
            if (tag.size() >= 2 && "nametag".equals(tag.get(0))) {
                return tag.get(1);
            }
        }

        // Fallback to parsing from content
        try {
            Map<String, Object> contentData = jsonMapper.readValue(event.content, Map.class);
            return (String) contentData.get("nametag");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse Unicity address from a binding event
     */
    public String parseAddressFromEvent(NostrEvent event) {
        if (event == null || event.kind != KIND_NAMETAG_BINDING) {
            return null;
        }

        // Look for address in tags
        for (List<String> tag : event.tags) {
            if (tag.size() >= 2 && "address".equals(tag.get(0))) {
                return tag.get(1);
            }
        }

        // Fallback to parsing from content
        try {
            Map<String, Object> contentData = jsonMapper.readValue(event.content, Map.class);
            return (String) contentData.get("address");
        } catch (Exception e) {
            return null;
        }
    }
}