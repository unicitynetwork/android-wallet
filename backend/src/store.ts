import { PaymentRequest } from './types';

export class PaymentRequestStore {
  private requests: Map<string, PaymentRequest> = new Map();

  create(request: PaymentRequest): void {
    this.requests.set(request.id, request);
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
    for (const request of this.requests.values()) {
      if (request.status === 'pending' && request.expiresAt < now) {
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
}