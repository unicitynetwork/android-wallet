FROM rust:1.75-alpine as builder

# Install build dependencies
RUN apk add --no-cache musl-dev openssl-dev pkgconfig git

# Clone and build nostr-rs-relay
RUN git clone https://github.com/scsibug/nostr-rs-relay.git /build
WORKDIR /build
RUN cargo build --release

# Final stage
FROM alpine:3.18

# Install runtime dependencies
RUN apk add --no-cache ca-certificates libgcc

# Copy binary from builder
COPY --from=builder /build/target/release/nostr-rs-relay /usr/local/bin/

# Create data directory
RUN mkdir -p /data

# Set working directory
WORKDIR /data

# Expose port
EXPOSE 8080

# Run the relay
CMD ["/usr/local/bin/nostr-rs-relay"]