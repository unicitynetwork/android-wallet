// app/src/main/assets/unicity-wrapper.js
console.log('========== UNICITY WRAPPER LOADED ==========');

// Global variables for SDK components
let sdkClient = null;
let offlineClient = null;
const AGGREGATOR_URL = 'https://gateway-test.unicity.network';
console.log('🌐 AGGREGATOR URL:', AGGREGATOR_URL);

/**
 * Initializes the Unicity SDK client
 */
function initializeSdk() {
  try {
    // Check what's available - bundled SDK is at window.unicity
    console.log('window.unicity available:', !!window.unicity);
    console.log('window.UnicitySDK available:', !!window.UnicitySDK);
    console.log('window keys:', Object.keys(window).filter(key => key.toLowerCase().includes('uni')));
    
    // Use the bundled SDK
    const sdk = window.unicity || window.UnicitySDK;
    if (!sdk) {
      console.error('No SDK found. Available window properties:', Object.keys(window).slice(0, 20));
      throw new Error('Unicity SDK not found');
    }
    
    // For compatibility, make it available as UnicitySDK too
    window.UnicitySDK = sdk;
    
    console.log('SDK available classes:', Object.keys(sdk || {}));
    console.log('AggregatorClient:', typeof sdk.AggregatorClient);
    console.log('StateTransitionClient:', typeof sdk.StateTransitionClient);
    console.log('OfflineStateTransitionClient:', typeof sdk.OfflineStateTransitionClient);
    
    const { AggregatorClient, StateTransitionClient, OfflineStateTransitionClient } = sdk;
    if (!AggregatorClient || !StateTransitionClient) {
      throw new Error('AggregatorClient or StateTransitionClient not found in SDK');
    }
    
    console.log('🔗 Creating AggregatorClient with URL:', AGGREGATOR_URL);
    const aggregatorClient = new AggregatorClient(AGGREGATOR_URL);
    sdkClient = new StateTransitionClient(aggregatorClient);
    
    if (OfflineStateTransitionClient) {
      offlineClient = new OfflineStateTransitionClient(aggregatorClient);
      console.log('Offline client initialized successfully');
    } else {
      console.warn('OfflineStateTransitionClient not available - offline transfers will not work');
    }
    console.log('SDK initialized successfully');
    // Test offline classes availability
    const offlineClassesAvailable = !!(sdk.OfflineStateTransitionClient && sdk.OfflineTransaction && sdk.OfflineCommitment);
    console.log('Offline classes available:', offlineClassesAvailable);
    console.log('OfflineTransaction:', typeof sdk.OfflineTransaction);
    console.log('OfflineCommitment:', typeof sdk.OfflineCommitment);
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      data: 'SDK initialized',
      offlineSupport: offlineClassesAvailable
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
 * Generates a receiving address for a specific token type using receiver's identity
 * This is used by the receiver to create an address that the sender can use for offline transfers
 * @param {string} tokenIdJson - The token ID to receive
 * @param {string} tokenTypeJson - The token type to receive  
 * @param {string} receiverIdentityJson - The receiver's identity
 */
async function generateReceivingAddressForOfflineTransfer(tokenIdString, tokenTypeString, receiverIdentityJson) {
  try {
    if (!sdkClient) {
      throw new Error('SDK not initialized');
    }
    
    console.log('Generating receiving address for offline transfer...');
    
    const { 
      SigningService, 
      MaskedPredicate,
      DirectAddress,
      TokenId,
      TokenType,
      HashAlgorithm
    } = window.UnicitySDK;
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    
    // TokenId and TokenType expect Uint8Array, decode from hex strings
    const { HexConverter } = window.UnicitySDK;
    const tokenId = new TokenId(HexConverter.decode(tokenIdString));
    const tokenType = new TokenType(HexConverter.decode(tokenTypeString));
    
    // Convert receiver identity
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Create receiver's signing service
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    
    // Create receiver's predicate for this token
    const receiverPredicate = await MaskedPredicate.create(
      tokenId,
      tokenType,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Create address from predicate reference
    const receivingAddress = await DirectAddress.create(receiverPredicate.reference);
    
    const result = {
      address: receivingAddress.toJSON(),
      predicate: receiverPredicate.toJSON(),
      identity: receiverIdentity
    };
    
    console.log('Receiving address generated successfully:', receivingAddress.toJSON());
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Failed to generate receiving address:', e);
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
      
      // Wait a bit for initialization
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      if (!sdkClient) {
        throw new Error('SDK failed to initialize');
      }
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
      DataHasher,
      DataHash,
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
    console.log('Coin data created:', typeof coinData);
    
    // Create token data
    const testTokenData = new TestTokenData(new TextEncoder().encode(tokenData.data || 'Unicity token'));
    console.log('Test token data created:', typeof testTokenData);
    console.log('Test token data properties:', Object.keys(testTokenData));
    
    // Create the token state
    const tokenState = await TokenState.create(predicate, new TextEncoder().encode(tokenData.stateData || 'Token state data'));
    console.log('Token state created:', typeof tokenState);
    
    // Create address for minting
    const address = await DirectAddress.create(predicate.reference);
    console.log('Address created:', typeof address);
    
    // Submit mint transaction - using MintTransactionData object
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const stateData = new Uint8Array();  // Empty state data as in documentation
    
    console.log('Creating mint transaction data...');
    
    // Declare variables outside try-catch for proper scope
    let mintCommitment;
    let inclusionProof;
    
    try {
      // Use the exact pattern from SDK documentation
      const stateHash = await new DataHasher(HashAlgorithm.SHA256).update(stateData).digest();
      console.log('State hash created successfully:', typeof stateHash);
      
      // Check if MintTransactionData is available
      const { MintTransactionData } = window.UnicitySDK;
      if (!MintTransactionData) {
        throw new Error('MintTransactionData not available in SDK');
      }
      
      console.log('Creating MintTransactionData...');
      console.log('Parameters:');
      console.log('- tokenId:', typeof tokenId, tokenId);
      console.log('- tokenType:', typeof tokenType, tokenType);
      console.log('- testTokenData:', typeof testTokenData, testTokenData);
      console.log('- coinData:', typeof coinData, coinData);
      console.log('- address:', typeof address, address.toJSON());
      console.log('- salt:', typeof salt, salt);
      console.log('- stateHash:', typeof stateHash, stateHash);
      
      // Create MintTransactionData using the static create method
      // MintTransactionData.create(tokenId, tokenType, tokenData, coinData, recipient, salt, dataHash, reason)
      const mintTransactionData = await MintTransactionData.create(
        tokenId,
        tokenType,
        testTokenData.bytes,  // Use the raw bytes
        coinData,
        address.toJSON(),     // Pass the address as a string
        salt,
        stateHash,
        null  // reason (null for new mint)
      );
      
      console.log('MintTransactionData created:', typeof mintTransactionData);
      console.log('MintTransactionData hash:', mintTransactionData.hash);
      
      console.log('Submitting mint transaction...');
      console.log('🌐 About to call submitMintTransaction with URL:', AGGREGATOR_URL);
      console.log('🌐 SDK client type:', typeof sdkClient);
      mintCommitment = await sdkClient.submitMintTransaction(mintTransactionData);
      
      console.log('Mint commitment created successfully');
      console.log('Waiting for inclusion proof...');
      inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
    } catch (mintError) {
      console.error('Mint transaction failed:', mintError);
      console.error('Error details:', mintError.message);
      console.error('Stack trace:', mintError.stack);
      
      if (mintError.message.includes('Failed to fetch')) {
        console.error('🌐 NETWORK ERROR: Unable to connect to aggregator');
        console.error('🌐 Aggregator URL:', AGGREGATOR_URL);
        console.error('🌐 This could be due to:');
        console.error('   - Network connectivity issues');
        console.error('   - Aggregator server being down');
        console.error('   - CORS/security policy blocking the request');
        console.error('   - Firewall/proxy blocking HTTPS requests');
      }
      
      throw new Error(`Mint transaction failed: ${mintError.message}`);
    }
    
    console.log('Creating transaction...');
    console.log('mintCommitment type:', typeof mintCommitment, mintCommitment);
    console.log('inclusionProof type:', typeof inclusionProof, inclusionProof);
    console.log('mintCommitment properties:', Object.keys(mintCommitment || {}));
    console.log('inclusionProof properties:', Object.keys(inclusionProof || {}));
    
    // Try original approach first - the SDK example shows passing commitment directly
    console.log('Attempting createTransaction with commitment directly...');
    let mintTransaction;
    try {
      mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
      console.log('Transaction created successfully with direct approach');
    } catch (directError) {
      console.error('Direct approach failed:', directError.message);
      console.log('Trying destructured approach...');
      // Alternative: Try destructured approach if direct fails
      mintTransaction = await sdkClient.createTransaction(
        { requestId: mintCommitment.requestId, transactionData: mintCommitment.transactionData },
        inclusionProof
      );
      console.log('Transaction created successfully with destructured approach');
    }
    
    // Create the final token - CORRECTED CONSTRUCTOR
    console.log('Creating final token with correct parameters...');
    console.log('- tokenState:', typeof tokenState);
    console.log('- mintTransaction (genesis):', typeof mintTransaction);
    
    const token = new Token(
      tokenState,        // 1. state: TokenState
      mintTransaction,   // 2. genesis: MT (mint transaction)
      [],               // 3. transactions: Transaction<TransactionData>[] (empty for new token)
      [],               // 4. nametagTokens: NameTagToken[] (empty for new token)
      "2.0"             // 5. version: string (optional, defaults to TOKEN_VERSION)
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
 * Alice creates a transfer package (token + signed transaction) and submits it to the network
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
      PredicateJsonFactory,
      TransactionData,
      HashAlgorithm,
      DataHasher
    } = window.UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenDeserializer = new TokenJsonDeserializer(new PredicateJsonFactory());
    const token = await tokenDeserializer.deserialize(parsedTokenData.token || parsedTokenData);
    
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
 * Creates an offline transfer package that can be transmitted without network
 * @param {string} senderIdentityJson - The sender's identity
 * @param {string} recipientAddress - The recipient's address
 * @param {string} tokenJson - The token to transfer (must be self-contained with transactions)
 * @returns {Promise<string>} - JSON string containing the offline transaction
 */
async function createOfflineTransferPackage(senderIdentityJson, recipientAddress, tokenJson) {
  try {
    if (!offlineClient) {
      throw new Error('Offline SDK client not initialized');
    }
    
    console.log('Creating offline transfer package...');
    
    const senderIdentity = JSON.parse(senderIdentityJson);
    const parsedTokenData = typeof tokenJson === 'string' ? JSON.parse(tokenJson) : tokenJson;
    
    const { 
      SigningService, 
      TokenJsonDeserializer,
      PredicateJsonFactory,
      TransactionData,
      HashAlgorithm,
      DataHasher,
      OfflineTransaction
    } = window.UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate the Token object from JSON - this should include complete transaction history
    const tokenDeserializer = new TokenJsonDeserializer(new PredicateJsonFactory());
    const token = await tokenDeserializer.deserialize(parsedTokenData);
    
    console.log('Token reconstructed:', token.id ? token.id.toJSON() : 'undefined', 'transactions:', token.transactions?.length || 0);
    
    // Create sender signing service
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    // Create transaction data for ownership transfer
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('offline ownership transfer'));
    const dataHash = await dataHasher.digest();
    
    const transactionData = await TransactionData.create(
      token.state,  // Current token state with current owner predicate
      recipientAddress,  // New owner's address (from their predicate reference)
      salt,
      dataHash,
      new TextEncoder().encode('ownership transfer'),
      token.nametagTokens
    );
    
    // Create offline commitment (no network call)
    const offlineCommitment = await offlineClient.createOfflineCommitment(
      transactionData,
      senderSigningService
    );
    
    // Create offline transaction package containing the commitment and the complete token
    const offlineTransaction = new OfflineTransaction(offlineCommitment, token);
    
    // Use BigInt-safe serialization for offline transfer
    const offlineTransactionJsonString = offlineTransaction.toJSONString();
    
    console.log('Offline transfer package created successfully, size:', offlineTransactionJsonString.length);
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      data: offlineTransactionJsonString
    }));
  } catch (e) {
    console.error('Offline transfer package creation failed:', e);
    console.error('Error stack:', e.stack);
    AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: e.message }));
  }
}

