package com.tera.down.feature.player

import com.tera.down.feature.queue.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and persists video playback positions with in-memory caching and debounced database writes.
 * Ensures frequent position changes do not overload the database with excessive I/O.
 */
class PlaybackPositionTracker(
    private val libraryRepository: LibraryRepository?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val minPersistenceIntervalMs: Long = 5000L
) {

    private val mutex = Mutex()
    private val inMemoryPositions = ConcurrentHashMap<String, Long>()
    private val lastPersistedTimestamp = ConcurrentHashMap<String, Long>()

    /**
     * Resolves the starting playback position for a media item.
     * Checks if the local file exists and was not deleted/corrupted.
     * If the previous position was near completion (> 95% or within 3 seconds of end), resets to 0.
     */
    suspend fun getInitialPosition(
        libraryId: String?,
        filePath: String?,
        knownDurationMs: Long? = null
    ): Long {
        // If file was deleted, reset position
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (!file.exists() || file.length() <= 0L) {
                if (libraryId != null) inMemoryPositions.remove(libraryId)
                return 0L
            }
        }

        val cached = if (libraryId != null) inMemoryPositions[libraryId] else null
        val position = if (cached != null) {
            cached
        } else if (libraryId != null && libraryRepository != null) {
            val media = libraryRepository.getMediaById(libraryId)
            media?.lastPositionMs ?: 0L
        } else if (!filePath.isNullOrBlank() && libraryRepository != null) {
            val media = libraryRepository.getMediaByPath(filePath)
            media?.lastPositionMs ?: 0L
        } else {
            0L
        }

        // Cache resolved position
        if (libraryId != null) {
            inMemoryPositions[libraryId] = position
        }

        // Check if position was near completion
        val duration = knownDurationMs ?: 0L
        if (duration > 0L) {
            val isNearEnd = (position >= duration - 3000L) || (position.toDouble() / duration.toDouble() >= 0.95)
            if (isNearEnd) {
                return 0L
            }
        }

        return position.coerceAtLeast(0L)
    }

    /**
     * Updates position in-memory and persists to Room database if the debounce interval has elapsed.
     */
    fun updatePosition(
        libraryId: String?,
        positionMs: Long,
        durationMs: Long,
        forceImmediatePersist: Boolean = false
    ) {
        if (libraryId.isNullOrBlank() || libraryRepository == null) return

        val normalizedPosition = if (durationMs > 0L && (positionMs >= durationMs - 3000L || positionMs.toDouble() / durationMs.toDouble() >= 0.95)) {
            0L
        } else {
            positionMs.coerceAtLeast(0L)
        }

        inMemoryPositions[libraryId] = normalizedPosition

        val now = System.currentTimeMillis()
        val lastSaved = lastPersistedTimestamp[libraryId] ?: 0L

        if (forceImmediatePersist || (now - lastSaved >= minPersistenceIntervalMs)) {
            lastPersistedTimestamp[libraryId] = now
            scope.launch(ioDispatcher) {
                mutex.withLock {
                    libraryRepository.updatePlaybackPosition(libraryId, normalizedPosition)
                    if (durationMs > 0L) {
                        libraryRepository.updateDuration(libraryId, durationMs)
                    }
                }
            }
        }
    }

    /**
     * Flushes any pending position write immediately (e.g. on pause, stop, or player release).
     */
    suspend fun flush(libraryId: String?, positionMs: Long, durationMs: Long) {
        if (libraryId.isNullOrBlank() || libraryRepository == null) return

        val normalizedPosition = if (durationMs > 0L && (positionMs >= durationMs - 3000L || positionMs.toDouble() / durationMs.toDouble() >= 0.95)) {
            0L
        } else {
            positionMs.coerceAtLeast(0L)
        }

        inMemoryPositions[libraryId] = normalizedPosition
        lastPersistedTimestamp[libraryId] = System.currentTimeMillis()

        mutex.withLock {
            libraryRepository.updatePlaybackPosition(libraryId, normalizedPosition)
            if (durationMs > 0L) {
                libraryRepository.updateDuration(libraryId, durationMs)
            }
        }
    }

    /**
     * Clears tracked position for a given media ID.
     */
    suspend fun resetPosition(libraryId: String) {
        inMemoryPositions[libraryId] = 0L
        lastPersistedTimestamp[libraryId] = System.currentTimeMillis()
        mutex.withLock {
            libraryRepository?.updatePlaybackPosition(libraryId, 0L)
        }
    }
}
