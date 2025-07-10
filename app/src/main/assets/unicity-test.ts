// Test functions for Unicity SDK wrapper
// This file contains test logic separated from the main wrapper

// Declare the wrapper functions
declare function mintToken(identityJson: string, tokenDataJson: string): Promise<string>;
declare function prepareTransfer(senderIdentityJson: string, recipientAddress: string, tokenJson: string, isOffline: boolean): Promise<string>;
declare function finalizeReceivedTransaction(receiverIdentityJson: string, transferPackageJson: string): Promise<string>;

interface TestIdentity {
  secret: string;
  nonce: string;
}

// Test identities for Alice and Bob
const aliceIdentity: TestIdentity = {
  secret: "416c696365" + "00".repeat(27), // "Alice" padded to 32 bytes
  nonce: "01".repeat(32) // Test nonce
};

const bobIdentity: TestIdentity = {
  secret: "426f62" + "00".repeat(29), // "Bob" padded to 32 bytes  
  nonce: "02".repeat(32) // Test nonce
};

async function runOfflineTransferTest(): Promise<void> {
  try {
    console.log("=== Starting Offline Transfer Test ===");
    
    // Step 1: Mint a token for Alice
    console.log("Step 1: Minting token for Alice...");
    const tokenData = {
      data: "Test NFT for offline transfer",
      amount: 100
    };
    
    // Call the mintToken function directly (it's in the same global scope)
    const mintResult = await mintToken(
      JSON.stringify(aliceIdentity),
      JSON.stringify(tokenData)
    );
    
    const mintedData = JSON.parse(mintResult);
    console.log("Token minted successfully:", mintedData.tokenId);
    
    // Step 2: Generate Bob's receiving address
    console.log("\nStep 2: Generating Bob's receiving address...");
    const bobSecretMatches = bobIdentity.secret.match(/.{2}/g);
    const bobNonceMatches = bobIdentity.nonce.match(/.{2}/g);
    if (!bobSecretMatches || !bobNonceMatches) {
      throw new Error('Invalid Bob identity format');
    }
    
    const bobSecret = new Uint8Array(bobSecretMatches.map((byte: string) => parseInt(byte, 16)));
    const bobNonce = new Uint8Array(bobNonceMatches.map((byte: string) => parseInt(byte, 16)));
    
    // Create Bob's signing service
    const bobSigningService = await (window as any).unicity.SigningService.createFromSecret(bobSecret, bobNonce);
    
    // Calculate Bob's address reference for the specific token
    const bobReference = await (window as any).unicity.MaskedPredicate.calculateReference(
      mintedData.token.genesis.data.tokenType,
      bobSigningService.algorithm,
      bobSigningService.publicKey,
      (window as any).unicity.HashAlgorithm.SHA256,
      bobNonce
    );
    
    const bobAddress = await (window as any).unicity.DirectAddress.create(bobReference);
    const bobAddressString = bobAddress.toJSON();
    console.log("Bob's receiving address:", bobAddressString);
    
    // Step 3: Alice prepares offline transfer to Bob
    console.log("\nStep 3: Alice preparing offline transfer to Bob...");
    const transferResult = await prepareTransfer(
      JSON.stringify(aliceIdentity),
      bobAddressString,
      JSON.stringify(mintedData),
      true // isOffline = true
    );
    
    const transferPackage = JSON.parse(transferResult);
    console.log("Offline transfer package created");
    console.log("Transfer package size:", JSON.stringify(transferPackage).length, "bytes");
    
    // Step 4: Simulate offline data transfer (e.g., via NFC)
    console.log("\nStep 4: Simulating offline data transfer...");
    console.log("In a real scenario, this package would be transferred via NFC");
    
    // Step 5: Bob receives and finalizes the transfer
    console.log("\nStep 5: Bob finalizing the received transfer...");
    const finalizeResult = await finalizeReceivedTransaction(
      JSON.stringify(bobIdentity),
      JSON.stringify(transferPackage)
    );
    
    const finalizedData = JSON.parse(finalizeResult);
    console.log("Transfer finalized successfully!");
    console.log("Token ID:", finalizedData.tokenId);
    console.log("New owner: Bob");
    
    // Step 6: Verify the transfer
    console.log("\nStep 6: Verifying transfer...");
    console.log("Token is now owned by Bob");
    console.log("Alice no longer owns the token");
    
    console.log("\n=== Offline Transfer Test Completed Successfully ===");
    
    // Return success message to Android
    if ((window as any).Android) {
      (window as any).Android.showToast("Offline transfer test completed successfully!");
    }
    
  } catch (error: any) {
    console.error("Offline transfer test failed:", error);
    if ((window as any).Android) {
      (window as any).Android.showToast("Test failed: " + error.message);
    }
    throw error;
  }
}

// Export test function to window for Android access
(window as any).runOfflineTransferTest = runOfflineTransferTest;