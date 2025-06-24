package com.unicity.nfcwalletdemo.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class UnicitySdkService(context: Context) {

    private val webView: WebView = WebView(context)
    private val callbacks = ConcurrentHashMap<String, (Result<String>) -> Unit>()
    private var isInitialized = false
    
    companion object {
        private const val TAG = "UnicitySdkService"
    }

    init {
        setupWebView()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
        }
        
        webView.addJavascriptInterface(UnicityJsBridge(), "AndroidBridge")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView loaded: $url")
                isInitialized = true
            }
        }
        
        webView.loadUrl("file:///android_asset/bridge.html")
    }

    fun generateIdentity(callback: (Result<String>) -> Unit) {
        executeJs("generateIdentity()") { result ->
            callback(result)
        }
    }

    fun mintToken(identityJson: String, tokenDataJson: String, callback: (Result<String>) -> Unit) {
        val escapedIdentity = escapeJavaScriptString(identityJson)
        val escapedTokenData = escapeJavaScriptString(tokenDataJson)
        
        executeJs("mintToken('$escapedIdentity', '$escapedTokenData')") { result ->
            callback(result)
        }
    }

    fun createTransfer(
        senderIdentityJson: String, 
        receiverIdentityJson: String, 
        tokenJson: String, 
        callback: (Result<String>) -> Unit
    ) {
        val escapedSenderIdentity = escapeJavaScriptString(senderIdentityJson)
        val escapedReceiverIdentity = escapeJavaScriptString(receiverIdentityJson)
        val escapedToken = escapeJavaScriptString(tokenJson)
        
        executeJs("createTransfer('$escapedSenderIdentity', '$escapedReceiverIdentity', '$escapedToken')") { result ->
            callback(result)
        }
    }

    fun finishTransfer(receiverIdentityJson: String, transferJson: String, callback: (Result<String>) -> Unit) {
        val escapedReceiverIdentity = escapeJavaScriptString(receiverIdentityJson)
        val escapedTransfer = escapeJavaScriptString(transferJson)
        
        executeJs("finishTransfer('$escapedReceiverIdentity', '$escapedTransfer')") { result ->
            callback(result)
        }
    }

    fun runAutomatedTransferTest(callback: (Result<String>) -> Unit) {
        executeJs("runAutomatedTransferTest()") { result ->
            callback(result)
        }
    }

    fun runAutomatedOfflineTransferTest(callback: (Result<String>) -> Unit) {
        executeJs("runAutomatedOfflineTransferTest()") { result ->
            callback(result)
        }
    }

    /**
     * Creates an offline transfer package that can be transmitted via NFC without network
     */
    fun createOfflineTransferPackage(
        senderIdentityJson: String,
        recipientAddress: String,
        tokenJson: String,
        callback: (Result<String>) -> Unit
    ) {
        val escapedSenderIdentity = escapeJavaScriptString(senderIdentityJson)
        val escapedRecipientAddress = escapeJavaScriptString(recipientAddress)
        val escapedToken = escapeJavaScriptString(tokenJson)
        
        executeJs("createOfflineTransferPackage('$escapedSenderIdentity', '$escapedRecipientAddress', '$escapedToken')") { result ->
            callback(result)
        }
    }

    /**
     * Completes an offline transfer received via NFC
     */
    fun completeOfflineTransfer(
        receiverIdentityJson: String,
        offlineTransactionJson: String,
        callback: (Result<String>) -> Unit
    ) {
        val escapedReceiverIdentity = escapeJavaScriptString(receiverIdentityJson)
        val escapedOfflineTransaction = escapeJavaScriptString(offlineTransactionJson)
        
        executeJs("completeOfflineTransfer('$escapedReceiverIdentity', '$escapedOfflineTransaction')") { result ->
            callback(result)
        }
    }

    /**
     * Generates a receiving address for a token type
     */
    fun generateReceivingAddress(
        tokenIdHex: String,
        tokenTypeHex: String,
        receiverIdentityJson: String,
        callback: (Result<String>) -> Unit
    ) {
        val escapedTokenId = escapeJavaScriptString(tokenIdHex)
        val escapedTokenType = escapeJavaScriptString(tokenTypeHex)
        val escapedReceiverIdentity = escapeJavaScriptString(receiverIdentityJson)
        
        executeJs("generateReceivingAddress('$escapedTokenId', '$escapedTokenType', '$escapedReceiverIdentity')") { result ->
            callback(result)
        }
    }
    
    private fun executeJs(script: String, callback: (Result<String>) -> Unit) {
        if (!isInitialized) {
            callback(Result.failure(Exception("SDK not initialized")))
            return
        }
        
        val callId = java.util.UUID.randomUUID().toString()
        callbacks[callId] = callback
        
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Executing JavaScript: $script")
            webView.evaluateJavascript(script, null)
        }
    }
    
    private fun escapeJavaScriptString(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private inner class UnicityJsBridge {
        @JavascriptInterface
        fun postMessage(responseJson: String) {
            Handler(Looper.getMainLooper()).post {
                Log.d(TAG, "Received from JavaScript: $responseJson")
                
                val callback = callbacks.values.firstOrNull()
                callbacks.clear()

                try {
                    val response = JSONObject(responseJson)
                    when (response.getString("status")) {
                        "success" -> {
                            val data = response.getString("data")
                            callback?.invoke(Result.success(data))
                        }
                        "error" -> {
                            val message = response.getString("message")
                            callback?.invoke(Result.failure(Exception(message)))
                        }
                        else -> {
                            callback?.invoke(Result.failure(Exception("Unknown response status")))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JavaScript response", e)
                    callback?.invoke(Result.failure(e))
                }
            }
        }
    }
    
    fun destroy() {
        webView.destroy()
        callbacks.clear()
    }
}