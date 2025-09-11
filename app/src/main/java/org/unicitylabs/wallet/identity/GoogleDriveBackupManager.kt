package org.unicitylabs.wallet.identity

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class GoogleDriveBackupManager(private val context: Context) {
    
    companion object {
        private const val BACKUP_FILE_NAME = "unicity_wallet_backup.enc"
        private const val PREFS_NAME = "google_drive_backup"
        private const val KEY_LAST_BACKUP = "last_backup_timestamp"
        private const val SALT = "UnicityWalletSalt2024" // In production, use random salt
        private const val ITERATIONS = 10000
        private const val KEY_LENGTH = 256
    }
    
    private var driveService: Drive? = null
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    suspend fun initialize(account: GoogleSignInAccount) = withContext(Dispatchers.IO) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Unicity Wallet")
            .build()
    }
    
    suspend fun backupSeedPhrase(words: List<String>) = withContext(Dispatchers.IO) {
        val drive = driveService ?: throw Exception("Google Drive not initialized")
        
        // Encrypt the seed phrase
        val seedPhrase = words.joinToString(" ")
        val encryptedData = encrypt(seedPhrase, getEncryptionKey())
        
        // Check if backup file exists
        val existingFile = findBackupFile()
        
        val fileMetadata = File().apply {
            name = BACKUP_FILE_NAME
            parents = listOf("appDataFolder")
        }
        
        val mediaContent = com.google.api.client.http.ByteArrayContent(
            "application/octet-stream",
            encryptedData
        )
        
        if (existingFile != null) {
            // Update existing file
            drive.files().update(existingFile.id, null, mediaContent).execute()
        } else {
            // Create new file
            drive.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
        }
        
        // Save backup timestamp
        sharedPrefs.edit()
            .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
            .apply()
    }
    
    suspend fun restoreSeedPhrase(): List<String>? = withContext(Dispatchers.IO) {
        val drive = driveService ?: throw Exception("Google Drive not initialized")
        
        val backupFile = findBackupFile() ?: return@withContext null
        
        // Download file content
        val outputStream = ByteArrayOutputStream()
        drive.files().get(backupFile.id).executeMediaAndDownloadTo(outputStream)
        val encryptedData = outputStream.toByteArray()
        
        // Decrypt
        val decryptedPhrase = decrypt(encryptedData, getEncryptionKey())
        
        return@withContext decryptedPhrase.split(" ")
    }
    
    private fun findBackupFile(): File? {
        val drive = driveService ?: return null
        
        val result = drive.files().list()
            .setSpaces("appDataFolder")
            .setFields("files(id, name)")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .execute()
        
        return result.files?.firstOrNull()
    }
    
    fun getLastBackupTime(): Long? {
        return sharedPrefs.getLong(KEY_LAST_BACKUP, 0).takeIf { it > 0 }
    }
    
    private fun getEncryptionKey(): ByteArray {
        // In production, derive this from user's Google account ID or other unique identifier
        val spec = PBEKeySpec("UnicityWallet".toCharArray(), SALT.toByteArray(), ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
    
    private fun encrypt(data: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray())
        
        // Prepend IV to encrypted data
        return iv + encrypted
    }
    
    private fun decrypt(data: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        
        // Extract IV (first 16 bytes)
        val iv = data.sliceArray(0..15)
        val encrypted = data.sliceArray(16 until data.size)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val decrypted = cipher.doFinal(encrypted)
        
        return String(decrypted)
    }
}