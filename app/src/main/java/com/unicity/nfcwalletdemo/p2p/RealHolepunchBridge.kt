package com.unicity.nfcwalletdemo.p2p

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.*

/**
 * Real Holepunch/Hyperswarm implementation using Node.js subprocess
 * 
 * REQUIREMENTS:
 * 1. User must have Termux installed with Node.js
 * 2. Or ship Node.js binary with the app
 * 
 * This runs actual Hyperswarm in a Node.js process and communicates via stdin/stdout
 */
open class RealHolepunchBridge(private val context: Context) {
    companion object {
        private const val TAG = "RealHolepunchBridge"
    }
    
    private var nodeProcess: Process? = null
    private var processInput: BufferedWriter? = null
    private var processOutput: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val eventHandlers = mutableMapOf<String, (JSONObject) -> Unit>()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    
    fun initialize() {
        scope.launch {
            try {
                if (!setupNodeJS()) {
                    Log.e(TAG, "FATAL: Cannot run Holepunch without Node.js")
                    Log.e(TAG, "Install Termux and run: pkg install nodejs")
                    return@launch
                }
                _isReady.value = true
                Log.d(TAG, "Real Holepunch bridge initialized with Node.js")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Holepunch", e)
            }
        }
    }
    
    private suspend fun setupNodeJS(): Boolean {
        // First, copy Hyperswarm code to accessible location
        val nodeDir = File(context.getExternalFilesDir(null), "holepunch-node")
        if (!nodeDir.exists()) {
            copyAssetFolder("holepunch-node", nodeDir.parentFile!!)
        }
        
        // Check if bundled Node.js exists and extract if needed
        val bundledNodePath = "${context.filesDir}/node/bin/node"
        if (!File(bundledNodePath).exists()) {
            // Try to extract bundled Node.js if it exists in assets
            if (extractBundledNodeJS()) {
                Log.d(TAG, "Successfully extracted bundled Node.js")
            }
        }
        
        // Try to find Node.js
        val nodePaths = listOf(
            bundledNodePath, // Bundled with app (priority for production)
            "/data/data/com.termux/files/usr/bin/node", // Termux (for development)
            "/system/bin/node" // System (unlikely)
        )
        
        val nodePath = nodePaths.find { File(it).exists() }
        if (nodePath == null) {
            Log.e(TAG, "Node.js not found. Please ensure Node.js binary is bundled in assets/node/")
            return false
        }
        
        Log.d(TAG, "Found Node.js at: $nodePath")
        
        // Start Node.js process with Hyperswarm
        try {
            val processBuilder = ProcessBuilder(
                nodePath,
                File(nodeDir, "index.js").absolutePath
            )
            
            processBuilder.directory(nodeDir)
            nodeProcess = processBuilder.start()
            
            processInput = BufferedWriter(OutputStreamWriter(nodeProcess!!.outputStream))
            processOutput = BufferedReader(InputStreamReader(nodeProcess!!.inputStream))
            
            // Start reading output
            scope.launch {
                readProcessOutput()
            }
            
            // Initialize Hyperswarm
            sendToNode(JSONObject().apply {
                put("cmd", "init")
            })
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Node.js", e)
            return false
        }
    }
    
    private suspend fun readProcessOutput() {
        try {
            processOutput?.forEachLine { line ->
                if (line.startsWith("EVENT:")) {
                    val eventData = line.substring(6)
                    val json = JSONObject(eventData)
                    val event = json.getString("event")
                    val data = json.getJSONObject("data")
                    
                    scope.launch(Dispatchers.Main) {
                        eventHandlers[event]?.invoke(data)
                    }
                } else {
                    Log.d(TAG, "Node.js: $line")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Node.js output", e)
        }
    }
    
    private fun sendToNode(data: JSONObject) {
        try {
            processInput?.write(data.toString())
            processInput?.newLine()
            processInput?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to Node.js", e)
        }
    }
    
    fun addEventListener(event: String, handler: (JSONObject) -> Unit) {
        eventHandlers[event] = handler
    }
    
    fun removeEventListener(event: String) {
        eventHandlers.remove(event)
    }
    
    suspend fun sendCommand(command: String, data: JSONObject) {
        withContext(Dispatchers.IO) {
            sendToNode(JSONObject().apply {
                put("cmd", command)
                put("data", data)
            })
        }
    }
    
    private fun extractBundledNodeJS(): Boolean {
        return try {
            // Check if node binary exists in assets
            val nodeAssets = context.assets.list("node") ?: return false
            if (nodeAssets.isEmpty()) {
                Log.d(TAG, "No bundled Node.js found in assets/node/")
                return false
            }
            
            // Create target directory
            val nodeDir = File(context.filesDir, "node")
            nodeDir.mkdirs()
            
            // Extract all node files
            copyAssetFolder("node", context.filesDir)
            
            // Make node binary executable
            val nodeBinary = File(nodeDir, "bin/node")
            if (nodeBinary.exists()) {
                nodeBinary.setExecutable(true)
                Log.d(TAG, "Extracted Node.js binary to: ${nodeBinary.absolutePath}")
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bundled Node.js", e)
            false
        }
    }
    
    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assetFiles = context.assets.list(assetPath) ?: return
        
        val targetPath = File(targetDir, assetPath)
        targetPath.mkdirs()
        
        assetFiles.forEach { file ->
            val assetFile = "$assetPath/$file"
            val targetFile = File(targetPath, file)
            
            if (context.assets.list(assetFile)?.isNotEmpty() == true) {
                copyAssetFolder(assetFile, targetDir)
            } else {
                context.assets.open(assetFile).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setExecutable(true)
            }
        }
    }
    
    fun destroy() {
        scope.cancel()
        nodeProcess?.destroyForcibly()
        _isReady.value = false
    }
}