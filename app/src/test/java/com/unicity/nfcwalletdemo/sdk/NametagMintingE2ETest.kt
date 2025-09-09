package com.unicity.nfcwalletdemo.sdk

import com.unicity.nfcwalletdemo.di.ServiceProvider
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.MaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.NameTagTokenState
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenType
import org.unicitylabs.sdk.transaction.MintCommitment
import org.unicitylabs.sdk.transaction.MintTransactionReason
import org.unicitylabs.sdk.transaction.NametagMintTransactionData
import org.unicitylabs.sdk.util.InclusionProofUtils
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pure JVM end-to-end test for nametag minting against the real Unicity test aggregator.
 * 
 * This test does NOT require Android emulator - runs on pure JVM!
 * 
 * REQUIREMENTS:
 * - Internet connection to reach goggregator-test.unicity.network
 * 
 * Run with: ./gradlew test
 */
class NametagMintingE2ETest {
    
    private lateinit var stateTransitionClient: StateTransitionClient
    
    @Before
    fun setup() {
        // Check for internet connectivity
        val hasInternet = checkInternetConnection()
        
        Assume.assumeTrue(
            "Test requires internet connection to reach goggregator-test.unicity.network. " +
            "If running in CI/CD, ensure network access is available.",
            hasInternet
        )
        
        // Use the shared state transition client
        stateTransitionClient = ServiceProvider.stateTransitionClient
    }
    
