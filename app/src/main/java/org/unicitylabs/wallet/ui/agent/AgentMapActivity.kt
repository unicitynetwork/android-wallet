package org.unicitylabs.wallet.ui.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.data.chat.DismissedItem
import org.unicitylabs.wallet.data.chat.DismissedItemType
import org.unicitylabs.wallet.databinding.ActivityAgentMapBinding
import org.unicitylabs.wallet.network.Agent
import org.unicitylabs.wallet.network.AgentApiService
import org.unicitylabs.wallet.ui.chat.ChatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AgentMapActivity : AppCompatActivity(), OnMapReadyCallback {
    
    private lateinit var binding: ActivityAgentMapBinding
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var agentApiService: AgentApiService? = null
    private lateinit var agentAdapter: AgentAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private var chatDatabase: org.unicitylabs.wallet.data.chat.ChatDatabase? = null
    
    private var googleMap: GoogleMap? = null
    private var currentLocation: Location? = null
    private val agents = mutableListOf<Agent>()
    private val agentsWithChat = mutableSetOf<String>()
    private var isSatelliteView = false
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_ZOOM = 12f
        private const val TAG = "AgentMapActivity"
        
        // Demo agent names
        private val DEMO_AGENT_NAMES = listOf(
            "john_trader", "maria_exchange", "ahmed_crypto", "sarah_wallet",
            "david_cash", "fatima_money", "peter_exchange", "aisha_trader",
            "michael_crypto", "zainab_wallet", "james_money", "linda_exchange",
            "robert_trader", "amina_cash", "william_crypto"
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up custom toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // We use custom title
        
        // Show progress bar immediately while loading
        binding.progressBar.visibility = View.VISIBLE
        
        // Initialize only the most essential UI components first
        setupRecyclerView()
        setupBottomSheet()
        setupMapControls()
        setupChatIcon()
        
        // Defer ALL initialization to avoid ANR during activity transition
        // Use a longer delay to ensure the activity is completely rendered
        binding.root.postDelayed({
            // Initialize location client and API service after UI is ready
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            agentApiService = AgentApiService()
            
            // Setup map after a delay to avoid blocking during transition
            setupMap()
            
            // Initialize heavy components asynchronously
            lifecycleScope.launch {
                try {
                    chatDatabase = org.unicitylabs.wallet.data.chat.ChatDatabase.getDatabase(this@AgentMapActivity)
                    ensureP2PServiceRunning()
                    loadAgentsWithChat()
                    observeUnreadCount()
                    
                    // Check permissions and load location after other initialization
                    withContext(Dispatchers.Main) {
                        checkLocationPermissionAndLoad()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during initialization", e)
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@AgentMapActivity, "Error loading map", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 100) // 100ms delay to ensure smooth transition
    }
    
    private fun setupRecyclerView() {
        agentAdapter = AgentAdapter { agent ->
            // Handle agent click - zoom to agent on map
            val latLng = LatLng(agent.latitude, agent.longitude)
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            
            // Collapse bottom sheet to show map
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        
        binding.rvAgents.apply {
            layoutManager = LinearLayoutManager(this@AgentMapActivity)
            adapter = agentAdapter
        }
    }
    
    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }
    
    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Enable zoom controls
        googleMap?.uiSettings?.isZoomControlsEnabled = false // We use custom controls
        googleMap?.uiSettings?.isCompassEnabled = true
        
        // Set up custom info window adapter
        map.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoWindow(marker: Marker): View? = null
            
            override fun getInfoContents(marker: Marker): View? {
                if (marker.tag !is Agent) return null
                
                val view = layoutInflater.inflate(R.layout.custom_info_window, null)
                val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
                val tvSnippet = view.findViewById<TextView>(R.id.tvSnippet)
                
                tvTitle.text = marker.title
                tvSnippet.text = marker.snippet
                
                return view
            }
        })
        
        // Set up info window click listener for chat
        map.setOnInfoWindowClickListener { marker ->
            val agent = marker.tag as? Agent
            if (agent != null) {
                // Start chat activity
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_AGENT_TAG, agent.unicityTag)
                    putExtra(ChatActivity.EXTRA_AGENT_NAME, "${agent.unicityTag}@unicity")
                }
                startActivity(intent)
            }
        }
        
        // Check if demo mode is enabled
        val isDemoMode = org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this)
        
        if (isDemoMode) {
            // In demo mode, don't enable real location tracking
            googleMap?.isMyLocationEnabled = false
            
            // Add a blue marker at demo location if available
            currentLocation?.let {
                addUserLocationMarker(it.latitude, it.longitude)
                val latLng = LatLng(it.latitude, it.longitude)
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
            }
        } else {
            // Enable my location if permission granted
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                googleMap?.isMyLocationEnabled = true
            }
            
            // Move camera to current location if available
            currentLocation?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
            }
        }
    }
    
    private fun checkLocationPermissionAndLoad() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadCurrentLocationAndAgents()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    private fun loadCurrentLocationAndAgents() {
        binding.progressBar.visibility = View.VISIBLE
        
        // Load location and agents asynchronously to avoid blocking UI thread
        lifecycleScope.launch {
            try {
                // Check if demo mode is enabled
                if (org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this@AgentMapActivity)) {
                    // Use demo location
                    val demoLocation = org.unicitylabs.wallet.utils.UnicityLocationManager.createDemoLocation(this@AgentMapActivity)
                    currentLocation = demoLocation
                    
                    // Update map camera on main thread
                    withContext(Dispatchers.Main) {
                        val latLng = LatLng(demoLocation.latitude, demoLocation.longitude)
                        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
                    }
                    
                    // Load nearby agents (already runs in IO dispatcher)
                    loadNearbyAgents(demoLocation.latitude, demoLocation.longitude)
                } else if (ActivityCompat.checkSelfPermission(
                        this@AgentMapActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                        location?.let {
                            currentLocation = it
                            
                            // Update map camera
                            val latLng = LatLng(it.latitude, it.longitude)
                            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
                            
                            // Load nearby agents in coroutine
                            lifecycleScope.launch {
                                loadNearbyAgents(it.latitude, it.longitude)
                            }
                        } ?: run {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@AgentMapActivity, "Unable to get current location", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading location and agents", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@AgentMapActivity, "Error loading location", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadNearbyAgents(latitude: Double, longitude: Double) {
        lifecycleScope.launch {
            try {
                val result = agentApiService?.getNearbyAgents(latitude, longitude) ?: return@launch
                result.onSuccess { nearbyAgents ->
                    // Get current user's unicityTag to filter it out
                    val prefs = getSharedPreferences("UnicitywWalletPrefs", MODE_PRIVATE)
                    val currentUserTag = prefs.getString("unicity_tag", null)
                    
                    agents.clear()
                    // Filter out current user from the list
                    agents.addAll(nearbyAgents.filter { agent -> 
                        agent.unicityTag != currentUserTag
                    })
                    
                    // Update UI
                    updateAgentList()
                    addAgentMarkersToMap()
                    
                    binding.progressBar.visibility = View.GONE
                }.onFailure { error ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@AgentMapActivity,
                        "Failed to load agents: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@AgentMapActivity,
                    "Error loading agents: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun updateAgentList() {
        if (agents.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvAgents.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvAgents.visibility = View.VISIBLE
            // Force adapter to update with new list
            agentAdapter.submitList(agents.toList())
        }
    }
    
    private fun addAgentMarkersToMap() {
        googleMap?.clear()
        
        // Re-add user location marker if in demo mode
        if (org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this)) {
            currentLocation?.let {
                addUserLocationMarker(it.latitude, it.longitude)
            }
        }
        
        // Create scaled Unicity logo for markers
        val unicityIcon = getScaledUnicityIcon()
        val unicityIconWithChat = getScaledUnicityIconWithChat()
        
        agents.forEach { agent ->
            val latLng = LatLng(agent.latitude, agent.longitude)
            val hasChat = agentsWithChat.contains(agent.unicityTag)
            
            val marker = googleMap?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("${agent.unicityTag}@unicity")
                    .snippet("${String.format("%.1f", agent.distance)} km away${if (hasChat) " • 💬" else ""}")
                    .icon(if (hasChat) unicityIconWithChat else unicityIcon)
            )
            marker?.tag = agent
        }
    }
    
    private fun updateMapMarkers() {
        // Clear and re-add all markers with updated chat status
        addAgentMarkersToMap()
    }
    
    private fun getScaledUnicityIcon(): BitmapDescriptor {
        val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.unicity_logo)
        // Scale to approximately 80x80 pixels (adjust as needed)
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 80, 80, true)
        return BitmapDescriptorFactory.fromBitmap(scaledBitmap)
    }
    
    private fun getScaledUnicityIconWithChat(): BitmapDescriptor {
        // Create a combined bitmap with unicity logo and chat bubble
        val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.unicity_logo)
        val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, 80, 80, true)
        
        // Create a canvas to draw both icons
        val combinedBitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(combinedBitmap)
        
        // Draw the logo
        canvas.drawBitmap(scaledLogo, 0f, 0f, null)
        
        // Draw a small chat indicator in the bottom right
        val chatPaint = android.graphics.Paint().apply {
            color = resources.getColor(R.color.primary_color, null)
            style = android.graphics.Paint.Style.FILL
        }
        
        // Draw chat bubble background circle
        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(65f, 65f, 16f, bgPaint)
        canvas.drawCircle(65f, 65f, 14f, chatPaint)
        
        // Draw chat icon
        val chatIcon = resources.getDrawable(R.drawable.ic_chat_bubble, null)
        chatIcon?.setBounds(55, 55, 75, 75)
        chatIcon?.setTint(android.graphics.Color.WHITE)
        chatIcon?.draw(canvas)
        
        return BitmapDescriptorFactory.fromBitmap(combinedBitmap)
    }
    
    private fun addUserLocationMarker(latitude: Double, longitude: Double) {
        val latLng = LatLng(latitude, longitude)
        googleMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Your Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadCurrentLocationAndAgents()
                
                // Enable my location on map only if not in demo mode
                if (!org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this) &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    googleMap?.isMyLocationEnabled = true
                }
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to find nearby agents",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupMapControls() {
        // Map type toggle
        binding.btnMapType.setOnClickListener {
            isSatelliteView = !isSatelliteView
            googleMap?.mapType = if (isSatelliteView) {
                GoogleMap.MAP_TYPE_SATELLITE
            } else {
                GoogleMap.MAP_TYPE_NORMAL
            }
            // Update icon
            binding.btnMapType.setImageResource(
                if (isSatelliteView) R.drawable.ic_location_on else R.drawable.ic_satellite
            )
        }
        
        // Zoom controls
        binding.btnZoomIn.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        
        binding.btnZoomOut.setOnClickListener {
            googleMap?.animateCamera(CameraUpdateFactory.zoomOut())
        }
        
        // Demo mode button
        binding.btnDemoMode.setOnClickListener {
            generateDemoAgentsAtCurrentView()
        }
    }
    
    
    private var titlePressStartTime = 0L
    
    private fun generateDemoAgentsAtCurrentView() {
        val map = googleMap ?: return
        
        // Get current map center
        val centerLatLng = map.cameraPosition.target
        val centerLat = centerLatLng.latitude
        val centerLon = centerLatLng.longitude
        
        // Clear existing markers and agents
        map.clear()
        agents.clear()
        
        // Add user location marker at center
        currentLocation = Location("demo").apply {
            latitude = centerLat
            longitude = centerLon
        }
        addUserLocationMarker(centerLat, centerLon)
        
        // Generate 10 random agents within visible bounds
        val visibleRegion = map.projection.visibleRegion
        val bounds = visibleRegion.latLngBounds
        
        val randomAgents = mutableListOf<Agent>()
        val usedNames = mutableSetOf<String>()
        
        for (i in 0 until 10) {
            // Get a unique name
            var agentName: String
            do {
                agentName = DEMO_AGENT_NAMES.random()
            } while (agentName in usedNames)
            usedNames.add(agentName)
            
            // Generate random position within visible bounds
            val latRange = bounds.northeast.latitude - bounds.southwest.latitude
            val lonRange = bounds.northeast.longitude - bounds.southwest.longitude
            
            val randomLat = bounds.southwest.latitude + (Math.random() * latRange)
            val randomLon = bounds.southwest.longitude + (Math.random() * lonRange)
            
            // Calculate distance from center
            val distance = calculateDistance(centerLat, centerLon, randomLat, randomLon)
            
            // Generate a realistic timestamp (within last hour)
            val minutesAgo = (Math.random() * 60).toInt()
            val timestamp = Date(System.currentTimeMillis() - minutesAgo * 60 * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            
            randomAgents.add(
                Agent(
                    unicityTag = agentName,
                    latitude = randomLat,
                    longitude = randomLon,
                    distance = distance,
                    lastUpdateAt = sdf.format(timestamp)
                )
            )
        }
        
        // Sort by distance and update UI
        agents.clear() // Clear again to be sure
        agents.addAll(randomAgents.sortedBy { it.distance })
        
        // Force UI update
        updateAgentList()
        addAgentMarkersToMap()
        
        // Expand bottom sheet to show the list
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        
        Toast.makeText(this, "Generated 10 demo agents", Toast.LENGTH_SHORT).show()
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    
    private fun loadAgentsWithChat() {
        lifecycleScope.launch {
            chatDatabase?.conversationDao()?.getAllConversations()?.collect { conversations ->
                agentsWithChat.clear()
                agentsWithChat.addAll(conversations.map { it.conversationId })
                
                // Refresh map markers if available
                if (googleMap != null) {
                    updateMapMarkers()
                }
            }
        }
    }
    
    private fun ensureP2PServiceRunning() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", android.content.Context.MODE_PRIVATE)
        val isAgent = sharedPrefs.getBoolean("is_agent", false)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""

        android.util.Log.d(TAG, "ensureP2PServiceRunning - isAgent: $isAgent, unicityTag: $unicityTag")

        if (isAgent && unicityTag.isNotEmpty()) {
            // Use P2PServiceFactory to get or create P2P service
            val existingService = org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance()
            if (existingService == null) {
                android.util.Log.d(TAG, "P2P service not running, starting it now...")
                try {
                    val publicKey = unicityTag // TODO: Get actual public key
                    val service = org.unicitylabs.wallet.p2p.P2PServiceFactory.getInstance(
                        context = applicationContext,
                        userTag = unicityTag,
                        userPublicKey = publicKey
                    )
                    service?.start()
                    android.util.Log.d(TAG, "P2P service (NIP-17) started from AgentMapActivity")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to start P2P service", e)
                }
            } else {
                android.util.Log.d(TAG, "P2P service (NIP-17) already running")
            }
        }
    }
    
    private fun setupChatIcon() {
        val chatIconContainer = findViewById<View>(R.id.chat_icon_container)
        chatIconContainer.setOnClickListener {
            showChatConversations()
        }
    }
    
    private fun observeUnreadCount() {
        lifecycleScope.launch {
            chatDatabase?.conversationDao()?.getTotalUnreadCount()?.collectLatest { unreadCount ->
                updateUnreadBadge(unreadCount ?: 0)
            }
        }
    }
    
    private fun updateUnreadBadge(count: Int) {
        val badge = findViewById<TextView>(R.id.unread_badge)
        if (count > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (count > 99) "99+" else count.toString()
        } else {
            badge.visibility = View.GONE
        }
    }
    
    private fun showChatConversations() {
        lifecycleScope.launch {
            val allConversations = chatDatabase?.conversationDao()?.getAllConversationsList() ?: emptyList()
            
            // Filter out conversations with self
            val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", MODE_PRIVATE)
            val currentUserTag = sharedPrefs.getString("unicity_tag", "") ?: ""
            val conversations = allConversations.filter { it.conversationId != currentUserTag }
            
            // Create custom dialog view
            val dialogView = layoutInflater.inflate(R.layout.dialog_conversations, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvConversations)
            val emptyStateView = dialogView.findViewById<TextView>(R.id.tvEmptyState)
            
            val dialog = AlertDialog.Builder(this@AgentMapActivity)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .create()
            
            // Create adapter with deferred actions
            lateinit var adapter: ConversationsAdapter
            
            // Function to refresh the list
            val refreshList: suspend () -> Unit = {
                val updatedConversations = (chatDatabase?.conversationDao()?.getAllConversationsList() ?: emptyList())
                    .filter { it.conversationId != currentUserTag }
                
                if (updatedConversations.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyStateView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyStateView.visibility = View.GONE
                    adapter.submitList(updatedConversations)
                }
            }
            
            adapter = ConversationsAdapter(
                    onItemClick = { conversation ->
                        val intent = Intent(this@AgentMapActivity, ChatActivity::class.java).apply {
                            putExtra(ChatActivity.EXTRA_AGENT_TAG, conversation.agentTag)
                            putExtra(ChatActivity.EXTRA_AGENT_NAME, "${conversation.agentTag}@unicity")
                        }
                        startActivity(intent)
                        dialog.dismiss()
                    },
                    onClearClick = { conversation ->
                        // Clear all messages in the conversation
                        lifecycleScope.launch {
                            chatDatabase?.messageDao()?.deleteAllMessagesForConversation(conversation.conversationId)
                            // Reset conversation
                            chatDatabase?.conversationDao()?.updateConversation(
                                conversation.copy(
                                    lastMessageTime = System.currentTimeMillis(),
                                    lastMessageText = null,
                                    unreadCount = 0
                                )
                            )
                            Toast.makeText(this@AgentMapActivity, "Messages cleared", Toast.LENGTH_SHORT).show()
                            refreshList()
                        }
                    },
                    onDeleteClick = { conversation ->
                        // Show confirmation dialog
                        AlertDialog.Builder(this@AgentMapActivity)
                            .setTitle("Delete Conversation")
                            .setMessage("Are you sure you want to delete this conversation? A new handshake will be required to chat again.")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    // Delete all messages
                                    chatDatabase?.messageDao()?.deleteAllMessagesForConversation(conversation.conversationId)
                                    // Delete conversation
                                    chatDatabase?.conversationDao()?.deleteConversation(conversation)
                                    // Mark as dismissed so it doesn't reappear from relay
                                    chatDatabase?.dismissedItemDao()?.insertDismissedItem(
                                        DismissedItem(conversation.conversationId, DismissedItemType.CONVERSATION)
                                    )
                                    Toast.makeText(this@AgentMapActivity, "Conversation deleted", Toast.LENGTH_SHORT).show()
                                    refreshList()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )
            
            // Setup RecyclerView
            recyclerView.layoutManager = LinearLayoutManager(this@AgentMapActivity)
            recyclerView.adapter = adapter
            
            // Show appropriate view based on conversations
            if (conversations.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateView.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateView.visibility = View.GONE
                adapter.submitList(conversations)
            }
            
            // Show dialog
            dialog.show()
        }
    }
    
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> "${diff / 86400_000}d ago"
        }
    }
}