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

// Transfer Request Types
export interface TransferRequest {
  requestId: string;
  senderTag: string;
  recipientTag: string;
  assetType: 'crypto' | 'token';
  assetName: string;
  amount: string;
  message?: string;
  status: 'pending' | 'accepted' | 'rejected' | 'expired';
  createdAt: string;
  expiresAt: string;
  acceptedAt?: string;
  rejectedAt?: string;
}

export interface CreateTransferRequestDto {
  senderTag?: string;
  recipientTag: string;
  assetType: 'crypto' | 'token';
  assetName: string;
  amount: string;
  message?: string;
}