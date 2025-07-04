package com.unicity.nfcwalletdemo

import com.unicity.nfcwalletdemo.data.model.Token
import org.junit.Test
import org.junit.Assert.*

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