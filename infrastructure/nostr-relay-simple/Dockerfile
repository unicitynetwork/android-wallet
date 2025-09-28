# Use a simple, lightweight Nostr relay implementation
FROM node:18-alpine

# Install dependencies
RUN apk add --no-cache git python3 make g++ curl

# Clone nostream (production-ready Nostr relay)
WORKDIR /app
RUN git clone https://github.com/Cameri/nostream.git . && \
    git checkout v2.0.0

# Install Node dependencies
RUN npm ci --omit=dev

# Copy default config
RUN cp .env.example .env

# Configure for simple operation
RUN sed -i 's/DB_HOST=.*/DB_HOST=localhost/' .env && \
    sed -i 's/DB_PORT=.*/DB_PORT=5432/' .env && \
    sed -i 's/RELAY_PORT=.*/RELAY_PORT=8080/' .env

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD curl -f http://localhost:8080 || exit 1

# Run the relay
CMD ["npm", "start"]