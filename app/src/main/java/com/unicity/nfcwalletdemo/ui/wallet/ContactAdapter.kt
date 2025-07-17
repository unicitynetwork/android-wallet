package com.unicity.nfcwalletdemo.ui.wallet

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicity.nfcwalletdemo.data.model.Contact
import com.unicity.nfcwalletdemo.databinding.ItemContactBinding
import kotlin.random.Random

class ContactAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(
        private val binding: ItemContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            // Set contact info
            binding.contactName.text = contact.name
            binding.contactAddress.text = contact.address
            
            // Set avatar
            binding.avatarText.text = contact.getInitials()
            
            // Generate a consistent color for the avatar based on the contact ID
            val avatarColor = generateAvatarColor(contact.id)
            binding.avatarContainer.setBackgroundColor(avatarColor)
            
            // Show Unicity badge if applicable
            binding.unicityBadge.visibility = if (contact.hasUnicityTag()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            
            // Set click listener
            binding.root.setOnClickListener {
                onContactClick(contact)
            }
        }
        
        private fun generateAvatarColor(seed: String): Int {
            val random = Random(seed.hashCode())
            val colors = listOf(
                Color.parseColor("#1976D2"), // Blue
                Color.parseColor("#388E3C"), // Green
                Color.parseColor("#D32F2F"), // Red
                Color.parseColor("#7B1FA2"), // Purple
                Color.parseColor("#F57C00"), // Orange
                Color.parseColor("#00796B"), // Teal
                Color.parseColor("#5D4037"), // Brown
                Color.parseColor("#455A64")  // Blue Grey
            )
            return colors[random.nextInt(colors.size)]
        }
    }

    class ContactDiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Contact, newItem: Contact): Boolean {
            return oldItem == newItem
        }
    }
}