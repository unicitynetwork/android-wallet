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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
    private var isScanning = false
    private val scanScope = CoroutineScope(Dispatchers.Main + Job())
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<android.bluetooth.BluetoothDevice>()
    private var bluetoothManager: BluetoothManager? = null
    
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
        
        // Start advertising immediately if permissions are granted
        if (checkBluetoothPermissions()) {
            startGattServer() // Start GATT server first
            startAdvertising() // Then start advertising
            updateCurrentDeviceInfo()
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
        
        binding.recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(this@BluetoothMeshActivity)
            adapter = deviceAdapter
        }
        
        // Setup buttons
        binding.btnStartDiscovery.setOnClickListener {
            if (isScanning) {
                stopDiscovery()
            } else {
                startDiscovery()
            }
        }
        
        binding.btnClearList.setOnClickListener {
            discoveredDevices.clear()
            deviceAdapter.notifyDataSetChanged()
            binding.tvDeviceCount.text = "Discovered devices: 0"
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
    
    private fun startAdvertising() {
        try {
            if (!checkBluetoothPermissions()) {
                Log.e(TAG, "Missing Bluetooth permissions for advertising")
                return
            }
            
            val bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (bluetoothLeAdvertiser == null) {
                Log.e(TAG, "Bluetooth LE Advertising not supported")
                binding.tvStatus.text = "BLE Advertising not supported"
                return
            }
            
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely
                .build()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .build()
            
            bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started advertising with UUID: $MESH_SERVICE_UUID")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in advertising", e)
            binding.tvStatus.text = "Permission error"
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
            runOnUiThread {
                binding.tvStatus.text = "Advertising active"
            }
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "Advertising failed: $errorMsg")
            runOnUiThread {
                binding.tvStatus.text = "Advertising failed: $errorMsg"
            }
        }
    }
    
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
            binding.btnStartDiscovery.text = "Stop Discovery"
            binding.tvStatus.text = "Scanning for all BLE devices..."
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
            binding.btnStartDiscovery.text = "Start Discovery"
            binding.tvStatus.text = "Discovery stopped"
            Log.d(TAG, "Stopped BLE scan")
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
                    Log.d(TAG, "Found wallet device: ${device.address} ($deviceName) RSSI: $rssi Service: $hasOurService")
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
                if (existingIndex >= 0) {
                    // Update existing device
                    discoveredDevices[existingIndex] = DiscoveredDevice(
                        address = device.address,
                        name = deviceName,
                        rssi = rssi,
                        lastSeen = System.currentTimeMillis()
                    )
                    deviceAdapter.notifyItemChanged(existingIndex)
                } else {
                    // Add new device
                    discoveredDevices.add(
                        DiscoveredDevice(
                            address = device.address,
                            name = deviceName,
                            rssi = rssi
                        )
                    )
                    deviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
                }
                
                // Sort devices by signal strength (proximity)
                discoveredDevices.sortByDescending { it.rssi }
                deviceAdapter.notifyDataSetChanged()
                
                binding.tvDeviceCount.text = "Discovered devices: ${discoveredDevices.size}"
                binding.tvStatus.text = "Found ${discoveredDevices.size} wallet device(s)"
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            Toast.makeText(this@BluetoothMeshActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun onDeviceClicked(device: DiscoveredDevice) {
        Log.d(TAG, "Clicked on device: ${device.address}")
        
        // Send a mesh message to the device
        scanScope.launch {
            sendMeshMessage(device)
        }
    }
    
    private suspend fun sendMeshMessage(device: DiscoveredDevice) {
        val message = "Hello from ${getLocalDeviceId()}"
        
        runOnUiThread {
            Toast.makeText(
                this@BluetoothMeshActivity,
                "Connecting to ${device.name ?: device.address}...",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        try {
            // Get the actual BluetoothDevice object
            val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            if (bluetoothDevice == null) {
                Log.e(TAG, "Could not get BluetoothDevice for address ${device.address}")
                return
            }
            
            // Connect to the device's GATT server
            val gatt = bluetoothDevice.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to ${device.address}, discovering services...")
                        val discoverResult = gatt.discoverServices()
                        Log.d(TAG, "discoverServices() returned: $discoverResult")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from ${device.address}")
                        gatt.close()
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Log.d(TAG, "onServicesDiscovered status: $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val services = gatt.services
                        Log.d(TAG, "Discovered ${services.size} services")
                        services.forEach { service ->
                            Log.d(TAG, "Service: ${service.uuid}")
                        }
                        
                        val service = gatt.getService(MESH_SERVICE_UUID)
                        if (service != null) {
                            Log.d(TAG, "Found mesh service")
                            val characteristic = service.getCharacteristic(MESH_MESSAGE_UUID)
                            if (characteristic != null) {
                                Log.d(TAG, "Found message characteristic, properties: ${characteristic.properties}")
                                // Send the message
                                val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
                                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val result = gatt.writeCharacteristic(characteristic, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                    Log.d(TAG, "Write result (API 33+): $result")
                                    result == BluetoothGatt.GATT_SUCCESS
                                } else {
                                    characteristic.value = messageBytes
                                    val result = gatt.writeCharacteristic(characteristic)
                                    Log.d(TAG, "Write result (legacy): $result")
                                    result
                                }
                                Log.d(TAG, "Message write initiated: $success, message length: ${messageBytes.size}")
                            } else {
                                Log.e(TAG, "Message characteristic not found in service")
                                val chars = service.characteristics
                                Log.e(TAG, "Service has ${chars.size} characteristics")
                                chars.forEach { char ->
                                    Log.e(TAG, "Characteristic: ${char.uuid}")
                                }
                            }
                        } else {
                            Log.e(TAG, "Mesh service not found")
                        }
                    } else {
                        Log.e(TAG, "Service discovery failed with status: $status")
                    }
                }
                
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Message sent successfully")
                        runOnUiThread {
                            Toast.makeText(
                                this@BluetoothMeshActivity,
                                "Message sent to ${device.name ?: device.address}!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Log.e(TAG, "Failed to send message: $status")
                    }
                    gatt.disconnect()
                }
            })
            
            Log.d(TAG, "Initiating GATT connection to ${device.address}")
            
            // For debugging - list all currently connected devices
            connectedDevices.forEach { connectedDevice ->
                Log.d(TAG, "Currently connected: ${connectedDevice.address}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to device", e)
            runOnUiThread {
                Toast.makeText(this, "Permission error", Toast.LENGTH_SHORT).show()
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
    
    private fun startGattServer() {
        try {
            val gattServerCallback = object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    Log.d(TAG, "onServiceAdded: status=$status, service=${service.uuid}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        runOnUiThread {
                            binding.tvStatus.text = "GATT server ready"
                        }
                    } else {
                        Log.e(TAG, "Failed to add service, status: $status")
                        runOnUiThread {
                            binding.tvStatus.text = "GATT service error: $status"
                        }
                    }
                }
                
                override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
                    Log.d(TAG, "Device ${device.address} connection state: $newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevices.add(device)
                    } else {
                        connectedDevices.remove(device)
                    }
                }
                
                override fun onCharacteristicWriteRequest(
                    device: android.bluetooth.BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray
                ) {
                    Log.d(TAG, "onCharacteristicWriteRequest from ${device.address}, characteristic: ${characteristic.uuid}")
                    
                    if (characteristic.uuid == MESH_MESSAGE_UUID) {
                        val message = String(value, StandardCharsets.UTF_8)
                        Log.d(TAG, "Received message from ${device.address}: $message")
                        
                        runOnUiThread {
                            // Show the received message as a toast
                            Toast.makeText(
                                this@BluetoothMeshActivity,
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                            
                            // Also update the status
                            binding.tvStatus.text = "Received: $message"
                        }
                    }
                    
                    if (responseNeeded) {
                        Log.d(TAG, "Sending response to ${device.address}")
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
            
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                binding.tvStatus.text = "GATT server failed"
                return
            }
            
            // Create the mesh service
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            
            // Add message characteristic with both write and write without response
            val messageCharacteristic = BluetoothGattCharacteristic(
                MESH_MESSAGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            service.addCharacteristic(messageCharacteristic)
            val success = gattServer?.addService(service) ?: false
            
            Log.d(TAG, "GATT server started, service added: $success")
            if (!success) {
                binding.tvStatus.text = "Failed to add GATT service"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting GATT server", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        
        // Stop GATT server
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server", e)
        }
        
        // Stop advertising
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping advertising", e)
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}