/**
 * Completes an offline transfer received from another device
 * @param {string} receiverIdentityJson - The receiver's identity
 * @param {string} offlineTransactionJson - The offline transaction package
 * @returns {Promise<string>} - JSON string containing the updated token
 */
async function completeOfflineTransfer(receiverIdentityJson, offlineTransactionJson) {
  try {
    if (!offlineClient || !sdkClient) {
      throw new Error('SDK clients not initialized');
    }
    
    console.log('Starting offline transfer completion...');
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const { 
      SigningService, 
      MaskedPredicate,
      TokenState,
      HashAlgorithm,
      OfflineTransaction
    } = window.UnicitySDK;
    
    // Deserialize the offline transaction using BigInt-safe method
    console.log('Deserializing offline transaction...');
    const offlineTransaction = await OfflineTransaction.fromJSONString(offlineTransactionJson);
    
    console.log('Offline transaction deserialized:');
    console.log('- Token ID:', offlineTransaction.token.id ? offlineTransaction.token.id.toJSON() : 'undefined');
    console.log('- Token type:', offlineTransaction.token.type ? offlineTransaction.token.type.toJSON() : 'undefined');
    console.log('- Current transactions:', offlineTransaction.token.transactions?.length || 0);
    
    // Step 1: Submit the offline transaction to the network
    console.log('Submitting offline transaction to network...');
    const transaction = await offlineClient.submitOfflineTransaction(offlineTransaction.commitment);
    
    console.log('Transaction submitted successfully, request ID:', transaction.requestId ? transaction.requestId.toJSON() : 'undefined');
    
    // Convert receiver identity
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Step 2: Create receiver's predicate (new owner)
    console.log('Creating receiver predicate for new ownership...');
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      offlineTransaction.token.id,
      offlineTransaction.token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    console.log('Receiver predicate created:', recipientPredicate.reference ? recipientPredicate.reference.toJSON() : 'undefined');
    
    // Step 3: Create new token state with receiver as owner
    const newStateData = new TextEncoder().encode('ownership transferred');
    const newTokenState = await TokenState.create(recipientPredicate, newStateData);
    
    // Step 4: Finish the transaction - this updates the Token object with the new transaction
    console.log('Finishing transaction to update token ownership...');
    const updatedToken = await sdkClient.finishTransaction(
      offlineTransaction.token,  // Original token with history
      newTokenState,             // New state with receiver as owner
      transaction               // The completed transaction
    );
    
    console.log('Offline transfer completed successfully!');
    console.log('- Updated token transactions:', updatedToken.transactions?.length || 0);
    console.log('- New owner predicate:', updatedToken.state?.predicate?.reference ? updatedToken.state.predicate.reference.toJSON() : 'undefined');
    
    // Return the updated token as JSON - this now contains the complete transaction history
    const result = {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
    
    AndroidBridge.postMessage(JSON.stringify({ status: 'success', data: JSON.stringify(result) }));
  } catch (e) {
    console.error('Offline transfer completion failed:', e);
    console.error('Error stack:', e.stack);
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
      PredicateJsonFactory,
      Transaction,
      TokenState,
      HashAlgorithm
    } = window.UnicitySDK;
    
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
    const tokenFactory = new TokenFactory(new PredicateJsonFactory());
    const tokenDataFromJSON = (data) => {
      if (typeof data !== 'string') {
        throw new Error('Invalid token data');
      }
      return Promise.resolve({ toCBOR: () => HexConverter.decode(data), toJSON: () => data });
    };
    const token = await tokenFactory.create(transferPackage.token, tokenDataFromJSON);
    
    // Bob receives the commitment from Alice and gets the inclusion proof
    const { Commitment } = window.UnicitySDK;
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
 * AUTOMATED OFFLINE TRANSFER TEST - Tests the new offline transfer API
 * This simulates the complete offline transfer flow without NFC
 */
async function runAutomatedOfflineTransferTest() {
  try {
    console.log('🚀 TEST FUNCTION CALLED - runAutomatedOfflineTransferTest()');
    console.log('🌐 CURRENT AGGREGATOR URL:', AGGREGATOR_URL);
    console.log('========================================');
    console.log('STARTING AUTOMATED OFFLINE TRANSFER TEST');
    console.log('========================================');
    
    if (!sdkClient || !offlineClient) {
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
    
    // Step 3: Alice mints a token
    console.log('Step 3: Alice mints a token...');
    console.log('🌐 About to mint token using URL:', AGGREGATOR_URL);
    const aliceToken = await mintTokenDirectly(aliceIdentity, 'OfflineTestToken', '150', 'Automated offline test token');
    console.log('Alice minted token successfully, ID:', aliceToken.tokenId ? aliceToken.tokenId.toJSON() : 'undefined');
    
    // Step 4: Bob generates receiving address
    console.log('Step 4: Bob generates receiving address...');
    const bobReceivingAddress = await generateReceivingAddressDirectly(aliceToken.tokenId, aliceToken.tokenType, bobIdentity);
    console.log('Bob generated address:', bobReceivingAddress);
    
    // Step 5: Alice creates OFFLINE transfer package (no network submission)
    console.log('Step 5: Alice creates offline transfer package...');
    const offlineTransferPackage = await createOfflineTransferDirectly(aliceIdentity, bobReceivingAddress, aliceToken);
    console.log('Alice created offline transfer package with keys:', Object.keys(offlineTransferPackage));
    
    // Step 6: Bob completes the offline transfer (submits to network when available)
    console.log('Step 6: Bob completes the offline transfer...');
    const completedOfflineTransfer = await completeOfflineTransferDirectly(bobIdentity, offlineTransferPackage);
    console.log('Bob completed offline transfer successfully!');
    console.log('Bob\'s token transactions count:', completedOfflineTransfer.token.transactions.length);
    
    console.log('========================================');
    console.log('AUTOMATED OFFLINE TRANSFER TEST COMPLETED SUCCESSFULLY!');
    console.log('Token successfully transferred from Alice to Bob using offline method');
    console.log('========================================');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      message: 'Automated offline transfer test completed successfully',
      data: {
        aliceTokenId: aliceToken.id,
        bobTokenId: completedOfflineTransfer.token.id,
        offlineTransferPackageKeys: Object.keys(offlineTransferPackage),
        bobTransactionCount: completedOfflineTransfer.token.transactions.length
      }
    }));
    
  } catch (error) {
    console.error('========================================');
    console.error('AUTOMATED OFFLINE TRANSFER TEST FAILED!');
    console.error('Error:', error.message);
    console.error('Stack:', error.stack);
    console.error('========================================');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'error', 
      message: `Automated offline transfer test failed: ${error.message}`,
      error: error.stack
    }));
  }
}

