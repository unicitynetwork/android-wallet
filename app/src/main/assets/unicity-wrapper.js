// app/src/main/assets/unicity-wrapper.js
console.log('========== UNICITY WRAPPER LOADED ==========');

// Global variables for SDK components
let sdkClient = null;
const AGGREGATOR_URL = 'https://gateway-test.unicity.network';
console.log('🌐 AGGREGATOR URL:', AGGREGATOR_URL);

/**
 * Initializes the Unicity SDK client
 */
function initializeSdk() {
  try {
    const sdk = window.unicity || window.UnicitySDK;
    if (!sdk) {
      throw new Error('Unicity SDK not found');
    }
    
    window.UnicitySDK = sdk;
    
    const { AggregatorClient, StateTransitionClient } = sdk;
    if (!AggregatorClient || !StateTransitionClient) {
      throw new Error('AggregatorClient or StateTransitionClient not found in SDK');
    }
    
    const aggregatorClient = new AggregatorClient(AGGREGATOR_URL);
    sdkClient = new StateTransitionClient(aggregatorClient);
    
    console.log('SDK initialized successfully');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      data: 'SDK initialized'
    }));
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
    const secret = crypto.getRandomValues(new Uint8Array(32));
    const nonce = crypto.getRandomValues(new Uint8Array(32));
    
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
      console.log('SDK not initialized, attempting to initialize...');
      initializeSdk();
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      if (!sdkClient) {
        throw new Error('SDK failed to initialize');
      }
    }
    
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
      MintTransactionData
    } = window.UnicitySDK;
    
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
    const coinId = new CoinId(crypto.getRandomValues(new Uint8Array(32)));
    const coinData = TokenCoinData.create([[coinId, BigInt(tokenData.amount || 100)]]);
    
    // Create token data
    const testTokenData = new TestTokenData(new TextEncoder().encode(tokenData.data || 'Unicity token'));
    
    // Create the token state
    const tokenState = await TokenState.create(predicate, null);
    
    // Create address for minting
    const address = await DirectAddress.create(predicate.reference);
    
    // Submit mint transaction
    const salt = crypto.getRandomValues(new Uint8Array(32));
    
    const mintTransactionData = await MintTransactionData.create(
      tokenId,
      tokenType,
      testTokenData.bytes,
      coinData,
      address.toJSON(),
      salt,
      null,  // No data hash when state data is null
      null   // reason (null for new mint)
    );
    
    const mintCommitment = await sdkClient.submitMintTransaction(mintTransactionData);
    const inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
    const mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
    
    // Create the final token
    const token = new Token(
      tokenState,
      mintTransaction,
      [],    // transactions (empty for new token)
      [],    // nametagTokens (empty for new token)
      "2.0"  // version
    );
    
    const result = {
      token: token.toJSON(),
      identity: identity,
      requestId: mintCommitment.requestId.toJSON()
    };
    
    console.log('Token minted successfully!');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Token minting failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Prepares a transfer (works for both online and offline)
 * @param {string} senderIdentityJson - The sender's identity
 * @param {string} recipientAddress - The recipient's address
 * @param {string} tokenJson - The token to transfer
 * @param {boolean} isOffline - Whether this is an offline transfer
 */
