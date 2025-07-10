// Type definitions for Android Bridge communication
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

// Type definitions for our wrapper methods
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

// Extend Window interface for Android bridge
interface Window {
  Android?: AndroidBridge;
}

// Import the SDK type definitions
/// <reference path="./unicity-sdk.d.ts" />

// Re-declare unicity since the reference path doesn't seem to work in this webpack config
declare const unicity: any;

// SDK configuration
const aggregatorUrl = "https://gateway-test.unicity.network";

// Helper function to create client
function createClient(): any {
  console.log('Creating client with aggregator URL:', aggregatorUrl);
  const aggregatorClient = new unicity.AggregatorClient(aggregatorUrl);
  return new unicity.StateTransitionClient(aggregatorClient);
}

// Core wrapper functions with TypeScript types
async function mintToken(identityJson: string, tokenDataJson: string): Promise<string> {
  try {
    const identity: IdentityJson = JSON.parse(identityJson);
    const tokenData: TokenDataJson = JSON.parse(tokenDataJson);
    
    const client = createClient();
    
    // Create identity components
    const secretMatches = identity.secret.match(/.{2}/g);
    const nonceMatches = identity.nonce.match(/.{2}/g);
    if (!secretMatches || !nonceMatches) {
      throw new Error('Invalid identity format');
    }
    const secret = new Uint8Array(secretMatches.map((byte: string) => parseInt(byte, 16)));
    const nonce = new Uint8Array(nonceMatches.map((byte: string) => parseInt(byte, 16)));
    
    // Create signing service
    const signingService = await unicity.SigningService.createFromSecret(secret, nonce);
    
    // Generate token ID and type
    const tokenId = unicity.TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
    const tokenType = unicity.TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
    
    // Create predicate and address
    const predicate = await unicity.MaskedPredicate.create(
      tokenId,
      tokenType,
      signingService,
      unicity.HashAlgorithm.SHA256,
      nonce
    );
    const address = await unicity.DirectAddress.create(predicate.reference);
    
    // Create coin data if amount is specified
    let coinData = null;
    if (tokenData.amount && tokenData.amount > 0) {
      coinData = unicity.TokenCoinData.create([
        [new unicity.CoinId(crypto.getRandomValues(new Uint8Array(32))), BigInt(tokenData.amount)]
      ]);
    }
    
    // Create mint transaction data
    const mintData = await unicity.MintTransactionData.create(
      tokenId,
      tokenType,
      new TextEncoder().encode(tokenData.data || ''),
      coinData,
      address.toJSON(),
      crypto.getRandomValues(new Uint8Array(32)),
      null,
      null
    );
    
    // Submit mint transaction
    console.log('Submitting mint transaction...');
    const commitment = await client.submitMintTransaction(mintData);
    console.log('Mint transaction submitted, waiting for inclusion proof...');
    
    // Wait for inclusion proof using SDK's built-in function
    console.log('Using SDK waitInclusionProof utility...');
    const inclusionProof = await unicity.waitInclusionProof(client, commitment);
    console.log('Inclusion proof received and verified');
    
    // Create transaction
    console.log('Creating transaction with commitment and inclusion proof...');
    const transaction = await client.createTransaction(commitment, inclusionProof);
    console.log('Transaction created successfully:', transaction);
    
    // Create token state
    const tokenState = await unicity.TokenState.create(predicate, null);
    
    // Create final token
    const token = new unicity.Token(tokenState, transaction, [], [], "2.0");
    
    // Return token data
    return JSON.stringify({
      tokenId: tokenId.toJSON(),
      token: token
    });
  } catch (error: any) {
    console.error("Mint token error:", error);
    throw new Error(`Failed to mint token: ${error.message}`);
  }
}


