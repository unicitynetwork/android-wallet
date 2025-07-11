package com.unicity.nfcwalletdemo.ui.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicity.nfcwalletdemo.databinding.ActivityBluetoothMeshBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import android.os.ParcelUuid
import java.util.UUID
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import java.nio.charset.StandardCharsets
import android.view.View
import com.unicity.nfcwalletdemo.bluetooth.BluetoothMeshManager
import kotlinx.coroutines.flow.collectLatest

class BluetoothMeshActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "BluetoothMeshActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        // Use a unique UUID for the wallet app
        private val MESH_SERVICE_UUID = UUID.fromString("00001830-0000-1000-8000-00805f9b34fb")
        private val MESH_MESSAGE_UUID = UUID.fromString("00002a90-0000-1000-8000-00805f9b34fb")
    }
    
    private lateinit var binding: ActivityBluetoothMeshBinding
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private val discoveredDevices = mutableListOf<DiscoveredDevice>()
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = true // Always scanning
    private val scanScope = CoroutineScope(Dispatchers.Main + Job())
    private var bluetoothManager: BluetoothManager? = null
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 1000L // Only update list every 1 second
    
    data class DiscoveredDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothMeshBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupBluetooth()
        
        // Start discovery immediately if permissions are granted
        if (checkBluetoothPermissions()) {
            // Use the shared BluetoothMeshManager - no local advertising needed
            startDiscovery() // Start discovery automatically
            updateCurrentDeviceInfo()
            
            // Observe mesh events
            observeMeshEvents()
            
            // Update status to show we're using the shared mesh service
            binding.tvStatus.text = "Using shared mesh service"
        } else {
            binding.tvStatus.text = "Bluetooth permissions required"
            binding.tvCurrentDevice.text = "This device: Permission required"
        }
    }
    
    private fun setupUI() {
        supportActionBar?.title = "Bluetooth Mesh Discovery"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Setup RecyclerView
        deviceAdapter = BluetoothDeviceAdapter(discoveredDevices) { device ->
            onDeviceClicked(device)
        }
        
        // Create and set adapter before applying to RecyclerView
        val linearLayoutManager = LinearLayoutManager(this)
        binding.recyclerViewDevices.layoutManager = linearLayoutManager
        binding.recyclerViewDevices.adapter = deviceAdapter
        binding.recyclerViewDevices.setHasFixedSize(false)
        
        // Force layout to ensure RecyclerView is visible
        binding.recyclerViewDevices.post {
            binding.recyclerViewDevices.requestLayout()
            // Debug: check if RecyclerView has proper size
            Log.d(TAG, "RecyclerView dimensions - width: ${binding.recyclerViewDevices.width}, height: ${binding.recyclerViewDevices.height}")
            Log.d(TAG, "RecyclerView visibility: ${binding.recyclerViewDevices.visibility}")
            Log.d(TAG, "RecyclerView parent: ${binding.recyclerViewDevices.parent?.javaClass?.simpleName}")
            Log.d(TAG, "RecyclerView has adapter: ${binding.recyclerViewDevices.adapter != null}")
            Log.d(TAG, "RecyclerView has layoutManager: ${binding.recyclerViewDevices.layoutManager != null}")
        }
        
        // Discovery is always on, no need for start/stop button
        // Button is removed from layout
        
        binding.btnClearList.setOnClickListener {
            Log.d(TAG, "Clear button clicked, current list size: ${discoveredDevices.size}")
            discoveredDevices.clear()
            deviceAdapter.notifyDataSetChanged()
            binding.tvDeviceCount.text = "Discovered devices: 0"
            binding.tvStatus.text = "Device list cleared"
            
            // Force a layout refresh
            binding.recyclerViewDevices.requestLayout()
        }
        
        // Add a refresh button to manually update RSSI values
        binding.btnClearList.setOnLongClickListener {
            // Just refresh the list without sorting
            deviceAdapter.notifyDataSetChanged()
            Toast.makeText(this, "Refreshed list", Toast.LENGTH_SHORT).show()
            true
        }
        
        // Display current device info
        updateCurrentDeviceInfo()
    }
    
    private fun setupBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun checkPermissions() {
        // This method is no longer used - permissions should be requested at first launch
        if (!checkBluetoothPermissions()) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth Permissions Required")
                .setMessage("This feature requires Bluetooth permissions. Please grant permissions in the app settings or restart the app.")
                .setPositiveButton("OK") { _, _ -> 
                    finish()
                }
                .show()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // Not used anymore - permissions handled at first launch
    }
    
    // Removed startAdvertising and advertiseCallback - now using shared BluetoothMeshManager
    // which already handles advertising from MainActivity
    
    private fun startDiscovery() {
        if (!checkBluetoothPermissions()) {
            Toast.makeText(this, "Bluetooth permissions required. Please restart the app.", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Toast.makeText(this, "BLE Scanner not available", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Start with no filters to see all BLE devices for debugging
            // We'll filter in the callback instead
            val scanFilters = emptyList<android.bluetooth.le.ScanFilter>()
            
            val scanSettings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            scanner.startScan(scanFilters, scanSettings, scanCallback)
            isScanning = true
            binding.tvStatus.text = "Scanning for wallet devices..."
            Log.d(TAG, "Started BLE scan without filters for debugging")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in startDiscovery", e)
            Toast.makeText(this, "Permission denied for Bluetooth scanning", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopDiscovery() {
        if (!checkBluetoothPermissions()) return
        
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            binding.tvStatus.text = "Discovery paused"
            Log.d(TAG, "Stopped BLE scan")
            
            // Restart discovery after a short delay to save battery
            lifecycleScope.launch {
                delay(5000) // 5 second pause
                if (!isScanning && checkBluetoothPermissions()) {
                    startDiscovery()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in stopDiscovery", e)
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val rssi = scanResult.rssi
                val scanRecord = scanResult.scanRecord
                
                // Check if device advertises our service UUID
                val hasOurService = scanRecord?.serviceUuids?.any { 
                    it.uuid == MESH_SERVICE_UUID 
                } ?: false
                
                // Get device name
                val deviceName = try {
                    if (ActivityCompat.checkSelfPermission(this@BluetoothMeshActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.name ?: scanRecord?.deviceName
                    } else {
                        scanRecord?.deviceName
                    }
                } catch (e: SecurityException) {
                    scanRecord?.deviceName
                }
                
                // Log all discovered devices for debugging
                if (hasOurService || deviceName?.contains("Unicity", ignoreCase = true) == true) {
//                    Log.d(TAG, "Found wallet device: ${device.address} ($deviceName) RSSI: $rssi Service: $hasOurService")
                }
                
                // Only show devices with our service UUID or "Unicity" in name
                if (!hasOurService && deviceName?.contains("Unicity", ignoreCase = true) != true) {
                    return // Skip non-wallet devices
                }
                
                // Filter out very weak signals
                if (rssi < -90) {
                    return
                }
                
                // Update or add device to list
                val existingIndex = discoveredDevices.indexOfFirst { it.address == device.address }
                val currentTime = System.currentTimeMillis()
                
                if (existingIndex >= 0) {
                    // Update existing device's RSSI and name if available
                    val existingDevice = discoveredDevices[existingIndex]
                    discoveredDevices[existingIndex] = existingDevice.copy(
                        name = deviceName ?: existingDevice.name, // Update name if we get a better one
                        rssi = rssi,
                        lastSeen = currentTime
                    )
                } else {
                    // Only add new device if it's truly new (not seen before)
                    // Check if this might be a duplicate with slightly different address
                    val possibleDuplicate = discoveredDevices.any { existingDevice ->
                        // If names match and they're both non-null, it might be the same device
                        existingDevice.name != null && deviceName != null && 
                        existingDevice.name == deviceName
                    }
                    
                    if (!possibleDuplicate) {
                        discoveredDevices.add(
                            DiscoveredDevice(
                                address = device.address,
                                name = deviceName,
                                rssi = rssi
                            )
                        )
                        
                        // Immediately notify for new devices
                        runOnUiThread {
                            deviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
                            // Force RecyclerView to update
                            binding.recyclerViewDevices.scrollToPosition(discoveredDevices.size - 1)
                        }
                    } else {
                        Log.d(TAG, "Skipping possible duplicate device: $deviceName ($device.address)")
                    }
                }
                
                // Update UI periodically to avoid constant redraws
                if (currentTime - lastUpdateTime > UPDATE_INTERVAL_MS) {
                    lastUpdateTime = currentTime
                    // Update adapter on UI thread
                    runOnUiThread {
                        // Don't sort - keep discovery order
                        deviceAdapter.notifyDataSetChanged()
                        
                        // Debug log
//                        Log.d(TAG, "Updated adapter - devices: ${discoveredDevices.size}")
//                        discoveredDevices.forEach { device ->
//                            Log.d(TAG, "  - ${device.name ?: "Unknown"} (${device.address}) RSSI: ${device.rssi}")
//                        }
                    }
                }
                
                runOnUiThread {
                    binding.tvDeviceCount.text = "Discovered devices: ${discoveredDevices.size}"
                    if (discoveredDevices.isNotEmpty()) {
                        binding.tvStatus.text = "Found ${discoveredDevices.size} wallet device(s)"
                    }
                }
                
                // Debug logging
//                Log.d(TAG, "Device list size: ${discoveredDevices.size}, Adapter item count: ${deviceAdapter.itemCount}")
//                Log.d(TAG, "RecyclerView height: ${binding.recyclerViewDevices.height}, child count: ${binding.recyclerViewDevices.childCount}")
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            Toast.makeText(this@BluetoothMeshActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }
    
    private var isSending = false // Prevent multiple simultaneous sends
    
    private fun onDeviceClicked(device: DiscoveredDevice) {
        // Prevent multiple clicks
        if (isSending) {
            Toast.makeText(this, "Already sending...", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "Clicked on device: ${device.address}")
        
        // Vibrate for feedback
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
        
        // Send a mesh message using the shared BluetoothMeshManager
        isSending = true
        lifecycleScope.launch {
            try {
                // Show progress in status
                runOnUiThread {
                    binding.tvStatus.text = "Connecting to ${device.name ?: device.address}..."
                }
                
                val message = "Hello from ${getLocalDeviceId()}"
                val success = BluetoothMeshManager.sendMessage(device.address, message)
                
                runOnUiThread {
                    if (success) {
                        binding.tvStatus.text = "Message sent successfully!"
                        Toast.makeText(
                            this@BluetoothMeshActivity,
                            "Message sent!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        binding.tvStatus.text = "Failed to send message"
                        Toast.makeText(
                            this@BluetoothMeshActivity,
                            "Failed to send message",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                runOnUiThread {
                    binding.tvStatus.text = "Error: ${e.message}"
                    Toast.makeText(
                        this@BluetoothMeshActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isSending = false
            }
        }
    }
    
    private fun getLocalDeviceId(): String {
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        return "Device ${deviceId.take(6)}"
    }
    
    private fun updateCurrentDeviceInfo() {
        try {
            val deviceName = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.name ?: "Unknown"
            } else {
                "Permission required"
            }
            
            // Get a stable device identifier
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            
            binding.tvCurrentDevice.text = "This device: $deviceName\nID: ${deviceId.take(8)}..."
            binding.tvCurrentDevice.setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            Log.d(TAG, "Current device: $deviceName - ID: $deviceId")
        } catch (e: SecurityException) {
            binding.tvCurrentDevice.text = "This device: Permission required"
            Log.e(TAG, "Security exception getting device info", e)
        }
    }
    
    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun observeMeshEvents() {
        lifecycleScope.launch {
            BluetoothMeshManager.meshEvents.collectLatest { event ->
                when (event) {
                    is BluetoothMeshManager.MeshEvent.MessageReceived -> {
                        runOnUiThread {
                            // Show received message
                            Toast.makeText(
                                this@BluetoothMeshActivity,
                                event.message,
                                Toast.LENGTH_LONG
                            ).show()
                            
                            binding.tvStatus.text = "Received: ${event.message}"
                        }
                    }
                    else -> {
                        // Handle other events if needed
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        // No need to stop advertising - handled by BluetoothMeshManager
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}