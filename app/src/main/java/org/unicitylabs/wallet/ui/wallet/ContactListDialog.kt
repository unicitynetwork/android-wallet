package org.unicitylabs.wallet.ui.wallet

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unicitylabs.wallet.R
import org.unicitylabs.wallet.data.model.Contact
import org.unicitylabs.wallet.databinding.DialogContactListBinding
import org.unicitylabs.wallet.utils.ContactsHelper

class ContactListDialog(
    context: Context,
    private val onContactSelected: (Contact) -> Unit,
    private val onRequestPermission: ((String, Int) -> Unit)? = null
) : Dialog(context, R.style.FullScreenDialog) {

    private lateinit var binding: DialogContactListBinding
    private lateinit var contactAdapter: ContactAdapter
    private var allContacts = listOf<Contact>()
    private var showOnlyUnicity = false
    private val contactsHelper = ContactsHelper(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = DialogContactListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up full screen dialog
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.background_light)
        }
        
        setupViews()
        loadContacts()
    }
    
    private fun setupViews() {
        // Set up back button
        binding.btnBack.setOnClickListener {
            dismiss()
        }
        
        // Set up RecyclerView
        contactAdapter = ContactAdapter { contact ->
            onContactSelected(contact)
            dismiss()
        }
        
        binding.contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = contactAdapter
        }
        
        // Set up search
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterContacts(s?.toString() ?: "")
            }
        })
        
        // Set up Unicity filter toggle
        binding.unicityFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
            showOnlyUnicity = isChecked
            filterContacts(binding.searchEditText.text?.toString() ?: "")
        }
    }
    
    private fun loadContacts() {
        // Check if we have contacts permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Load phone contacts
            loadPhoneContacts()
        } else {
            // Request permission
            if (onRequestPermission != null) {
                // Show permission rationale
                Toast.makeText(
                    context,
                    "Contact permission needed to show your contacts",
                    Toast.LENGTH_LONG
                ).show()
                onRequestPermission.invoke(Manifest.permission.READ_CONTACTS, REQUEST_CODE_CONTACTS)
                // Show empty list until permission is granted
                allContacts = emptyList()
                filterContacts("")
            } else {
                // No permission handler provided, show empty list
                allContacts = emptyList()
                filterContacts("")
            }
        }
    }
    
    private fun loadPhoneContacts() {
        // Show loading state
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.contactsRecyclerView.visibility = android.view.View.GONE
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val phoneContacts = contactsHelper.loadPhoneContacts()
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.contactsRecyclerView.visibility = android.view.View.VISIBLE

                    // Use actual phone contacts, even if empty
                    allContacts = phoneContacts
                    filterContacts("")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.contactsRecyclerView.visibility = android.view.View.VISIBLE
                    Toast.makeText(
                        context,
                        "Error loading contacts: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Show empty list on error
                    allContacts = emptyList()
                    filterContacts("")
                }
            }
        }
    }
    
    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == REQUEST_CODE_CONTACTS && granted) {
            loadPhoneContacts()
        }
    }
    
    private fun filterContacts(query: String) {
        val filtered = allContacts.filter { contact ->
            val matchesQuery = query.isEmpty() ||
                              contact.name.contains(query, ignoreCase = true) ||
                              contact.address.contains(query, ignoreCase = true)

            val matchesUnicityFilter = !showOnlyUnicity || contact.hasUnicityTag()

            matchesQuery && matchesUnicityFilter
        }

        contactAdapter.submitList(filtered)

        // Show/hide empty state
        binding.emptyStateLayout.visibility = if (filtered.isEmpty()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    companion object {
        const val REQUEST_CODE_CONTACTS = 1001
    }
}