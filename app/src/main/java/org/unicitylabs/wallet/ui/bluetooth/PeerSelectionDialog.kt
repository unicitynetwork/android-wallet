package org.unicitylabs.wallet.ui.bluetooth

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.bluetooth.DiscoveredPeer

/**
 * Dialog for selecting a BT mesh peer to send tokens to
 */
class PeerSelectionDialog : DialogFragment() {
    
    interface PeerSelectionListener {
        fun onPeerSelected(peer: DiscoveredPeer)
        fun onNfcSelected()
        fun onCancelled()
    }
    
    private var peers: List<DiscoveredPeer> = emptyList()
    private var listener: PeerSelectionListener? = null
    private lateinit var adapter: PeerAdapter
    
    fun setPeers(peers: List<DiscoveredPeer>) {
        this.peers = peers
        if (::adapter.isInitialized) {
            adapter.updatePeers(peers)
        }
    }
    
    fun setListener(listener: PeerSelectionListener) {
        this.listener = listener
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_peer_selection, null)
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.peersRecyclerView)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val nfcOption = view.findViewById<View>(R.id.nfcOption)
        
        adapter = PeerAdapter { peer ->
            listener?.onPeerSelected(peer)
            dismiss()
        }
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.updatePeers(peers)
        
        if (peers.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
        
        nfcOption.setOnClickListener {
            listener?.onNfcSelected()
            dismiss()
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Recipient")
            .setView(view)
            .setNegativeButton("Cancel") { _, _ ->
                listener?.onCancelled()
            }
            .create()
    }
    
    private class PeerAdapter(
        private val onPeerClick: (DiscoveredPeer) -> Unit
    ) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {
        
        private var peers: List<DiscoveredPeer> = emptyList()
        
        fun updatePeers(newPeers: List<DiscoveredPeer>) {
            peers = newPeers
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_peer, parent, false)
            return PeerViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
            holder.bind(peers[position], onPeerClick)
        }
        
        override fun getItemCount() = peers.size
        
        class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.peerName)
            private val idText: TextView = itemView.findViewById(R.id.peerId)
            private val statusText: TextView = itemView.findViewById(R.id.peerStatus)
            
            fun bind(peer: DiscoveredPeer, onClick: (DiscoveredPeer) -> Unit) {
                nameText.text = peer.deviceName
                idText.text = "ID: ${peer.peerId.take(8)}..."
                
                val timeSince = System.currentTimeMillis() - peer.lastSeen
                statusText.text = when {
                    timeSince < 5000 -> "Active"
                    timeSince < 30000 -> "Recently seen"
                    else -> "Last seen ${timeSince / 60000}m ago"
                }
                
                itemView.setOnClickListener { onClick(peer) }
            }
        }
    }
}