/**
 * Direct offline transfer creation that doesn't use Android bridge
 */
async function createOfflineTransferDirectly(senderIdentity, recipientAddress, tokenJson) {
  try {
    if (!offlineClient) {
      throw new Error('Offline SDK client not initialized');
    }
    
    // Get the actual token object from our token data structure
    const token = tokenJson.token;
    
    const { 
      SigningService, 
      TransactionData,
      HashAlgorithm,
      DataHasher,
      OfflineTransaction,
      HexConverter
    } = window.UnicitySDK;
    
    // Create the transfer
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const senderSigningService = await SigningService.createFromSecret(senderSecret, senderNonce);
    
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const dataHasher = new DataHasher(HashAlgorithm.SHA256);
    dataHasher.update(new TextEncoder().encode('offline token transfer'));
    const dataHash = await dataHasher.digest();
    
    const transactionData = await TransactionData.create(
      token.state,
      recipientAddress,
      salt,
      dataHash,
      new TextEncoder().encode('offline transfer'),
      token.nametagTokens
    );
    
    // Create offline commitment (no network call)
    const offlineCommitment = await offlineClient.createOfflineCommitment(
      transactionData,
      senderSigningService
    );
    
    // Create offline transaction package
    const offlineTransaction = new OfflineTransaction(offlineCommitment, token);
    
    // Use the new SDK method designed specifically for transfer/storage
    // This properly handles BigInt serialization for NFC transfer
    return JSON.stringify(offlineTransaction.toJSON());
  } catch (e) {
  console.error(e.stack)
    console.error('createOfflineTransferDirectly failed:', e);
    throw e;
  }
}

