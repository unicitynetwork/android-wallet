// app/src/main/assets/unicity-wrapper.js
console.log('========== UNICITY WRAPPER LOADED ==========');

// Global variables for SDK components
let sdkClient = null;
const AGGREGATOR_URL = 'https://aggregator-test.mainnet.unicity.network';

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
    const coinId = new CoinId(crypto.getRandomValues(new Uint8Array(32)));
    const coinData = new TokenCoinData([[coinId, BigInt(tokenData.amount || 100)]]);
    
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
      requestId: mintCommitment.requestId.toJSON()
    };
    
    console.log('Real Unicity token minted successfully!');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Token minting failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Step 1 of transfer: Generate receiving address
 * Bob generates a receiving address for a specific token type and ID
 * @param {string} tokenIdHex - The token ID in hex format
 * @param {string} tokenTypeHex - The token type in hex format
 * @param {string} receiverIdentityJson - Bob's identity
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
    } = UnicitySDK;
    
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
      nonce: receiverIdentity.nonce // Bob needs to remember this for later
    };
    
    console.log('Receiving address generated:', result.address);
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Failed to generate receiving address:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Step 2 of transfer: Create transfer package
 * Alice creates a transfer package (token + signed transaction) but doesn't submit it
 * @param {string} senderIdentityJson - Alice's identity
 * @param {string} recipientAddress - Bob's receiving address (from step 1)
 * @param {string} tokenJson - The current JSON state of the token being sent
 */
