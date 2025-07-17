import { PaymentRequest, TransferRequest } from './types';

export class PaymentRequestStore {
  private requests: Map<string, PaymentRequest> = new Map();

  create(request: PaymentRequest): void {
    this.requests.set(request.requestId, request);
  }

  get(id: string): PaymentRequest | undefined {
    return this.requests.get(id);
  }

  update(id: string, updates: Partial<PaymentRequest>): PaymentRequest | undefined {
    const request = this.requests.get(id);
    if (!request) return undefined;

    const updated = { ...request, ...updates };
    this.requests.set(id, updated);
    return updated;
  }

  delete(id: string): boolean {
    return this.requests.delete(id);
  }

  getAllExpired(now: Date): PaymentRequest[] {
    const expired: PaymentRequest[] = [];
    const nowStr = now.toISOString();
    for (const request of this.requests.values()) {
      if (request.status === 'pending' && request.expiresAt < nowStr) {
        expired.push(request);
      }
    }
    return expired;
  }

  markExpired(ids: string[]): void {
    for (const id of ids) {
      const request = this.requests.get(id);
      if (request) {
        request.status = 'expired';
      }
    }
  }

  getAll(): PaymentRequest[] {
    return Array.from(this.requests.values());
  }
}

export class TransferRequestStore {
  private requests: Map<string, TransferRequest> = new Map();

  create(request: TransferRequest): void {
    this.requests.set(request.requestId, request);
  }

  get(id: string): TransferRequest | undefined {
    return this.requests.get(id);
  }

  update(id: string, updates: Partial<TransferRequest>): TransferRequest | undefined {
    const request = this.requests.get(id);
    if (!request) return undefined;

    const updated = { ...request, ...updates };
    this.requests.set(id, updated);
    return updated;
  }

  delete(id: string): boolean {
    return this.requests.delete(id);
  }

  getPendingByRecipient(recipientTag: string): TransferRequest[] {
    const pending: TransferRequest[] = [];
    const now = new Date().toISOString();
    
    for (const request of this.requests.values()) {
      if (request.recipientTag === recipientTag && 
          request.status === 'pending' && 
          request.expiresAt > now) {
        pending.push(request);
      }
    }
    return pending;
  }

  getAllExpired(now: Date): TransferRequest[] {
    const expired: TransferRequest[] = [];
    const nowStr = now.toISOString();
    for (const request of this.requests.values()) {
      if (request.status === 'pending' && request.expiresAt < nowStr) {
        expired.push(request);
      }
    }
    return expired;
  }

  markExpired(ids: string[]): void {
    for (const id of ids) {
      const request = this.requests.get(id);
      if (request) {
        request.status = 'expired';
      }
    }
  }

  getAll(): TransferRequest[] {
    return Array.from(this.requests.values());
  }
}