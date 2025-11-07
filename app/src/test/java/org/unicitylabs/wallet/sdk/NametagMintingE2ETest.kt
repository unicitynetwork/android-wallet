package org.unicitylabs.wallet.sdk

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.api.SubmitCommitmentStatus
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.Token
import org.unicitylabs.sdk.token.TokenId
import org.unicitylabs.sdk.token.TokenState
import org.unicitylabs.sdk.token.TokenType
import org.unicitylabs.sdk.transaction.MintCommitment
import org.unicitylabs.sdk.transaction.MintTransaction
import org.unicitylabs.sdk.transaction.MintTransactionReason
import org.unicitylabs.sdk.util.InclusionProofUtils
import org.unicitylabs.wallet.di.ServiceProvider
import org.unicitylabs.wallet.util.HexUtils
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
    private lateinit var trustBase: RootTrustBase

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

        // Load the real trustbase from test resources
        trustBase = loadTrustBaseFromResources()
    }

    private fun loadTrustBaseFromResources(): RootTrustBase {
        return try {
            val resourceStream = javaClass.classLoader?.getResourceAsStream("trustbase-testnet.json")
                ?: throw IllegalStateException("trustbase-testnet.json not found in test resources")

            val json = resourceStream.bufferedReader().use { it.readText() }
            UnicityObjectMapper.JSON.readValue(json, RootTrustBase::class.java)
        } catch (e: Exception) {
            println("Failed to load trustbase from resources, using fallback: ${e.message}")
            // Fallback to ServiceProvider's trustbase
            ServiceProvider.getRootTrustBase()
        }
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

        // Step 2: Generate test identity (owner private key)
        val ownerPrivateKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }

        // Step 3: Mint the nametag (function creates all addresses internally)
        val nametagToken = withTimeout(3.minutes) {
            mintNametagDirectly(nametagString, ownerPrivateKey)
        }


        assertNotNull("Failed to mint nametag", nametagToken)
        println("✅ Nametag minted successfully!")
        println("Token ID: ${nametagToken?.getId()}")

        // Step 4: Verify the token structure
        assertNotNull("Token should have ID", nametagToken?.getId())
        assertNotNull("Token should have genesis", nametagToken?.getGenesis())
        assertNotNull("Token should have state", nametagToken?.getState())
        
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
        
        val signingService = SigningService.createFromMaskedSecret(secret, nonce)

        val ownerTokenId = TokenId(ByteArray(32).apply { random.nextBytes(this) })
        val ownerTokenType = TokenType(ByteArray(32).apply { random.nextBytes(this) })
        val ownerPredicate = MaskedPredicate.create(ownerTokenId, ownerTokenType, signingService, HashAlgorithm.SHA256, nonce)
        val ownerAddress = ownerPredicate.getReference().toAddress()

        val nametagTokenId = TokenId(ByteArray(32).apply { random.nextBytes(this) })
        val nametagTokenType = TokenType(ByteArray(32).apply { random.nextBytes(this) })
        val nametagPredicate = MaskedPredicate.create(nametagTokenId, nametagTokenType, signingService, HashAlgorithm.SHA256, nonce)
        val nametagAddress = nametagPredicate.getReference().toAddress()
        
        // First minting should succeed
        val firstToken = withTimeout(3.minutes) {
            mintNametagDirectly(nametagString, secret)
        }
        assertNotNull("First minting should succeed", firstToken)
        println("✅ First minting succeeded")

        // Second minting with same nametag should fail
        try {
            withTimeout(30.seconds) {
                mintNametagDirectly(nametagString, secret)
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
        ownerPrivateKey: ByteArray
    ): Token<*>? {
        return try {
            println("Minting nametag: $nametagString")

            // Create signing services (like faucet NametagMinter)
            val ownerSigningService = SigningService.createFromSecret(ownerPrivateKey)

            // Use FIXED token type (same as all tokens in the system)
            val UNICITY_TOKEN_TYPE = "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509"
            val tokenType = TokenType(HexUtils.decodeHex(UNICITY_TOKEN_TYPE))

            // Create nametag's own address (with random nonce)
            val nametagNonce = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val nametagSigningService = SigningService.createFromMaskedSecret(ownerPrivateKey, nametagNonce)
            val nametagAddress = org.unicitylabs.sdk.predicate.embedded.MaskedPredicateReference.create(
                tokenType,
                nametagSigningService,
                HashAlgorithm.SHA256,
                nametagNonce
            ).toAddress()

            // Create owner's target address (deterministic for receiving tokens)
            val ownerAddress = org.unicitylabs.sdk.predicate.embedded.UnmaskedPredicateReference.create(
                tokenType,
                ownerSigningService,
                HashAlgorithm.SHA256
            ).toAddress()

            // Create salt
            val salt = ByteArray(32).apply { SecureRandom().nextBytes(this) }

            // Create mint transaction data
            val mintTransactionData = MintTransaction.NametagData(
                nametagString,
                tokenType,
                nametagAddress,
                salt,
                ownerAddress
            )

            // Create mint commitment
            val mintCommitment: MintCommitment<MintTransactionReason> =
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
                trustBase,
                mintCommitment
            ).await()
            println("Inclusion proof received")

            // Create the genesis transaction
            val genesisTransaction = mintCommitment.toTransaction(inclusionProof)

            // Create token with correct predicate (using nametagSigningService that created nametagAddress)
            val loadedTrustBase = ServiceProvider.getRootTrustBase()
            val nametagToken = Token.create(
                loadedTrustBase,
                TokenState(
                    MaskedPredicate.create(
                        mintCommitment.getTransactionData().tokenId,
                        mintCommitment.getTransactionData().tokenType,
                        nametagSigningService,
                        HashAlgorithm.SHA256,
                        nametagNonce
                    ),
                    null
                ),
                genesisTransaction
            )

            println("Nametag minted successfully: $nametagString")
            nametagToken
            
        } catch (e: Exception) {
            println("Error minting nametag: ${e.message}")
            throw e
        }
    }

}
