package org.unicitylabs.wallet.ui.profile

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import org.unicitylabs.wallet.identity.GoogleDriveBackupManager
import org.unicitylabs.wallet.identity.IdentityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserProfileViewModel(application: Application) : AndroidViewModel(application) {
    
    private val identityManager = IdentityManager(application)
    private val driveBackupManager = GoogleDriveBackupManager(application)
    
    private val _seedPhrase = MutableLiveData<List<String>?>()
    val seedPhrase: LiveData<List<String>?> = _seedPhrase
    
    private val _isPhraseVisible = MutableLiveData(false)
    val isPhraseVisible: LiveData<Boolean> = _isPhraseVisible
    
    private val _googleAccount = MutableLiveData<GoogleSignInAccount?>()
    val googleAccount: LiveData<GoogleSignInAccount?> = _googleAccount
    
    private val _lastBackupTime = MutableLiveData<String?>()
    val lastBackupTime: LiveData<String?> = _lastBackupTime
    
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message
    
    init {
        loadProfile()
        checkGoogleSignIn()
    }
    
    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val phrase = identityManager.getSeedPhrase()
                if (phrase != null) {
                    _seedPhrase.value = phrase
                } else {
                    // Generate new identity if none exists
                    val (identity, words) = identityManager.generateNewIdentity()
                    _seedPhrase.value = words
                    _message.value = "New wallet created"
                }
            } catch (e: Exception) {
                _message.value = "Error loading profile: ${e.message}"
            }
        }
    }
    
    private fun checkGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(getApplication())
        _googleAccount.value = account
        
        if (account != null) {
            loadLastBackupTime()
        }
    }
    
    private fun loadLastBackupTime() {
        viewModelScope.launch {
            val timestamp = driveBackupManager.getLastBackupTime()
            _lastBackupTime.value = timestamp?.let {
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(it))
            }
        }
    }
    
    fun togglePhraseVisibility() {
        _isPhraseVisible.value = !(_isPhraseVisible.value ?: false)
    }
    
    fun handleSignInResult(data: Intent) {
        viewModelScope.launch {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
                _googleAccount.value = account
                
                if (account != null) {
                    driveBackupManager.initialize(account)
                    loadLastBackupTime()
                    
                    // Auto backup after sign in
                    backupToGoogleDrive()
                }
            } catch (e: Exception) {
                _message.value = "Sign in failed: ${e.message}"
            }
        }
    }
    
    fun disableGoogleDriveBackup() {
        viewModelScope.launch {
            val account = GoogleSignIn.getLastSignedInAccount(getApplication())
            if (account != null) {
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                    com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
                )
                    .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_APPDATA))
                    .build()
                
                GoogleSignIn.getClient(getApplication(), gso).signOut()
            }
            
            _googleAccount.value = null
            _lastBackupTime.value = null
        }
    }
    
    fun backupToGoogleDrive() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val phrase = _seedPhrase.value ?: throw Exception("No seed phrase to backup")
                driveBackupManager.backupSeedPhrase(phrase)
                loadLastBackupTime()
                _message.value = "Backup successful"
            } catch (e: Exception) {
                _message.value = "Backup failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun restoreFromGoogleDrive() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val restoredPhrase = driveBackupManager.restoreSeedPhrase()
                if (restoredPhrase != null) {
                    val identity = identityManager.restoreFromSeedPhrase(restoredPhrase)
                    _seedPhrase.value = restoredPhrase
                    _message.value = "Restore successful"
                } else {
                    _message.value = "No backup found on Google Drive"
                }
            } catch (e: Exception) {
                _message.value = "Restore failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    suspend fun restoreFromSeedPhrase(phraseString: String) {
        _isLoading.value = true
        try {
            val words = phraseString.trim().split("\\s+".toRegex())
            if (words.size != 12) {
                throw Exception("Invalid phrase length. Expected 12 words, got ${words.size}")
            }
            
            val identity = identityManager.restoreFromSeedPhrase(words)
            _seedPhrase.value = words
            _isPhraseVisible.value = false
            _message.value = "Wallet restored successfully"
            
            // Auto backup if signed in
            if (_googleAccount.value != null) {
                backupToGoogleDrive()
            }
        } catch (e: Exception) {
            _message.value = "Restore failed: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
    
    fun isSignedInToGoogle(): Boolean {
        return _googleAccount.value != null
    }
    
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(UserProfileViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return UserProfileViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}