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
POST /payment-requests
Body: { "recipientAddress": "0x..." }
```

### Get Payment Request
```
GET /payment-requests/:id
```

### Complete Payment Request
```
PUT /payment-requests/:id/complete
Body: {
  "senderAddress": "0x...",
  "currencySymbol": "USDC",
  "amount": "10.50",
  "transactionId": "optional-tx-id"
}
```

### List All Payment Requests (Testing)
```
GET /payment-requests
```

**Note**: The polling endpoint has been removed. Use GET /payment-requests/:id instead.

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

## Data Models

### PaymentRequest
```typescript
{
  requestId: string        // Changed from 'id'
  recipientAddress: string
  status: 'pending' | 'completed' | 'expired'
  createdAt: string       // ISO 8601 date string
  expiresAt: string       // ISO 8601 date string
  completedAt?: string    // ISO 8601 date string
  paymentDetails?: {      // Changed from 'payment'
    senderAddress: string
    currencySymbol: string
    amount: string
    transactionId?: string
  }
}
```