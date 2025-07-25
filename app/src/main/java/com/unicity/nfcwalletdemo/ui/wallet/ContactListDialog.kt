package com.unicity.nfcwalletdemo.ui.wallet

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicity.nfcwalletdemo.R
import com.unicity.nfcwalletdemo.data.model.Contact
import com.unicity.nfcwalletdemo.databinding.DialogContactListBinding
import com.unicity.nfcwalletdemo.utils.ContactsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // Request permission or fall back to mock data
            if (onRequestPermission != null) {
                // Show permission rationale
                Toast.makeText(
                    context,
                    "Contact permission needed to show your contacts",
                    Toast.LENGTH_LONG
                ).show()
                onRequestPermission.invoke(Manifest.permission.READ_CONTACTS, REQUEST_CODE_CONTACTS)
                // For now, show mock data until permission is granted
                allContacts = getMockContacts()
                filterContacts("")
            } else {
                // No permission handler provided, use mock data
                allContacts = getMockContacts()
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
                    
                    if (phoneContacts.isEmpty()) {
                        // If no phone contacts, use mock data
                        allContacts = getMockContacts()
                    } else {
                        allContacts = phoneContacts
                    }
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
                    // Fall back to mock data
                    allContacts = getMockContacts()
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
    
    private fun getMockContacts(): List<Contact> {
        return listOf(
            Contact("1", "Alice Johnson", "alice@unicity", isUnicityUser = true),
            Contact("2", "Bob Smith", "0x742d35Cc6634C0532925a3b844Bc9e7595f06789"),
            Contact("3", "Charlie Brown", "charlie.brown@unicity", isUnicityUser = true),
            Contact("4", "David Wilson", "david.wilson@example.com"),
            Contact("5", "Emma Davis", "emma@unicity", isUnicityUser = true),
            Contact("6", "Frank Miller", "0x123456789abcdef0123456789abcdef012345678"),
            Contact("7", "Grace Lee", "grace.lee@unicity", isUnicityUser = true),
            Contact("8", "Henry Taylor", "henry.taylor@email.com"),
            Contact("9", "Iris Chen", "iris@unicity", isUnicityUser = true),
            Contact("10", "Jack Robinson", "jack.robinson@company.com"),
            Contact("11", "Katie Martinez", "katie@unicity", isUnicityUser = true),
            Contact("12", "Liam Anderson", "liam.anderson@gmail.com"),
            Contact("13", "Maria Garcia", "maria.garcia@unicity", isUnicityUser = true),
            Contact("14", "Nathan White", "0xabcdef0123456789abcdef0123456789abcdef01"),
            Contact("15", "Olivia Thompson", "olivia@unicity", isUnicityUser = true)
        )
    }
    
    companion object {
        const val REQUEST_CODE_CONTACTS = 1001
    }
}