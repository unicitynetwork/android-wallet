package com.unicity.nfcwalletdemo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unicity.nfcwalletdemo.identity.IdentityManager
import com.unicity.nfcwalletdemo.nametag.NametagService
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.MaskedPredicate
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assume

/**
 * End-to-end test for nametag minting against the real Unicity test aggregator.
 * 
 * REQUIREMENTS:
 * - Internet connection to reach goggregator-test.unicity.network
 * - If running on emulator, ensure it has internet access
 * - If tests fail with "Unable to resolve host", restart emulator or check network settings
 * 
 * This test will:
 * 1. Generate a random nametag string to avoid conflicts
 * 2. Create or use an existing wallet identity
 * 3. Mint the nametag on the real Unicity test network
 * 4. Verify the nametag was successfully minted and stored
 * 
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NametagMintingE2ETest {
    
    private lateinit var context: Context
    private lateinit var identityManager: IdentityManager
    private lateinit var nametagService: NametagService
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Check for internet connectivity
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        
        Assume.assumeTrue(
            "Test requires internet connection to reach goggregator-test.unicity.network. " +
            "If running on emulator, ensure it has internet access in AVD settings.",
            hasInternet
        )
        
        identityManager = IdentityManager(context)
        nametagService = NametagService(context)
    }
    
    @Test
    fun testMintNametagOnRealNetwork() = runBlocking {
        println("=== Starting E2E Nametag Minting Test ===")
        
        // Step 1: Generate a unique nametag string
        val randomSuffix = UUID.randomUUID().toString().take(8)
        val nametagString = "test_${randomSuffix}"
        println("Testing with nametag: $nametagString")
        
        // Step 2: Ensure we have a wallet identity
        val identity = ensureWalletIdentity()
        assertNotNull("Failed to create/get wallet identity", identity)
        println("Wallet identity ready")
        
        // Step 3: Create the owner address for the nametag
        val ownerAddress = createOwnerAddress(identity!!)
        assertNotNull("Failed to create owner address", ownerAddress)
        println("Owner address created: ${ownerAddress.address}")
        
        // Step 4: Check if nametag already exists locally (cleanup from previous test)
        if (nametagService.hasNametag(nametagString)) {
            println("Cleaning up existing local nametag: $nametagString")
            nametagService.deleteNametag(nametagString)
        }
        
        // Step 5: Mint the nametag with timeout (network operation)
        println("Minting nametag on Unicity test network...")
        println("This may take 30-60 seconds...")
        
        val nametagToken = withTimeout(3.minutes) {
            nametagService.mintNametag(nametagString, ownerAddress)
        }
        
        // Step 6: Verify minting was successful
        assertNotNull("Failed to mint nametag - token is null", nametagToken)
        println("✓ Nametag minted successfully!")
        
        // Step 7: Verify the nametag was saved locally
        assertTrue(
            "Nametag was not saved locally after minting",
            nametagService.hasNametag(nametagString)
        )
        println("✓ Nametag saved locally")
        
        // Step 8: Verify we can load the minted nametag
        val loadedNametag = nametagService.loadNametag(nametagString)
        assertNotNull("Failed to load minted nametag", loadedNametag)
        assertTrue(
            "Loaded nametag ID doesn't match minted token",
            nametagToken!!.id.bytes.contentEquals(loadedNametag?.id?.bytes ?: byteArrayOf())
        )
        println("✓ Nametag can be loaded from storage")
        
        // Step 9: Test export functionality
        val exportedData = nametagService.exportNametag(nametagString)
        assertNotNull("Failed to export nametag", exportedData)
        assertTrue(
            "Exported data doesn't contain expected format marker",
            exportedData!!.contains("\"format\":\"txf\"")
        )
        assertTrue(
            "Exported data doesn't contain nametag string",
            exportedData.contains("\"nametag\":\"$nametagString\"")
        )
        println("✓ Nametag can be exported as .txf")
        
        // Step 10: Get the proxy address for receiving tokens
        val proxyAddress = nametagService.getProxyAddress(nametagToken!!)
        assertNotNull("Failed to get proxy address", proxyAddress)
        println("✓ Proxy address created: ${proxyAddress.address}")
        
        // Step 11: Cleanup - delete the test nametag
        val deleted = nametagService.deleteNametag(nametagString)
        assertTrue("Failed to delete test nametag", deleted)
        println("✓ Test nametag cleaned up")
        
        println("=== E2E Nametag Minting Test PASSED ===")
        println("Successfully minted nametag '$nametagString' on Unicity test network!")
    }
    
    /**
     * Ensures we have a wallet identity, creating one if necessary
     */
    private suspend fun ensureWalletIdentity(): com.unicity.nfcwalletdemo.data.model.UserIdentity? {
        return if (identityManager.hasIdentity()) {
            identityManager.getCurrentIdentity()
        } else {
            // Generate a new identity for testing
            val (identity, _) = identityManager.generateNewIdentity()
            identity
        }
    }
    
    /**
     * Creates an owner address from the wallet identity
     */
    private fun createOwnerAddress(
        identity: com.unicity.nfcwalletdemo.data.model.UserIdentity
    ): DirectAddress {
        // Convert hex strings to byte arrays
        val secret = hexToBytes(identity.secret)
        val nonce = hexToBytes(identity.nonce)
        
        // Create signing service and predicate
        val signingService = SigningService.createFromSecret(secret, nonce)
        val predicate = MaskedPredicate.create(
            signingService,
            HashAlgorithm.SHA256,
            nonce
        )
        
        // Generate a token type for the address
        val tokenType = TokenType(ByteArray(32).apply {
            SecureRandom().nextBytes(this)
        })
        
        // Return the address
        return predicate.getReference(tokenType).toAddress()
    }
    
    /**
     * Converts hex string to byte array
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    @Test
    fun testNametagMintingWithExistingNametag() = runBlocking {
        println("=== Testing Duplicate Nametag Prevention ===")
        
        // Create a unique nametag
        val nametagString = "test_duplicate_${UUID.randomUUID().toString().take(8)}"
        
        // Ensure identity exists
        val identity = ensureWalletIdentity()
        assertNotNull(identity)
        
        val ownerAddress = createOwnerAddress(identity!!)
        
        // First minting should succeed
        val firstToken = withTimeout(3.minutes) {
            nametagService.mintNametag(nametagString, ownerAddress)
        }
        assertNotNull("First minting should succeed", firstToken)
        println("✓ First minting succeeded")
        
        // Second minting should return the existing token (not mint again)
        val secondToken = nametagService.mintNametag(nametagString, ownerAddress)
        assertNotNull("Second call should return existing token", secondToken)
        assertTrue(
            "Second call should return same token ID",
            firstToken?.id?.bytes?.contentEquals(secondToken?.id?.bytes ?: byteArrayOf()) ?: false
        )
        println("✓ Duplicate minting prevented - returned existing token")
        
        // Cleanup
        nametagService.deleteNametag(nametagString)
        
        println("=== Duplicate Prevention Test PASSED ===")
    }
    
    @Test
    fun testNametagImportExport() = runBlocking {
        println("=== Testing Nametag Import/Export ===")
        
        // Create and mint a nametag
        val nametagString = "test_export_${UUID.randomUUID().toString().take(8)}"
        val identity = ensureWalletIdentity()
        assertNotNull(identity)
        
        val ownerAddress = createOwnerAddress(identity!!)
        
        val originalToken = withTimeout(3.minutes) {
            nametagService.mintNametag(nametagString, ownerAddress)
        }
        assertNotNull("Failed to mint token for export test", originalToken)
        
        // Export the nametag
        val exportedData = nametagService.exportNametag(nametagString)
        assertNotNull("Failed to export nametag", exportedData)
        println("✓ Nametag exported: ${exportedData?.length} bytes")
        
        // Delete the original
        assertTrue(nametagService.deleteNametag(nametagString))
        assertFalse(
            "Nametag should not exist after deletion",
            nametagService.hasNametag(nametagString)
        )
        
        // Parse the exported data to get the nonce
        val objectMapper = org.unicitylabs.sdk.serializer.UnicityObjectMapper.JSON
        val exportedJson = objectMapper.readTree(exportedData)
        val nonceBase64 = exportedJson.get("nonce").asText()
        val nonce = android.util.Base64.decode(nonceBase64, android.util.Base64.NO_WRAP)
        
        // Import the nametag
        val importedToken = nametagService.importNametag(nametagString, exportedData!!, nonce)
        assertNotNull("Failed to import nametag", importedToken)
        println("✓ Nametag imported successfully")
        
        // Verify the imported token matches the original
        assertTrue(
            "Imported token ID should match original",
            originalToken?.id?.bytes?.contentEquals(importedToken?.id?.bytes ?: byteArrayOf()) ?: false
        )
        
        assertTrue(
            "Imported nametag should exist locally",
            nametagService.hasNametag(nametagString)
        )
        println("✓ Import/Export cycle completed successfully")
        
        // Cleanup
        nametagService.deleteNametag(nametagString)
        
        println("=== Import/Export Test PASSED ===")
    }
}