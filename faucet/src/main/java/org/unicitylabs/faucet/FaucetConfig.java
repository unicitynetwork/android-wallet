package org.unicitylabs.faucet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Configuration class for the faucet application
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaucetConfig {

    public String registryUrl;    // URL to unicity-ids registry JSON
    public String nostrRelay;
    public String aggregatorUrl;
    public String faucetMnemonic; // BIP-39 mnemonic phrase
    public int defaultAmount;
    public String defaultCoin;    // Default coin name (e.g., "solana", "bitcoin")

    /**
     * Load configuration from resources, with environment variable overrides
     */
    public static FaucetConfig load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        InputStream configStream = FaucetConfig.class
            .getResourceAsStream("/faucet-config.json");

        if (configStream == null) {
            throw new IOException("faucet-config.json not found in resources");
        }

        FaucetConfig config = mapper.readValue(configStream, FaucetConfig.class);

        // Override with environment variables if present
        String envMnemonic = System.getenv("FAUCET_MNEMONIC");
        if (envMnemonic != null && !envMnemonic.trim().isEmpty()) {
            config.faucetMnemonic = envMnemonic.trim();
            System.out.println("✅ Using FAUCET_MNEMONIC from environment variable");
        }

        // Validate mnemonic is set
        if (config.faucetMnemonic == null || config.faucetMnemonic.trim().isEmpty()) {
            throw new IOException("Faucet mnemonic not configured. Set FAUCET_MNEMONIC environment variable or configure in faucet-config.json");
        }

        return config;
    }
}
