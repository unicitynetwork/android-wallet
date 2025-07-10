interface AndroidBridge {
    onResult(requestId: string, data: string): void;
    onError(requestId: string, error: string): void;
}
interface AndroidRequest {
    id: string;
    method: string;
    params: any;
}
interface AndroidResponse {
    success: boolean;
    data?: any;
    error?: string;
}
interface IdentityJson {
    secret: string;
    nonce: string;
}
interface TokenDataJson {
    data: string;
    amount: number;
}
interface TransferPackageJson {
    commitment: any;
    token: any;
}
interface Window {
    Android?: AndroidBridge;
}
declare const unicity: {
    StateTransitionClient: any;
    AggregatorClient: any;
    Commitment: any;
    CommitmentJsonSerializer: any;
    TokenFactory: any;
    TokenJsonSerializer: any;
    PredicateJsonFactory: any;
    RequestId: any;
    MintTransactionData: any;
    TokenId: any;
    TokenType: any;
    DirectAddress: any;
    SigningService: any;
    HashAlgorithm: any;
    MaskedPredicate: any;
    TokenCoinData: any;
    CoinId: any;
    Token: any;
    TokenState: any;
    Transaction: any;
    TransactionData: any;
    TransactionJsonSerializer: any;
    SubmitCommitmentStatus: any;
    waitInclusionProof: any;
};
declare const aggregatorUrl = "https://gateway-test.unicity.network";
declare function createClient(): any;
declare function mintToken(identityJson: string, tokenDataJson: string): Promise<string>;
declare function prepareTransfer(senderIdentityJson: string, recipientAddress: string, tokenJson: string, isOffline?: boolean): Promise<string>;
declare function finalizeReceivedTransaction(receiverIdentityJson: string, transferPackageJson: string): Promise<string>;
declare function handleAndroidRequest(request: string): Promise<void>;
//# sourceMappingURL=unicity-wrapper.d.ts.map