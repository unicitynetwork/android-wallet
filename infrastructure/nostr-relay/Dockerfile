# Use exact same version as AWS deployment
FROM scsibug/nostr-rs-relay:0.9.0

# Copy our custom config with increased limits
COPY config.toml /usr/src/app/config.toml

# Expose port
EXPOSE 8080

# Run with custom config
CMD ["/usr/src/app/nostr-rs-relay", "--config", "/usr/src/app/config.toml"]