async function prepareTransfer(senderIdentityJson, recipientAddress, tokenJson, isOffline = false) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log(`Preparing ${isOffline ? 'offline' : 'online'} transfer...`);
    
    const senderIdentity = JSON.parse(senderIdentityJson);
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    
    const { 
      SigningService, 
      TokenFactory,
      PredicateJsonFactory,
      TransactionData,
      Commitment,
      CommitmentJsonSerializer,
      TokenJsonSerializer
    } = window.UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenFactory = new TokenFactory(new TokenJsonSerializer(new PredicateJsonFactory()));
    const token = await tokenFactory.create(parsedTokenData.token || parsedTokenData);
    
    // Create sender signing service
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    // Create transaction data
    const salt = crypto.getRandomValues(new Uint8Array(32));
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress,
      salt,
      null, // No data hash when state data is null
      null, // No state data
      token.nametagTokens
    );
    
    if (isOffline) {
      // For offline transfers, create commitment without network submission
      const commitment = await Commitment.create(
        transactionData,
        senderSigningService
      );
      
      const offlinePackage = {
        commitment: CommitmentJsonSerializer.serialize(commitment),
        token: token.toJSON()
      };
      
      console.log('Offline transfer package created successfully');
      console.log('Package structure:', Object.keys(offlinePackage));
      console.log('Commitment keys:', Object.keys(offlinePackage.commitment));
      
      AndroidBridge.postMessage(JSON.stringify({ 
        status: 'success', 
        data: JSON.stringify(offlinePackage)
      }));
    } else {
      // For online transfers, submit to network
      const commitment = await sdkClient.submitTransaction(transactionData, senderSigningService);
      const inclusionProof = await waitInclusionProof(sdkClient, commitment);
      const transaction = await sdkClient.createTransaction(commitment, inclusionProof);
      
      const transferPackage = {
        token: token.toJSON(),
        transaction: transaction.toJSON(),
        commitment: commitment.requestId.toJSON()
      };
      
      console.log('Online transfer package created successfully');
      AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(transferPackage) }));
    }
  } catch (e) {
    console.error('Transfer preparation failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Finalizes a received transaction (works for both online and offline)
 * @param {string} receiverIdentityJson - The receiver's identity
 * @param {string} transferPackageJson - The transfer package
 */
async function finalizeReceivedTransaction(receiverIdentityJson, transferPackageJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Finalizing received transaction...');
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const transferPackage = JSON.parse(transferPackageJson);
    
    const { 
      SigningService, 
      MaskedPredicate,
      TokenState,
      HashAlgorithm,
      TokenFactory,
      PredicateJsonFactory,
      CommitmentJsonSerializer,
      Commitment,
      TokenJsonSerializer
    } = window.UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from the package
    const tokenFactory = new TokenFactory(new TokenJsonSerializer(new PredicateJsonFactory()));
    const token = await tokenFactory.create(transferPackage.token);
    
    // Check if this is an offline transfer (has serialized commitment) or online transfer
    let commitment;
    let transaction;
    
    if (transferPackage.commitment && typeof transferPackage.commitment === 'object' && transferPackage.commitment.transactionData) {
      // Offline transfer - deserialize commitment
      console.log('Processing offline transfer...');
      console.log('Token ID:', token.id?.toString());
      console.log('Token Type:', token.type?.toString());
      
      const commitmentSerializer = new CommitmentJsonSerializer(new PredicateJsonFactory());
      commitment = await commitmentSerializer.deserialize(
        token.id,
        token.type,
        transferPackage.commitment
      );
      
      console.log('Commitment deserialized, submitting to network...');
      
      // Submit the commitment to the network
      await sdkClient.submitCommitment(commitment);
      console.log('Commitment submitted, waiting for inclusion proof...');
      
      const inclusionProof = await waitInclusionProof(sdkClient, commitment);
      transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    } else {
      // Online transfer - commitment is just the requestId
      console.log('Processing online transfer...');
      commitment = await Commitment.fromJSON(transferPackage.commitment);
      const inclusionProof = await waitInclusionProof(sdkClient, commitment);
      transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    }
    
    // Create receiver's predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      token.id,
      token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Create new token state with receiver as owner
    const newTokenState = await TokenState.create(recipientPredicate, null);
    
    // Finish the transaction
    const updatedToken = await sdkClient.finishTransaction(
      token,
      newTokenState,
      transaction
    );
    
    const result = {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
    
    console.log('Transaction finalized successfully!');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Transaction finalization failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Generates a receiving address for a specific token
 * @param {string} tokenIdHex - The token ID in hex format
 * @param {string} tokenTypeHex - The token type in hex format
 * @param {string} receiverIdentityJson - The receiver's identity
 */
async function generateReceivingAddress(tokenIdHex, tokenTypeHex, receiverIdentityJson) {
  try {
    const { 
      SigningService, 
      MaskedPredicate, 
      DirectAddress,
      TokenId,
      TokenType,
      HashAlgorithm
    } = window.UnicitySDK;
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    
    // Convert hex strings to proper types
    const tokenId = TokenId.fromDto(tokenIdHex);
    const tokenType = TokenType.fromDto(tokenTypeHex);
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Create receiver's signing service and predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      tokenId,
      tokenType,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Create the receiving address
    const recipientAddress = await DirectAddress.create(recipientPredicate.reference);
    
    const result = {
      address: recipientAddress.toJSON(),
      nonce: receiverIdentity.nonce
    };
    
    console.log('Receiving address generated:', result.address);
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Failed to generate receiving address:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Deserializes a token from JSON string
 * @param {string} tokenJsonString - The stringified JSON of the token
 */
async function deserializeToken(tokenJsonString) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    const { 
      TokenJsonSerializer,
      PredicateJsonFactory
    } = window.UnicitySDK;
    
    const tokenJson = JSON.parse(tokenJsonString);
    
    // Validate token structure
    if (!tokenJson.version || !tokenJson.state || !tokenJson.genesis) {
      throw new Error('Invalid token JSON structure');
    }
    
    // Use the deserializer directly
    const predicateFactory = new PredicateJsonFactory();
    const tokenSerializer = new TokenJsonSerializer(predicateFactory);
    const token = await tokenSerializer.deserialize(tokenJson);
    
    const result = {
      token: token.toJSON(),
      tokenId: token.id?.toString() || 'unknown',
      tokenType: token.type?.toString() || 'unknown'
    };
    
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Token deserialization failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

// ===== UTILITY CLASSES AND FUNCTIONS =====

/**
 * Utility class for test token data
 */
class TestTokenData {
  constructor(data) {
    this._data = new Uint8Array(data);
  }
  
  get bytes() {
    return new Uint8Array(this._data);
  }
  
  toJSON() {
    return Array.from(this._data).map(b => b.toString(16).padStart(2, '0')).join('');
  }
}

/**
 * Utility function to wait for inclusion proof
 */
async function waitInclusionProof(client, commitment, timeout = 30000) {
  const { InclusionProofVerificationStatus } = window.UnicitySDK;
  
  const startTime = Date.now();
  console.log('Waiting for inclusion proof...');
  
  while (Date.now() - startTime < timeout) {
    try {
      const inclusionProof = await client.getInclusionProof(commitment);
      
      if ((await inclusionProof.verify(commitment.requestId)) === InclusionProofVerificationStatus.OK) {
        console.log('Inclusion proof verified successfully');
        return inclusionProof;
      }
    } catch (err) {
      // Continue waiting if not found yet
    }
    
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  
  throw new Error('Timeout waiting for inclusion proof');
}

// ===== SDK INITIALIZATION =====

function waitForSdkAndInitialize() {
  const maxAttempts = 10;
  let attempts = 0;
  
  function checkAndInit() {
    attempts++;
    console.log(`Checking for SDK... (attempt ${attempts}/${maxAttempts})`);
    
    if (window.unicity) {
      console.log('SDK found, initializing...');
      initializeSdk();
    } else if (attempts < maxAttempts) {
      setTimeout(checkAndInit, 500);
    } else {
      console.error('SDK failed to load after maximum attempts');
      AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: 'SDK failed to load' }));
    }
  }
  
  checkAndInit();
}

// Initialize SDK when page loads
document.addEventListener('DOMContentLoaded', function() {
  waitForSdkAndInitialize();
});

// Also try to initialize immediately in case DOM is already loaded
if (document.readyState === 'complete' || document.readyState === 'interactive') {
  waitForSdkAndInitialize();
}


// ===== BACKWARD COMPATIBILITY WRAPPER FUNCTIONS =====

/**
 * Wrapper for createTransfer - generates address and prepares transfer
 */
async function createTransfer(senderIdentityJson, receiverIdentityJson, tokenJson) {
  try {
    // Parse to get token ID and type
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    const tokenObj = parsedTokenData.token || parsedTokenData;
    
    // Generate receiving address
    const { HexConverter, TokenId, TokenType, SigningService, MaskedPredicate, DirectAddress, HashAlgorithm } = window.UnicitySDK;
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const tokenId = new TokenId(HexConverter.decode(tokenObj.id));
    const tokenType = new TokenType(HexConverter.decode(tokenObj.type));
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(tokenId, tokenType, receiverSigningService, HashAlgorithm.SHA256, receiverNonce);
    const recipientAddress = await DirectAddress.create(recipientPredicate.reference);
    
    // Prepare online transfer
    await prepareTransfer(senderIdentityJson, recipientAddress.toJSON(), tokenJson, false);
  } catch (e) {
    console.error('createTransfer failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Wrapper for finishTransfer
 */
async function finishTransfer(receiverIdentityJson, transferJson) {
  return finalizeReceivedTransaction(receiverIdentityJson, transferJson);
}

/**
 * Wrapper for createOfflineTransferPackage
 */
async function createOfflineTransferPackage(senderIdentityJson, recipientAddress, tokenJson) {
  return prepareTransfer(senderIdentityJson, recipientAddress, tokenJson, true);
}

/**
 * Wrapper for completeOfflineTransfer
 */
async function completeOfflineTransfer(receiverIdentityJson, offlineTransactionJson) {
  return finalizeReceivedTransaction(receiverIdentityJson, offlineTransactionJson);
}

/**
 * Wrapper for generateReceivingAddressForOfflineTransfer
 */
async function generateReceivingAddressForOfflineTransfer(tokenIdString, tokenTypeString, receiverIdentityJson) {
  return generateReceivingAddress(tokenIdString, tokenTypeString, receiverIdentityJson);
}

// Make functions available globally for Android to call
window.generateIdentity = generateIdentity;
window.mintToken = mintToken;
window.deserializeToken = deserializeToken;
window.prepareTransfer = prepareTransfer;
window.finalizeReceivedTransaction = finalizeReceivedTransaction;
window.generateReceivingAddress = generateReceivingAddress;

// Backward compatibility
window.createTransfer = createTransfer;
window.finishTransfer = finishTransfer;
window.createOfflineTransferPackage = createOfflineTransferPackage;
window.completeOfflineTransfer = completeOfflineTransfer;
window.generateReceivingAddressForOfflineTransfer = generateReceivingAddressForOfflineTransfer;