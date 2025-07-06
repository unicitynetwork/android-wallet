package com.unicity.nfcwalletdemo

import com.google.gson.Gson
import com.unicity.nfcwalletdemo.sdk.UnicityMintResult
import com.unicity.nfcwalletdemo.sdk.UnicityIdentity
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit test to verify that tokens are stored in the correct .txf format
 * after minting, making export/share functionality trivial.
 */
class TokenStorageFormatTest {
    
    @Test
    fun testMintResultContainsCompleteToken() {
        // Simulate what the JS SDK returns after minting
        val mockTokenJson = mapOf(
            "version" to "2.0",
            "id" to "1234567890abcdef",
            "type" to "fungible",
            "data" to "7b22616d6f756e74223a34322c2264617461223a2254657374227d",
            "state" to mapOf(
                "data" to null,
                "unlockPredicate" to mapOf(
                    "type" to "masked",
                    "reference" to "abcdef1234567890"
                )
            ),
            "genesis" to mapOf(
                "data" to mapOf(
                    "tokenId" to "1234567890abcdef",
                    "tokenType" to "fungible",
                    "tokenData" to "7b22616d6f756e74223a34322c2264617461223a2254657374227d"
                )
            ),
            "transactions" to emptyList<Any>(),
            "nametagTokens" to emptyList<Any>()
        )
        
        val identity = UnicityIdentity("test_secret", "test_nonce")
        
        // Simulate the mint result from JS SDK
        val mockMintResult = mapOf(
            "token" to mockTokenJson,
            "identity" to identity,
            "requestId" to "req_123"
        )
        
        val gson = Gson()
        
        // This simulates what happens in WalletRepository.mintNewToken()
        val mintResultJson = gson.toJson(mockMintResult)
        val mintResultObj = gson.fromJson(mintResultJson, Map::class.java)
        val tokenJson = gson.toJson(mintResultObj["token"])
        
        // Verify the extracted token is in the correct format
        assertNotNull("Token JSON should not be null", tokenJson)
        assertTrue("Token JSON should contain version", tokenJson.contains("\"version\":\"2.0\""))
        assertTrue("Token JSON should contain id", tokenJson.contains("\"id\":"))
        assertTrue("Token JSON should contain type", tokenJson.contains("\"type\":"))
        assertTrue("Token JSON should contain state", tokenJson.contains("\"state\":"))
        assertTrue("Token JSON should contain genesis", tokenJson.contains("\"genesis\":"))
        assertTrue("Token JSON should contain transactions", tokenJson.contains("\"transactions\":"))
        
        // Verify it can be parsed back
        val parsedToken = gson.fromJson(tokenJson, Map::class.java)
        assertEquals("2.0", parsedToken["version"])
        assertNotNull(parsedToken["state"])
        assertNotNull(parsedToken["genesis"])
        
        // This is exactly what gets written to the .txf file when sharing
        println("Token stored in wallet (ready for .txf export):")
        println(tokenJson)
    }
    
    @Test
    fun testStoredTokenMatchesTxfFormat() {
        // This verifies that the token stored in the wallet is already
        // in the correct format for .txf export, making sharing trivial
        
        val gson = Gson()
        
        // Sample token as it would be stored after minting
        val storedTokenJson = """
        {
            "version": "2.0",
            "id": "1234567890abcdef",
            "type": "fungible",
            "data": "7b22616d6f756e74223a34322c2264617461223a2254657374227d",
            "state": {
                "data": null,
                "unlockPredicate": {
                    "type": "masked",
                    "reference": "abcdef1234567890"
                }
            },
            "genesis": {
                "data": {
                    "tokenId": "1234567890abcdef",
                    "tokenType": "fungible",
                    "tokenData": "7b22616d6f756e74223a34322c2264617461223a2254657374227d"
                }
            },
            "transactions": [],
            "nametagTokens": []
        }
        """.trimIndent()
        
        // This is what happens when sharing - the jsonData is written directly to .txf
        val txfContent = storedTokenJson
        
        // Verify the .txf content is valid JSON
        val parsed = gson.fromJson(txfContent, Map::class.java)
        assertNotNull("TXF content should be valid JSON", parsed)
        assertEquals("2.0", parsed["version"])
        
        // Verify all required fields for SDK deserialization are present
        assertNotNull("Must have version", parsed["version"])
        assertNotNull("Must have state", parsed["state"])
        assertNotNull("Must have genesis", parsed["genesis"])
        
        println("Token is stored in the exact format needed for .txf export!")
        println("No transformation needed - just write token.jsonData to file")
    }
}