interface AndroidBridge {
    onResult(requestId: string, data: string): void;
    onError(requestId: string, error: string): void;
    showToast(message: string): void;
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
declare const unicity: any;
declare const aggregatorUrl = "https://gateway-test.unicity.network";
declare function createClient(): any;
declare function mintToken(identityJson: string, tokenDataJson: string): Promise<string>;
declare function prepareTransfer(senderIdentityJson: string, recipientAddress: string, tokenJson: string, isOffline?: boolean): Promise<string>;
declare function finalizeReceivedTransaction(receiverIdentityJson: string, transferPackageJson: string): Promise<string>;
declare function handleAndroidRequest(request: string): Promise<void>;
//# sourceMappingURL=unicity-wrapper.d.ts.map