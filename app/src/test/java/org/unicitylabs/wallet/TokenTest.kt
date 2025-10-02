package org.unicitylabs.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.unicitylabs.wallet.data.model.Token

class TokenTest {
    
    @Test
    fun token_creation_isCorrect() {
        val token = Token(
            name = "Test Token",
            type = "TestType"
        )
        
        assertNotNull(token.id)
        assertEquals("Test Token", token.name)
        assertEquals("TestType", token.type)
        assertTrue(token.timestamp > 0)
    }
    
    @Test
    fun token_copy_withAddress_isCorrect() {
        val originalToken = Token(
            name = "Test Token",
            type = "TestType"
        )
        
        val copiedToken = originalToken.copy(
            unicityAddress = "test_address_123"
        )
        
        assertEquals(originalToken.id, copiedToken.id)
        assertEquals(originalToken.name, copiedToken.name)
        assertEquals("test_address_123", copiedToken.unicityAddress)
    }
}