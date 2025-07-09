// app/src/main/assets/unicity-wrapper-test.js
console.log('========== UNICITY WRAPPER TEST LOADED ==========');

/**
 * Automated test for offline transfers
 */
async function runAutomatedOfflineTransferTest() {
  try {
    console.log('🚀 STARTING AUTOMATED OFFLINE TRANSFER TEST');
    console.log('🌐 AGGREGATOR URL:', AGGREGATOR_URL);
    
    if (!sdkClient) {
      initializeSdk();
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    
    // Helper function to execute and capture response
    async function executeAndCapture(fn) {
      let capturedData = null;
      let capturedError = null;
      const originalPostMessage = AndroidBridge.postMessage;
      
      // Create a promise that will resolve when we get the response
      const responsePromise = new Promise((resolve) => {
        AndroidBridge.postMessage = function(message) {
          originalPostMessage.call(this, message); // Call original to maintain normal flow
          try {
            const response = JSON.parse(message);
            if (response.status === 'success') {
              capturedData = response.data;
              resolve();
            } else if (response.status === 'error') {
              capturedError = response.message;
              resolve();
            }
          } catch (e) {
            capturedError = e.message;
            resolve();
          }
        };
      });
      
      // Execute the function
      fn();
      
      // Wait for response
      await responsePromise;
      
      // Restore original
      AndroidBridge.postMessage = originalPostMessage;
      
      if (capturedError) {
        throw new Error(capturedError);
      }
      
      return capturedData;
    }
    
    // Step 1: Create identities
    console.log('Creating Alice and Bob identities...');
    const aliceIdentity = {
      secret: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    
    const bobIdentity = {
      secret: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join(''),
      nonce: Array.from(crypto.getRandomValues(new Uint8Array(32))).map(b => b.toString(16).padStart(2, '0')).join('')
    };
    
    // Step 2: Alice mints a token
    console.log('Alice minting token...');
    const mintData = await executeAndCapture(
      () => mintToken(JSON.stringify(aliceIdentity), JSON.stringify({ amount: 150, data: 'Test token' }))
    );
    const aliceToken = JSON.parse(mintData);
    console.log('Token minted, ID:', aliceToken.token.id);
    
    // Step 3: Bob generates receiving address
    console.log('Bob generating receiving address...');
    const addressData = await executeAndCapture(
      () => generateReceivingAddress(aliceToken.token.id, aliceToken.token.type, JSON.stringify(bobIdentity))
    );
    const { address } = JSON.parse(addressData);
    console.log('Address generated:', address);
    
    // Step 4: Alice creates offline transfer package
    console.log('Alice creating offline transfer package...');
    const offlinePackage = await executeAndCapture(
      () => prepareTransfer(JSON.stringify(aliceIdentity), address, JSON.stringify(aliceToken), true)
    );
    console.log('Offline package created');
    
    // Step 5: Bob completes the offline transfer
    console.log('Bob completing offline transfer...');
    const finalData = await executeAndCapture(
      () => finalizeReceivedTransaction(JSON.stringify(bobIdentity), offlinePackage)
    );
    const result = JSON.parse(finalData);
    
    console.log('========================================');
    console.log('OFFLINE TRANSFER TEST COMPLETED!');
    console.log('Token transferred from Alice to Bob');
    console.log('Transactions count:', result.token.transactions.length);
    console.log('========================================');
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'success', 
      message: 'Automated offline transfer test completed successfully'
    }));
    
  } catch (error) {
    console.error('OFFLINE TRANSFER TEST FAILED!');
    console.error('Error:', error.message);
    
    AndroidBridge.postMessage(JSON.stringify({ 
      status: 'error', 
      message: `Test failed: ${error.message}`
    }));
  }
}

// Make test function available globally
window.runAutomatedOfflineTransferTest = runAutomatedOfflineTransferTest;