    private fun checkInternetConnection(): Boolean {
        return try {
            val url = URL("https://goggregator-test.unicity.network/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            // Accept any valid HTTP response
            responseCode in 200..599
        } catch (e: Exception) {
            println("Internet check failed: ${e.message}")
            false
        }
    }
    
    @Test
    fun testMintNametagOnRealNetwork() = runBlocking {
        // Step 1: Generate a unique nametag string
        val randomSuffix = UUID.randomUUID().toString().take(8)
        val nametagString = "test_${randomSuffix}"
        println("Testing with nametag: $nametagString")
        
        // Step 2: Generate test identity
        val random = SecureRandom()
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        val salt = ByteArray(32)
        random.nextBytes(secret)
        random.nextBytes(nonce)
        random.nextBytes(salt)
        
        // Create signing service
        val signingService = SigningService.createFromSecret(secret, nonce)
        
        // Create predicate for owner
        val ownerPredicate = MaskedPredicate.create(
            signingService,
            HashAlgorithm.SHA256,
            nonce
        )
        
        // Create token type for owner address
        val ownerTokenType = TokenType(ByteArray(32).apply {
            random.nextBytes(this)
        })
        
        // Create owner address from predicate reference
        val ownerAddress = ownerPredicate.getReference(ownerTokenType).toAddress()
        println("Generated owner address")
        
        // Step 3: Create nametag predicate
        val nametagPredicate = MaskedPredicate.create(
            signingService,
            HashAlgorithm.SHA256,
            nonce
        )
        
        // Create token type for the nametag
        val nametagTokenType = TokenType(ByteArray(32).apply {
            random.nextBytes(this)
        })
        
        // Get the nametag address
        val nametagAddress = nametagPredicate.getReference(nametagTokenType).toAddress()
        
        // Step 4: Mint the nametag
        val nametagToken = withTimeout(3.minutes) {
            mintNametagDirectly(
                nametagString,
                nametagTokenType,
                nametagAddress,
                ownerAddress,
                salt
            )
        }
        
        assertNotNull("Failed to mint nametag", nametagToken)
        println("✅ Nametag minted successfully!")
        println("Token ID: ${nametagToken?.id}")
        
        // Step 5: Verify the token structure
        assertNotNull("Token should have ID", nametagToken?.id)
        assertNotNull("Token should have genesis", nametagToken?.genesis)
        assertNotNull("Token should have state", nametagToken?.state)
        
        println("✅ All verifications passed!")
    }
    
    @Test
    fun testNametagDuplicatePrevention() = runBlocking {
        // Generate a unique nametag
        val randomSuffix = UUID.randomUUID().toString().take(8)
        val nametagString = "test_dup_${randomSuffix}"
        println("Testing duplicate prevention with: $nametagString")
        
        // Generate test identity
        val random = SecureRandom()
        val secret = ByteArray(32)
        val nonce = ByteArray(32)
        val salt = ByteArray(32)
        random.nextBytes(secret)
        random.nextBytes(nonce)
        random.nextBytes(salt)
        
        val signingService = SigningService.createFromSecret(secret, nonce)
        val ownerPredicate = MaskedPredicate.create(signingService, HashAlgorithm.SHA256, nonce)
        val ownerTokenType = TokenType(ByteArray(32).apply { random.nextBytes(this) })
        val ownerAddress = ownerPredicate.getReference(ownerTokenType).toAddress()
        
        val nametagPredicate = MaskedPredicate.create(signingService, HashAlgorithm.SHA256, nonce)
        val nametagTokenType = TokenType(ByteArray(32).apply { random.nextBytes(this) })
        val nametagAddress = nametagPredicate.getReference(nametagTokenType).toAddress()
        
        // First minting should succeed
        val firstToken = withTimeout(3.minutes) {
            mintNametagDirectly(nametagString, nametagTokenType, nametagAddress, ownerAddress, salt)
        }
        assertNotNull("First minting should succeed", firstToken)
        println("✅ First minting succeeded")
        
        // Second minting with same nametag should fail
        val salt2 = ByteArray(32).apply { random.nextBytes(this) }
        try {
            withTimeout(30.seconds) {
                mintNametagDirectly(nametagString, nametagTokenType, nametagAddress, ownerAddress, salt2)
            }
            fail("Second minting should have failed due to duplicate nametag")
        } catch (e: Exception) {
            println("✅ Duplicate minting correctly prevented: ${e.message}")
            // The exact error depends on the blockchain implementation
            // But it should fail in some way
        }
    }
    
    private suspend fun mintNametagDirectly(
        nametagString: String,
        nametagTokenType: TokenType,
        nametagAddress: DirectAddress,
        ownerAddress: DirectAddress,
        salt: ByteArray
    ): Token<*>? {
        return try {
            println("Minting nametag: $nametagString")
            
            // Create mint transaction data
            val mintTransactionData: NametagMintTransactionData<MintTransactionReason> = 
                NametagMintTransactionData(
                    nametagString,
                    nametagTokenType,
                    ByteArray(10), // Token data
                    null, // No coin data for nametag
                    nametagAddress,
                    salt,
                    ownerAddress
                )
            
            // Create mint commitment
            val mintCommitment: MintCommitment<NametagMintTransactionData<MintTransactionReason>> = 
                MintCommitment.create(mintTransactionData)
            
            // Submit to blockchain
            val submitResponse = stateTransitionClient.submitCommitment(mintCommitment).await()
            
            if (submitResponse.status != SubmitCommitmentStatus.SUCCESS) {
                throw RuntimeException("Failed to submit commitment: ${submitResponse.status}")
            }
            
            println("Commitment submitted successfully")
            
            // Wait for inclusion proof
            val inclusionProof = InclusionProofUtils.waitInclusionProof(
                stateTransitionClient,
                mintCommitment
            ).await()
            println("Inclusion proof received")
            
            // Create the genesis transaction
            val genesisTransaction = mintCommitment.toTransaction(inclusionProof)
            
            // Create the nametag token
            // Note: Using a simple predicate as placeholder for the state
            val nametagState = NameTagTokenState(
                MaskedPredicate.create(
                    SigningService.createFromSecret(ByteArray(32), ByteArray(32)),
                    HashAlgorithm.SHA256,
                    ByteArray(32)
                ),
                ownerAddress
            )
            
            val nametagToken = Token(nametagState, genesisTransaction)
            
            println("Nametag minted successfully: $nametagString")
            nametagToken
            
        } catch (e: Exception) {
            println("Error minting nametag: ${e.message}")
            throw e
        }
    }
}