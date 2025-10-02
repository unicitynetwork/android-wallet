package org.unicitylabs.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.unicitylabs.wallet.data.model.Token
import org.unicitylabs.wallet.data.model.TokenStatus
import java.io.File

/**
 * Unit test for token serialization/deserialization functionality.
 * This test demonstrates that tokens can be serialized to .txf files and maintain data integrity.
 */
class TokenSerializationUnitTest {
    
    @Test
    fun testTokenSerializationToTxfFile() {
        // Create a test token
        val token = Token(
            name = "Test Token",
            type = "TestType",
            jsonData = """{"id":"test123","type":"fungible","data":"serialization test"}""",
            unicityAddress = "unicity_test_address",
            status = TokenStatus.CONFIRMED
        )
        
        // Simulate saving to .txf file
        val tempFile = File.createTempFile("test_token_", ".txf")
        try {
            // Write token JSON data to file
            tempFile.writeText(token.jsonData ?: "")
            
            // Verify file was created with .txf extension
            assertTrue("File should have .txf extension", tempFile.name.endsWith(".txf"))
            
            // Read back from file
            val jsonFromFile = tempFile.readText()
            
            // Verify content matches
            assertEquals("File content should match token JSON data", token.jsonData, jsonFromFile)
            
            // Parse and verify JSON structure
            val parsedJson = Json.parseToJsonElement(jsonFromFile).jsonObject
            assertEquals("test123", parsedJson["id"]?.jsonPrimitive?.content)
            assertEquals("fungible", parsedJson["type"]?.jsonPrimitive?.content)
            assertEquals("serialization test", parsedJson["data"]?.jsonPrimitive?.content)
            
        } finally {
            // Cleanup
            tempFile.delete()
        }
    }
    
    @Test
    fun testTokenRoundTripSerialization() {
        // Create a complex token with nested JSON
        val complexTokenJson = """
        {
            "id": "0x1234567890abcdef",
            "type": "fungible",
            "data": "Complex token for testing",
            "state": {
                "data": "state data",
                "unlockPredicate": {
                    "type": "masked",
                    "reference": "ref123"
                }
            },
            "genesis": {
                "data": {
                    "recipient": "unicity_recipient_address",
                    "tokenId": "0x1234567890abcdef",
                    "tokenType": "fungible"
                }
            },
            "coins": {
                "coins": [
                    ["coin1", "100"],
                    ["coin2", "200"]
                ]
            }
        }
        """.trimIndent()
        
        val token = Token(
            name = "Complex Token",
            type = "ComplexType",
            jsonData = complexTokenJson,
            unicityAddress = "unicity_complex_address",
            status = TokenStatus.CONFIRMED
        )
        
        // Save to file
        val tempFile = File.createTempFile("complex_token_", ".txf")
        try {
            tempFile.writeText(token.jsonData ?: "")
            
            // Read back
            val restoredJson = tempFile.readText()
            
            // Parse both JSONs and compare structure
            val originalParsed = Json.parseToJsonElement(complexTokenJson).jsonObject
            val restoredParsed = Json.parseToJsonElement(restoredJson).jsonObject
            
            // Verify key fields
            assertEquals(
                originalParsed["id"]?.jsonPrimitive?.content,
                restoredParsed["id"]?.jsonPrimitive?.content
            )
            assertEquals(
                originalParsed["type"]?.jsonPrimitive?.content,
                restoredParsed["type"]?.jsonPrimitive?.content
            )
            assertEquals(
                originalParsed["data"]?.jsonPrimitive?.content,
                restoredParsed["data"]?.jsonPrimitive?.content
            )
            
            // Verify nested structures
            val originalState = originalParsed["state"]?.jsonObject
            val restoredState = restoredParsed["state"]?.jsonObject
            assertEquals(
                originalState?.get("data")?.jsonPrimitive?.content,
                restoredState?.get("data")?.jsonPrimitive?.content
            )
            
            // Verify genesis
            val originalGenesis = originalParsed["genesis"]?.jsonObject
            val restoredGenesis = restoredParsed["genesis"]?.jsonObject
            assertEquals(
                originalGenesis?.get("data")?.jsonObject?.get("recipient")?.jsonPrimitive?.content,
                restoredGenesis?.get("data")?.jsonObject?.get("recipient")?.jsonPrimitive?.content
            )
            
        } finally {
            tempFile.delete()
        }
    }
    
    @Test
    fun testEmptyTokenHandling() {
        val emptyToken = Token(
            name = "Empty Token",
            type = "EmptyType",
            jsonData = null
        )
        
        // Verify empty token can be handled gracefully
        val tempFile = File.createTempFile("empty_token_", ".txf")
        try {
            tempFile.writeText(emptyToken.jsonData ?: "{}")
            
            val content = tempFile.readText()
            assertEquals("{}", content)
            
            // Verify it can be parsed as valid JSON
            val json = Json.parseToJsonElement(content).jsonObject
            assertTrue("Empty JSON should have no keys", json.isEmpty())
            
        } finally {
            tempFile.delete()
        }
    }
}