export interface PaymentDetails {
  senderAddress: string;
  currencySymbol: string;
  amount: string;
  transactionId?: string;
}

export interface PaymentRequest {
  requestId: string;
  recipientAddress: string;
  currencySymbol?: string;
  amount?: string;
  status: 'pending' | 'completed' | 'expired';
  createdAt: string;
  expiresAt: string;
  completedAt?: string;
  paymentDetails?: PaymentDetails;
}

export interface CreatePaymentRequestDto {
  recipientAddress: string;
  currencySymbol?: string;
  amount?: string;
}

export interface CompletePaymentRequestDto {
  senderAddress: string;
  currencySymbol: string;
  amount: string;
  transactionId?: string;
}