async function createTransferPackage(senderIdentityJson, recipientAddress, tokenJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Creating transfer package...');
    
    const senderIdentity = JSON.parse(senderIdentityJson);
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    
    const { 
      SigningService, 
      TokenFactory,
      PredicateFactory,
      TransactionData,
      HashAlgorithm,
      DataHasher
    } = UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const token = await tokenFactory.fromJSON(parsedTokenData.token || parsedTokenData);
    
    // Create sender signing service
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    // Create transaction data
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('token transfer'));
    const dataHash = await dataHasher.digest();
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress, // Already in correct format from generateReceivingAddress
      salt,
      dataHash,
      new TextEncoder().encode('token transfer'),
      token.nametagTokens
    );
    
    // Submit transaction to get commitment but NOT the inclusion proof
    // This creates the cryptographic commitment without finalizing the transfer
    const commitment = await sdkClient.submitTransaction(transactionData, senderSigningService);
    
    // Wait for inclusion proof
    const inclusionProof = await waitInclusionProof(sdkClient, commitment);
    
    // Create transaction
    const transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    
    // Package everything Bob needs to complete the transfer
    const transferPackage = {
      token: token.toJSON(),
      transaction: transaction.toJSON(),
      commitment: commitment.requestId.toJSON()
    };
    
    console.log('Transfer package created successfully');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(transferPackage) }));
  } catch (e) {
    console.error('Transfer package creation failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Step 3 of transfer: Complete transfer
 * Bob completes the transfer using the package from Alice
 * @param {string} receiverIdentityJson - Bob's identity
 * @param {string} transferPackageJson - The transfer package from Alice
 */
async function completeTransfer(receiverIdentityJson, transferPackageJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Completing transfer...');
    console.log('Received transfer package JSON:', transferPackageJson);
    
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
    const transferPackage = JSON.parse(transferPackageJson);
    
    console.log('Parsed transfer package:', JSON.stringify(transferPackage, null, 2));
    console.log('Transfer package type:', typeof transferPackage);
    console.log('Transfer package keys:', Object.keys(transferPackage));
    console.log('Has error field:', 'error' in transferPackage);
    console.log('Has token field:', 'token' in transferPackage);
    console.log('Has transaction field:', 'transaction' in transferPackage);
    console.log('Has commitment field:', 'commitment' in transferPackage);
    
    if (transferPackage.error) {
      console.log('ERROR: Received error package instead of transfer package!');
      console.log('Error:', transferPackage.error);
      console.log('Message:', transferPackage.message);
      throw new Error(`Transfer package creation failed: ${transferPackage.message}`);
    }
    
    console.log('Token in package:', JSON.stringify(transferPackage.token, null, 2));
    console.log('Token version:', transferPackage.token?.version);
    
    // Convert hex strings back to Uint8Array
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Import the token from the transfer package
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const tokenDataFromJSON = (data) => {
      if (typeof data !== 'string') {
        throw new Error('Invalid token data');
      }
      return Promise.resolve({ toCBOR: () => HexConverter.decode(data), toJSON: () => data });
    };
    const token = await tokenFactory.create(transferPackage.token, tokenDataFromJSON);
    
    // Bob receives the commitment from Alice and gets the inclusion proof
    const { Commitment } = UnicitySDK;
    const commitment = await Commitment.fromJSON(transferPackage.commitment);
    
    console.log('Bob received commitment:', commitment.requestId.toJSON());
    
    // Bob waits for inclusion proof (Alice already submitted the transaction)
    const inclusionProof = await waitInclusionProof(sdkClient, commitment);
    
    console.log('Bob got inclusion proof');
    
    // Create the transaction with inclusion proof
    const transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    
    // Recreate receiver's predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      token.id,
      token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Complete the transaction with the recipient predicate
    const updatedToken = await sdkClient.finishTransaction(
      token,
      await TokenState.create(recipientPredicate, new TextEncoder().encode('received token')),
      transaction
    );
    
    const result = {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
    
    console.log('Transfer completed successfully!');
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Transfer completion failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}


/**
 * AUTOMATED TRANSFER TEST - Runs within the Android WebView context
 * This simulates the complete transfer flow without NFC
 */
async function runAutomatedTransferTest() {
  try {
    console.log('========================================');
    console.log('STARTING AUTOMATED TRANSFER TEST');
    console.log('========================================');
    
    if (!sdkClient) {
      initializeSdk();
      await new Promise(resolve => setTimeout(resolve, 1000)); // Wait for SDK to initialize
    }
    
    // Step 1: Create Alice's wallet (sender)
    console.log('Step 1: Creating Alice\'s wallet...');
    const aliceSecret = crypto.getRandomValues(new Uint8Array(32));
    const aliceNonce = crypto.getRandomValues(new Uint8Array(32));
    const aliceIdentity = {
      secret: Array.from(aliceSecret).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(aliceNonce).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    console.log('Alice identity created:', aliceIdentity.nonce.substring(0, 16) + '...');
    
    // Step 2: Create Bob's wallet (receiver)  
    console.log('Step 2: Creating Bob\'s wallet...');
    const bobSecret = crypto.getRandomValues(new Uint8Array(32));
    const bobNonce = crypto.getRandomValues(new Uint8Array(32));
    const bobIdentity = {
      secret: Array.from(bobSecret).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(bobNonce).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    console.log('Bob identity created:', bobIdentity.nonce.substring(0, 16) + '...');
    
    // Step 3: Alice mints a token (direct call, not through Android bridge)
    console.log('Step 3: Alice mints a token...');
    const aliceToken = await mintTokenDirectly(aliceIdentity, 'TestToken', '100', 'Automated test token');
    console.log('Alice minted token successfully, ID:', aliceToken.id);
    
    // Step 4: Bob generates receiving address
    console.log('Step 4: Bob generates receiving address...');
    const bobReceivingAddress = await generateReceivingAddressDirectly(aliceToken.id, aliceToken.type, bobIdentity);
    console.log('Bob generated address:', bobReceivingAddress);
    
    // Step 5: Alice creates transfer package
    console.log('Step 5: Alice creates transfer package...');
    const transferPackage = await createTransferDirectly(aliceIdentity, bobIdentity, aliceToken);
    console.log('Alice created transfer package with keys:', Object.keys(transferPackage));
    
    // Step 6: Bob completes the transfer
    console.log('Step 6: Bob completes the transfer...');
    const completedTransfer = await completeTransferDirectly(bobIdentity, transferPackage);
    console.log('Bob completed transfer successfully!');
    console.log('Bob\'s token transactions count:', completedTransfer.token.transactions.length);
    
    console.log('========================================');
    console.log('AUTOMATED TRANSFER TEST COMPLETED SUCCESSFULLY!');
    console.log('Token successfully transferred from Alice to Bob');
    console.log('========================================');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      message: 'Automated transfer test completed successfully',
      data: {
        aliceTokenId: aliceToken.id,
        bobTokenId: completedTransfer.token.id,
        transferPackageKeys: Object.keys(transferPackage),
        bobTransactionCount: completedTransfer.token.transactions.length
      }
    }));
    
  } catch (error) {
    console.error('========================================');
    console.error('AUTOMATED TRANSFER TEST FAILED!');
    console.error('Error:', error.message);
    console.error('Stack:', error.stack);
    console.error('========================================');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'error', 
      message: `Automated transfer test failed: ${error.message}`,
      error: error.stack
    }));
  }
}

