/******/ (() => { // webpackBootstrap
/******/ 	"use strict";
/*!*************************************************!*\
  !*** ../app/src/main/assets/unicity-wrapper.ts ***!
  \*************************************************/

// SDK configuration
const aggregatorUrl = "https://gateway-test.unicity.network";
// Helper function to create client
function createClient() {
    console.log('Creating client with aggregator URL:', aggregatorUrl);
    const aggregatorClient = new unicity.AggregatorClient(aggregatorUrl);
    return new unicity.StateTransitionClient(aggregatorClient);
}
// Core wrapper functions with TypeScript types
async function mintToken(identityJson, tokenDataJson) {
    try {
        const identity = JSON.parse(identityJson);
        const tokenData = JSON.parse(tokenDataJson);
        const client = createClient();
        // Create identity components
        const secretMatches = identity.secret.match(/.{2}/g);
        const nonceMatches = identity.nonce.match(/.{2}/g);
        if (!secretMatches || !nonceMatches) {
            throw new Error('Invalid identity format');
        }
        const secret = new Uint8Array(secretMatches.map((byte) => parseInt(byte, 16)));
        const nonce = new Uint8Array(nonceMatches.map((byte) => parseInt(byte, 16)));
        // Create signing service
        const signingService = await unicity.SigningService.createFromSecret(secret, nonce);
        // Generate token ID and type
        const tokenId = unicity.TokenId.create(crypto.getRandomValues(new Uint8Array(32)));
        const tokenType = unicity.TokenType.create(crypto.getRandomValues(new Uint8Array(32)));
        // Create predicate and address
        const predicate = await unicity.MaskedPredicate.create(tokenId, tokenType, signingService, unicity.HashAlgorithm.SHA256, nonce);
        const address = await unicity.DirectAddress.create(predicate.reference);
        // Create coin data if amount is specified
        let coinData = null;
        if (tokenData.amount && tokenData.amount > 0) {
            coinData = unicity.TokenCoinData.create([
                [new unicity.CoinId(crypto.getRandomValues(new Uint8Array(32))), BigInt(tokenData.amount)]
            ]);
        }
        // Create mint transaction data
        const mintData = await unicity.MintTransactionData.create(tokenId, tokenType, new TextEncoder().encode(tokenData.data || ''), coinData, address.toJSON(), crypto.getRandomValues(new Uint8Array(32)), null, null);
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
    }
    catch (error) {
        console.error("Mint token error:", error);
        throw new Error(`Failed to mint token: ${error.message}`);
    }
}
async function prepareTransfer(senderIdentityJson, recipientAddress, tokenJson, isOffline = false) {
    try {
        const senderIdentity = JSON.parse(senderIdentityJson);
        const tokenWrapper = JSON.parse(tokenJson);
        // Extract the actual token from the wrapper
        const token = tokenWrapper.token;
        // Parse sender identity
        const secretMatches = senderIdentity.secret.match(/.{2}/g);
        const nonceMatches = senderIdentity.nonce.match(/.{2}/g);
        if (!secretMatches || !nonceMatches) {
            throw new Error('Invalid sender identity format');
        }
        const secret = new Uint8Array(secretMatches.map((byte) => parseInt(byte, 16)));
        const nonce = new Uint8Array(nonceMatches.map((byte) => parseInt(byte, 16)));
        // Create signing service for the sender
        const signingService = await unicity.SigningService.createFromSecret(secret, nonce);
        // Create token factory to reconstruct the token
        const predicateFactory = new unicity.PredicateJsonFactory();
        const tokenFactory = new unicity.TokenFactory(new unicity.TokenJsonSerializer(predicateFactory));
        const fullToken = await tokenFactory.create(token);
        // Create transaction data for the transfer
        const transactionData = await unicity.TransactionData.create(fullToken.state, recipientAddress, crypto.getRandomValues(new Uint8Array(32)), // salt
        null, // dataHash - can be added if needed
        new TextEncoder().encode(''), // message - can be customized
        fullToken.nametagTokens || []);
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
        }
        else {
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
    }
    catch (error) {
        console.error("Prepare transfer error:", error);
        throw new Error(`Failed to prepare transfer: ${error.message}`);
    }
}
async function finalizeReceivedTransaction(receiverIdentityJson, transferPackageJson) {
    try {
        const receiverIdentity = JSON.parse(receiverIdentityJson);
        const transferPackage = JSON.parse(transferPackageJson);
        // Parse receiver identity
        const secretMatches = receiverIdentity.secret.match(/.{2}/g);
        const nonceMatches = receiverIdentity.nonce.match(/.{2}/g);
        if (!secretMatches || !nonceMatches) {
            throw new Error('Invalid receiver identity format');
        }
        const secret = new Uint8Array(secretMatches.map((byte) => parseInt(byte, 16)));
        const nonce = new Uint8Array(nonceMatches.map((byte) => parseInt(byte, 16)));
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
            const importedCommitment = await commitmentDeserializer.deserialize(importedToken.id, importedToken.type, transferPackage.commitment);
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
            const recipientPredicate = await unicity.MaskedPredicate.create(importedToken.id, importedToken.type, signingService, unicity.HashAlgorithm.SHA256, nonce);
            // Finish the transaction with the recipient predicate
            const updatedToken = await client.finishTransaction(importedToken, await unicity.TokenState.create(recipientPredicate, null), confirmedTx);
            return JSON.stringify({
                tokenId: updatedToken.id.toJSON(),
                token: updatedToken
            });
        }
        else if (transferPackage.transaction) {
            // This is an online transfer - transaction already exists
            console.log('Processing online transfer...');
            // Deserialize the transaction
            const transactionDeserializer = new unicity.TransactionJsonSerializer(predicateFactory);
            const importedTransaction = await transactionDeserializer.deserialize(importedToken.id, importedToken.type, transferPackage.transaction);
            // Create receiver predicate
            const recipientPredicate = await unicity.MaskedPredicate.create(importedToken.id, importedToken.type, signingService, unicity.HashAlgorithm.SHA256, nonce);
            // Finish the transaction with the recipient predicate
            const client = createClient();
            const updatedToken = await client.finishTransaction(importedToken, await unicity.TokenState.create(recipientPredicate, null), importedTransaction);
            return JSON.stringify({
                tokenId: updatedToken.id.toJSON(),
                token: updatedToken
            });
        }
        else {
            throw new Error('Invalid transfer package: missing commitment or transaction');
        }
    }
    catch (error) {
        console.error("Finalize received transaction error:", error);
        throw new Error(`Failed to finalize received transaction: ${error.message}`);
    }
}
// Android bridge handler with type safety
async function handleAndroidRequest(request) {
    if (!window.Android) {
        console.error("Android bridge not available");
        return;
    }
    let parsedRequest;
    try {
        parsedRequest = JSON.parse(request);
    }
    catch (error) {
        console.error("Failed to parse request:", error);
        return;
    }
    const { id, method, params } = parsedRequest;
    try {
        let result;
        switch (method) {
            case "mintToken":
                result = await mintToken(params.identityJson, params.tokenDataJson);
                break;
            case "prepareTransfer":
                result = await prepareTransfer(params.senderIdentityJson, params.recipientAddress, params.tokenJson, params.isOffline);
                break;
            case "finalizeReceivedTransaction":
                result = await finalizeReceivedTransaction(params.receiverIdentityJson, params.transferPackageJson);
                break;
            default:
                throw new Error(`Unknown method: ${method}`);
        }
        window.Android.onResult(id, result);
    }
    catch (error) {
        console.error(`Error handling ${method}:`, error);
        window.Android.onError(id, error.message || "Unknown error");
    }
}
// Export the handler for Android bridge communication
window.handleAndroidRequest = handleAndroidRequest;
// Also export individual functions for testing purposes
window.mintToken = mintToken;
window.prepareTransfer = prepareTransfer;
window.finalizeReceivedTransaction = finalizeReceivedTransaction;

/******/ })()
;
//# sourceMappingURL=data:application/json;charset=utf-8;base64,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LE1BQU0sSUFBSSxLQUFLLENBQUMsbUJBQW1CLE1BQU0sRUFBRSxDQUFDLENBQUM7UUFDakQsQ0FBQztRQUVELE1BQU0sQ0FBQyxPQUFPLENBQUMsUUFBUSxDQUFDLEVBQUUsRUFBRSxNQUFNLENBQUMsQ0FBQztJQUN0QyxDQUFDO0lBQUMsT0FBTyxLQUFVLEVBQUUsQ0FBQztRQUNwQixPQUFPLENBQUMsS0FBSyxDQUFDLGtCQUFrQixNQUFNLEdBQUcsRUFBRSxLQUFLLENBQUMsQ0FBQztRQUNsRCxNQUFNLENBQUMsT0FBTyxDQUFDLE9BQU8sQ0FBQyxFQUFFLEVBQUUsS0FBSyxDQUFDLE9BQU8sSUFBSSxlQUFlLENBQUMsQ0FBQztJQUMvRCxDQUFDO0FBQ0gsQ0FBQztBQUdELHNEQUFzRDtBQUNyRCxNQUFjLENBQUMsb0JBQW9CLEdBQUcsb0JBQW9CLENBQUM7QUFFNUQsd0RBQXdEO0FBQ3ZELE1BQWMsQ0FBQyxTQUFTLEdBQUcsU0FBUyxDQUFDO0FBQ3JDLE1BQWMsQ0FBQyxlQUFlLEdBQUcsZUFBZSxDQUFDO0FBQ2pELE1BQWMsQ0FBQywyQkFBMkIsR0FBRywyQkFBMkIsQ0FBQyIsInNvdXJjZXMiOlsid2VicGFjazovL3VuaWNpdHktc2RrLWJ1bmRsZXIvLi4vYXBwL3NyYy9tYWluL2Fzc2V0cy91bmljaXR5LXdyYXBwZXIudHMiXSwic291cmNlc0NvbnRlbnQiOlsiLy8gVHlwZSBkZWZpbml0aW9ucyBmb3IgQW5kcm9pZCBCcmlkZ2UgY29tbXVuaWNhdGlvblxuaW50ZXJmYWNlIEFuZHJvaWRCcmlkZ2Uge1xuICBvblJlc3VsdChyZXF1ZXN0SWQ6IHN0cmluZywgZGF0YTogc3RyaW5nKTogdm9pZDtcbiAgb25FcnJvcihyZXF1ZXN0SWQ6IHN0cmluZywgZXJyb3I6IHN0cmluZyk6IHZvaWQ7XG4gIHNob3dUb2FzdChtZXNzYWdlOiBzdHJpbmcpOiB2b2lkO1xufVxuXG5pbnRlcmZhY2UgQW5kcm9pZFJlcXVlc3Qge1xuICBpZDogc3RyaW5nO1xuICBtZXRob2Q6IHN0cmluZztcbiAgcGFyYW1zOiBhbnk7XG59XG5cbmludGVyZmFjZSBBbmRyb2lkUmVzcG9uc2Uge1xuICBzdWNjZXNzOiBib29sZWFuO1xuICBkYXRhPzogYW55O1xuICBlcnJvcj86IHN0cmluZztcbn1cblxuLy8gVHlwZSBkZWZpbml0aW9ucyBmb3Igb3VyIHdyYXBwZXIgbWV0aG9kc1xuaW50ZXJmYWNlIElkZW50aXR5SnNvbiB7XG4gIHNlY3JldDogc3RyaW5nO1xuICBub25jZTogc3RyaW5nO1xufVxuXG5pbnRlcmZhY2UgVG9rZW5EYXRhSnNvbiB7XG4gIGRhdGE6IHN0cmluZztcbiAgYW1vdW50OiBudW1iZXI7XG59XG5cbmludGVyZmFjZSBUcmFuc2ZlclBhY2thZ2VKc29uIHtcbiAgY29tbWl0bWVudDogYW55O1xuICB0b2tlbjogYW55O1xufVxuXG4vLyBFeHRlbmQgV2luZG93IGludGVyZmFjZSBmb3IgQW5kcm9pZCBicmlkZ2VcbmludGVyZmFjZSBXaW5kb3cge1xuICBBbmRyb2lkPzogQW5kcm9pZEJyaWRnZTtcbn1cblxuLy8gSW1wb3J0IHRoZSBTREsgdHlwZSBkZWZpbml0aW9uc1xuLy8vIDxyZWZlcmVuY2UgcGF0aD1cIi4vdW5pY2l0eS1zZGsuZC50c1wiIC8+XG5cbi8vIFJlLWRlY2xhcmUgdW5pY2l0eSBzaW5jZSB0aGUgcmVmZXJlbmNlIHBhdGggZG9lc24ndCBzZWVtIHRvIHdvcmsgaW4gdGhpcyB3ZWJwYWNrIGNvbmZpZ1xuZGVjbGFyZSBjb25zdCB1bmljaXR5OiBhbnk7XG5cbi8vIFNESyBjb25maWd1cmF0aW9uXG5jb25zdCBhZ2dyZWdhdG9yVXJsID0gXCJodHRwczovL2dhdGV3YXktdGVzdC51bmljaXR5Lm5ldHdvcmtcIjtcblxuLy8gSGVscGVyIGZ1bmN0aW9uIHRvIGNyZWF0ZSBjbGllbnRcbmZ1bmN0aW9uIGNyZWF0ZUNsaWVudCgpOiBhbnkge1xuICBjb25zb2xlLmxvZygnQ3JlYXRpbmcgY2xpZW50IHdpdGggYWdncmVnYXRvciBVUkw6JywgYWdncmVnYXRvclVybCk7XG4gIGNvbnN0IGFnZ3JlZ2F0b3JDbGllbnQgPSBuZXcgdW5pY2l0eS5BZ2dyZWdhdG9yQ2xpZW50KGFnZ3JlZ2F0b3JVcmwpO1xuICByZXR1cm4gbmV3IHVuaWNpdHkuU3RhdGVUcmFuc2l0aW9uQ2xpZW50KGFnZ3JlZ2F0b3JDbGllbnQpO1xufVxuXG4vLyBDb3JlIHdyYXBwZXIgZnVuY3Rpb25zIHdpdGggVHlwZVNjcmlwdCB0eXBlc1xuYXN5bmMgZnVuY3Rpb24gbWludFRva2VuKGlkZW50aXR5SnNvbjogc3RyaW5nLCB0b2tlbkRhdGFKc29uOiBzdHJpbmcpOiBQcm9taXNlPHN0cmluZz4ge1xuICB0cnkge1xuICAgIGNvbnN0IGlkZW50aXR5OiBJZGVudGl0eUpzb24gPSBKU09OLnBhcnNlKGlkZW50aXR5SnNvbik7XG4gICAgY29uc3QgdG9rZW5EYXRhOiBUb2tlbkRhdGFKc29uID0gSlNPTi5wYXJzZSh0b2tlbkRhdGFKc29uKTtcbiAgICBcbiAgICBjb25zdCBjbGllbnQgPSBjcmVhdGVDbGllbnQoKTtcbiAgICBcbiAgICAvLyBDcmVhdGUgaWRlbnRpdHkgY29tcG9uZW50c1xuICAgIGNvbnN0IHNlY3JldE1hdGNoZXMgPSBpZGVudGl0eS5zZWNyZXQubWF0Y2goLy57Mn0vZyk7XG4gICAgY29uc3Qgbm9uY2VNYXRjaGVzID0gaWRlbnRpdHkubm9uY2UubWF0Y2goLy57Mn0vZyk7XG4gICAgaWYgKCFzZWNyZXRNYXRjaGVzIHx8ICFub25jZU1hdGNoZXMpIHtcbiAgICAgIHRocm93IG5ldyBFcnJvcignSW52YWxpZCBpZGVudGl0eSBmb3JtYXQnKTtcbiAgICB9XG4gICAgY29uc3Qgc2VjcmV0ID0gbmV3IFVpbnQ4QXJyYXkoc2VjcmV0TWF0Y2hlcy5tYXAoKGJ5dGU6IHN0cmluZykgPT4gcGFyc2VJbnQoYnl0ZSwgMTYpKSk7XG4gICAgY29uc3Qgbm9uY2UgPSBuZXcgVWludDhBcnJheShub25jZU1hdGNoZXMubWFwKChieXRlOiBzdHJpbmcpID0+IHBhcnNlSW50KGJ5dGUsIDE2KSkpO1xuICAgIFxuICAgIC8vIENyZWF0ZSBzaWduaW5nIHNlcnZpY2VcbiAgICBjb25zdCBzaWduaW5nU2VydmljZSA9IGF3YWl0IHVuaWNpdHkuU2lnbmluZ1NlcnZpY2UuY3JlYXRlRnJvbVNlY3JldChzZWNyZXQsIG5vbmNlKTtcbiAgICBcbiAgICAvLyBHZW5lcmF0ZSB0b2tlbiBJRCBhbmQgdHlwZVxuICAgIGNvbnN0IHRva2VuSWQgPSB1bmljaXR5LlRva2VuSWQuY3JlYXRlKGNyeXB0by5nZXRSYW5kb21WYWx1ZXMobmV3IFVpbnQ4QXJyYXkoMzIpKSk7XG4gICAgY29uc3QgdG9rZW5UeXBlID0gdW5pY2l0eS5Ub2tlblR5cGUuY3JlYXRlKGNyeXB0by5nZXRSYW5kb21WYWx1ZXMobmV3IFVpbnQ4QXJyYXkoMzIpKSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIHByZWRpY2F0ZSBhbmQgYWRkcmVzc1xuICAgIGNvbnN0IHByZWRpY2F0ZSA9IGF3YWl0IHVuaWNpdHkuTWFza2VkUHJlZGljYXRlLmNyZWF0ZShcbiAgICAgIHRva2VuSWQsXG4gICAgICB0b2tlblR5cGUsXG4gICAgICBzaWduaW5nU2VydmljZSxcbiAgICAgIHVuaWNpdHkuSGFzaEFsZ29yaXRobS5TSEEyNTYsXG4gICAgICBub25jZVxuICAgICk7XG4gICAgY29uc3QgYWRkcmVzcyA9IGF3YWl0IHVuaWNpdHkuRGlyZWN0QWRkcmVzcy5jcmVhdGUocHJlZGljYXRlLnJlZmVyZW5jZSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIGNvaW4gZGF0YSBpZiBhbW91bnQgaXMgc3BlY2lmaWVkXG4gICAgbGV0IGNvaW5EYXRhID0gbnVsbDtcbiAgICBpZiAodG9rZW5EYXRhLmFtb3VudCAmJiB0b2tlbkRhdGEuYW1vdW50ID4gMCkge1xuICAgICAgY29pbkRhdGEgPSB1bmljaXR5LlRva2VuQ29pbkRhdGEuY3JlYXRlKFtcbiAgICAgICAgW25ldyB1bmljaXR5LkNvaW5JZChjcnlwdG8uZ2V0UmFuZG9tVmFsdWVzKG5ldyBVaW50OEFycmF5KDMyKSkpLCBCaWdJbnQodG9rZW5EYXRhLmFtb3VudCldXG4gICAgICBdKTtcbiAgICB9XG4gICAgXG4gICAgLy8gQ3JlYXRlIG1pbnQgdHJhbnNhY3Rpb24gZGF0YVxuICAgIGNvbnN0IG1pbnREYXRhID0gYXdhaXQgdW5pY2l0eS5NaW50VHJhbnNhY3Rpb25EYXRhLmNyZWF0ZShcbiAgICAgIHRva2VuSWQsXG4gICAgICB0b2tlblR5cGUsXG4gICAgICBuZXcgVGV4dEVuY29kZXIoKS5lbmNvZGUodG9rZW5EYXRhLmRhdGEgfHwgJycpLFxuICAgICAgY29pbkRhdGEsXG4gICAgICBhZGRyZXNzLnRvSlNPTigpLFxuICAgICAgY3J5cHRvLmdldFJhbmRvbVZhbHVlcyhuZXcgVWludDhBcnJheSgzMikpLFxuICAgICAgbnVsbCxcbiAgICAgIG51bGxcbiAgICApO1xuICAgIFxuICAgIC8vIFN1Ym1pdCBtaW50IHRyYW5zYWN0aW9uXG4gICAgY29uc29sZS5sb2coJ1N1Ym1pdHRpbmcgbWludCB0cmFuc2FjdGlvbi4uLicpO1xuICAgIGNvbnN0IGNvbW1pdG1lbnQgPSBhd2FpdCBjbGllbnQuc3VibWl0TWludFRyYW5zYWN0aW9uKG1pbnREYXRhKTtcbiAgICBjb25zb2xlLmxvZygnTWludCB0cmFuc2FjdGlvbiBzdWJtaXR0ZWQsIHdhaXRpbmcgZm9yIGluY2x1c2lvbiBwcm9vZi4uLicpO1xuICAgIFxuICAgIC8vIFdhaXQgZm9yIGluY2x1c2lvbiBwcm9vZiB1c2luZyBTREsncyBidWlsdC1pbiBmdW5jdGlvblxuICAgIGNvbnNvbGUubG9nKCdVc2luZyBTREsgd2FpdEluY2x1c2lvblByb29mIHV0aWxpdHkuLi4nKTtcbiAgICBjb25zdCBpbmNsdXNpb25Qcm9vZiA9IGF3YWl0IHVuaWNpdHkud2FpdEluY2x1c2lvblByb29mKGNsaWVudCwgY29tbWl0bWVudCk7XG4gICAgY29uc29sZS5sb2coJ0luY2x1c2lvbiBwcm9vZiByZWNlaXZlZCBhbmQgdmVyaWZpZWQnKTtcbiAgICBcbiAgICAvLyBDcmVhdGUgdHJhbnNhY3Rpb25cbiAgICBjb25zb2xlLmxvZygnQ3JlYXRpbmcgdHJhbnNhY3Rpb24gd2l0aCBjb21taXRtZW50IGFuZCBpbmNsdXNpb24gcHJvb2YuLi4nKTtcbiAgICBjb25zdCB0cmFuc2FjdGlvbiA9IGF3YWl0IGNsaWVudC5jcmVhdGVUcmFuc2FjdGlvbihjb21taXRtZW50LCBpbmNsdXNpb25Qcm9vZik7XG4gICAgY29uc29sZS5sb2coJ1RyYW5zYWN0aW9uIGNyZWF0ZWQgc3VjY2Vzc2Z1bGx5OicsIHRyYW5zYWN0aW9uKTtcbiAgICBcbiAgICAvLyBDcmVhdGUgdG9rZW4gc3RhdGVcbiAgICBjb25zdCB0b2tlblN0YXRlID0gYXdhaXQgdW5pY2l0eS5Ub2tlblN0YXRlLmNyZWF0ZShwcmVkaWNhdGUsIG51bGwpO1xuICAgIFxuICAgIC8vIENyZWF0ZSBmaW5hbCB0b2tlblxuICAgIGNvbnN0IHRva2VuID0gbmV3IHVuaWNpdHkuVG9rZW4odG9rZW5TdGF0ZSwgdHJhbnNhY3Rpb24sIFtdLCBbXSwgXCIyLjBcIik7XG4gICAgXG4gICAgLy8gUmV0dXJuIHRva2VuIGRhdGFcbiAgICByZXR1cm4gSlNPTi5zdHJpbmdpZnkoe1xuICAgICAgdG9rZW5JZDogdG9rZW5JZC50b0pTT04oKSxcbiAgICAgIHRva2VuOiB0b2tlblxuICAgIH0pO1xuICB9IGNhdGNoIChlcnJvcjogYW55KSB7XG4gICAgY29uc29sZS5lcnJvcihcIk1pbnQgdG9rZW4gZXJyb3I6XCIsIGVycm9yKTtcbiAgICB0aHJvdyBuZXcgRXJyb3IoYEZhaWxlZCB0byBtaW50IHRva2VuOiAke2Vycm9yLm1lc3NhZ2V9YCk7XG4gIH1cbn1cblxuXG5hc3luYyBmdW5jdGlvbiBwcmVwYXJlVHJhbnNmZXIoXG4gIHNlbmRlcklkZW50aXR5SnNvbjogc3RyaW5nLCBcbiAgcmVjaXBpZW50QWRkcmVzczogc3RyaW5nLCBcbiAgdG9rZW5Kc29uOiBzdHJpbmcsIFxuICBpc09mZmxpbmU6IGJvb2xlYW4gPSBmYWxzZVxuKTogUHJvbWlzZTxzdHJpbmc+IHtcbiAgdHJ5IHtcbiAgICBjb25zdCBzZW5kZXJJZGVudGl0eTogSWRlbnRpdHlKc29uID0gSlNPTi5wYXJzZShzZW5kZXJJZGVudGl0eUpzb24pO1xuICAgIGNvbnN0IHRva2VuV3JhcHBlciA9IEpTT04ucGFyc2UodG9rZW5Kc29uKTtcbiAgICBcbiAgICAvLyBFeHRyYWN0IHRoZSBhY3R1YWwgdG9rZW4gZnJvbSB0aGUgd3JhcHBlclxuICAgIGNvbnN0IHRva2VuID0gdG9rZW5XcmFwcGVyLnRva2VuO1xuICAgIFxuICAgIC8vIFBhcnNlIHNlbmRlciBpZGVudGl0eVxuICAgIGNvbnN0IHNlY3JldE1hdGNoZXMgPSBzZW5kZXJJZGVudGl0eS5zZWNyZXQubWF0Y2goLy57Mn0vZyk7XG4gICAgY29uc3Qgbm9uY2VNYXRjaGVzID0gc2VuZGVySWRlbnRpdHkubm9uY2UubWF0Y2goLy57Mn0vZyk7XG4gICAgaWYgKCFzZWNyZXRNYXRjaGVzIHx8ICFub25jZU1hdGNoZXMpIHtcbiAgICAgIHRocm93IG5ldyBFcnJvcignSW52YWxpZCBzZW5kZXIgaWRlbnRpdHkgZm9ybWF0Jyk7XG4gICAgfVxuICAgIGNvbnN0IHNlY3JldCA9IG5ldyBVaW50OEFycmF5KHNlY3JldE1hdGNoZXMubWFwKChieXRlOiBzdHJpbmcpID0+IHBhcnNlSW50KGJ5dGUsIDE2KSkpO1xuICAgIGNvbnN0IG5vbmNlID0gbmV3IFVpbnQ4QXJyYXkobm9uY2VNYXRjaGVzLm1hcCgoYnl0ZTogc3RyaW5nKSA9PiBwYXJzZUludChieXRlLCAxNikpKTtcbiAgICBcbiAgICAvLyBDcmVhdGUgc2lnbmluZyBzZXJ2aWNlIGZvciB0aGUgc2VuZGVyXG4gICAgY29uc3Qgc2lnbmluZ1NlcnZpY2UgPSBhd2FpdCB1bmljaXR5LlNpZ25pbmdTZXJ2aWNlLmNyZWF0ZUZyb21TZWNyZXQoc2VjcmV0LCBub25jZSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIHRva2VuIGZhY3RvcnkgdG8gcmVjb25zdHJ1Y3QgdGhlIHRva2VuXG4gICAgY29uc3QgcHJlZGljYXRlRmFjdG9yeSA9IG5ldyB1bmljaXR5LlByZWRpY2F0ZUpzb25GYWN0b3J5KCk7XG4gICAgY29uc3QgdG9rZW5GYWN0b3J5ID0gbmV3IHVuaWNpdHkuVG9rZW5GYWN0b3J5KG5ldyB1bmljaXR5LlRva2VuSnNvblNlcmlhbGl6ZXIocHJlZGljYXRlRmFjdG9yeSkpO1xuICAgIGNvbnN0IGZ1bGxUb2tlbiA9IGF3YWl0IHRva2VuRmFjdG9yeS5jcmVhdGUodG9rZW4pO1xuICAgIFxuICAgIC8vIENyZWF0ZSB0cmFuc2FjdGlvbiBkYXRhIGZvciB0aGUgdHJhbnNmZXJcbiAgICBjb25zdCB0cmFuc2FjdGlvbkRhdGEgPSBhd2FpdCB1bmljaXR5LlRyYW5zYWN0aW9uRGF0YS5jcmVhdGUoXG4gICAgICBmdWxsVG9rZW4uc3RhdGUsXG4gICAgICByZWNpcGllbnRBZGRyZXNzLFxuICAgICAgY3J5cHRvLmdldFJhbmRvbVZhbHVlcyhuZXcgVWludDhBcnJheSgzMikpLCAvLyBzYWx0XG4gICAgICBudWxsLCAvLyBkYXRhSGFzaCAtIGNhbiBiZSBhZGRlZCBpZiBuZWVkZWRcbiAgICAgIG5ldyBUZXh0RW5jb2RlcigpLmVuY29kZSgnJyksIC8vIG1lc3NhZ2UgLSBjYW4gYmUgY3VzdG9taXplZFxuICAgICAgZnVsbFRva2VuLm5hbWV0YWdUb2tlbnMgfHwgW11cbiAgICApO1xuICAgIFxuICAgIC8vIENyZWF0ZSBjb21taXRtZW50XG4gICAgY29uc3QgY29tbWl0bWVudCA9IGF3YWl0IHVuaWNpdHkuQ29tbWl0bWVudC5jcmVhdGUodHJhbnNhY3Rpb25EYXRhLCBzaWduaW5nU2VydmljZSk7XG4gICAgXG4gICAgaWYgKGlzT2ZmbGluZSkge1xuICAgICAgLy8gRm9yIG9mZmxpbmUgdHJhbnNmZXIsIHNlcmlhbGl6ZSB0aGUgY29tbWl0bWVudCBhbmQgdG9rZW5cbiAgICAgIGNvbnN0IHRyYW5zZmVyUGFja2FnZSA9IHtcbiAgICAgICAgY29tbWl0bWVudDogdW5pY2l0eS5Db21taXRtZW50SnNvblNlcmlhbGl6ZXIuc2VyaWFsaXplKGNvbW1pdG1lbnQpLFxuICAgICAgICB0b2tlbjogdG9rZW5cbiAgICAgIH07XG4gICAgICBcbiAgICAgIGNvbnNvbGUubG9nKCdDcmVhdGVkIG9mZmxpbmUgdHJhbnNmZXIgcGFja2FnZScpO1xuICAgICAgcmV0dXJuIEpTT04uc3RyaW5naWZ5KHRyYW5zZmVyUGFja2FnZSk7XG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIEZvciBvbmxpbmUgdHJhbnNmZXIsIHN1Ym1pdCB0byBhZ2dyZWdhdG9yXG4gICAgICBjb25zdCBjbGllbnQgPSBjcmVhdGVDbGllbnQoKTtcbiAgICAgIGNvbnN0IHJlc3BvbnNlID0gYXdhaXQgY2xpZW50LnN1Ym1pdENvbW1pdG1lbnQoY29tbWl0bWVudCk7XG4gICAgICBcbiAgICAgIGlmIChyZXNwb25zZS5zdGF0dXMgIT09IHVuaWNpdHkuU3VibWl0Q29tbWl0bWVudFN0YXR1cy5TVUNDRVNTKSB7XG4gICAgICAgIHRocm93IG5ldyBFcnJvcihgRmFpbGVkIHRvIHN1Ym1pdCB0cmFuc2ZlcjogJHtyZXNwb25zZS5zdGF0dXN9YCk7XG4gICAgICB9XG4gICAgICBcbiAgICAgIGNvbnNvbGUubG9nKCdUcmFuc2ZlciBzdWJtaXR0ZWQsIHdhaXRpbmcgZm9yIGluY2x1c2lvbiBwcm9vZi4uLicpO1xuICAgICAgY29uc3QgaW5jbHVzaW9uUHJvb2YgPSBhd2FpdCB1bmljaXR5LndhaXRJbmNsdXNpb25Qcm9vZihjbGllbnQsIGNvbW1pdG1lbnQpO1xuICAgICAgY29uc29sZS5sb2coJ0luY2x1c2lvbiBwcm9vZiByZWNlaXZlZCcpO1xuICAgICAgXG4gICAgICAvLyBDcmVhdGUgdGhlIHRyYW5zYWN0aW9uXG4gICAgICBjb25zdCB0cmFuc2FjdGlvbiA9IGF3YWl0IGNsaWVudC5jcmVhdGVUcmFuc2FjdGlvbihjb21taXRtZW50LCBpbmNsdXNpb25Qcm9vZik7XG4gICAgICBcbiAgICAgIC8vIFJldHVybiB0aGUgdHJhbnNmZXIgcGFja2FnZSB3aXRoIHRoZSB0cmFuc2FjdGlvblxuICAgICAgY29uc3QgdHJhbnNmZXJQYWNrYWdlID0ge1xuICAgICAgICB0cmFuc2FjdGlvbjogdW5pY2l0eS5UcmFuc2FjdGlvbkpzb25TZXJpYWxpemVyLnNlcmlhbGl6ZSh0cmFuc2FjdGlvbiksXG4gICAgICAgIHRva2VuOiB0b2tlbixcbiAgICAgICAgcmVjaXBpZW50QWRkcmVzczogcmVjaXBpZW50QWRkcmVzc1xuICAgICAgfTtcbiAgICAgIFxuICAgICAgcmV0dXJuIEpTT04uc3RyaW5naWZ5KHRyYW5zZmVyUGFja2FnZSk7XG4gICAgfVxuICB9IGNhdGNoIChlcnJvcjogYW55KSB7XG4gICAgY29uc29sZS5lcnJvcihcIlByZXBhcmUgdHJhbnNmZXIgZXJyb3I6XCIsIGVycm9yKTtcbiAgICB0aHJvdyBuZXcgRXJyb3IoYEZhaWxlZCB0byBwcmVwYXJlIHRyYW5zZmVyOiAke2Vycm9yLm1lc3NhZ2V9YCk7XG4gIH1cbn1cblxuYXN5bmMgZnVuY3Rpb24gZmluYWxpemVSZWNlaXZlZFRyYW5zYWN0aW9uKFxuICByZWNlaXZlcklkZW50aXR5SnNvbjogc3RyaW5nLCBcbiAgdHJhbnNmZXJQYWNrYWdlSnNvbjogc3RyaW5nXG4pOiBQcm9taXNlPHN0cmluZz4ge1xuICB0cnkge1xuICAgIGNvbnN0IHJlY2VpdmVySWRlbnRpdHk6IElkZW50aXR5SnNvbiA9IEpTT04ucGFyc2UocmVjZWl2ZXJJZGVudGl0eUpzb24pO1xuICAgIGNvbnN0IHRyYW5zZmVyUGFja2FnZSA9IEpTT04ucGFyc2UodHJhbnNmZXJQYWNrYWdlSnNvbik7XG4gICAgXG4gICAgLy8gUGFyc2UgcmVjZWl2ZXIgaWRlbnRpdHlcbiAgICBjb25zdCBzZWNyZXRNYXRjaGVzID0gcmVjZWl2ZXJJZGVudGl0eS5zZWNyZXQubWF0Y2goLy57Mn0vZyk7XG4gICAgY29uc3Qgbm9uY2VNYXRjaGVzID0gcmVjZWl2ZXJJZGVudGl0eS5ub25jZS5tYXRjaCgvLnsyfS9nKTtcbiAgICBpZiAoIXNlY3JldE1hdGNoZXMgfHwgIW5vbmNlTWF0Y2hlcykge1xuICAgICAgdGhyb3cgbmV3IEVycm9yKCdJbnZhbGlkIHJlY2VpdmVyIGlkZW50aXR5IGZvcm1hdCcpO1xuICAgIH1cbiAgICBjb25zdCBzZWNyZXQgPSBuZXcgVWludDhBcnJheShzZWNyZXRNYXRjaGVzLm1hcCgoYnl0ZTogc3RyaW5nKSA9PiBwYXJzZUludChieXRlLCAxNikpKTtcbiAgICBjb25zdCBub25jZSA9IG5ldyBVaW50OEFycmF5KG5vbmNlTWF0Y2hlcy5tYXAoKGJ5dGU6IHN0cmluZykgPT4gcGFyc2VJbnQoYnl0ZSwgMTYpKSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIHNpZ25pbmcgc2VydmljZSBmb3IgdGhlIHJlY2VpdmVyXG4gICAgY29uc3Qgc2lnbmluZ1NlcnZpY2UgPSBhd2FpdCB1bmljaXR5LlNpZ25pbmdTZXJ2aWNlLmNyZWF0ZUZyb21TZWNyZXQoc2VjcmV0LCBub25jZSk7XG4gICAgXG4gICAgLy8gQ3JlYXRlIGZhY3RvcmllcyBmb3IgZGVzZXJpYWxpemF0aW9uXG4gICAgY29uc3QgcHJlZGljYXRlRmFjdG9yeSA9IG5ldyB1bmljaXR5LlByZWRpY2F0ZUpzb25GYWN0b3J5KCk7XG4gICAgY29uc3QgdG9rZW5GYWN0b3J5ID0gbmV3IHVuaWNpdHkuVG9rZW5GYWN0b3J5KG5ldyB1bmljaXR5LlRva2VuSnNvblNlcmlhbGl6ZXIocHJlZGljYXRlRmFjdG9yeSkpO1xuICAgIFxuICAgIC8vIEltcG9ydCB0aGUgdG9rZW5cbiAgICBjb25zdCBpbXBvcnRlZFRva2VuID0gYXdhaXQgdG9rZW5GYWN0b3J5LmNyZWF0ZSh0cmFuc2ZlclBhY2thZ2UudG9rZW4pO1xuICAgIFxuICAgIGlmICh0cmFuc2ZlclBhY2thZ2UuY29tbWl0bWVudCkge1xuICAgICAgLy8gVGhpcyBpcyBhbiBvZmZsaW5lIHRyYW5zZmVyIC0gbmVlZCB0byBzdWJtaXQgdGhlIGNvbW1pdG1lbnQgZmlyc3RcbiAgICAgIGNvbnNvbGUubG9nKCdQcm9jZXNzaW5nIG9mZmxpbmUgdHJhbnNmZXIuLi4nKTtcbiAgICAgIFxuICAgICAgLy8gRGVzZXJpYWxpemUgdGhlIGNvbW1pdG1lbnRcbiAgICAgIGNvbnN0IGNvbW1pdG1lbnREZXNlcmlhbGl6ZXIgPSBuZXcgdW5pY2l0eS5Db21taXRtZW50SnNvblNlcmlhbGl6ZXIocHJlZGljYXRlRmFjdG9yeSk7XG4gICAgICBjb25zdCBpbXBvcnRlZENvbW1pdG1lbnQgPSBhd2FpdCBjb21taXRtZW50RGVzZXJpYWxpemVyLmRlc2VyaWFsaXplKFxuICAgICAgICBpbXBvcnRlZFRva2VuLmlkLFxuICAgICAgICBpbXBvcnRlZFRva2VuLnR5cGUsXG4gICAgICAgIHRyYW5zZmVyUGFja2FnZS5jb21taXRtZW50XG4gICAgICApO1xuICAgICAgXG4gICAgICAvLyBTdWJtaXQgdGhlIGNvbW1pdG1lbnQgdG8gdGhlIGFnZ3JlZ2F0b3JcbiAgICAgIGNvbnN0IGNsaWVudCA9IGNyZWF0ZUNsaWVudCgpO1xuICAgICAgY29uc3QgcmVzcG9uc2UgPSBhd2FpdCBjbGllbnQuc3VibWl0Q29tbWl0bWVudChpbXBvcnRlZENvbW1pdG1lbnQpO1xuICAgICAgXG4gICAgICBpZiAocmVzcG9uc2Uuc3RhdHVzICE9PSB1bmljaXR5LlN1Ym1pdENvbW1pdG1lbnRTdGF0dXMuU1VDQ0VTUykge1xuICAgICAgICB0aHJvdyBuZXcgRXJyb3IoYEZhaWxlZCB0byBzdWJtaXQgb2ZmbGluZSB0cmFuc2ZlcjogJHtyZXNwb25zZS5zdGF0dXN9YCk7XG4gICAgICB9XG4gICAgICBcbiAgICAgIGNvbnNvbGUubG9nKCdPZmZsaW5lIHRyYW5zZmVyIHN1Ym1pdHRlZCwgd2FpdGluZyBmb3IgaW5jbHVzaW9uIHByb29mLi4uJyk7XG4gICAgICBjb25zdCBpbmNsdXNpb25Qcm9vZiA9IGF3YWl0IHVuaWNpdHkud2FpdEluY2x1c2lvblByb29mKGNsaWVudCwgaW1wb3J0ZWRDb21taXRtZW50KTtcbiAgICAgIGNvbnNvbGUubG9nKCdJbmNsdXNpb24gcHJvb2YgcmVjZWl2ZWQnKTtcbiAgICAgIFxuICAgICAgLy8gQ3JlYXRlIHRoZSB0cmFuc2FjdGlvblxuICAgICAgY29uc3QgY29uZmlybWVkVHggPSBhd2FpdCBjbGllbnQuY3JlYXRlVHJhbnNhY3Rpb24oaW1wb3J0ZWRDb21taXRtZW50LCBpbmNsdXNpb25Qcm9vZik7XG4gICAgICBcbiAgICAgIC8vIENyZWF0ZSByZWNlaXZlciBwcmVkaWNhdGVcbiAgICAgIGNvbnN0IHJlY2lwaWVudFByZWRpY2F0ZSA9IGF3YWl0IHVuaWNpdHkuTWFza2VkUHJlZGljYXRlLmNyZWF0ZShcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi5pZCxcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi50eXBlLFxuICAgICAgICBzaWduaW5nU2VydmljZSxcbiAgICAgICAgdW5pY2l0eS5IYXNoQWxnb3JpdGhtLlNIQTI1NixcbiAgICAgICAgbm9uY2VcbiAgICAgICk7XG4gICAgICBcbiAgICAgIC8vIEZpbmlzaCB0aGUgdHJhbnNhY3Rpb24gd2l0aCB0aGUgcmVjaXBpZW50IHByZWRpY2F0ZVxuICAgICAgY29uc3QgdXBkYXRlZFRva2VuID0gYXdhaXQgY2xpZW50LmZpbmlzaFRyYW5zYWN0aW9uKFxuICAgICAgICBpbXBvcnRlZFRva2VuLFxuICAgICAgICBhd2FpdCB1bmljaXR5LlRva2VuU3RhdGUuY3JlYXRlKHJlY2lwaWVudFByZWRpY2F0ZSwgbnVsbCksXG4gICAgICAgIGNvbmZpcm1lZFR4XG4gICAgICApO1xuICAgICAgXG4gICAgICByZXR1cm4gSlNPTi5zdHJpbmdpZnkoe1xuICAgICAgICB0b2tlbklkOiB1cGRhdGVkVG9rZW4uaWQudG9KU09OKCksXG4gICAgICAgIHRva2VuOiB1cGRhdGVkVG9rZW5cbiAgICAgIH0pO1xuICAgICAgXG4gICAgfSBlbHNlIGlmICh0cmFuc2ZlclBhY2thZ2UudHJhbnNhY3Rpb24pIHtcbiAgICAgIC8vIFRoaXMgaXMgYW4gb25saW5lIHRyYW5zZmVyIC0gdHJhbnNhY3Rpb24gYWxyZWFkeSBleGlzdHNcbiAgICAgIGNvbnNvbGUubG9nKCdQcm9jZXNzaW5nIG9ubGluZSB0cmFuc2Zlci4uLicpO1xuICAgICAgXG4gICAgICAvLyBEZXNlcmlhbGl6ZSB0aGUgdHJhbnNhY3Rpb25cbiAgICAgIGNvbnN0IHRyYW5zYWN0aW9uRGVzZXJpYWxpemVyID0gbmV3IHVuaWNpdHkuVHJhbnNhY3Rpb25Kc29uU2VyaWFsaXplcihwcmVkaWNhdGVGYWN0b3J5KTtcbiAgICAgIGNvbnN0IGltcG9ydGVkVHJhbnNhY3Rpb24gPSBhd2FpdCB0cmFuc2FjdGlvbkRlc2VyaWFsaXplci5kZXNlcmlhbGl6ZShcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi5pZCxcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi50eXBlLFxuICAgICAgICB0cmFuc2ZlclBhY2thZ2UudHJhbnNhY3Rpb25cbiAgICAgICk7XG4gICAgICBcbiAgICAgIC8vIENyZWF0ZSByZWNlaXZlciBwcmVkaWNhdGVcbiAgICAgIGNvbnN0IHJlY2lwaWVudFByZWRpY2F0ZSA9IGF3YWl0IHVuaWNpdHkuTWFza2VkUHJlZGljYXRlLmNyZWF0ZShcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi5pZCxcbiAgICAgICAgaW1wb3J0ZWRUb2tlbi50eXBlLFxuICAgICAgICBzaWduaW5nU2VydmljZSxcbiAgICAgICAgdW5pY2l0eS5IYXNoQWxnb3JpdGhtLlNIQTI1NixcbiAgICAgICAgbm9uY2VcbiAgICAgICk7XG4gICAgICBcbiAgICAgIC8vIEZpbmlzaCB0aGUgdHJhbnNhY3Rpb24gd2l0aCB0aGUgcmVjaXBpZW50IHByZWRpY2F0ZVxuICAgICAgY29uc3QgY2xpZW50ID0gY3JlYXRlQ2xpZW50KCk7XG4gICAgICBjb25zdCB1cGRhdGVkVG9rZW4gPSBhd2FpdCBjbGllbnQuZmluaXNoVHJhbnNhY3Rpb24oXG4gICAgICAgIGltcG9ydGVkVG9rZW4sXG4gICAgICAgIGF3YWl0IHVuaWNpdHkuVG9rZW5TdGF0ZS5jcmVhdGUocmVjaXBpZW50UHJlZGljYXRlLCBudWxsKSxcbiAgICAgICAgaW1wb3J0ZWRUcmFuc2FjdGlvblxuICAgICAgKTtcbiAgICAgIFxuICAgICAgcmV0dXJuIEpTT04uc3RyaW5naWZ5KHtcbiAgICAgICAgdG9rZW5JZDogdXBkYXRlZFRva2VuLmlkLnRvSlNPTigpLFxuICAgICAgICB0b2tlbjogdXBkYXRlZFRva2VuXG4gICAgICB9KTtcbiAgICAgIFxuICAgIH0gZWxzZSB7XG4gICAgICB0aHJvdyBuZXcgRXJyb3IoJ0ludmFsaWQgdHJhbnNmZXIgcGFja2FnZTogbWlzc2luZyBjb21taXRtZW50IG9yIHRyYW5zYWN0aW9uJyk7XG4gICAgfVxuICB9IGNhdGNoIChlcnJvcjogYW55KSB7XG4gICAgY29uc29sZS5lcnJvcihcIkZpbmFsaXplIHJlY2VpdmVkIHRyYW5zYWN0aW9uIGVycm9yOlwiLCBlcnJvcik7XG4gICAgdGhyb3cgbmV3IEVycm9yKGBGYWlsZWQgdG8gZmluYWxpemUgcmVjZWl2ZWQgdHJhbnNhY3Rpb246ICR7ZXJyb3IubWVzc2FnZX1gKTtcbiAgfVxufVxuXG5cbi8vIEFuZHJvaWQgYnJpZGdlIGhhbmRsZXIgd2l0aCB0eXBlIHNhZmV0eVxuYXN5bmMgZnVuY3Rpb24gaGFuZGxlQW5kcm9pZFJlcXVlc3QocmVxdWVzdDogc3RyaW5nKTogUHJvbWlzZTx2b2lkPiB7XG4gIGlmICghd2luZG93LkFuZHJvaWQpIHtcbiAgICBjb25zb2xlLmVycm9yKFwiQW5kcm9pZCBicmlkZ2Ugbm90IGF2YWlsYWJsZVwiKTtcbiAgICByZXR1cm47XG4gIH1cbiAgXG4gIGxldCBwYXJzZWRSZXF1ZXN0OiBBbmRyb2lkUmVxdWVzdDtcbiAgdHJ5IHtcbiAgICBwYXJzZWRSZXF1ZXN0ID0gSlNPTi5wYXJzZShyZXF1ZXN0KTtcbiAgfSBjYXRjaCAoZXJyb3IpIHtcbiAgICBjb25zb2xlLmVycm9yKFwiRmFpbGVkIHRvIHBhcnNlIHJlcXVlc3Q6XCIsIGVycm9yKTtcbiAgICByZXR1cm47XG4gIH1cbiAgXG4gIGNvbnN0IHsgaWQsIG1ldGhvZCwgcGFyYW1zIH0gPSBwYXJzZWRSZXF1ZXN0O1xuICBcbiAgdHJ5IHtcbiAgICBsZXQgcmVzdWx0OiBzdHJpbmc7XG4gICAgXG4gICAgc3dpdGNoIChtZXRob2QpIHtcbiAgICAgIGNhc2UgXCJtaW50VG9rZW5cIjpcbiAgICAgICAgcmVzdWx0ID0gYXdhaXQgbWludFRva2VuKHBhcmFtcy5pZGVudGl0eUpzb24sIHBhcmFtcy50b2tlbkRhdGFKc29uKTtcbiAgICAgICAgYnJlYWs7XG4gICAgICAgIFxuICAgICAgY2FzZSBcInByZXBhcmVUcmFuc2ZlclwiOlxuICAgICAgICByZXN1bHQgPSBhd2FpdCBwcmVwYXJlVHJhbnNmZXIoXG4gICAgICAgICAgcGFyYW1zLnNlbmRlcklkZW50aXR5SnNvbixcbiAgICAgICAgICBwYXJhbXMucmVjaXBpZW50QWRkcmVzcyxcbiAgICAgICAgICBwYXJhbXMudG9rZW5Kc29uLFxuICAgICAgICAgIHBhcmFtcy5pc09mZmxpbmVcbiAgICAgICAgKTtcbiAgICAgICAgYnJlYWs7XG4gICAgICAgIFxuICAgICAgY2FzZSBcImZpbmFsaXplUmVjZWl2ZWRUcmFuc2FjdGlvblwiOlxuICAgICAgICByZXN1bHQgPSBhd2FpdCBmaW5hbGl6ZVJlY2VpdmVkVHJhbnNhY3Rpb24oXG4gICAgICAgICAgcGFyYW1zLnJlY2VpdmVySWRlbnRpdHlKc29uLFxuICAgICAgICAgIHBhcmFtcy50cmFuc2ZlclBhY2thZ2VKc29uXG4gICAgICAgICk7XG4gICAgICAgIGJyZWFrO1xuICAgICAgICBcbiAgICAgIGRlZmF1bHQ6XG4gICAgICAgIHRocm93IG5ldyBFcnJvcihgVW5rbm93biBtZXRob2Q6ICR7bWV0aG9kfWApO1xuICAgIH1cbiAgICBcbiAgICB3aW5kb3cuQW5kcm9pZC5vblJlc3VsdChpZCwgcmVzdWx0KTtcbiAgfSBjYXRjaCAoZXJyb3I6IGFueSkge1xuICAgIGNvbnNvbGUuZXJyb3IoYEVycm9yIGhhbmRsaW5nICR7bWV0aG9kfTpgLCBlcnJvcik7XG4gICAgd2luZG93LkFuZHJvaWQub25FcnJvcihpZCwgZXJyb3IubWVzc2FnZSB8fCBcIlVua25vd24gZXJyb3JcIik7XG4gIH1cbn1cblxuXG4vLyBFeHBvcnQgdGhlIGhhbmRsZXIgZm9yIEFuZHJvaWQgYnJpZGdlIGNvbW11bmljYXRpb25cbih3aW5kb3cgYXMgYW55KS5oYW5kbGVBbmRyb2lkUmVxdWVzdCA9IGhhbmRsZUFuZHJvaWRSZXF1ZXN0O1xuXG4vLyBBbHNvIGV4cG9ydCBpbmRpdmlkdWFsIGZ1bmN0aW9ucyBmb3IgdGVzdGluZyBwdXJwb3Nlc1xuKHdpbmRvdyBhcyBhbnkpLm1pbnRUb2tlbiA9IG1pbnRUb2tlbjtcbih3aW5kb3cgYXMgYW55KS5wcmVwYXJlVHJhbnNmZXIgPSBwcmVwYXJlVHJhbnNmZXI7XG4od2luZG93IGFzIGFueSkuZmluYWxpemVSZWNlaXZlZFRyYW5zYWN0aW9uID0gZmluYWxpemVSZWNlaXZlZFRyYW5zYWN0aW9uOyJdLCJuYW1lcyI6W10sInNvdXJjZVJvb3QiOiIifQ==