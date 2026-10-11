package com.tera.down.feature.manifest

import com.tera.down.core.manifest.DownloadManifestItem
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Supported file export formats for manifest records.
 */
enum class ManifestExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv")
}

/**
 * Result outcome of an export operation.
 */
sealed interface ManifestExportResult {
    data class Success(val recordCount: Int, val format: ManifestExportFormat) : ManifestExportResult
    data class Failure(val message: String, val cause: Throwable? = null) : ManifestExportResult
}

/**
 * Streams manifest items directly into an [OutputStream] using UTF-8 encoding.
 * Designed to avoid holding the entire export payload in memory.
 */
object ManifestExporter {

    const val SCHEMA_VERSION = 1
    const val APP_VERSION = "1.0.0"

    /**
     * Exports items in the specified format to the target stream.
     */
    fun export(
        format: ManifestExportFormat,
        items: List<DownloadManifestItem>,
        outputStream: OutputStream,
        appVersion: String = APP_VERSION,
        schemaVersion: Int = SCHEMA_VERSION
    ): ManifestExportResult {
        return try {
            when (format) {
                ManifestExportFormat.JSON -> exportToJson(items, outputStream, appVersion, schemaVersion)
                ManifestExportFormat.CSV -> exportToCsv(items, outputStream)
            }
            ManifestExportResult.Success(recordCount = items.size, format = format)
        } catch (e: Exception) {
            ManifestExportResult.Failure(
                message = e.message ?: "Failed to export manifest",
                cause = e
            )
        }
    }

    /**
     * Exports manifest items as a JSON document according to schema version 1.
     */
    fun exportToJson(
        items: List<DownloadManifestItem>,
        outputStream: OutputStream,
        appVersion: String = APP_VERSION,
        schemaVersion: Int = SCHEMA_VERSION
    ) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        try {
            writer.write("{\n")
            writer.write("  \"schemaVersion\": $schemaVersion,\n")
            writer.write("  \"appVersion\": \"${escapeJson(appVersion)}\",\n")
            writer.write("  \"exportedAt\": ${System.currentTimeMillis()},\n")
            writer.write("  \"totalRecords\": ${items.size},\n")
            writer.write("  \"records\": [\n")

            items.forEachIndexed { index, item ->
                writer.write("    {\n")
                writer.write("      \"id\": \"${escapeJson(item.id)}\",\n")
                writer.write("      \"taskId\": \"${escapeJson(item.taskId)}\",\n")
                writer.write("      \"sourceUrlRedacted\": \"${escapeJson(item.sourceUrlRedacted)}\",\n")
                writer.write("      \"fileName\": \"${escapeJson(item.fileName)}\",\n")
                writer.write("      \"fileSize\": ${item.fileSize},\n")
                writer.write("      \"mediaType\": \"${escapeJson(item.mediaType.name)}\",\n")
                writer.write("      \"destinationPath\": ${item.destinationPath?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                writer.write("      \"status\": \"${escapeJson(item.status.name)}\",\n")
                writer.write("      \"bytesReceived\": ${item.bytesReceived},\n")
                writer.write("      \"resumed\": ${item.resumed},\n")
                writer.write("      \"errorCategory\": ${item.errorCategory?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                writer.write("      \"errorMessage\": ${item.errorMessage?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                writer.write("      \"checksumSha256\": ${item.checksumSha256?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                writer.write("      \"createdAt\": ${item.createdAt},\n")
                writer.write("      \"completedAt\": ${item.completedAt ?: "null"},\n")
                writer.write("      \"attemptCount\": ${item.attemptCount},\n")
                writer.write("      \"schemaVersion\": ${item.schemaVersion}\n")
                writer.write(if (index < items.size - 1) "    },\n" else "    }\n")
            }

            writer.write("  ]\n")
            writer.write("}\n")
            writer.flush()
        } finally {
            // Keep stream open if wrapped, flush ensures all content is written
        }
    }

    /**
     * Exports manifest items as RFC 4180 standard CSV.
     */
    fun exportToCsv(
        items: List<DownloadManifestItem>,
        outputStream: OutputStream
    ) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        try {
            // RFC 4180 header row
            writer.write("id,taskId,sourceUrlRedacted,fileName,fileSizeBytes,mediaType,destinationPath,status,bytesReceived,resumed,errorCategory,errorMessage,checksumSha256,createdAt,completedAt,attemptCount,schemaVersion\r\n")

            for (item in items) {
                val row = listOf(
                    escapeCsv(item.id),
                    escapeCsv(item.taskId),
                    escapeCsv(item.sourceUrlRedacted),
                    escapeCsv(item.fileName),
                    item.fileSize.toString(),
                    escapeCsv(item.mediaType.name),
                    escapeCsv(item.destinationPath ?: ""),
                    escapeCsv(item.status.name),
                    item.bytesReceived.toString(),
                    item.resumed.toString(),
                    escapeCsv(item.errorCategory ?: ""),
                    escapeCsv(item.errorMessage ?: ""),
                    escapeCsv(item.checksumSha256 ?: ""),
                    item.createdAt.toString(),
                    item.completedAt?.toString() ?: "",
                    item.attemptCount.toString(),
                    item.schemaVersion.toString()
                ).joinToString(",")
                writer.write(row)
                writer.write("\r\n")
            }
            writer.flush()
        } finally {
            // Keep stream open if wrapped, flush ensures all content is written
        }
    }

    internal fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    internal fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