async function prepareTransfer(
  senderIdentityJson: string, 
  recipientAddress: string, 
  tokenJson: string, 
  isOffline: boolean = false
): Promise<string> {
  try {
    const senderIdentity: IdentityJson = JSON.parse(senderIdentityJson);
    const tokenWrapper = JSON.parse(tokenJson);
    
    // Extract the actual token from the wrapper
    const token = tokenWrapper.token;
    
    // Parse sender identity
    const secretMatches = senderIdentity.secret.match(/.{2}/g);
    const nonceMatches = senderIdentity.nonce.match(/.{2}/g);
    if (!secretMatches || !nonceMatches) {
      throw new Error('Invalid sender identity format');
    }
    const secret = new Uint8Array(secretMatches.map((byte: string) => parseInt(byte, 16)));
    const nonce = new Uint8Array(nonceMatches.map((byte: string) => parseInt(byte, 16)));
    
    // Create signing service for the sender
    const signingService = await unicity.SigningService.createFromSecret(secret, nonce);
    
    // Create token factory to reconstruct the token
    const predicateFactory = new unicity.PredicateJsonFactory();
    const tokenFactory = new unicity.TokenFactory(new unicity.TokenJsonSerializer(predicateFactory));
    const fullToken = await tokenFactory.create(token);
    
    // Create transaction data for the transfer
    const transactionData = await unicity.TransactionData.create(
      fullToken.state,
      recipientAddress,
      crypto.getRandomValues(new Uint8Array(32)), // salt
      null, // dataHash - can be added if needed
      new TextEncoder().encode(''), // message - can be customized
      fullToken.nametagTokens || []
    );
    
    // Create commitment
    const commitment = await unicity.Commitment.create(transactionData, signingService);
    
    if (isOffline) {
      // For offline transfer, serialize the commitment and token
      const transferPackage = {
        commitment: unicity.CommitmentJsonSerializer.serialize(commitment),
        token: token
      };
      
      console.log('Created offline transfer package');
      return JSON.stringify(transferPackage);
    } else {
      // For online transfer, submit to aggregator
      const client = createClient();
      const response = await client.submitCommitment(commitment);
      
      if (response.status !== unicity.SubmitCommitmentStatus.SUCCESS) {
        throw new Error(`Failed to submit transfer: ${response.status}`);
      }
      
      console.log('Transfer submitted, waiting for inclusion proof...');
      const inclusionProof = await unicity.waitInclusionProof(client, commitment);
      console.log('Inclusion proof received');
      
      // Create the transaction
      const transaction = await client.createTransaction(commitment, inclusionProof);
      
      // Return the transfer package with the transaction
      const transferPackage = {
        transaction: unicity.TransactionJsonSerializer.serialize(transaction),
        token: token,
        recipientAddress: recipientAddress
      };
      
      return JSON.stringify(transferPackage);
    }
  } catch (error: any) {
    console.error("Prepare transfer error:", error);
    throw new Error(`Failed to prepare transfer: ${error.message}`);
  }
}

