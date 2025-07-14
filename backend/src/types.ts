export interface Payment {
  senderAddress: string;
  currencySymbol: string;
  amount: string;
  transactionId?: string;
}

export interface PaymentRequest {
  id: string;
  recipientAddress: string;
  status: 'pending' | 'completed' | 'expired';
  createdAt: Date;
  expiresAt: Date;
  completedAt?: Date;
  payment?: Payment;
}

export interface CreatePaymentRequestDto {
  recipientAddress: string;
}

export interface CompletePaymentRequestDto {
  senderAddress: string;
  currencySymbol: string;
  amount: string;
  transactionId?: string;
}