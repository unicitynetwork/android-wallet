package com.unicity.nfcwalletdemo.data.model

data class Contact(
    val id: String,
    val name: String,
    val address: String,
    val avatarUrl: String? = null,
    val isUnicityUser: Boolean = false
) {
    // Get initials for avatar
    fun getInitials(): String {
        val parts = name.split(" ")
        return when {
            parts.size >= 2 -> "${parts[0].firstOrNull()?.uppercaseChar() ?: ""}${parts[1].firstOrNull()?.uppercaseChar() ?: ""}"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "?"
        }
    }
    
    // Check if any field contains @unicity
    fun hasUnicityTag(): Boolean {
        return isUnicityUser || 
               address.contains("@unicity", ignoreCase = true) ||
               name.contains("@unicity", ignoreCase = true)
    }
}