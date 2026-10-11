package com.tera.down.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt DESC")
    fun observeTasksByStatus(status: DownloadStatus): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') ORDER BY createdAt DESC")
    fun observeActiveTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE surl = :surl AND fsId = :fsId AND status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') LIMIT 1")
    suspend fun getActiveTask(surl: String, fsId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<DownloadTaskEntity>)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String): Int

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun deleteCompletedTasks(): Int

    @Query("UPDATE download_tasks SET status = 'QUEUED', errorMessage = null, updatedAt = :timestamp WHERE status = 'FAILED'")
    suspend fun resetFailedTasks(timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun countActiveTasks(): Int

    @Query("SELECT * FROM download_tasks WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueuedTasks(): List<DownloadTaskEntity>
}
