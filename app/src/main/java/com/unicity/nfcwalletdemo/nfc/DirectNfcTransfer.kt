package com.unicity.nfcwalletdemo.nfc

import android.util.Log
import com.google.gson.Gson
import com.unicity.nfcwalletdemo.data.model.Token

object DirectNfcTransfer {
    private const val TAG = "DirectNfcTransfer"
    private const val MAX_CHUNK_SIZE = 245 // Safe NFC APDU size
    private val gson = Gson()
    
    // Commands
    private const val CMD_GET_TOKEN_SIZE: Byte = 0x10
    private const val CMD_GET_TOKEN_CHUNK: Byte = 0x11
    private const val CMD_SEND_TOKEN_START: Byte = 0x20
    private const val CMD_SEND_TOKEN_CHUNK: Byte = 0x21
    private const val CMD_SEND_TOKEN_END: Byte = 0x22
    
    fun serializeToken(token: Token): ByteArray {
        val json = gson.toJson(token)
        Log.d(TAG, "Serialized token size: ${json.toByteArray().size} bytes")
        return json.toByteArray()
    }
    
    fun deserializeToken(data: ByteArray): Token {
        val json = String(data)
        return gson.fromJson(json, Token::class.java)
    }
    
    fun splitIntoChunks(data: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            val chunkSize = minOf(MAX_CHUNK_SIZE, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + chunkSize)
            chunks.add(chunk)
            offset += chunkSize
        }
        
        Log.d(TAG, "Split ${data.size} bytes into ${chunks.size} chunks")
        return chunks
    }
    
    fun createSuccessResponse(): ByteArray {
        return byteArrayOf(0x90.toByte(), 0x00.toByte())
    }
    
    fun createErrorResponse(): ByteArray {
        return byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
    
    fun isSuccessResponse(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }
}