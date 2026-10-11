package com.tera.down.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tera.down.core.database.entity.LibraryMediaEntity
import com.tera.down.core.model.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryMediaDao {

    @Query("SELECT * FROM library_media ORDER BY dateAdded DESC")
    fun observeAllMedia(): Flow<List<LibraryMediaEntity>>

    @Query("SELECT * FROM library_media WHERE mediaType = :mediaType ORDER BY dateAdded DESC")
    fun observeMediaByType(mediaType: MediaType): Flow<List<LibraryMediaEntity>>

    @Query("SELECT * FROM library_media ORDER BY dateAdded DESC")
    suspend fun getAllMedia(): List<LibraryMediaEntity>

    @Query("SELECT * FROM library_media WHERE id = :id LIMIT 1")
    suspend fun getMediaById(id: String): LibraryMediaEntity?

    @Query("SELECT * FROM library_media WHERE path = :path LIMIT 1")
    suspend fun getMediaByPath(path: String): LibraryMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: LibraryMediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaList(mediaList: List<LibraryMediaEntity>)

    @Delete
    suspend fun deleteMedia(media: LibraryMediaEntity)

    @Query("SELECT * FROM library_media WHERE taskId = :taskId LIMIT 1")
    suspend fun getMediaByTaskId(taskId: String): LibraryMediaEntity?

    @Query("DELETE FROM library_media WHERE id = :id")
    suspend fun deleteMediaById(id: String): Int

    @Query("UPDATE library_media SET lastPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long): Int

    @Query("UPDATE library_media SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: String, durationMs: Long): Int

    @Query("UPDATE library_media SET name = :newName, path = :newPath WHERE id = :id")
    suspend fun updateNameAndPath(id: String, newName: String, newPath: String): Int
}
