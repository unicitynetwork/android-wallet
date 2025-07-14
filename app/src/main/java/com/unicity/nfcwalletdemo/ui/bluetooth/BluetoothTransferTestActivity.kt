package com.unicity.nfcwalletdemo.ui.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.unicity.nfcwalletdemo.bluetooth.BluetoothMeshTransferService
// Removed theme import as it doesn't exist
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Test activity for Bluetooth mesh transfers
 * Demonstrates sending and receiving data over BT mesh
 */
class BluetoothTransferTestActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "BTTransferTest"
        private const val TEST_TOKEN_SIZE = 10 * 1024 // 10KB test data
    }
    
    private lateinit var bluetoothService: BluetoothMeshTransferService
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        bluetoothService = BluetoothMeshTransferService(this)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        setContent {
            MaterialTheme {
                BluetoothTransferTestScreen()
            }
        }
        
        checkAndRequestPermissions()
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        val missingPermissions = permissions.filter { permission ->
            ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            initializeBluetooth()
        } else {
            requestBluetoothPermissions.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun initializeBluetooth() {
        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBtIntent)
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun BluetoothTransferTestScreen() {
        var transferState by remember { mutableStateOf<BluetoothMeshTransferService.TransferState>(
            BluetoothMeshTransferService.TransferState.Idle
        ) }
        var targetMAC by remember { mutableStateOf("") }
        var transferId by remember { mutableStateOf("") }
        var isReceiver by remember { mutableStateOf(false) }
        
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        
        // Observe transfer state
        LaunchedEffect(bluetoothService) {
            bluetoothService.transferState.collect { state ->
                transferState = state
            }
        }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bluetooth Transfer Test") }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Device info
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Device Info",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("MAC Address: ${bluetoothService.getBluetoothMAC()}")
                        Text("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled ?: false}")
                    }
                }
                
                // Transfer state
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (transferState) {
                            is BluetoothMeshTransferService.TransferState.Error -> MaterialTheme.colorScheme.errorContainer
                            is BluetoothMeshTransferService.TransferState.Completed -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Transfer State",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        when (val state = transferState) {
                            is BluetoothMeshTransferService.TransferState.Idle -> {
                                Text("Ready to start transfer")
                            }
                            is BluetoothMeshTransferService.TransferState.Discovering -> {
                                Text("Discovering device: ${state.targetMAC}")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is BluetoothMeshTransferService.TransferState.Connected -> {
                                Text("Connected to: ${state.device.address}")
                            }
                            is BluetoothMeshTransferService.TransferState.Transferring -> {
                                Text("Transferring: ${(state.progress * 100).toInt()}%")
                                LinearProgressIndicator(
                                    progress = state.progress,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is BluetoothMeshTransferService.TransferState.Completed -> {
                                Text("Transfer completed! ID: ${state.tokenId}")
                            }
                            is BluetoothMeshTransferService.TransferState.Error -> {
                                Text("Error: ${state.message}")
                            }
                        }
                    }
                }
                
                // Mode selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { isReceiver = false },
                        modifier = Modifier.weight(1f),
                        enabled = !isReceiver
                    ) {
                        Text("Sender Mode")
                    }
                    Button(
                        onClick = { isReceiver = true },
                        modifier = Modifier.weight(1f),
                        enabled = isReceiver
                    ) {
                        Text("Receiver Mode")
                    }
                }
                
                // Input fields
                if (!isReceiver) {
                    OutlinedTextField(
                        value = targetMAC,
                        onValueChange = { targetMAC = it },
                        label = { Text("Target MAC Address") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("00:11:22:33:44:55") }
                    )
                }
                
                OutlinedTextField(
                    value = transferId,
                    onValueChange = { transferId = it },
                    label = { Text("Transfer ID") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Leave empty for auto-generated") }
                )
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                startTransfer(
                                    isReceiver = isReceiver,
                                    targetMAC = targetMAC,
                                    transferId = transferId.ifEmpty { UUID.randomUUID().toString() }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = transferState is BluetoothMeshTransferService.TransferState.Idle ||
                                 transferState is BluetoothMeshTransferService.TransferState.Error ||
                                 transferState is BluetoothMeshTransferService.TransferState.Completed
                    ) {
                        Text(if (isReceiver) "Start Receiving" else "Start Sending")
                    }
                    
                    Button(
                        onClick = {
                            bluetoothService.cancelTransfer(transferId)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = transferState !is BluetoothMeshTransferService.TransferState.Idle
                    ) {
                        Text("Cancel")
                    }
                }
                
                // Test data info
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Test Info",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "• Test data size: ${TEST_TOKEN_SIZE / 1024} KB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• Chunk size: 512 bytes",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• Expected chunks: ${TEST_TOKEN_SIZE / 512}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    
    private suspend fun startTransfer(isReceiver: Boolean, targetMAC: String, transferId: String) {
        try {
            if (isReceiver) {
                Log.d(TAG, "Starting as receiver for transfer: $transferId")
                val receivedData = bluetoothService.startReceiverMode(transferId, targetMAC)
                Log.d(TAG, "Received ${receivedData.size} bytes")
                Toast.makeText(this, "Received ${receivedData.size} bytes!", Toast.LENGTH_LONG).show()
            } else {
                if (targetMAC.isEmpty()) {
                    Toast.makeText(this, "Please enter target MAC address", Toast.LENGTH_SHORT).show()
                    return
                }
                
                Log.d(TAG, "Starting as sender to $targetMAC for transfer: $transferId")
                // Generate test data
                val testData = ByteArray(TEST_TOKEN_SIZE) { (it % 256).toByte() }
                bluetoothService.startSenderMode(transferId, targetMAC, testData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            Toast.makeText(this, "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.cleanup()
    }
}