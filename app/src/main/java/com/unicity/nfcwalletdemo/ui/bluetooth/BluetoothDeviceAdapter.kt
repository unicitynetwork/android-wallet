package com.unicity.nfcwalletdemo.ui.bluetooth

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.R

class BluetoothDeviceAdapter(
    private val devices: List<BluetoothMeshActivity.DiscoveredDevice>,
    private val onDeviceClick: (BluetoothMeshActivity.DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder>() {
    
    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        val tvRssi: TextView = view.findViewById(R.id.tvRssi)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        holder.tvDeviceName.text = device.name ?: "Unknown Device"
        holder.tvDeviceAddress.text = device.address
        
        // Show signal strength with visual indicator
        val signalStrength = when {
            device.rssi >= -50 -> "Very Close"
            device.rssi >= -70 -> "Close"
            device.rssi >= -80 -> "Medium"
            else -> "Far"
        }
        holder.tvRssi.text = "$signalStrength (${device.rssi} dBm)"
        
        // Color code by proximity
        val color = when {
            device.rssi >= -50 -> android.R.color.holo_green_dark
            device.rssi >= -70 -> android.R.color.holo_blue_dark
            device.rssi >= -80 -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        holder.tvRssi.setTextColor(holder.itemView.context.getColor(color))
        
        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }
    
    override fun getItemCount(): Int = devices.size
}