package com.unicity.nfcwalletdemo.ui.profile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.api.services.drive.DriveScopes
import com.unicity.nfcwalletdemo.databinding.ActivityUserProfileBinding
import com.unicity.nfcwalletdemo.network.AgentApiService
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UserProfileActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var viewModel: UserProfileViewModel
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var agentApiService: AgentApiService
    private var isAgentMode = false
    private var p2pMessagingService: com.unicity.nfcwalletdemo.p2p.P2PMessagingService? = null
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
    
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { intent ->
                viewModel.handleSignInResult(intent)
            }
        }
    }
    
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            binding.switchAgent.isChecked = false
            Toast.makeText(this, "Location permission is required for agent mode", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "User Profile"
        
        viewModel = ViewModelProvider(
            this,
            UserProfileViewModel.Factory(application)
        )[UserProfileViewModel::class.java]
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        agentApiService = AgentApiService()
        setupLocationCallback()
        
        setupViews()
        observeViewModel()
    }
    
    private fun setupViews() {
        // Unicity tag section
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val savedTag = sharedPrefs.getString("unicity_tag", "")
        val savedAgentStatus = sharedPrefs.getBoolean("is_agent", false)
        val savedAvailability = sharedPrefs.getBoolean("agent_available", true)
        
        binding.etUnicityTag.setText(savedTag)
        binding.switchAgent.isChecked = savedAgentStatus
        isAgentMode = savedAgentStatus
        
        // Show/hide availability switch based on agent mode
        binding.switchAvailability.visibility = if (savedAgentStatus) View.VISIBLE else View.GONE
        binding.switchAvailability.isChecked = savedAvailability
        
        // If agent mode is already enabled, start location updates and P2P service
        if (savedAgentStatus) {
            checkLocationPermissionAndStart()
            startP2PService()
        }
        
        binding.switchAgent.setOnCheckedChangeListener { _, isChecked ->
            isAgentMode = isChecked
            if (isChecked) {
                val tag = binding.etUnicityTag.text.toString().trim()
                if (tag.isEmpty()) {
                    Toast.makeText(this, "Please enter a Unicity tag first", Toast.LENGTH_SHORT).show()
                    binding.switchAgent.isChecked = false
                    return@setOnCheckedChangeListener
                }
                checkLocationPermissionAndStart()
                startP2PService()
                binding.switchAvailability.visibility = View.VISIBLE
            } else {
                stopLocationUpdates()
                stopP2PService()
                binding.switchAvailability.visibility = View.GONE
            }
            sharedPrefs.edit().putBoolean("is_agent", isChecked).apply()
        }
        
        // Handle availability toggle
        binding.switchAvailability.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("agent_available", isChecked).apply()
            p2pMessagingService?.updateAvailability(isChecked)
            
            // Update agent availability in backend
            val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
            if (unicityTag.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
                        agentApiService.updateAgentLocation(
                            unicityTag,
                            0.0, // Will be updated with actual location
                            0.0,
                            isChecked
                        )
                    } catch (e: Exception) {
                        // Ignore errors
                    }
                }
            }
        }
        
        binding.btnSaveUnicityTag.setOnClickListener {
            val tag = binding.etUnicityTag.text.toString().trim()
            if (tag.isEmpty()) {
                Toast.makeText(this, "Please enter a Unicity tag", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Remove @unicity if user accidentally included it
            val cleanTag = tag.removePrefix("@unicity").removePrefix("@")
            
            // Save to SharedPreferences
            sharedPrefs.edit().putString("unicity_tag", cleanTag).apply()
            
            Toast.makeText(this, "Unicity tag saved: $cleanTag@unicity", Toast.LENGTH_SHORT).show()
            
            // If agent mode is on, update location with new tag
            if (isAgentMode) {
                checkLocationPermissionAndStart()
            }
        }
        
        // Recovery phrase section
        binding.btnShowHidePhrase.setOnClickListener {
            viewModel.togglePhraseVisibility()
        }
        
        binding.btnCopyPhrase.setOnClickListener {
            copyPhraseToClipboard()
        }
        
        // Google Drive backup section
        binding.switchGoogleDriveBackup.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                signInToGoogle()
            } else {
                viewModel.disableGoogleDriveBackup()
            }
        }
        
        binding.tvGoogleAccount.setOnClickListener {
            if (binding.switchGoogleDriveBackup.isChecked) {
                showAccountPicker()
            }
        }
        
        binding.btnBackupNow.setOnClickListener {
            viewModel.backupToGoogleDrive()
        }
        
        // Restore options
        binding.btnRestoreFromPhrase.setOnClickListener {
            showRestoreFromPhraseDialog()
        }
        
        binding.btnRestoreFromDrive.setOnClickListener {
            if (viewModel.isSignedInToGoogle()) {
                viewModel.restoreFromGoogleDrive()
            } else {
                signInToGoogle()
            }
        }
    }
    
    private fun observeViewModel() {
        viewModel.seedPhrase.observe(this) { phrase ->
            if (phrase != null) {
                displaySeedPhrase(phrase)
            } else {
                binding.tvSeedPhrase.visibility = View.GONE
                binding.tvPhraseHidden.visibility = View.VISIBLE
            }
        }
        
        viewModel.isPhraseVisible.observe(this) { isVisible ->
            binding.tvSeedPhrase.visibility = if (isVisible) View.VISIBLE else View.GONE
            binding.tvPhraseHidden.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnShowHidePhrase.text = if (isVisible) "Hide" else "Show"
        }
        
        viewModel.googleAccount.observe(this) { account ->
            if (account != null) {
                binding.tvGoogleAccount.text = account.email
                binding.switchGoogleDriveBackup.isChecked = true
                binding.btnBackupNow.isEnabled = true
            } else {
                binding.tvGoogleAccount.text = "Not connected"
                binding.switchGoogleDriveBackup.isChecked = false
                binding.btnBackupNow.isEnabled = false
            }
        }
        
        viewModel.lastBackupTime.observe(this) { time ->
            binding.tvLastBackup.text = if (time != null) {
                "Last backup: $time"
            } else {
                "Last backup: Never"
            }
        }
        
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnBackupNow.isEnabled = !isLoading && viewModel.isSignedInToGoogle()
            binding.btnRestoreFromDrive.isEnabled = !isLoading
            binding.btnRestoreFromPhrase.isEnabled = !isLoading
        }
        
        viewModel.message.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun displaySeedPhrase(words: List<String>) {
        // Display all words in a single TextView, separated by spaces
        binding.tvSeedPhrase.text = words.joinToString(" ")
    }
    
    private fun copyPhraseToClipboard() {
        viewModel.seedPhrase.value?.let { words ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Seed Phrase", words.joinToString(" "))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Seed phrase copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun signInToGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        
        val client = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(client.signInIntent)
    }
    
    private fun showAccountPicker() {
        // Re-sign in to show account picker
        signInToGoogle()
    }
    
    private fun showRestoreFromPhraseDialog() {
        val dialogBinding = layoutInflater.inflate(
            com.unicity.nfcwalletdemo.R.layout.dialog_restore_phrase,
            null
        )
        
        AlertDialog.Builder(this)
            .setTitle("Restore from Recovery Phrase")
            .setView(dialogBinding)
            .setPositiveButton("Restore") { dialog, _ ->
                val editText = dialogBinding.findViewById<android.widget.EditText>(
                    com.unicity.nfcwalletdemo.R.id.etSeedPhrase
                )
                val phrase = editText.text.toString().trim()
                if (phrase.isNotEmpty()) {
                    lifecycleScope.launch {
                        viewModel.restoreFromSeedPhrase(phrase)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateAgentLocation(location)
                }
            }
        }
    }
    
    private fun checkLocationPermissionAndStart() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.MINUTES.toMillis(5) // Update every 5 minutes
        ).apply {
            setMinUpdateIntervalMillis(TimeUnit.MINUTES.toMillis(1)) // Minimum 1 minute between updates
        }.build()
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
            
            // Get immediate location
            if (com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)) {
                // Use demo location immediately
                val demoLocation = com.unicity.nfcwalletdemo.utils.DemoLocationManager.createDemoLocation(this)
                updateAgentLocation(demoLocation)
            } else {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let { updateAgentLocation(it) }
                }
            }
        }
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        
        // Mark agent as inactive
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        if (unicityTag.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    agentApiService.deactivateAgent(unicityTag)
                } catch (e: Exception) {
                    // Ignore errors when deactivating
                }
            }
        }
    }
    
    private fun updateAgentLocation(location: Location) {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        if (unicityTag.isNotEmpty()) {
            // Use demo location if demo mode is enabled
            val finalLocation = if (com.unicity.nfcwalletdemo.utils.DemoLocationManager.isDemoModeEnabled(this)) {
                com.unicity.nfcwalletdemo.utils.DemoLocationManager.createDemoLocation(this)
            } else {
                location
            }
            
            lifecycleScope.launch {
                try {
                    agentApiService.updateAgentLocation(
                        unicityTag,
                        finalLocation.latitude,
                        finalLocation.longitude,
                        true
                    )
                } catch (e: Exception) {
                    // Silently ignore errors - this runs in background
                }
            }
        }
    }
    
    private fun startP2PService() {
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        if (unicityTag.isNotEmpty()) {
            // TODO: Get actual public key from wallet
            // For now, use unicity tag as a placeholder for public key
            val publicKey = unicityTag
            
            p2pMessagingService = com.unicity.nfcwalletdemo.p2p.P2PMessagingService(
                context = applicationContext,
                userTag = unicityTag,
                userPublicKey = publicKey
            )
            
            // Set initial availability
            val isAvailable = sharedPrefs.getBoolean("agent_available", true)
            p2pMessagingService?.updateAvailability(isAvailable)
        }
    }
    
    private fun stopP2PService() {
        p2pMessagingService?.shutdown()
        p2pMessagingService = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isAgentMode) {
            stopLocationUpdates()
            stopP2PService()
        }
    }
}