/**
 * Direct mint function that doesn't use Android bridge callbacks
 */
async function mintTokenDirectly(identity, tokenName, amount, customData) {
  const { 
    SigningService, 
    MaskedPredicate, 
    DirectAddress,
    TokenId,
    TokenType,
    TokenCoinData,
    CoinId,
    TokenState,
    Token,
    HashAlgorithm,
    DataHasher
  } = UnicitySDK;
  
  const secret = new Uint8Array(identity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  const nonce = new Uint8Array(identity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  
  const tokenId = TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
  const tokenType = TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
  const testTokenData = new TestTokenData(new TextEncoder().encode(customData));
  const coinData = new TokenCoinData([[new CoinId(crypto.getRandomValues(new Uint8Array(32))), BigInt(amount)]]);
  
  const signingService = await SigningService.createFromSecret(secret, nonce);
  const predicate = await MaskedPredicate.create(tokenId, tokenType, signingService, HashAlgorithm.SHA256, nonce);
  const tokenState = await TokenState.create(predicate, new TextEncoder().encode('Default state'));
  
  const dataHasher = new DataHasher(HashAlgorithm.SHA256);
  dataHasher.update(new TextEncoder().encode('mint token'));
  const dataHash = await dataHasher.digest();
  
  const mintCommitment = await sdkClient.submitMintTransaction(
    await DirectAddress.create(predicate.reference),
    tokenId,
    tokenType,
    testTokenData,
    coinData,
    crypto.getRandomValues(new Uint8Array(32)),
    dataHash,
    null
  );
  
  const inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
  const mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
  
  const token = new Token(tokenId, tokenType, testTokenData, coinData, tokenState, [mintTransaction]);
  return token.toJSON();
}

/**
 * Direct address generation that doesn't use Android bridge
 */
async function generateReceivingAddressDirectly(tokenIdHex, tokenTypeHex, receiverIdentity) {
  const { 
    SigningService, 
    MaskedPredicate, 
    DirectAddress,
    TokenId,
    TokenType,
    HashAlgorithm
  } = UnicitySDK;
  
  const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  
  const tokenId = new TokenId(tokenIdHex);
  const tokenType = new TokenType(tokenTypeHex);
  
  const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
  const recipientPredicate = await MaskedPredicate.create(tokenId, tokenType, receiverSigningService, HashAlgorithm.SHA256, receiverNonce);
  const address = await DirectAddress.create(recipientPredicate.reference);
  
  return address.toJSON();
}

/**
 * Direct transfer creation that doesn't use Android bridge
 */
async function createTransferDirectly(senderIdentity, receiverIdentity, tokenJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    const tokenObj = parsedTokenData.token || parsedTokenData;
    
    const { 
      SigningService, 
      MaskedPredicate, 
      DirectAddress,
      TokenId,
      TokenType,
      HashAlgorithm,
      TokenFactory,
      PredicateFactory,
      TransactionData,
      DataHasher,
      HexConverter
    } = UnicitySDK;
    
    // Generate Bob's address first
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const tokenId = new TokenId(tokenObj.id);
    const tokenType = new TokenType(tokenObj.type);
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      tokenId,
      tokenType,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    const recipientAddress = await DirectAddress.create(recipientPredicate.reference);
    
    // Now create the transfer
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const tokenDataFromJSON = (data) => {
      if (typeof data !== 'string') {
        throw new Error('Invalid token data');
      }
      return Promise.resolve({ toCBOR: () => HexConverter.decode(data), toJSON: () => data });
    };
    const token = await tokenFactory.create(tokenObj, tokenDataFromJSON);
    
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('token transfer'));
    const dataHash = await dataHasher.digest();
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress,
      salt,
      dataHash,
      new TextEncoder().encode('token transfer'),
      token.nametagTokens
    );
    
    const commitment = await sdkClient.submitTransaction(transactionData, senderSigningService);
    
    return {
      token: token.toJSON(),
      commitment: commitment.toJSON(),
      recipientAddress: recipientAddress.toJSON()
    };
  } catch (e) {
    console.error('createTransferDirectly failed:', e);
    throw e;
  }
}