async function finalizeReceivedTransaction(
  receiverIdentityJson: string, 
  transferPackageJson: string
): Promise<string> {
  try {
    const receiverIdentity: IdentityJson = JSON.parse(receiverIdentityJson);
    const transferPackage = JSON.parse(transferPackageJson);
    
    // Parse receiver identity
    const secretMatches = receiverIdentity.secret.match(/.{2}/g);
    const nonceMatches = receiverIdentity.nonce.match(/.{2}/g);
    if (!secretMatches || !nonceMatches) {
      throw new Error('Invalid receiver identity format');
    }
    const secret = new Uint8Array(secretMatches.map((byte: string) => parseInt(byte, 16)));
    const nonce = new Uint8Array(nonceMatches.map((byte: string) => parseInt(byte, 16)));
    
    // Create signing service for the receiver
    const signingService = await unicity.SigningService.createFromSecret(secret, nonce);
    
    // Create factories for deserialization
    const predicateFactory = new unicity.PredicateJsonFactory();
    const tokenFactory = new unicity.TokenFactory(new unicity.TokenJsonSerializer(predicateFactory));
    
    // Import the token
    const importedToken = await tokenFactory.create(transferPackage.token);
    
    if (transferPackage.commitment) {
      // This is an offline transfer - need to submit the commitment first
      console.log('Processing offline transfer...');
      
      // Deserialize the commitment
      const commitmentDeserializer = new unicity.CommitmentJsonSerializer(predicateFactory);
      const importedCommitment = await commitmentDeserializer.deserialize(
        importedToken.id,
        importedToken.type,
        transferPackage.commitment
      );
      
      // Submit the commitment to the aggregator
      const client = createClient();
      const response = await client.submitCommitment(importedCommitment);
      
      if (response.status !== unicity.SubmitCommitmentStatus.SUCCESS) {
        throw new Error(`Failed to submit offline transfer: ${response.status}`);
      }
      
      console.log('Offline transfer submitted, waiting for inclusion proof...');
      const inclusionProof = await unicity.waitInclusionProof(client, importedCommitment);
      console.log('Inclusion proof received');
      
      // Create the transaction
      const confirmedTx = await client.createTransaction(importedCommitment, inclusionProof);
      
      // Create receiver predicate
      const recipientPredicate = await unicity.MaskedPredicate.create(
        importedToken.id,
        importedToken.type,
        signingService,
        unicity.HashAlgorithm.SHA256,
        nonce
      );
      
      // Finish the transaction with the recipient predicate
      const updatedToken = await client.finishTransaction(
        importedToken,
        await unicity.TokenState.create(recipientPredicate, null),
        confirmedTx
      );
      
      return JSON.stringify({
        tokenId: updatedToken.id.toJSON(),
        token: updatedToken
      });
      
    } else if (transferPackage.transaction) {
      // This is an online transfer - transaction already exists
      console.log('Processing online transfer...');
      
      // Deserialize the transaction
      const transactionDeserializer = new unicity.TransactionJsonSerializer(predicateFactory);
      const importedTransaction = await transactionDeserializer.deserialize(
        importedToken.id,
        importedToken.type,
        transferPackage.transaction
      );
      
      // Create receiver predicate
      const recipientPredicate = await unicity.MaskedPredicate.create(
        importedToken.id,
        importedToken.type,
        signingService,
        unicity.HashAlgorithm.SHA256,
        nonce
      );
      
      // Finish the transaction with the recipient predicate
      const client = createClient();
      const updatedToken = await client.finishTransaction(
        importedToken,
        await unicity.TokenState.create(recipientPredicate, null),
        importedTransaction
      );
      
      return JSON.stringify({
        tokenId: updatedToken.id.toJSON(),
        token: updatedToken
      });
      
    } else {
      throw new Error('Invalid transfer package: missing commitment or transaction');
    }
  } catch (error: any) {
    console.error("Finalize received transaction error:", error);
    throw new Error(`Failed to finalize received transaction: ${error.message}`);
  }
}


// Android bridge handler with type safety
async function handleAndroidRequest(request: string): Promise<void> {
  if (!window.Android) {
    console.error("Android bridge not available");
    return;
  }
  
  let parsedRequest: AndroidRequest;
  try {
    parsedRequest = JSON.parse(request);
  } catch (error) {
    console.error("Failed to parse request:", error);
    return;
  }
  
  const { id, method, params } = parsedRequest;
  
  try {
    let result: string;
    
    switch (method) {
      case "mintToken":
        result = await mintToken(params.identityJson, params.tokenDataJson);
        break;
        
      case "prepareTransfer":
        result = await prepareTransfer(
          params.senderIdentityJson,
          params.recipientAddress,
          params.tokenJson,
          params.isOffline
        );
        break;
        
      case "finalizeReceivedTransaction":
        result = await finalizeReceivedTransaction(
          params.receiverIdentityJson,
          params.transferPackageJson
        );
        break;
        
      default:
        throw new Error(`Unknown method: ${method}`);
    }
    
    window.Android.onResult(id, result);
  } catch (error: any) {
    console.error(`Error handling ${method}:`, error);
    window.Android.onError(id, error.message || "Unknown error");
  }
}


// Export the handler for Android bridge communication
(window as any).handleAndroidRequest = handleAndroidRequest;

// Also export individual functions for testing purposes
(window as any).mintToken = mintToken;
(window as any).prepareTransfer = prepareTransfer;
(window as any).finalizeReceivedTransaction = finalizeReceivedTransaction;