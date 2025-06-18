// app/src/main/assets/unicity-wrapper.js

// Global variables for SDK components
let sdkClient = null;
const AGGREGATOR_URL = 'https://gateway-test.unicity.network';

/**
 * Initializes the Unicity SDK client
 */
function initializeSdk() {
  try {
    // Check what's available in UnicitySDK
    console.log('UnicitySDK available classes:', Object.keys(UnicitySDK || {}));
    
    // Log first few classes to see structure
    const sdkKeys = Object.keys(UnicitySDK || {});
    console.log('First 10 SDK classes:', sdkKeys.slice(0, 10));
    
    const { AggregatorClient, StateTransitionClient } = UnicitySDK;
    if (!AggregatorClient || !StateTransitionClient) {
      throw new Error('AggregatorClient or StateTransitionClient not found in UnicitySDK');
    }
    
    const aggregatorClient = new AggregatorClient(AGGREGATOR_URL);
    sdkClient = new StateTransitionClient(aggregatorClient);
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: 'SDK initialized' }));
  } catch (e) {
    console.error('SDK initialization error:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Generates a new identity (secret key) for a user
 */
function generateIdentity() {
  try {
    // Generate a 32-byte secret key
    const secret = crypto.getRandomValues(new Uint8Array(32));
    const nonce = crypto.getRandomValues(new Uint8Array(32));
    
    // Convert to hex for easy storage and transmission
    const secretHex = Array.from(secret).map(b => b.toString(16).padStart(2, '0')).join('');
    const nonceHex = Array.from(nonce).map(b => b.toString(16).padStart(2, '0')).join('');
    
    const identity = {
      secret: secretHex,
      nonce: nonceHex
    };
    
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(identity) }));
  } catch (e) {
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Mints a new token
 * @param {string} identityJson - The stringified JSON of the owner's identity
 * @param {string} tokenDataJson - The stringified JSON of the token's data
 */
async function mintToken(identityJson, tokenDataJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Attempting to mint real Unicity token...');
    
    const identity = JSON.parse(identityJson);
    const tokenData = JSON.parse(tokenDataJson);
    
    const { 
      SigningService, 
      MaskedPredicate, 
      DirectAddress, 
      TokenId, 
      TokenType,
      TokenCoinData,
      CoinId,
      Token,
      TokenState,
      HashAlgorithm,
      DataHasher
    } = UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const secret = new Uint8Array(identity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const nonce = new Uint8Array(identity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Create signing service
    const signingService = await SigningService.createFromSecret(secret, nonce);
    
    // Generate token identifiers
    const tokenId = TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
    const tokenType = TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
    
    // Create predicate for the token owner
    const predicate = await MaskedPredicate.create(
      tokenId,
      tokenType,
      signingService,
      HashAlgorithm.SHA256,
      nonce
    );
    
    // Create coin data
    const coinId = CoinId.create(crypto.getRandomValues(new Uint8Array(32)));
    const coinData = TokenCoinData.create(coinId, BigInt(tokenData.amount || 100));
    
    // Create token data
    const testTokenData = new TestTokenData(new TextEncoder().encode(tokenData.data || 'Unicity token'));
    
    // Create the token state
    const tokenState = await TokenState.create(predicate, new TextEncoder().encode(tokenData.stateData || 'Token state data'));
    
    // Create address for minting
    const address = await DirectAddress.create(predicate.reference);
    
    // Submit mint transaction
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode(tokenData.data || 'default data'));
    const dataHash = await dataHasher.digest();
    
    console.log('Submitting mint transaction...');
    const mintCommitment = await sdkClient.submitMintTransaction(
      address,
      tokenId,
      tokenType,
      testTokenData,
      coinData,
      salt,
      dataHash,
      null
    );
    
    console.log('Waiting for inclusion proof...');
    const inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
    
    console.log('Creating transaction...');
    const mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
    
    // Create the final token
    const token = new Token(
      tokenId,
      tokenType,
      testTokenData,
      coinData,
      tokenState,
      [mintTransaction]
    );
    
    const result = {
      token: token.toJSON(),
      identity: identity,
      commitment: mintCommitment.toJSON()
    };
    
    console.log('Real Unicity token minted successfully!');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Token minting failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Creates a transfer transaction for a token
 * @param {string} senderIdentityJson - Stringified JSON of the sender's identity
 * @param {string} receiverIdentityJson - Stringified JSON of the receiver's identity  
 * @param {string} tokenJson - The current JSON state of the token being sent
 */
async function createTransfer(senderIdentityJson, receiverIdentityJson, tokenJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Creating real Unicity transfer...');
    
    const senderIdentity = JSON.parse(senderIdentityJson);
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const tokenData = JSON.parse(tokenJson);
    
    const { 
      SigningService, 
      MaskedPredicate, 
      DirectAddress, 
      TokenFactory,
      PredicateFactory,
      TransactionData,
      HashAlgorithm,
      DataHasher
    } = UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const token = await tokenFactory.create(tokenData.token, TestTokenData.fromJSON);
    
    // Create sender signing service
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    // Create receiver predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      token.id,
      token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Create recipient address
    const recipientAddress = await DirectAddress.create(recipientPredicate.reference);
    
    // Create transaction data
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('transfer data'));
    const dataHash = await dataHasher.digest();
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress.toJSON(),
      salt,
      dataHash,
      new TextEncoder().encode('token transfer'),
      token.nametagTokens
    );
    
    // Submit transaction
    const commitment = await sdkClient.submitTransaction(transactionData, senderSigningService);
    
    // Wait for inclusion proof
    const inclusionProof = await waitInclusionProof(sdkClient, commitment);
    
    // Create transaction
    const transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    
    const result = {
      token: tokenData.token,
      transaction: transaction.toJSON(),
      receiverPredicate: {
        secret: receiverIdentity.secret,
        nonce: receiverIdentity.nonce
      }
    };
    
    console.log('Real transfer created successfully');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Transfer creation failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Completes a token transfer on the receiver side
 * @param {string} receiverIdentityJson - Stringified JSON of the receiver's identity
 * @param {string} transferJson - The transfer data from sender
 */
async function finishTransfer(receiverIdentityJson, transferJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    const { 
      SigningService, 
      MaskedPredicate, 
      TokenFactory,
      PredicateFactory,
      Transaction,
      TokenState,
      HashAlgorithm
    } = UnicitySDK;
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const transferData = JSON.parse(transferJson);
    
    // Convert hex strings back to Uint8Array
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token and transaction from JSON
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const token = await tokenFactory.create(transferData.token, TestTokenData.fromJSON);
    
    const transaction = await Transaction.fromJSON(
      token.id,
      token.type,
      transferData.transaction,
      new PredicateFactory()
    );
    
    // Create receiver predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      token.id,
      token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Finish the transaction
    const updatedToken = await sdkClient.finishTransaction(
      token,
      await TokenState.create(recipientPredicate, new TextEncoder().encode('received token')),
      transaction
    );
    
    const result = {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
    
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Transfer completion failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Utility class for test token data
 */
class TestTokenData {
  constructor(data) {
    this._data = new Uint8Array(data);
  }
  
  get data() {
    return new Uint8Array(this._data);
  }
  
  static fromJSON(data) {
    if (typeof data !== 'string') {
      throw new Error('Invalid test token data');
    }
    const bytes = new Uint8Array(data.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    return new TestTokenData(bytes);
  }
  
  toJSON() {
    return Array.from(this._data).map(b => b.toString(16).padStart(2, '0')).join('');
  }
  
  toCBOR() {
    return this.data;
  }
  
  toString() {
    return `TestTokenData: ${this.toJSON()}`;
  }
}

/**
 * Utility function to wait for inclusion proof
 */
async function waitInclusionProof(client, commitment, timeout = 10000) {
  const { InclusionProofVerificationStatus } = UnicitySDK;
  
  const startTime = Date.now();
  while (Date.now() - startTime < timeout) {
    try {
      const inclusionProof = await client.getInclusionProof(commitment);
      if ((await inclusionProof.verify(commitment.requestId.toBigInt())) === InclusionProofVerificationStatus.OK) {
        return inclusionProof;
      }
    } catch (err) {
      // Continue waiting if not found yet
    }
    
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  
  throw new Error('Timeout waiting for inclusion proof');
}

// Initialize SDK when page loads
document.addEventListener('DOMContentLoaded', function() {
  initializeSdk();
});