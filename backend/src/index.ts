import express from 'express';
import cors from 'cors';
import { v4 as uuidv4 } from 'uuid';
import { PaymentRequestStore } from './store';
import { CreatePaymentRequestDto, CompletePaymentRequestDto, PaymentRequest } from './types';
import { config } from './config';

const app = express();
const store = new PaymentRequestStore();

// Middleware
app.use(cors({ origin: config.corsOrigin }));
app.use(express.json());

// Health check
app.get('/health', (_, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Handle OPTIONS for CORS
app.options('*', (_, res) => {
  res.sendStatus(200);
});

// Create payment request
app.post('/payment-requests', (req, res) => {
  try {
    const { recipientAddress } = req.body as CreatePaymentRequestDto;
    
    if (!recipientAddress) {
      return res.status(400).json({ error: 'recipientAddress is required' });
    }

    const now = new Date();
    const expiresAt = new Date(now.getTime() + config.requestExpirySeconds * 1000);
    
    const request: PaymentRequest = {
      requestId: uuidv4(),
      recipientAddress,
      status: 'pending',
      createdAt: now.toISOString(),
      expiresAt: expiresAt.toISOString(),
    };

    store.create(request);

    // Generate deep link for QR code
    const qrData = `nfcwallet://payment-request?id=${request.requestId}&recipient=${encodeURIComponent(recipientAddress)}`;

    return res.status(201).json({
      ...request,
      qrData,
    });
  } catch (error) {
    console.error('Error creating payment request:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
});

// Get payment request
app.get('/payment-requests/:id', (req, res) => {
  try {
    const { id } = req.params;
    const request = store.get(id);

    if (!request) {
      return res.status(404).json({ error: 'Payment request not found' });
    }

    return res.json(request);
  } catch (error) {
    console.error('Error getting payment request:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
});

// Complete payment request
app.put('/payment-requests/:id/complete', (req, res) => {
  try {
    const { id } = req.params;
    const { senderAddress, currencySymbol, amount, transactionId } = req.body as CompletePaymentRequestDto;

    if (!senderAddress || !currencySymbol || !amount) {
      return res.status(400).json({ error: 'senderAddress, currencySymbol, and amount are required' });
    }

    const request = store.get(id);
    if (!request) {
      return res.status(404).json({ error: 'Payment request not found' });
    }

    if (request.status !== 'pending') {
      return res.status(400).json({ error: `Payment request is ${request.status}` });
    }

    // Check if expired
    if (request.expiresAt < new Date().toISOString()) {
      store.update(id, { status: 'expired' });
      return res.status(400).json({ error: 'Payment request has expired' });
    }

    const updated = store.update(id, {
      status: 'completed',
      completedAt: new Date().toISOString(),
      paymentDetails: {
        senderAddress,
        currencySymbol,
        amount,
        transactionId,
      },
    });

    return res.json(updated);
  } catch (error) {
    console.error('Error completing payment request:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
});

// List all payment requests (for testing)
app.get('/payment-requests', (_, res) => {
  try {
    const allRequests = store.getAll();
    return res.json(allRequests);
  } catch (error) {
    console.error('Error listing payment requests:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
});

// Cleanup expired requests
setInterval(() => {
  const now = new Date();
  const expired = store.getAllExpired(now);
  
  if (expired.length > 0) {
    console.log(`Marking ${expired.length} requests as expired`);
    store.markExpired(expired.map(r => r.requestId));
  }
}, config.cleanupIntervalSeconds * 1000);

// Start server
app.listen(config.port, () => {
  console.log(`Payment request backend running on port ${config.port}`);
  console.log(`Request expiry: ${config.requestExpirySeconds} seconds`);
  console.log(`Cleanup interval: ${config.cleanupIntervalSeconds} seconds`);
});