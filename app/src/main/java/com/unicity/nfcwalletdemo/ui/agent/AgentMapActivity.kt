package com.unicity.nfcwalletdemo.ui.agent

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.Marker
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.widget.TextView
import android.content.Intent
import com.unicity.nfcwalletdemo.ui.chat.ChatActivity
import kotlin.math.*
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.databinding.ActivityAgentMapBinding
import com.unicity.nfcwalletdemo.network.Agent
import com.unicity.nfcwalletdemo.network.AgentApiService
import kotlinx.coroutines.launch

class AgentMapActivity : AppCompatActivity(), OnMapReadyCallback {
    
    private lateinit var binding: ActivityAgentMapBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var agentApiService: AgentApiService
    private lateinit var agentAdapter: AgentAdapter
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var chatDatabase: com.unicity.nfcwalletdemo.data.chat.ChatDatabase
    
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
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Find Agents"
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        agentApiService = AgentApiService()
        chatDatabase = com.unicity.nfcwalletdemo.data.chat.ChatDatabase.getDatabase(this)
        
        // Ensure P2P service is running if agent mode is enabled
        ensureP2PServiceRunning()
        
        setupRecyclerView()
        setupBottomSheet()
        setupMap()
        setupMapControls()
        
        checkLocationPermissionAndLoad()
        loadAgentsWithChat()
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
        val isDemoMode = com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)
        
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
        
        // Check if demo mode is enabled
        if (com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)) {
            // Use demo location
            val demoLocation = com.unicity.nfcwalletdemo.utils.DemoLocationManager.createDemoLocation(this)
            currentLocation = demoLocation
            
            // Update map camera
            val latLng = LatLng(demoLocation.latitude, demoLocation.longitude)
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
            
            // Load nearby agents
            loadNearbyAgents(demoLocation.latitude, demoLocation.longitude)
        } else if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    currentLocation = it
                    
                    // Update map camera
                    val latLng = LatLng(it.latitude, it.longitude)
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
                    
                    // Load nearby agents
                    loadNearbyAgents(it.latitude, it.longitude)
                } ?: run {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Unable to get current location", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadNearbyAgents(latitude: Double, longitude: Double) {
        lifecycleScope.launch {
            try {
                val result = agentApiService.getNearbyAgents(latitude, longitude)
                result.onSuccess { nearbyAgents ->
                    // Get current user's unicityTag to filter it out
                    val prefs = getSharedPreferences("UnicitywWalletPrefs", MODE_PRIVATE)
                    val currentUserTag = prefs.getString("user_unicity_tag", null)
                    
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
        if (com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)) {
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
                if (!com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this) &&
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
            chatDatabase.conversationDao().getAllConversations().collect { conversations ->
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
            // Check if P2P service is already running
            val existingService = com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getExistingInstance()
            if (existingService == null) {
                android.util.Log.d(TAG, "P2P service not running, starting it now...")
                try {
                    val publicKey = unicityTag // TODO: Get actual public key
                    com.unicity.nfcwalletdemo.p2p.P2PMessagingService.getInstance(
                        context = applicationContext,
                        userTag = unicityTag,
                        userPublicKey = publicKey
                    )
                    android.util.Log.d(TAG, "P2P service started from AgentMapActivity")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to start P2P service", e)
                }
            } else {
                android.util.Log.d(TAG, "P2P service already running")
            }
        }
    }
}