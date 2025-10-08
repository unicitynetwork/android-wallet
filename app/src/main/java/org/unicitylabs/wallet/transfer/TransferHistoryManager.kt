package org.unicitylabs.wallet.transfer

import android.content.Context
import android.content.SharedPreferences
import org.unicitylabs.wallet.data.model.TransferRecord
import org.unicitylabs.wallet.data.model.TransferStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Simple manager for tracking outgoing transfers
 * Prevents token loss by keeping transfer records
 */
class TransferHistoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("transfer_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveTransfer(record: TransferRecord) {
        val transfers = getAllTransfers().toMutableList()
        transfers.removeIf { it.transferId == record.transferId }
        transfers.add(record)

        val json = gson.toJson(transfers)
        prefs.edit().putString("transfers", json).apply()
    }

    fun getAllTransfers(): List<TransferRecord> {
        val json = prefs.getString("transfers", null) ?: return emptyList()
        val type = object : TypeToken<List<TransferRecord>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getPendingTransfers(): List<TransferRecord> {
        return getAllTransfers().filter {
            it.status in listOf(TransferStatus.PENDING, TransferStatus.COMMITTED, TransferStatus.CONFIRMED)
        }
    }

    fun updateStatus(transferId: String, status: TransferStatus, errorMessage: String? = null) {
        val transfers = getAllTransfers().toMutableList()
        val index = transfers.indexOfFirst { it.transferId == transferId }
        if (index >= 0) {
            transfers[index] = transfers[index].copy(status = status, errorMessage = errorMessage)
            val json = gson.toJson(transfers)
            prefs.edit().putString("transfers", json).apply()
        }
    }
}
