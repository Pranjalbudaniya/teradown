package com.tera.down.feature.downloader

/**
 * Interface defining the low-level streaming file download engine.
 */
interface DownloadEngine {

    /**
     * Streams bytes from the requested URL directly to a local temporary file,
     * verifying HTTP byte ranges for resumable transfers, and finalizes the file upon completion.
     *
     * @param request Target parameters including URL, destination, temp path, and resume flags.
     * @param onProgress Callback invoked periodically with (downloadedBytes, totalBytes, speedBytesPerSec).
     * @return [DownloadResult] indicating success, cancellation, or structured failure.
     */
    suspend fun download(
        request: DownloadRequest,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit = { _, _, _ -> }
    ): DownloadResult
}
