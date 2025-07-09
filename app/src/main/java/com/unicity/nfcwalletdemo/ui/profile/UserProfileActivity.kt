package com.unicity.nfcwalletdemo.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.unicity.nfcwalletdemo.databinding.ActivityUserProfileBinding
import kotlinx.coroutines.launch

class UserProfileActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityUserProfileBinding
    private lateinit var viewModel: UserProfileViewModel
    
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { intent ->
                viewModel.handleSignInResult(intent)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "User Profile"
        
        viewModel = ViewModelProvider(
            this,
            UserProfileViewModel.Factory(application)
        )[UserProfileViewModel::class.java]
        
        setupViews()
        observeViewModel()
    }
    
    private fun setupViews() {
        // Recovery phrase section
        binding.btnShowHidePhrase.setOnClickListener {
            viewModel.togglePhraseVisibility()
        }
        
        binding.btnCopyPhrase.setOnClickListener {
            copyPhraseToClipboard()
        }
        
        // Google Drive backup section
        binding.switchGoogleDriveBackup.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                signInToGoogle()
            } else {
                viewModel.disableGoogleDriveBackup()
            }
        }
        
        binding.tvGoogleAccount.setOnClickListener {
            if (binding.switchGoogleDriveBackup.isChecked) {
                showAccountPicker()
            }
        }
        
        binding.btnBackupNow.setOnClickListener {
            viewModel.backupToGoogleDrive()
        }
        
        // Restore options
        binding.btnRestoreFromPhrase.setOnClickListener {
            showRestoreFromPhraseDialog()
        }
        
        binding.btnRestoreFromDrive.setOnClickListener {
            if (viewModel.isSignedInToGoogle()) {
                viewModel.restoreFromGoogleDrive()
            } else {
                signInToGoogle()
            }
        }
    }
    
    private fun observeViewModel() {
        viewModel.seedPhrase.observe(this) { phrase ->
            if (phrase != null) {
                displaySeedPhrase(phrase)
            } else {
                binding.tvSeedPhrase.visibility = View.GONE
                binding.tvPhraseHidden.visibility = View.VISIBLE
            }
        }
        
        viewModel.isPhraseVisible.observe(this) { isVisible ->
            binding.tvSeedPhrase.visibility = if (isVisible) View.VISIBLE else View.GONE
            binding.tvPhraseHidden.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnShowHidePhrase.text = if (isVisible) "Hide" else "Show"
        }
        
        viewModel.googleAccount.observe(this) { account ->
            if (account != null) {
                binding.tvGoogleAccount.text = account.email
                binding.switchGoogleDriveBackup.isChecked = true
                binding.btnBackupNow.isEnabled = true
            } else {
                binding.tvGoogleAccount.text = "Not connected"
                binding.switchGoogleDriveBackup.isChecked = false
                binding.btnBackupNow.isEnabled = false
            }
        }
        
        viewModel.lastBackupTime.observe(this) { time ->
            binding.tvLastBackup.text = if (time != null) {
                "Last backup: $time"
            } else {
                "Last backup: Never"
            }
        }
        
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnBackupNow.isEnabled = !isLoading && viewModel.isSignedInToGoogle()
            binding.btnRestoreFromDrive.isEnabled = !isLoading
            binding.btnRestoreFromPhrase.isEnabled = !isLoading
        }
        
        viewModel.message.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun displaySeedPhrase(words: List<String>) {
        // Display all words in a single TextView, separated by spaces
        binding.tvSeedPhrase.text = words.joinToString(" ")
    }
    
    private fun copyPhraseToClipboard() {
        viewModel.seedPhrase.value?.let { words ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Seed Phrase", words.joinToString(" "))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Seed phrase copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun signInToGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        
        val client = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(client.signInIntent)
    }
    
    private fun showAccountPicker() {
        // Re-sign in to show account picker
        signInToGoogle()
    }
    
    private fun showRestoreFromPhraseDialog() {
        val dialogBinding = layoutInflater.inflate(
            com.unicity.nfcwalletdemo.R.layout.dialog_restore_phrase,
            null
        )
        
        AlertDialog.Builder(this)
            .setTitle("Restore from Recovery Phrase")
            .setView(dialogBinding)
            .setPositiveButton("Restore") { dialog, _ ->
                val editText = dialogBinding.findViewById<android.widget.EditText>(
                    com.unicity.nfcwalletdemo.R.id.etSeedPhrase
                )
                val phrase = editText.text.toString().trim()
                if (phrase.isNotEmpty()) {
                    lifecycleScope.launch {
                        viewModel.restoreFromSeedPhrase(phrase)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}