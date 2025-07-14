# Payment Request Backend

Backend service for QR code-based payment requests in the NFC Wallet Demo app.

## Features

- Create payment requests with unique IDs
- Generate QR codes with deep links
- Complete payment requests with transfer details
- Poll for payment status updates
- Automatic request expiration

## Quick Start

### Using Docker

```bash
docker-compose up
```

### Manual Setup

```bash
# Install dependencies
npm install

# Development mode
npm run dev

# Build and run
npm run build
npm start
```

## API Endpoints

### Create Payment Request
```
POST /api/payment-requests
Body: { "recipientAddress": "0x..." }
```

### Get Payment Request
```
GET /api/payment-requests/:id
```

### Complete Payment Request
```
POST /api/payment-requests/:id/complete
Body: {
  "senderAddress": "0x...",
  "currencySymbol": "USDC",
  "amount": "10.50",
  "transactionId": "optional-tx-id"
}
```

### Poll Payment Status
```
GET /api/payment-requests/:id/poll
```

## Configuration

Environment variables:
- `PORT` - Server port (default: 3001)
- `REQUEST_EXPIRY_SECONDS` - Request expiration time (default: 60)
- `CLEANUP_INTERVAL_SECONDS` - Cleanup interval (default: 30)
- `CORS_ORIGIN` - CORS origin (default: *)

## Deep Link Format

QR codes contain deep links in this format:
```
nfcwallet://payment-request?id=<request-id>&recipient=<address>
```