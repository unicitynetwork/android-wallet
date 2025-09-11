package org.unicitylabs.wallet.ui.profile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.api.services.drive.DriveScopes
import org.unicitylabs.wallet.databinding.ActivityUserProfileBinding
import org.unicitylabs.wallet.identity.IdentityManager
import org.unicitylabs.wallet.nametag.NametagService
import org.unicitylabs.wallet.network.AgentApiService
import kotlinx.coroutines.launch
import org.unicitylabs.sdk.address.DirectAddress
import org.unicitylabs.sdk.hash.HashAlgorithm
import org.unicitylabs.sdk.predicate.MaskedPredicate
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import org.unicitylabs.sdk.token.TokenType
import java.util.concurrent.TimeUnit

class UserProfileActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var viewModel: UserProfileViewModel
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var agentApiService: AgentApiService
    private lateinit var nametagService: NametagService
    private lateinit var identityManager: IdentityManager
    private var isAgentMode = false
    private var p2pMessagingService: org.unicitylabs.wallet.p2p.P2PMessagingService? = null
    
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
    
    private val exportNametagLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { exportNametagToFile(it) }
    }
    
    private val importNametagLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importNametagFromFile(it) }
    }
    
    private var pendingNametagExport: String? = null
    private var currentNametagString: String? = null
    
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
        nametagService = NametagService(this)
        identityManager = IdentityManager(this)
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
            Log.d("UserProfileActivity", "Agent mode enabled: $savedAgentStatus")
            Log.d("UserProfileActivity", "Unicity tag: ${sharedPrefs.getString("unicity_tag", "")}")
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
            
            // Remove @unicity if user accidentally included it (it's just for display)
            val cleanTag = tag.removePrefix("@unicity").removePrefix("@")
            
            // Check if tag actually changed
            val oldTag = sharedPrefs.getString("unicity_tag", "") ?: ""
            val tagChanged = oldTag != cleanTag
            
            // Save to SharedPreferences
            sharedPrefs.edit().putString("unicity_tag", cleanTag).apply()
            
            // Mint or check nametag (using the raw tag without suffix)
            lifecycleScope.launch {
                // Check if identity exists first
                if (!identityManager.hasIdentity()) {
                    Log.w("UserProfileActivity", "No wallet identity found - user needs to create/restore wallet first")
                    binding.llNametagStatus.visibility = View.VISIBLE
                    binding.tvNametagStatus.text = "Please create or restore your wallet first"
                    binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                } else {
                    mintOrCheckNametag(cleanTag)
                }
            }
            
            // Show with @unicity suffix for display purposes only
            Toast.makeText(this, "Unicity tag saved: $cleanTag (displays as $cleanTag@unicity)", Toast.LENGTH_SHORT).show()
            
            // If agent mode is on, update location and restart P2P if tag changed
            if (isAgentMode) {
                checkLocationPermissionAndStart()
                if (tagChanged) {
                    // Restart P2P service with new tag
                    startP2PService()
                }
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
            org.unicitylabs.wallet.R.layout.dialog_restore_phrase,
            null
        )
        
        AlertDialog.Builder(this)
            .setTitle("Restore from Recovery Phrase")
            .setView(dialogBinding)
            .setPositiveButton("Restore") { dialog, _ ->
                val editText = dialogBinding.findViewById<android.widget.EditText>(
                    org.unicitylabs.wallet.R.id.etSeedPhrase
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
            if (org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this)) {
                // Use demo location immediately
                val demoLocation = org.unicitylabs.wallet.utils.UnicityLocationManager.createDemoLocation(this)
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
            val finalLocation = if (org.unicitylabs.wallet.utils.UnicityLocationManager.isDemoModeEnabled(this)) {
                org.unicitylabs.wallet.utils.UnicityLocationManager.createDemoLocation(this)
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
        Log.d("UserProfileActivity", "Starting P2P service...")
        
        // First check if there's an existing P2P service and shut it down
        val existingService = org.unicitylabs.wallet.p2p.P2PMessagingService.getExistingInstance()
        if (existingService != null) {
            Log.d("UserProfileActivity", "Shutting down existing P2P service before starting new one")
            existingService.shutdown()
            // Give it a moment to clean up
            Thread.sleep(100)
        }
        
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val unicityTag = sharedPrefs.getString("unicity_tag", "") ?: ""
        
        Log.d("UserProfileActivity", "Attempting to start P2P with tag: $unicityTag")
        
        if (unicityTag.isNotEmpty()) {
            Log.d("UserProfileActivity", "Creating P2P service for tag: $unicityTag")
            // TODO: Get actual public key from wallet
            // For now, use unicity tag as a placeholder for public key
            val publicKey = unicityTag
            
            try {
                p2pMessagingService = org.unicitylabs.wallet.p2p.P2PMessagingService.getInstance(
                    context = applicationContext,
                    userTag = unicityTag,
                    userPublicKey = publicKey
                )
                Log.d("UserProfileActivity", "P2P service created successfully")
                
                // Set initial availability
                val isAvailable = sharedPrefs.getBoolean("agent_available", true)
                p2pMessagingService?.updateAvailability(isAvailable)
                Log.d("UserProfileActivity", "P2P service availability set to: $isAvailable")
                
                // Send a broadcast to update MainActivity's location icon color
                val intent = Intent("org.unicitylabs.nfcwalletdemo.UPDATE_P2P_STATUS")
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e("UserProfileActivity", "Failed to create P2P service", e)
            }
        } else {
            Log.w("UserProfileActivity", "Cannot start P2P service: unicity tag is empty")
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
    
    private suspend fun mintOrCheckNametag(nametagString: String) {
        try {
            // Check if nametag exists
            if (nametagService.hasNametag(nametagString)) {
                // Nametag already exists
                runOnUiThread {
                    binding.llNametagStatus.visibility = View.VISIBLE
                    binding.llNametagActions.visibility = View.VISIBLE
                    binding.tvNametagStatus.text = "Nametag already minted"
                    binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_info)
                }
            } else {
                // Show minting progress
                runOnUiThread {
                    binding.llNametagStatus.visibility = View.VISIBLE
                    binding.tvNametagStatus.text = "Minting nametag..."
                    binding.ivNametagStatus.setImageResource(android.R.drawable.ic_popup_sync)
                }
                
                // Get the wallet's address
                val walletAddress = getWalletAddress()
                
                if (walletAddress != null) {
                    // Mint the nametag
                    val nametagToken = nametagService.mintNametag(nametagString, walletAddress)
                    
                    if (nametagToken != null) {
                        runOnUiThread {
                            binding.llNametagStatus.visibility = View.VISIBLE
                            binding.llNametagActions.visibility = View.VISIBLE
                            binding.tvNametagStatus.text = "Nametag minted successfully"
                            binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_info)
                            
                            Toast.makeText(this, "Nametag minted successfully!", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        runOnUiThread {
                            binding.llNametagStatus.visibility = View.VISIBLE
                            binding.tvNametagStatus.text = "Failed to mint nametag"
                            binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                            
                            Toast.makeText(this, "Failed to mint nametag. Try again later.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.llNametagStatus.visibility = View.VISIBLE
                        binding.tvNametagStatus.text = "Wallet not initialized"
                        binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                    }
                }
            }
            
            // Setup export/import buttons
            setupNametagButtons(nametagString)
            
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Error minting/checking nametag", e)
            runOnUiThread {
                binding.llNametagStatus.visibility = View.VISIBLE
                binding.tvNametagStatus.text = "Error: ${e.message}"
                binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_alert)
            }
        }
    }
    
    private suspend fun getWalletAddress(): DirectAddress? {
        return try {
            // Get the identity from IdentityManager
            val identity = identityManager.getCurrentIdentity()
            if (identity == null) {
                Log.e("UserProfileActivity", "No identity found")
                return null
            }
            
            // Convert hex strings to byte arrays
            val secret = hexToBytes(identity.secret)
            val nonce = hexToBytes(identity.nonce)
            
            // Create signing service and predicate
            val signingService = SigningService.createFromSecret(secret, nonce)
            val predicate = MaskedPredicate.create(
                signingService,
                HashAlgorithm.SHA256,
                nonce
            )
            
            // Generate a token type for the address
            val tokenType = TokenType(ByteArray(32).apply {
                java.security.SecureRandom().nextBytes(this)
            })
            
            // Return the address
            predicate.getReference(tokenType).toAddress()
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Error getting wallet address", e)
            null
        }
    }
    
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    private fun setupNametagButtons(nametagString: String) {
        currentNametagString = nametagString
        
        binding.btnExportNametag.setOnClickListener {
            lifecycleScope.launch {
                val exportData = nametagService.exportNametag(nametagString)
                if (exportData != null) {
                    pendingNametagExport = exportData
                    // Launch file picker to save as .txf file
                    val fileName = "nametag_${nametagString}.txf"
                    exportNametagLauncher.launch(fileName)
                } else {
                    runOnUiThread {
                        Toast.makeText(this@UserProfileActivity, "No nametag found to export", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        binding.btnImportNametag.setOnClickListener {
            // Launch file picker to select .txf file
            importNametagLauncher.launch("*/*") // Accept any file type, we'll validate extension
        }
    }
    
    private fun exportNametagToFile(uri: Uri) {
        try {
            pendingNametagExport?.let { data ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(data.toByteArray())
                    outputStream.flush()
                    Toast.makeText(this, "Nametag exported successfully", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Failed to export nametag", e)
            Toast.makeText(this, "Failed to export nametag: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pendingNametagExport = null
        }
    }
    
    private fun importNametagFromFile(uri: Uri) {
        try {
            // Check if file has .txf extension
            val fileName = getFileName(uri)
            if (!fileName.endsWith(".txf", ignoreCase = true)) {
                Toast.makeText(this, "Please select a .txf file", Toast.LENGTH_SHORT).show()
                return
            }
            
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonData = inputStream.bufferedReader().use { it.readText() }
                
                lifecycleScope.launch {
                    try {
                        // Parse the JSON to extract nametag data and nonce
                        val nametagData = UnicityObjectMapper.JSON.readTree(jsonData)
                        val nametag = nametagData.get("nametag")?.asText()
                        val nonceBase64 = nametagData.get("nonce")?.asText()
                        
                        if (nametag != null && nonceBase64 != null) {
                            val nonce = android.util.Base64.decode(nonceBase64, android.util.Base64.NO_WRAP)
                            val token = nametagService.importNametag(nametag, jsonData, nonce)
                            
                            if (token != null) {
                                runOnUiThread {
                                    Toast.makeText(this@UserProfileActivity, "Nametag imported successfully", Toast.LENGTH_LONG).show()
                                    // Update UI to show imported nametag
                                    binding.etUnicityTag.setText(nametag)
                                }
                                // Check the imported nametag status
                                mintOrCheckNametag(nametag)
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@UserProfileActivity, "Failed to import nametag", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@UserProfileActivity, "Invalid nametag file format", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("UserProfileActivity", "Failed to import nametag", e)
                        Toast.makeText(this@UserProfileActivity, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UserProfileActivity", "Failed to read nametag file", e)
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = it.getString(columnIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown.txf"
    }
    
    
    override fun onResume() {
        super.onResume()
        
        // Check nametag status when activity resumes
        val sharedPrefs = getSharedPreferences("UnicitywWalletPrefs", Context.MODE_PRIVATE)
        val savedTag = sharedPrefs.getString("unicity_tag", "")
        if (!savedTag.isNullOrEmpty()) {
            lifecycleScope.launch {
                // Check if identity exists first
                if (!identityManager.hasIdentity()) {
                    Log.w("UserProfileActivity", "No wallet identity found - user needs to create/restore wallet first")
                    binding.llNametagStatus.visibility = View.VISIBLE
                    binding.tvNametagStatus.text = "Please create or restore your wallet first"
                    binding.ivNametagStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                } else {
                    mintOrCheckNametag(savedTag)
                }
            }
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}