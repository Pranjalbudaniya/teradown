package com.tera.down.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tera.down.core.database.entity.DownloadManifestEntity
import com.tera.down.core.manifest.ManifestStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadManifestDao {

    @Query("SELECT * FROM download_manifest ORDER BY createdAt DESC")
    fun observeAllManifest(): Flow<List<DownloadManifestEntity>>

    @Query("SELECT * FROM download_manifest WHERE status = :status ORDER BY createdAt DESC")
    fun observeManifestByStatus(status: ManifestStatus): Flow<List<DownloadManifestEntity>>

    @Query("SELECT * FROM download_manifest ORDER BY createdAt DESC")
    suspend fun getAllManifest(): List<DownloadManifestEntity>

    @Query("SELECT * FROM download_manifest WHERE id = :id LIMIT 1")
    suspend fun getManifestById(id: String): DownloadManifestEntity?

    @Query("SELECT * FROM download_manifest WHERE taskId = :taskId LIMIT 1")
    suspend fun getManifestByTaskId(taskId: String): DownloadManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManifest(entity: DownloadManifestEntity)

    @Update
    suspend fun updateManifest(entity: DownloadManifestEntity)

    @Query("DELETE FROM download_manifest WHERE id = :id")
    suspend fun deleteManifestById(id: String): Int

    @Query("DELETE FROM download_manifest")
    suspend fun deleteAllManifest(): Int

    @Query("SELECT COUNT(*) FROM download_manifest")
    suspend fun countManifest(): Int
}
