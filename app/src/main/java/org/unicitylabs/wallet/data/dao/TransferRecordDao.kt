package org.unicitylabs.wallet.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.unicitylabs.wallet.data.model.TransferRecord
import org.unicitylabs.wallet.data.model.TransferStatus

@Dao
interface TransferRecordDao {

    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferRecord>>

    @Query("SELECT * FROM transfer_records WHERE status = :status ORDER BY timestamp DESC")
    fun getTransfersByStatus(status: TransferStatus): Flow<List<TransferRecord>>

    @Query("SELECT * FROM transfer_records WHERE transferId = :transferId")
    suspend fun getTransferById(transferId: String): TransferRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferRecord)

    @Update
    suspend fun updateTransfer(transfer: TransferRecord)

    @Query("UPDATE transfer_records SET status = :status WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: String, status: TransferStatus)

    @Query("UPDATE transfer_records SET status = :status, errorMessage = :errorMessage WHERE transferId = :transferId")
    suspend fun updateStatusWithError(transferId: String, status: TransferStatus, errorMessage: String)

    @Delete
    suspend fun deleteTransfer(transfer: TransferRecord)

    @Query("DELETE FROM transfer_records WHERE transferId = :transferId")
    suspend fun deleteTransferById(transferId: String)
}
