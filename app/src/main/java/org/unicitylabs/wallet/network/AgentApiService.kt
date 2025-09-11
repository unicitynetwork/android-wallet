package org.unicitylabs.wallet.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AgentApiService {
    companion object {
        private const val TAG = "AgentApiService"
        // Backend URL - same as TransferApiService
        private const val LAMBDA_URL = "https://7jmtgxyogc.execute-api.me-central-1.amazonaws.com/Prod"
        private const val LOCAL_URL = "http://10.0.2.2:3001" // For Android emulator
        
        // Use LAMBDA_URL for production
        private val API_URL = LAMBDA_URL
    }
    
    suspend fun updateAgentLocation(
        unicityTag: String,
        latitude: Double,
        longitude: Double,
        isActive: Boolean,
        connectionInfo: AgentConnectionInfo? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_URL/agents/update-location")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            
            val body = JSONObject().apply {
                put("unicityTag", unicityTag)
                put("latitude", latitude)
                put("longitude", longitude)
                put("isActive", isActive)
                
                // Add connection info for P2P
                connectionInfo?.let {
                    put("connectionInfo", JSONObject().apply {
                        put("localIp", it.localIp)
                        put("publicIp", it.publicIp)
                        put("wsPort", it.wsPort)
                        put("supportsRelay", it.supportsRelay)
                    })
                }
            }
            
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Agent location updated successfully")
                Result.success(Unit)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Failed to update agent location: $responseCode - $error")
                Result.failure(Exception("Failed to update agent location: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating agent location", e)
            Result.failure(e)
        }
    }
    
    suspend fun getNearbyAgents(
        latitude: Double,
        longitude: Double,
        maxDistance: Double = 100.0
    ): Result<List<Agent>> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_URL/agents/nearby?lat=$latitude&lon=$longitude&maxDistance=$maxDistance")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
                
                val agents = mutableListOf<Agent>()
                val jsonArray = org.json.JSONArray(response)
                
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    agents.add(
                        Agent(
                            unicityTag = jsonObject.getString("unicityTag"),
                            latitude = jsonObject.getDouble("latitude"),
                            longitude = jsonObject.getDouble("longitude"),
                            distance = jsonObject.getDouble("distance"),
                            lastUpdateAt = jsonObject.getString("lastUpdateAt")
                        )
                    )
                }
                
                Result.success(agents)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Failed to get nearby agents: $responseCode - $error")
                Result.failure(Exception("Failed to get nearby agents: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting nearby agents", e)
            Result.failure(e)
        }
    }
    
    suspend fun deactivateAgent(unicityTag: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_URL/agents/$unicityTag")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "DELETE"
                setRequestProperty("Content-Type", "application/json")
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Agent deactivated successfully")
                Result.success(Unit)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Failed to deactivate agent: $responseCode - $error")
                Result.failure(Exception("Failed to deactivate agent: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating agent", e)
            Result.failure(e)
        }
    }
}

data class Agent(
    val unicityTag: String,
    val latitude: Double,
    val longitude: Double,
    val distance: Double,
    val lastUpdateAt: String
)

data class AgentConnectionInfo(
    val localIp: String,
    val publicIp: String?,
    val wsPort: Int,
    val supportsRelay: Boolean
)