/**
 * Direct transfer completion that doesn't use Android bridge
 */
async function completeTransferDirectly(receiverIdentity, transferPackage) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    const { 
      SigningService, 
      MaskedPredicate, 
      TokenFactory,
      PredicateFactory,
      Commitment,
      TokenState,
      HashAlgorithm,
      HexConverter
    } = UnicitySDK;
    
    console.log('Completing transfer directly...');
    console.log('Transfer package:', JSON.stringify(transferPackage, null, 2));
    
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Import the token from the transfer package
    const tokenFactory = new TokenFactory(new PredicateFactory());
    const tokenDataFromJSON = (data) => {
      if (typeof data !== 'string') {
        throw new Error('Invalid token data');
      }
      return Promise.resolve({ toCBOR: () => HexConverter.decode(data), toJSON: () => data });
    };
    const token = await tokenFactory.create(transferPackage.token, tokenDataFromJSON);
    
    // Bob receives the commitment from Alice and gets the inclusion proof
    const commitment = await Commitment.fromJSON(transferPackage.commitment);
    
    console.log('Bob received commitment:', commitment.requestId.toJSON());
    
    // Bob waits for inclusion proof (Alice already submitted the transaction)
    const inclusionProof = await waitInclusionProof(sdkClient, commitment);
    
    console.log('Bob got inclusion proof');
    
    // Create the transaction with inclusion proof
    const transaction = await sdkClient.createTransaction(commitment, inclusionProof);
    
    // Recreate receiver's predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      token.id,
      token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Complete the transaction with the recipient predicate
    const updatedToken = await sdkClient.finishTransaction(
      token,
      await TokenState.create(recipientPredicate, new TextEncoder().encode('received token')),
      transaction
    );
    
    return {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
  } catch (e) {
    console.error('completeTransferDirectly failed:', e);
    throw e;
  }
}

