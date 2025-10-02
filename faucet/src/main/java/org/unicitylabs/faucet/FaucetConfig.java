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

    public static class TokenType {
        public int systemId;
        public String unitId;
    }

    public TokenType tokenType;
    public String coinId;
    public String nostrRelay;
    public String aggregatorUrl;
    public String faucetPrivateKey;
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
