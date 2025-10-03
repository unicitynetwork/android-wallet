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

    public String tokenType;  // Hex string for token type (32 bytes)
    public String coinId;     // Hex string for coin ID (32 bytes)
    public String nostrRelay;
    public String aggregatorUrl;
    public String faucetMnemonic;  // BIP-39 mnemonic phrase
    public int defaultAmount;

    /**
     * Load configuration from resources
     */
    public static FaucetConfig load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        InputStream configStream = FaucetConfig.class
            .getResourceAsStream("/faucet-config.json");

        if (configStream == null) {
            throw new IOException("faucet-config.json not found in resources");
        }

        return mapper.readValue(configStream, FaucetConfig.class);
    }
}
