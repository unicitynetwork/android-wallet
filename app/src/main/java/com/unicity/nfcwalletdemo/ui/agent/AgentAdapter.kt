package com.unicity.nfcwalletdemo.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.databinding.ItemAgentBinding
import com.unicity.nfcwalletdemo.network.Agent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class AgentAdapter(
    private val onAgentClick: (Agent) -> Unit
) : ListAdapter<Agent, AgentAdapter.AgentViewHolder>(AgentDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AgentViewHolder {
        val binding = ItemAgentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AgentViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: AgentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class AgentViewHolder(
        private val binding: ItemAgentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(agent: Agent) {
            binding.tvAgentTag.text = "${agent.unicityTag}@unicity"
            binding.tvDistance.text = String.format("%.1f km", agent.distance)
            
            // Calculate time since last update
            val lastUpdateTime = getTimeSinceLastUpdate(agent.lastUpdateAt)
            binding.tvLastUpdate.text = "Last seen: $lastUpdateTime"
            
            binding.root.setOnClickListener {
                onAgentClick(agent)
            }
        }
        
        private fun getTimeSinceLastUpdate(lastUpdateAt: String): String {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val updateTime = sdf.parse(lastUpdateAt)
                val now = Date()
                
                val diffMillis = now.time - (updateTime?.time ?: now.time)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
                
                when {
                    minutes < 1 -> "just now"
                    minutes < 60 -> "$minutes min ago"
                    minutes < 1440 -> "${minutes / 60} hours ago"
                    else -> "${minutes / 1440} days ago"
                }
            } catch (e: Exception) {
                "unknown"
            }
        }
    }
    
    class AgentDiffCallback : DiffUtil.ItemCallback<Agent>() {
        override fun areItemsTheSame(oldItem: Agent, newItem: Agent): Boolean {
            return oldItem.unicityTag == newItem.unicityTag
        }
        
        override fun areContentsTheSame(oldItem: Agent, newItem: Agent): Boolean {
            return oldItem == newItem
        }
    }
}