package org.unicitylabs.wallet.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import java.math.BigInteger

/**
 * Serializes BigInteger as string in JSON to avoid precision loss
 * and support arbitrarily large token amounts
 */
class BigIntegerStringSerializer : JsonSerializer<BigInteger>() {
    override fun serialize(value: BigInteger?, gen: JsonGenerator, serializers: SerializerProvider) {
        if (value == null) {
            gen.writeNull()
        } else {
            gen.writeString(value.toString())
        }
    }
}
