package com.unicity.nfcwalletdemo.sdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class UnicityIdentity(
    val secret: String,
    val nonce: String
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): UnicityIdentity = Gson().fromJson(json, UnicityIdentity::class.java)
    }
}

data class UnicityTokenData(
    val data: String = "Default token data",
    val amount: Long = 100,
    val stateData: String = "Default state"
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): UnicityTokenData = Gson().fromJson(json, UnicityTokenData::class.java)
    }
}

data class UnicityMintResult(
    val token: Any,
    val identity: UnicityIdentity,
    val status: String? = null  // Add status field for pending tokens
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): UnicityMintResult = Gson().fromJson(json, UnicityMintResult::class.java)
        
        fun success(tokenJson: String): UnicityMintResult {
            // Parse token JSON to extract identity if possible
            // For now, create a dummy identity since we need to refactor this
            val dummyIdentity = UnicityIdentity("", "")
            return UnicityMintResult(tokenJson, dummyIdentity, "success")
        }
        
        fun error(message: String): UnicityMintResult {
            val dummyIdentity = UnicityIdentity("", "")
            return UnicityMintResult(message, dummyIdentity, "error")
        }
    }
}

data class UnicityTransferResult(
    val token: Any,
    val transaction: Any,
    @SerializedName("receiverPredicate")
    val receiverPredicate: UnicityIdentity
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): UnicityTransferResult = Gson().fromJson(json, UnicityTransferResult::class.java)
    }
}

data class UnicityToken(
    val id: String,
    val name: String,
    val data: String,
    val size: Int,
    val identity: UnicityIdentity? = null,
    val mintResult: UnicityMintResult? = null,
    val isReal: Boolean = true
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): UnicityToken = Gson().fromJson(json, UnicityToken::class.java)
    }
}