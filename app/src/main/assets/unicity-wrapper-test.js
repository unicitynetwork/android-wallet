// app/src/main/assets/unicity-wrapper-test.js
console.log('========== UNICITY WRAPPER TEST LOADED ==========');

/**
 * Pure JavaScript test for offline transfers - no AndroidBridge needed
 */
async function runAutomatedOfflineTransferTest() {
  try {
    console.log('🚀 STARTING AUTOMATED OFFLINE TRANSFER TEST');
    console.log('🌐 AGGREGATOR URL:', AGGREGATOR_URL);
    
    // Ensure SDK is initialized
    if (!sdkClient) {
      console.log('Initializing SDK...');
      initializeSdk();
      await new Promise(resolve => setTimeout(resolve, 1000));
      if (!sdkClient) {
        throw new Error('SDK not initialized');
      }
    }
    
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
      MintTransactionData,
      TokenFactory,
      PredicateJsonFactory,
      TransactionData,
      Commitment,
      CommitmentJsonSerializer,
      TokenJsonSerializer
    } = window.UnicitySDK;
    
    // Step 1: Create identities
    console.log('Step 1: Creating Alice and Bob identities...');
    const aliceIdentity = {
      secret: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    
    const bobIdentity = {
      secret: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    console.log('✓ Identities created');
    
    // Step 2: Alice mints a token (direct SDK calls)
    console.log('\nStep 2: Alice minting token...');
    const aliceSecret = new Uint8Array(aliceIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const aliceNonce = new Uint8Array(aliceIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const signingService = await SigningService.createFromSecret(aliceSecret, aliceNonce);
    const tokenId = TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
    const tokenType = TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
    
    const predicate = await MaskedPredicate.create(tokenId, tokenType, signingService, HashAlgorithm.SHA256, aliceNonce);
    const coinData = TokenCoinData.create([[new CoinId(crypto.getRandomValues(new Uint8Array(32))), BigInt(150)]]);
    const testTokenData = new TestTokenData(new TextEncoder().encode('Offline test token'));
    const tokenState = await TokenState.create(predicate, null);
    const address = await DirectAddress.create(predicate.reference);
    
    const mintTransactionData = await MintTransactionData.create(
      tokenId, tokenType, testTokenData.bytes, coinData,
      address.toJSON(), crypto.getRandomValues(new Uint8Array(32)), null, null
    );
    
    console.log('Submitting mint transaction...');
    const mintCommitment = await sdkClient.submitMintTransaction(mintTransactionData);
    const inclusionProof = await waitInclusionProof(sdkClient, mintCommitment);
    const mintTransaction = await sdkClient.createTransaction(mintCommitment, inclusionProof);
    
    const aliceToken = new Token(tokenState, mintTransaction, [], [], "2.0");
    console.log('✓ Token minted, ID:', tokenId.toJSON());
    
    // Step 3: Bob generates receiving address
    console.log('\nStep 3: Bob generating receiving address...');
    const bobSecret = new Uint8Array(bobIdentity.secret.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    const bobNonce = new Uint8Array(bobIdentity.nonce.match(/.{2}/g).map(byte => parseInt(byte, 16)));
    
    const bobSigningService = await SigningService.createFromSecret(bobSecret, bobNonce);
    const bobPredicate = await MaskedPredicate.create(tokenId, tokenType, bobSigningService, HashAlgorithm.SHA256, bobNonce);
    const bobAddress = await DirectAddress.create(bobPredicate.reference);
    console.log('✓ Address generated:', bobAddress.toJSON());
    
    // Step 4: Alice creates offline transfer package
    console.log('\nStep 4: Alice creating offline transfer package...');
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const transactionData = await TransactionData.create(
      aliceToken.state,
      bobAddress.toJSON(),
      salt,
      null,
      null,
      aliceToken.nametagTokens
    );
    
    const commitment = await Commitment.create(transactionData, signingService);
    const offlinePackage = {
      commitment: CommitmentJsonSerializer.serialize(commitment),
      token: aliceToken.toJSON()
    };
    console.log('✓ Offline package created');
    console.log('  - Package size:', JSON.stringify(offlinePackage).length, 'bytes');
    console.log('  - Has commitment:', !!offlinePackage.commitment);
    console.log('  - Has token:', !!offlinePackage.token);
    
    // Step 5: Bob completes the offline transfer
    console.log('\nStep 5: Bob completing offline transfer...');
    
    // Recreate token from package
    const tokenFactory = new TokenFactory(new TokenJsonSerializer(new PredicateJsonFactory()));
    const receivedToken = await tokenFactory.create(offlinePackage.token);
    
    // Deserialize commitment
    const commitmentSerializer = new CommitmentJsonSerializer(new PredicateJsonFactory());
    const receivedCommitment = await commitmentSerializer.deserialize(
      receivedToken.id,
      receivedToken.type,
      offlinePackage.commitment
    );
    
    console.log('Submitting commitment to network...');
    await sdkClient.submitCommitment(receivedCommitment);
    
    console.log('Waiting for inclusion proof...');
    const transferInclusionProof = await waitInclusionProof(sdkClient, receivedCommitment);
    const transaction = await sdkClient.createTransaction(receivedCommitment, transferInclusionProof);
    
    // Create Bob's new token state
    const newTokenState = await TokenState.create(bobPredicate, null);
    
    // Finish the transaction
    const bobToken = await sdkClient.finishTransaction(receivedToken, newTokenState, transaction);
    
    console.log('✓ Transfer completed!');
    console.log('  - Bob now owns the token');
    console.log('  - Transaction count:', bobToken.transactions.length);
    
    console.log('\n========================================');
    console.log('OFFLINE TRANSFER TEST COMPLETED SUCCESSFULLY!');
    console.log('========================================');
    
    // Return success to AndroidBridge if it exists
    if (typeof AndroidBridge !== 'undefined') {
      AndroidBridge.postMessage(JSON.stringify({ 
        status: 'success', 
        data: JSON.stringify({
          message: 'Automated offline transfer test completed successfully',
          token: bobToken.toJSON(),
          identity: bobIdentity
        })
      }));
    }
    
    return { success: true, bobToken: bobToken.toJSON() };
    
  } catch (error) {
    console.error('\n========================================');
    console.error('OFFLINE TRANSFER TEST FAILED!');
    console.error('Error:', error.message);
    console.error('Stack:', error.stack);
    console.error('========================================');
    
    // Return error to AndroidBridge if it exists
    if (typeof AndroidBridge !== 'undefined') {
      AndroidBridge.postMessage(JSON.stringify({ 
        status: 'error', 
        message: `Test failed: ${error.message}`,
        data: '' // Android SDK expects data field even for errors
      }));
    }
    
    throw error;
  }
}

// Make test function available globally
window.runAutomatedOfflineTransferTest = runAutomatedOfflineTransferTest;