package com.unicity.nfcwalletdemo.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnicitySdkService(context: Context) {

    private val webView: WebView = WebView(context)
    private val callbacks = ConcurrentHashMap<String, (Result<String>) -> Unit>()
    private val gson = Gson()
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
            allowFileAccess = true
            allowContentAccess = true
            // Allow network access from file URLs for the SDK to work
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
            // Enable network access
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setUserAgentString(userAgentString + " UnicityWallet/1.0")
        }
        
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView loaded: $url")
                isInitialized = true
            }
        }
        
        webView.loadUrl("file:///android_asset/bridge.html")
    }

    // Data classes for structured communication
    private data class AndroidRequest(
        val id: String,
        val method: String,
        val params: Map<String, Any>
    )

    // Public API methods
    fun generateIdentity(callback: (Result<String>) -> Unit) {
        // For now, generate a simple identity locally
        // In the future, this could use the BIP-39 identity from IdentityManager
        val identity = UnicityIdentity(
            secret = UUID.randomUUID().toString().replace("-", ""),
            nonce = UUID.randomUUID().toString().replace("-", "")
        )
        callback(Result.success(identity.toJson()))
    }

    fun mintToken(identityJson: String, tokenDataJson: String, callback: (Result<String>) -> Unit) {
        val params = mapOf(
            "identityJson" to identityJson,
            "tokenDataJson" to tokenDataJson
        )
        callWrapperMethod("mintToken", params, callback)
    }
    
    fun createOfflineTransferPackage(
        senderIdentityJson: String,
        recipientAddress: String,
        tokenJson: String,
        callback: (Result<String>) -> Unit
    ) {
        val params = mapOf(
            "senderIdentityJson" to senderIdentityJson,
            "recipientAddress" to recipientAddress,
            "tokenJson" to tokenJson,
            "isOffline" to true
        )
        callWrapperMethod("prepareTransfer", params, callback)
    }

    fun completeOfflineTransfer(
        receiverIdentityJson: String,
        offlineTransactionJson: String,
        callback: (Result<String>) -> Unit
    ) {
        val params = mapOf(
            "receiverIdentityJson" to receiverIdentityJson,
            "transferPackageJson" to offlineTransactionJson
        )
        callWrapperMethod("finalizeReceivedTransaction", params, callback)
    }

    fun createTransfer(
        senderIdentityJson: String, 
        receiverIdentityJson: String, 
        tokenJson: String, 
        callback: (Result<String>) -> Unit
    ) {
        // Extract receiver address from receiver identity
        val receiverIdentity = UnicityIdentity.fromJson(receiverIdentityJson)
        val recipientAddress = "oddity_${receiverIdentity.secret.take(16)}" // Simplified address generation
        
        val params = mapOf(
            "senderIdentityJson" to senderIdentityJson,
            "recipientAddress" to recipientAddress,
            "tokenJson" to tokenJson,
            "isOffline" to false
        )
        callWrapperMethod("prepareTransfer", params, callback)
    }

    fun finishTransfer(receiverIdentityJson: String, transferJson: String, callback: (Result<String>) -> Unit) {
        val params = mapOf(
            "receiverIdentityJson" to receiverIdentityJson,
            "transferPackageJson" to transferJson
        )
        callWrapperMethod("finalizeReceivedTransaction", params, callback)
    }
    
    // Test methods
    fun runOfflineTransferTest(callback: (Result<String>) -> Unit) {
        if (!isInitialized) {
            callback(Result.failure(Exception("SDK not initialized")))
            return
        }
        
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Running offline transfer test")
            webView.evaluateJavascript("runOfflineTransferTest()", null)
            // Return success immediately since the test runs asynchronously and logs results
            callback(Result.success("Test started - check console logs"))
        }
    }

    // Simplified method to call wrapper functions using the structured approach
    private fun callWrapperMethod(method: String, params: Map<String, Any>, callback: (Result<String>) -> Unit) {
        if (!isInitialized) {
            callback(Result.failure(Exception("SDK not initialized")))
            return
        }
        
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        
        val request = AndroidRequest(
            id = requestId,
            method = method,
            params = params
        )
        
        val requestJson = gson.toJson(request)
        val escapedJson = escapeJavaScriptString(requestJson)
        
        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Calling wrapper method: $method")
            webView.evaluateJavascript("handleAndroidRequest('$escapedJson')", null)
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

    private inner class AndroidBridge {
        @JavascriptInterface
        fun onResult(requestId: String, data: String) {
            Handler(Looper.getMainLooper()).post {
                Log.d(TAG, "Success response for request $requestId: $data")
                callbacks[requestId]?.invoke(Result.success(data))
                callbacks.remove(requestId)
            }
        }
        
        @JavascriptInterface
        fun onError(requestId: String, error: String) {
            Handler(Looper.getMainLooper()).post {
                Log.e(TAG, "Error response for request $requestId: $error")
                callbacks[requestId]?.invoke(Result.failure(Exception(error)))
                callbacks.remove(requestId)
            }
        }
        
        @JavascriptInterface
        fun showToast(message: String) {
            Handler(Looper.getMainLooper()).post {
                Log.i(TAG, "Toast from JS: $message")
                // Toast would require context, so just log for now
            }
        }
    }
    
    fun destroy() {
        webView.destroy()
        callbacks.clear()
    }
}