/**
 * Direct offline transfer completion that doesn't use Android bridge
 */
async function completeOfflineTransferDirectly(receiverIdentity, offlineTransactionJson) {
  try {
    if (!offlineClient || !sdkClient) {
      throw new Error('SDK clients not initialized');
    }
    
    const { 
      SigningService, 
      MaskedPredicate,
      TokenState,
      HashAlgorithm,
      OfflineTransaction
    } = window.UnicitySDK;
    
    console.log('Completing offline transfer directly...');
    
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Use the new SDK method designed specifically for transfer/storage deserialization
    // This properly handles BigInt deserialization from NFC transfer
    const offlineTransaction = await OfflineTransaction.fromJSONString(offlineTransactionJson);
    
    // Submit the offline transaction to the network
    console.log('Submitting offline transaction to network...');
    const transaction = await offlineClient.submitOfflineTransaction(offlineTransaction.commitment);
    
    // Recreate receiver's predicate
    const receiverSigningService = await SigningService.createFromSecret(receiverSecret, receiverNonce);
    const recipientPredicate = await MaskedPredicate.create(
      offlineTransaction.token.id,
      offlineTransaction.token.type,
      receiverSigningService,
      HashAlgorithm.SHA256,
      receiverNonce
    );
    
    // Complete the transaction
    const updatedToken = await sdkClient.finishTransaction(
      offlineTransaction.token,
      await TokenState.create(recipientPredicate, new TextEncoder().encode('offline token transfer')),
      transaction
    );
    
    return {
      token: updatedToken.toJSON(),
      identity: receiverIdentity
    };
  } catch (e) {
    console.error('completeOfflineTransferDirectly failed:', e);
    throw e;
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
  } = window.UnicitySDK;
  
  const secret = new Uint8Array(identity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  const nonce = new Uint8Array(identity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
  
  const tokenId = TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
  const tokenType = TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
  const testTokenData = new TestTokenData(new TextEncoder().encode(customData));
  const coinData = TokenCoinData.create([[new CoinId(crypto.getRandomValues(new Uint8Array(32))), BigInt(amount)]]);
  
  const signingService = await SigningService.createFromSecret(secret, nonce);
  const predicate = await MaskedPredicate.create(tokenId, tokenType, signingService, HashAlgorithm.SHA256, nonce);
  const tokenState = await TokenState.create(predicate, new TextEncoder().encode('mint token'));
  
  const dataHasher = new DataHasher(HashAlgorithm.SHA256);
  dataHasher.update(new TextEncoder().encode('mint token'));
  const dataHash = await dataHasher.digest();
  
  // Create MintTransactionData object
  const { MintTransactionData } = window.UnicitySDK;
  const address = await DirectAddress.create(predicate.reference);
  const mintTransactionData = await MintTransactionData.create(
    tokenId,
    tokenType,
    testTokenData.bytes,
    coinData,
    address,
    crypto.getRandomValues(new Uint8Array(32)),
    dataHash,
    null
  );
  
  const mintCommitment = await sdkClient.submitMintTransaction(mintTransactionData);
  
  const inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
  const mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
  
  const token = new Token(tokenState, mintTransaction, [], [], "2.0");
  
  // Return token in a format suitable for offline transfers
  // Instead of using toJSON() which breaks BigInt values, we return the token object directly
  return {
    token: token,
    tokenState: tokenState,
    mintTransaction: mintTransaction,
    tokenId: tokenId,
    tokenType: tokenType,
    predicate: predicate
  };
}

/**
 * Direct address generation that doesn't use Android bridge
 */
async function generateReceivingAddressDirectly(tokenId, tokenType, receiverIdentity) {
  const { 
    SigningService, 
    MaskedPredicate, 
    DirectAddress,
    TokenId,
    TokenType,
    HashAlgorithm,
    HexConverter
  } = window.UnicitySDK;
  
  const receiverSecret = new Uint8Array(HexConverter.decode(receiverIdentity.secret));
  const receiverNonce = new Uint8Array(HexConverter.decode(receiverIdentity.nonce));
  
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
      PredicateJsonFactory,
      TransactionData,
      DataHasher,
      HexConverter
    } = window.UnicitySDK;
    
    // Generate Bob's address first
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // TokenId and TokenType expect Uint8Array, decode from hex strings
    const tokenId = new TokenId(HexConverter.decode(tokenObj.id));
    const tokenType = new TokenType(HexConverter.decode(tokenObj.type));
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
    
    const tokenFactory = new TokenFactory(new PredicateJsonFactory());
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
      PredicateJsonFactory,
      Commitment,
      TokenState,
      HashAlgorithm,
      HexConverter
    } = window.UnicitySDK;
    
    console.log('Completing transfer directly...');
    console.log('Transfer package:', JSON.stringify(transferPackage, null, 2));
    
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Import the token from the transfer package
    const tokenFactory = new TokenFactory(new PredicateJsonFactory());
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
    } = window.UnicitySDK;
    
    const receiverIdentity = JSON.parse(receiverIdentityJson);
    const receiverSecret = new Uint8Array(receiverIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const receiverNonce = new Uint8Array(receiverIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Create receiver's predicate
    // TokenId and TokenType expect Uint8Array, decode from hex strings
    const tokenId = new TokenId(HexConverter.decode(tokenObj.id));
    const tokenType = new TokenType(HexConverter.decode(tokenObj.type));
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
    } = window.UnicitySDK;
    
    // Convert hex strings back to Uint8Array
    const senderSecret = new Uint8Array(senderIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const senderNonce = new Uint8Array(senderIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    // Recreate token from JSON
    const tokenFactory = new TokenFactory(new PredicateJsonFactory());
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
  
  // Add bytes property required by SDK
  get bytes() {
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
  const { InclusionProofVerificationStatus } = window.UnicitySDK;
  
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


// Wait for the SDK to be loaded before initializing
function waitForSdkAndInitialize() {
  const maxAttempts = 10;
  let attempts = 0;
  
  function checkAndInit() {
    attempts++;
    console.log(`Attempt ${attempts}: Checking for SDK...`);
    console.log('window.unicity available:', !!window.unicity);
    
    if (window.unicity) {
      console.log('SDK found, initializing...');
      initializeSdk();
    } else if (attempts < maxAttempts) {
      console.log(`SDK not ready yet, waiting... (attempt ${attempts}/${maxAttempts})`);
      setTimeout(checkAndInit, 500); // Wait 500ms and try again
    } else {
      console.error('SDK failed to load after maximum attempts');
      AndroidBridge.postMessage(JSON.stringify({ status: 'error', message: 'SDK failed to load' }));
    }
  }
  
  checkAndInit();
}

// Initialize SDK when page loads
document.addEventListener('DOMContentLoaded', function() {
  console.log('DOMContentLoaded - waiting for SDK...');
  waitForSdkAndInitialize();
});

// Also try to initialize immediately in case DOM is already loaded
if (document.readyState === 'complete' || document.readyState === 'interactive') {
  console.log('Document already loaded - waiting for SDK...');
  waitForSdkAndInitialize();
}