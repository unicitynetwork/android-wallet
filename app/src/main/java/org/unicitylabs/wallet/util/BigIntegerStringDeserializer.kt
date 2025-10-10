package org.unicitylabs.wallet.util

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import java.math.BigInteger

/**
 * Deserializes BigInteger from string in JSON
 * Supports both string and numeric representations
 */
class BigIntegerStringDeserializer : JsonDeserializer<BigInteger>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BigInteger? {
        return when {
            p.currentToken.isNumeric -> BigInteger(p.text)
            p.currentToken.isScalarValue -> {
                val text = p.text
                if (text.isNullOrEmpty()) null else BigInteger(text)
            }
            else -> null
        }
    }
}
