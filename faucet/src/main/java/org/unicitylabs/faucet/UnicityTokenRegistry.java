package org.unicitylabs.faucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for Unicity token/coin definitions
 * Caches metadata from GitHub unicitynetwork/unicity-ids repository
 */
public class UnicityTokenRegistry {

    private static final String REGISTRY_URL =
        "https://raw.githubusercontent.com/unicitynetwork/unicity-ids/refs/heads/main/unicity-ids.testnet.json";
    private static final String CACHE_FILE = System.getProperty("user.home") + "/.unicity/registry-cache.json";
    private static final long CACHE_VALIDITY_HOURS = 24;

    private static UnicityTokenRegistry instance;
    private final Map<String, CoinDefinition> coinsById;

    public static class IconEntry {
        public String url;
    }

    public static class CoinDefinition {
        public String network;
        public String assetKind;
        public String name;
        public String symbol;
        public Integer decimals; // Can be null if not specified
        public String description;
        public String icon; // Legacy field (deprecated)
        public List<IconEntry> icons; // New icons array
        public String id;

        /**
         * Get the best icon URL (prefer PNG over SVG)
         */
        public String getIconUrl() {
            // Try new icons array first
            if (icons != null && !icons.isEmpty()) {
                // Prefer PNG
                for (IconEntry iconEntry : icons) {
                    if (iconEntry.url.toLowerCase().contains(".png")) {
                        return iconEntry.url;
                    }
                }
                // Fall back to first icon
                return icons.get(0).url;
            }

            // Fall back to legacy icon field
            return icon;
        }

        @Override
        public String toString() {
            return String.format("%s (%s) - decimals: %d", name, symbol, decimals);
        }
    }

    private UnicityTokenRegistry() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<CoinDefinition> definitions;

        // Try to load from cache first
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists() && !isCacheStale(cacheFile)) {
            System.out.println("📦 Loading registry from cache...");
            definitions = mapper.readValue(
                cacheFile,
                mapper.getTypeFactory().constructCollectionType(List.class, CoinDefinition.class)
            );
        } else {
            // Fetch from GitHub
            System.out.println("🌐 Fetching registry from GitHub...");
            URL url = new URL(REGISTRY_URL);
            definitions = mapper.readValue(
                url,
                mapper.getTypeFactory().constructCollectionType(List.class, CoinDefinition.class)
            );

            // Save to cache
            saveToCache(mapper, definitions);
        }

        // Index by ID
        coinsById = new HashMap<>();
        for (CoinDefinition def : definitions) {
            coinsById.put(def.id, def);
        }

        System.out.println("✅ Token registry loaded: " + coinsById.size() + " definitions");
    }

    private boolean isCacheStale(File cacheFile) {
        long ageMillis = System.currentTimeMillis() - cacheFile.lastModified();
        long ageHours = ageMillis / (1000 * 60 * 60);
        return ageHours > CACHE_VALIDITY_HOURS;
    }

    private void saveToCache(ObjectMapper mapper, List<CoinDefinition> definitions) {
        try {
            File cacheFile = new File(CACHE_FILE);
            cacheFile.getParentFile().mkdirs(); // Create ~/.unicity directory if needed
            mapper.writeValue(cacheFile, definitions);
            System.out.println("💾 Registry cached to: " + CACHE_FILE);
        } catch (IOException e) {
            System.err.println("⚠️  Failed to cache registry: " + e.getMessage());
        }
    }

    public static synchronized UnicityTokenRegistry getInstance() throws IOException {
        if (instance == null) {
            instance = new UnicityTokenRegistry();
        }
        return instance;
    }

    /**
     * Get coin definition by coin ID hex
     */
    public CoinDefinition getCoinDefinition(String coinIdHex) {
        return coinsById.get(coinIdHex);
    }

    /**
     * Get decimals for a coin, returns 8 as default if not found
     */
    public int getDecimals(String coinIdHex) {
        CoinDefinition coin = getCoinDefinition(coinIdHex);
        if (coin != null && coin.decimals != null) {
            return coin.decimals;
        }
        return 8; // Default to 8 decimals
    }
}