// Keep the old functions for backward compatibility but update them to use new flow
async function createTransfer(senderIdentityJson, receiverIdentityJson, tokenJson) {
  try {
    console.log('============ CREATE TRANSFER STARTED ============');
    console.log('Using updated createTransfer for backward compatibility...');
    console.log('Sender Identity:', senderIdentityJson);
    console.log('Receiver Identity:', receiverIdentityJson);
    console.log('Token JSON:', tokenJson);
    
    // Parse the token to get ID and type
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    const tokenObj = parsedTokenData.token || parsedTokenData;
    console.log('Parsed token object:', JSON.stringify(tokenObj, null, 2));
    
    // Step 1: Generate receiving address (normally Bob would do this)
    const { 
      SigningService, 
      MaskedPredicate, 
      DirectAddress,
      TokenId,
      TokenType,
      HashAlgorithm,
      Token,
      TransactionData,
      DataHasher,
      HexConverter
    } = UnicitySDK;
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Create receiver's predicate
    const tokenId = new TokenId(tokenObj.id);
    const tokenType = new TokenType(tokenObj.type);
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      tokenId,
      tokenType,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    const recipientAddress = await DirectAddress.create(recipientPredicate.reference);
    
    // Step 2: Create transfer package inline (avoid double posting)
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    const senderIdentity = JSON.parse(senderIdentityJson);
    
    const { 
      TokenFactory,
      PredicateFactory
    } = UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenFactory = new TokenFactory(new PredicateFactory());
    // Create a simple fromJSON function for the token data
    const tokenDataFromJSON = (data) => {
      if (typeof data !== 'string') {
        throw new Error('Invalid token data');
      }
      return Promise.resolve({ toCBOR: () => HexConverter.decode(data), toJSON: () => data });
    };
    const token = await tokenFactory.create(tokenObj, tokenDataFromJSON);
    
    console.log('Token recreated successfully');
    console.log('Token ID:', token.id.toJSON());
    console.log('Token state:', token.state);
    console.log('Token state data:', token.state?.data);
    console.log('Token state predicate:', token.state?.unlockPredicate);
    console.log('Number of transactions:', token.transactions?.length);
    
    // Check if token has already been transferred
    if (token.transactions && token.transactions.length > 0) {
      console.log('Token transaction history:');
      token.transactions.forEach((tx, index) => {
        console.log(`Transaction ${index}:`, {
          type: tx.data?.type || (index === 0 ? 'mint' : 'transfer'),
          recipient: tx.data?.recipient,
          hasInclusionProof: !!tx.inclusionProof
        });
      });
      
      // Check if this is a spent token
      if (token.transactions.length > 1) {
        console.warn('WARNING: Token has been transferred before!');
        const lastTransfer = token.transactions[token.transactions.length - 1];
        console.warn('Last transfer was to:', lastTransfer.data?.recipient);
      }
    }
    
    // Create sender signing service
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    // Create transaction data
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('token transfer'));
    const dataHash = await dataHasher.digest();
    
    console.log('Creating transaction data...');
    console.log('Token state:', token.state);
    console.log('Recipient address:', recipientAddress);
    console.log('Token nametag tokens:', token.nametagTokens);
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress,
      salt,
      dataHash,
      new TextEncoder().encode('token transfer'),
      token.nametagTokens
    );
    
    // Alice submits the transaction but doesn't wait for inclusion proof
    // She sends the commitment to Bob, who will get the inclusion proof later
    const commitment = await sdkClient.submitTransaction(transactionData, senderSigningService);
    
    console.log('Transaction submitted, commitment:', commitment.requestId.toJSON());
    
    // Return the transfer package in the expected format
    const tokenData = token.toJSON();
    console.log('Token being sent:', JSON.stringify(tokenData, null, 2));
    console.log('Token version:', tokenData.version);
    
    const result = {
      token: tokenData,
      commitment: commitment.toJSON(),
      recipientAddress: recipientAddress
    };
    
    console.log('Transfer package created successfully');
    console.log('Full transfer package:', JSON.stringify(result, null, 2));
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('createTransfer failed:', e);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

async function finishTransfer(receiverIdentityJson, transferJson) {
  console.log('Using updated finishTransfer for backward compatibility...');
  return completeTransfer(receiverIdentityJson, transferJson);
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
async function waitInclusionProof(client, commitment, timeout = 30000) {
  const { InclusionProofVerificationStatus } = UnicitySDK;
  
  const startTime = Date.now();
  console.log('Waiting for inclusion proof, requestId:', commitment.requestId.toJSON());
  
  while (Date.now() - startTime < timeout) {
    try {
      console.log('Requesting inclusion proof, elapsed:', Date.now() - startTime + 'ms');
      const inclusionProof = await client.getInclusionProof(commitment);
      console.log('Got inclusion proof, verifying...');
      
      if ((await inclusionProof.verify(commitment.requestId.toBigInt())) === InclusionProofVerificationStatus.OK) {
        console.log('Inclusion proof verified successfully');
        return inclusionProof;
      }
      console.log('Inclusion proof verification failed, retrying...');
    } catch (err) {
      console.log('Inclusion proof request failed:', err.message);
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