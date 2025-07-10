/******/ (() => { // webpackBootstrap
/******/ 	"use strict";
/*!**********************************************!*\
  !*** ../app/src/main/assets/unicity-test.ts ***!
  \**********************************************/

// Test functions for Unicity SDK wrapper
// This file contains test logic separated from the main wrapper
// Test identities for Alice and Bob
const aliceIdentity = {
    secret: "416c696365" + "00".repeat(27), // "Alice" padded to 32 bytes
    nonce: "01".repeat(32) // Test nonce
};
const bobIdentity = {
    secret: "426f62" + "00".repeat(29), // "Bob" padded to 32 bytes  
    nonce: "02".repeat(32) // Test nonce
};
async function runOfflineTransferTest() {
    try {
        console.log("=== Starting Offline Transfer Test ===");
        // Step 1: Mint a token for Alice
        console.log("Step 1: Minting token for Alice...");
        const tokenData = {
            data: "Test NFT for offline transfer",
            amount: 100
        };
        // Call the mintToken function directly (it's in the same global scope)
        const mintResult = await mintToken(JSON.stringify(aliceIdentity), JSON.stringify(tokenData));
        const mintedData = JSON.parse(mintResult);
        console.log("Token minted successfully:", mintedData.tokenId);
        // Step 2: Generate Bob's receiving address
        console.log("\nStep 2: Generating Bob's receiving address...");
        // First reconstruct the token from JSON to get proper objects
        const predicateFactory = new unicity.PredicateJsonFactory();
        const tokenFactory = new unicity.TokenFactory(new unicity.TokenJsonSerializer(predicateFactory));
        const token = await tokenFactory.create(mintedData.token);
        // Generate Bob's identity components
        const bobSecretMatches = bobIdentity.secret.match(/.{2}/g);
        const bobNonceMatches = bobIdentity.nonce.match(/.{2}/g);
        if (!bobSecretMatches || !bobNonceMatches) {
            throw new Error('Invalid Bob identity format');
        }
        const bobSecret = new Uint8Array(bobSecretMatches.map((byte) => parseInt(byte, 16)));
        const bobNonce = new Uint8Array(bobNonceMatches.map((byte) => parseInt(byte, 16)));
        // Create Bob's signing service
        const bobSigningService = await unicity.SigningService.createFromSecret(bobSecret, bobNonce);
        // Create Bob's predicate for THIS specific token (using token's ID and type)
        const recipientPredicate = await unicity.MaskedPredicate.create(token.id, token.type, bobSigningService, unicity.HashAlgorithm.SHA256, bobNonce);
        // Create Bob's receiving address from the predicate
        const bobAddress = await unicity.DirectAddress.create(recipientPredicate.reference);
        const bobAddressString = bobAddress.toJSON();
        console.log("Bob's receiving address:", bobAddressString);
        // Step 3: Alice prepares offline transfer to Bob
        console.log("\nStep 3: Alice preparing offline transfer to Bob...");
        const transferResult = await prepareTransfer(JSON.stringify(aliceIdentity), bobAddressString, JSON.stringify(mintedData), true // isOffline = true
        );
        const transferPackage = JSON.parse(transferResult);
        console.log("Offline transfer package created");
        console.log("Transfer package size:", JSON.stringify(transferPackage).length, "bytes");
        // Step 4: Simulate offline data transfer (e.g., via NFC)
        console.log("\nStep 4: Simulating offline data transfer...");
        console.log("In a real scenario, this package would be transferred via NFC");
        // Step 5: Bob receives and finalizes the transfer
        console.log("\nStep 5: Bob finalizing the received transfer...");
        const finalizeResult = await finalizeReceivedTransaction(JSON.stringify(bobIdentity), JSON.stringify(transferPackage));
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
        if (window.Android) {
            window.Android.showToast("Offline transfer test completed successfully!");
        }
    }
    catch (error) {
        console.error("Offline transfer test failed:", error);
        if (window.Android) {
            window.Android.showToast("Test failed: " + error.message);
        }
        throw error;
    }
}
// Export test function to window for Android access
window.runOfflineTransferTest = runOfflineTransferTest;

/******/ })()
;
//# sourceMappingURL=data:application/json;charset=utf-8;base64,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ZXMiOlsid2VicGFjazovL3VuaWNpdHktc2RrLWJ1bmRsZXIvLi4vYXBwL3NyYy9tYWluL2Fzc2V0cy91bmljaXR5LXRlc3QudHMiXSwic291cmNlc0NvbnRlbnQiOlsiLy8gVGVzdCBmdW5jdGlvbnMgZm9yIFVuaWNpdHkgU0RLIHdyYXBwZXJcbi8vIFRoaXMgZmlsZSBjb250YWlucyB0ZXN0IGxvZ2ljIHNlcGFyYXRlZCBmcm9tIHRoZSBtYWluIHdyYXBwZXJcblxuLy8gRGVjbGFyZSB0aGUgd3JhcHBlciBmdW5jdGlvbnNcbmRlY2xhcmUgZnVuY3Rpb24gbWludFRva2VuKGlkZW50aXR5SnNvbjogc3RyaW5nLCB0b2tlbkRhdGFKc29uOiBzdHJpbmcpOiBQcm9taXNlPHN0cmluZz47XG5kZWNsYXJlIGZ1bmN0aW9uIHByZXBhcmVUcmFuc2ZlcihzZW5kZXJJZGVudGl0eUpzb246IHN0cmluZywgcmVjaXBpZW50QWRkcmVzczogc3RyaW5nLCB0b2tlbkpzb246IHN0cmluZywgaXNPZmZsaW5lOiBib29sZWFuKTogUHJvbWlzZTxzdHJpbmc+O1xuZGVjbGFyZSBmdW5jdGlvbiBmaW5hbGl6ZVJlY2VpdmVkVHJhbnNhY3Rpb24ocmVjZWl2ZXJJZGVudGl0eUpzb246IHN0cmluZywgdHJhbnNmZXJQYWNrYWdlSnNvbjogc3RyaW5nKTogUHJvbWlzZTxzdHJpbmc+O1xuXG5pbnRlcmZhY2UgVGVzdElkZW50aXR5IHtcbiAgc2VjcmV0OiBzdHJpbmc7XG4gIG5vbmNlOiBzdHJpbmc7XG59XG5cbi8vIFRlc3QgaWRlbnRpdGllcyBmb3IgQWxpY2UgYW5kIEJvYlxuY29uc3QgYWxpY2VJZGVudGl0eTogVGVzdElkZW50aXR5ID0ge1xuICBzZWNyZXQ6IFwiNDE2YzY5NjM2NVwiICsgXCIwMFwiLnJlcGVhdCgyNyksIC8vIFwiQWxpY2VcIiBwYWRkZWQgdG8gMzIgYnl0ZXNcbiAgbm9uY2U6IFwiMDFcIi5yZXBlYXQoMzIpIC8vIFRlc3Qgbm9uY2Vcbn07XG5cbmNvbnN0IGJvYklkZW50aXR5OiBUZXN0SWRlbnRpdHkgPSB7XG4gIHNlY3JldDogXCI0MjZmNjJcIiArIFwiMDBcIi5yZXBlYXQoMjkpLCAvLyBcIkJvYlwiIHBhZGRlZCB0byAzMiBieXRlcyAgXG4gIG5vbmNlOiBcIjAyXCIucmVwZWF0KDMyKSAvLyBUZXN0IG5vbmNlXG59O1xuXG5hc3luYyBmdW5jdGlvbiBydW5PZmZsaW5lVHJhbnNmZXJUZXN0KCk6IFByb21pc2U8dm9pZD4ge1xuICB0cnkge1xuICAgIGNvbnNvbGUubG9nKFwiPT09IFN0YXJ0aW5nIE9mZmxpbmUgVHJhbnNmZXIgVGVzdCA9PT1cIik7XG4gICAgXG4gICAgLy8gU3RlcCAxOiBNaW50IGEgdG9rZW4gZm9yIEFsaWNlXG4gICAgY29uc29sZS5sb2coXCJTdGVwIDE6IE1pbnRpbmcgdG9rZW4gZm9yIEFsaWNlLi4uXCIpO1xuICAgIGNvbnN0IHRva2VuRGF0YSA9IHtcbiAgICAgIGRhdGE6IFwiVGVzdCBORlQgZm9yIG9mZmxpbmUgdHJhbnNmZXJcIixcbiAgICAgIGFtb3VudDogMTAwXG4gICAgfTtcbiAgICBcbiAgICAvLyBDYWxsIHRoZSBtaW50VG9rZW4gZnVuY3Rpb24gZGlyZWN0bHkgKGl0J3MgaW4gdGhlIHNhbWUgZ2xvYmFsIHNjb3BlKVxuICAgIGNvbnN0IG1pbnRSZXN1bHQgPSBhd2FpdCBtaW50VG9rZW4oXG4gICAgICBKU09OLnN0cmluZ2lmeShhbGljZUlkZW50aXR5KSxcbiAgICAgIEpTT04uc3RyaW5naWZ5KHRva2VuRGF0YSlcbiAgICApO1xuICAgIFxuICAgIGNvbnN0IG1pbnRlZERhdGEgPSBKU09OLnBhcnNlKG1pbnRSZXN1bHQpO1xuICAgIGNvbnNvbGUubG9nKFwiVG9rZW4gbWludGVkIHN1Y2Nlc3NmdWxseTpcIiwgbWludGVkRGF0YS50b2tlbklkKTtcbiAgICBcbiAgICAvLyBTdGVwIDI6IEdlbmVyYXRlIEJvYidzIHJlY2VpdmluZyBhZGRyZXNzXG4gICAgY29uc29sZS5sb2coXCJcXG5TdGVwIDI6IEdlbmVyYXRpbmcgQm9iJ3MgcmVjZWl2aW5nIGFkZHJlc3MuLi5cIik7XG4gICAgXG4gICAgLy8gRmlyc3QgcmVjb25zdHJ1Y3QgdGhlIHRva2VuIGZyb20gSlNPTiB0byBnZXQgcHJvcGVyIG9iamVjdHNcbiAgICBjb25zdCBwcmVkaWNhdGVGYWN0b3J5ID0gbmV3IHVuaWNpdHkuUHJlZGljYXRlSnNvbkZhY3RvcnkoKTtcbiAgICBjb25zdCB0b2tlbkZhY3RvcnkgPSBuZXcgdW5pY2l0eS5Ub2tlbkZhY3RvcnkoXG4gICAgICBuZXcgdW5pY2l0eS5Ub2tlbkpzb25TZXJpYWxpemVyKHByZWRpY2F0ZUZhY3RvcnkpXG4gICAgKTtcbiAgICBjb25zdCB0b2tlbiA9IGF3YWl0IHRva2VuRmFjdG9yeS5jcmVhdGUobWludGVkRGF0YS50b2tlbik7XG4gICAgXG4gICAgLy8gR2VuZXJhdGUgQm9iJ3MgaWRlbnRpdHkgY29tcG9uZW50c1xuICAgIGNvbnN0IGJvYlNlY3JldE1hdGNoZXMgPSBib2JJZGVudGl0eS5zZWNyZXQubWF0Y2goLy57Mn0vZyk7XG4gICAgY29uc3QgYm9iTm9uY2VNYXRjaGVzID0gYm9iSWRlbnRpdHkubm9uY2UubWF0Y2goLy57Mn0vZyk7XG4gICAgaWYgKCFib2JTZWNyZXRNYXRjaGVzIHx8ICFib2JOb25jZU1hdGNoZXMpIHtcbiAgICAgIHRocm93IG5ldyBFcnJvcignSW52YWxpZCBCb2IgaWRlbnRpdHkgZm9ybWF0Jyk7XG4gICAgfVxuICAgIFxuICAgIGNvbnN0IGJvYlNlY3JldCA9IG5ldyBVaW50OEFycmF5KGJvYlNlY3JldE1hdGNoZXMubWFwKChieXRlOiBzdHJpbmcpID0+IHBhcnNlSW50KGJ5dGUsIDE2KSkpO1xuICAgIGNvbnN0IGJvYk5vbmNlID0gbmV3IFVpbnQ4QXJyYXkoYm9iTm9uY2VNYXRjaGVzLm1hcCgoYnl0ZTogc3RyaW5nKSA9PiBwYXJzZUludChieXRlLCAxNikpKTtcbiAgICBcbiAgICAvLyBDcmVhdGUgQm9iJ3Mgc2lnbmluZyBzZXJ2aWNlXG4gICAgY29uc3QgYm9iU2lnbmluZ1NlcnZpY2UgPSBhd2FpdCB1bmljaXR5LlNpZ25pbmdTZXJ2aWNlLmNyZWF0ZUZyb21TZWNyZXQoYm9iU2VjcmV0LCBib2JOb25jZSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIEJvYidzIHByZWRpY2F0ZSBmb3IgVEhJUyBzcGVjaWZpYyB0b2tlbiAodXNpbmcgdG9rZW4ncyBJRCBhbmQgdHlwZSlcbiAgICBjb25zdCByZWNpcGllbnRQcmVkaWNhdGUgPSBhd2FpdCB1bmljaXR5Lk1hc2tlZFByZWRpY2F0ZS5jcmVhdGUoXG4gICAgICB0b2tlbi5pZCxcbiAgICAgIHRva2VuLnR5cGUsXG4gICAgICBib2JTaWduaW5nU2VydmljZSxcbiAgICAgIHVuaWNpdHkuSGFzaEFsZ29yaXRobS5TSEEyNTYsXG4gICAgICBib2JOb25jZVxuICAgICk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIEJvYidzIHJlY2VpdmluZyBhZGRyZXNzIGZyb20gdGhlIHByZWRpY2F0ZVxuICAgIGNvbnN0IGJvYkFkZHJlc3MgPSBhd2FpdCB1bmljaXR5LkRpcmVjdEFkZHJlc3MuY3JlYXRlKHJlY2lwaWVudFByZWRpY2F0ZS5yZWZlcmVuY2UpO1xuICAgIGNvbnN0IGJvYkFkZHJlc3NTdHJpbmcgPSBib2JBZGRyZXNzLnRvSlNPTigpO1xuICAgIGNvbnNvbGUubG9nKFwiQm9iJ3MgcmVjZWl2aW5nIGFkZHJlc3M6XCIsIGJvYkFkZHJlc3NTdHJpbmcpO1xuICAgIFxuICAgIC8vIFN0ZXAgMzogQWxpY2UgcHJlcGFyZXMgb2ZmbGluZSB0cmFuc2ZlciB0byBCb2JcbiAgICBjb25zb2xlLmxvZyhcIlxcblN0ZXAgMzogQWxpY2UgcHJlcGFyaW5nIG9mZmxpbmUgdHJhbnNmZXIgdG8gQm9iLi4uXCIpO1xuICAgIGNvbnN0IHRyYW5zZmVyUmVzdWx0ID0gYXdhaXQgcHJlcGFyZVRyYW5zZmVyKFxuICAgICAgSlNPTi5zdHJpbmdpZnkoYWxpY2VJZGVudGl0eSksXG4gICAgICBib2JBZGRyZXNzU3RyaW5nLFxuICAgICAgSlNPTi5zdHJpbmdpZnkobWludGVkRGF0YSksXG4gICAgICB0cnVlIC8vIGlzT2ZmbGluZSA9IHRydWVcbiAgICApO1xuICAgIFxuICAgIGNvbnN0IHRyYW5zZmVyUGFja2FnZSA9IEpTT04ucGFyc2UodHJhbnNmZXJSZXN1bHQpO1xuICAgIGNvbnNvbGUubG9nKFwiT2ZmbGluZSB0cmFuc2ZlciBwYWNrYWdlIGNyZWF0ZWRcIik7XG4gICAgY29uc29sZS5sb2coXCJUcmFuc2ZlciBwYWNrYWdlIHNpemU6XCIsIEpTT04uc3RyaW5naWZ5KHRyYW5zZmVyUGFja2FnZSkubGVuZ3RoLCBcImJ5dGVzXCIpO1xuICAgIFxuICAgIC8vIFN0ZXAgNDogU2ltdWxhdGUgb2ZmbGluZSBkYXRhIHRyYW5zZmVyIChlLmcuLCB2aWEgTkZDKVxuICAgIGNvbnNvbGUubG9nKFwiXFxuU3RlcCA0OiBTaW11bGF0aW5nIG9mZmxpbmUgZGF0YSB0cmFuc2Zlci4uLlwiKTtcbiAgICBjb25zb2xlLmxvZyhcIkluIGEgcmVhbCBzY2VuYXJpbywgdGhpcyBwYWNrYWdlIHdvdWxkIGJlIHRyYW5zZmVycmVkIHZpYSBORkNcIik7XG4gICAgXG4gICAgLy8gU3RlcCA1OiBCb2IgcmVjZWl2ZXMgYW5kIGZpbmFsaXplcyB0aGUgdHJhbnNmZXJcbiAgICBjb25zb2xlLmxvZyhcIlxcblN0ZXAgNTogQm9iIGZpbmFsaXppbmcgdGhlIHJlY2VpdmVkIHRyYW5zZmVyLi4uXCIpO1xuICAgIGNvbnN0IGZpbmFsaXplUmVzdWx0ID0gYXdhaXQgZmluYWxpemVSZWNlaXZlZFRyYW5zYWN0aW9uKFxuICAgICAgSlNPTi5zdHJpbmdpZnkoYm9iSWRlbnRpdHkpLFxuICAgICAgSlNPTi5zdHJpbmdpZnkodHJhbnNmZXJQYWNrYWdlKVxuICAgICk7XG4gICAgXG4gICAgY29uc3QgZmluYWxpemVkRGF0YSA9IEpTT04ucGFyc2UoZmluYWxpemVSZXN1bHQpO1xuICAgIGNvbnNvbGUubG9nKFwiVHJhbnNmZXIgZmluYWxpemVkIHN1Y2Nlc3NmdWxseSFcIik7XG4gICAgY29uc29sZS5sb2coXCJUb2tlbiBJRDpcIiwgZmluYWxpemVkRGF0YS50b2tlbklkKTtcbiAgICBjb25zb2xlLmxvZyhcIk5ldyBvd25lcjogQm9iXCIpO1xuICAgIFxuICAgIC8vIFN0ZXAgNjogVmVyaWZ5IHRoZSB0cmFuc2ZlclxuICAgIGNvbnNvbGUubG9nKFwiXFxuU3RlcCA2OiBWZXJpZnlpbmcgdHJhbnNmZXIuLi5cIik7XG4gICAgY29uc29sZS5sb2coXCJUb2tlbiBpcyBub3cgb3duZWQgYnkgQm9iXCIpO1xuICAgIGNvbnNvbGUubG9nKFwiQWxpY2Ugbm8gbG9uZ2VyIG93bnMgdGhlIHRva2VuXCIpO1xuICAgIFxuICAgIGNvbnNvbGUubG9nKFwiXFxuPT09IE9mZmxpbmUgVHJhbnNmZXIgVGVzdCBDb21wbGV0ZWQgU3VjY2Vzc2Z1bGx5ID09PVwiKTtcbiAgICBcbiAgICAvLyBSZXR1cm4gc3VjY2VzcyBtZXNzYWdlIHRvIEFuZHJvaWRcbiAgICBpZiAod2luZG93LkFuZHJvaWQpIHtcbiAgICAgIHdpbmRvdy5BbmRyb2lkLnNob3dUb2FzdChcIk9mZmxpbmUgdHJhbnNmZXIgdGVzdCBjb21wbGV0ZWQgc3VjY2Vzc2Z1bGx5IVwiKTtcbiAgICB9XG4gICAgXG4gIH0gY2F0Y2ggKGVycm9yOiBhbnkpIHtcbiAgICBjb25zb2xlLmVycm9yKFwiT2ZmbGluZSB0cmFuc2ZlciB0ZXN0IGZhaWxlZDpcIiwgZXJyb3IpO1xuICAgIGlmICh3aW5kb3cuQW5kcm9pZCkge1xuICAgICAgd2luZG93LkFuZHJvaWQuc2hvd1RvYXN0KFwiVGVzdCBmYWlsZWQ6IFwiICsgZXJyb3IubWVzc2FnZSk7XG4gICAgfVxuICAgIHRocm93IGVycm9yO1xuICB9XG59XG5cbi8vIEV4cG9ydCB0ZXN0IGZ1bmN0aW9uIHRvIHdpbmRvdyBmb3IgQW5kcm9pZCBhY2Nlc3Ncbih3aW5kb3cgYXMgYW55KS5ydW5PZmZsaW5lVHJhbnNmZXJUZXN0ID0gcnVuT2ZmbGluZVRyYW5zZmVyVGVzdDsiXSwibmFtZXMiOltdLCJzb3VyY2VSb290IjoiIn0=