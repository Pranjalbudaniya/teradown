package com.tera.down

import com.tera.down.core.manifest.DownloadManifestItem
import com.tera.down.core.manifest.ManifestStatus
import com.tera.down.core.model.MediaType
import com.tera.down.core.player.MediaUrlSanitizer
import com.tera.down.feature.manifest.ManifestExportFormat
import com.tera.down.feature.manifest.ManifestExportResult
import com.tera.down.feature.manifest.ManifestExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ManifestSerializationTest {

    private fun createSampleItem(
        id: String = "man-1",
        taskId: String = "task-1",
        rawUrl: String = "https://example.com/share?token=secret123&pwd=pass456&file=movie.mp4",
        fileName: String = "sample_video.mp4",
        fileSize: Long = 10485760L,
        status: ManifestStatus = ManifestStatus.COMPLETED,
        bytesReceived: Long = 10485760L,
        resumed: Boolean = true,
        errorCategory: String? = null,
        errorMessage: String? = null,
        destinationPath: String? = "/storage/emulated/0/Download/sample_video.mp4"
    ): DownloadManifestItem {
        return DownloadManifestItem(
            id = id,
            taskId = taskId,
            sourceUrlRedacted = MediaUrlSanitizer.sanitize(rawUrl),
            fileName = fileName,
            fileSize = fileSize,
            mediaType = MediaType.VIDEO,
            destinationPath = destinationPath,
            status = status,
            bytesReceived = bytesReceived,
            resumed = resumed,
            errorCategory = errorCategory,
            errorMessage = errorMessage,
            checksumSha256 = null,
            createdAt = 1700000000000L,
            completedAt = 1700000060000L,
            attemptCount = 2,
            schemaVersion = 1
        )
    }

    @Test
    fun jsonExport_producesValidJsonWithDocumentedSchema() {
        val item = createSampleItem()
        val out = ByteArrayOutputStream()

        val result = ManifestExporter.export(
            format = ManifestExportFormat.JSON,
            items = listOf(item),
            outputStream = out,
            appVersion = "1.2.3",
            schemaVersion = 1
        )

        assertTrue(result is ManifestExportResult.Success)
        val jsonStr = out.toString(Charsets.UTF_8.name())

        // Validate top-level metadata schema
        assertTrue(jsonStr.startsWith("{\n"))
        assertTrue(jsonStr.contains("\"schemaVersion\": 1"))
        assertTrue(jsonStr.contains("\"appVersion\": \"1.2.3\""))
        assertTrue(jsonStr.contains("\"totalRecords\": 1"))
        assertTrue(jsonStr.contains("\"records\": ["))
        assertTrue(jsonStr.trim().endsWith("}"))

        // Validate record fields
        assertTrue(jsonStr.contains("\"id\": \"man-1\""))
        assertTrue(jsonStr.contains("\"taskId\": \"task-1\""))
        assertTrue(jsonStr.contains("\"fileName\": \"sample_video.mp4\""))
        assertTrue(jsonStr.contains("\"fileSize\": 10485760"))
        assertTrue(jsonStr.contains("\"mediaType\": \"VIDEO\""))
        assertTrue(jsonStr.contains("\"status\": \"COMPLETED\""))
        assertTrue(jsonStr.contains("\"bytesReceived\": 10485760"))
        assertTrue(jsonStr.contains("\"resumed\": true"))
        assertTrue(jsonStr.contains("\"destinationPath\": \"/storage/emulated/0/Download/sample_video.mp4\""))
        assertTrue(jsonStr.contains("\"attemptCount\": 2"))
        assertTrue(jsonStr.contains("\"schemaVersion\": 1"))

        // Ensure sensitive tokens are redacted in exported JSON
        assertFalse(jsonStr.contains("secret123"))
        assertFalse(jsonStr.contains("pass456"))
        assertTrue(jsonStr.contains("token=***"))
        assertTrue(jsonStr.contains("pwd=***"))
    }

    @Test
    fun jsonExport_handlesEmptyList() {
        val out = ByteArrayOutputStream()
        val result = ManifestExporter.export(
            format = ManifestExportFormat.JSON,
            items = emptyList(),
            outputStream = out
        )

        assertTrue(result is ManifestExportResult.Success)
        val jsonStr = out.toString(Charsets.UTF_8.name())
        assertTrue(jsonStr.contains("\"totalRecords\": 0"))
        assertTrue(jsonStr.contains("\"records\": [\n  ]"))
    }

    @Test
    fun csvExport_producesRfc4180CompliantHeadersAndEscaping() {
        val itemWithSpecialChars = createSampleItem(
            fileName = "cool, \"special\" \n name.mp4",
            destinationPath = "/path/with,comma/file.mp4"
        )
        val out = ByteArrayOutputStream()

        val result = ManifestExporter.export(
            format = ManifestExportFormat.CSV,
            items = listOf(itemWithSpecialChars),
            outputStream = out
        )

        assertTrue(result is ManifestExportResult.Success)
        val csvStr = out.toString(Charsets.UTF_8.name())

        val lines = csvStr.lines()
        // Header verification
        assertTrue(lines[0].startsWith("id,taskId,sourceUrlRedacted,fileName,fileSizeBytes"))
        // Check RFC 4180 double-quote escaping: "cool, ""special"" \n name.mp4"
        assertTrue(csvStr.contains("\"cool, \"\"special\"\" \n name.mp4\""))
        assertTrue(csvStr.contains("\"/path/with,comma/file.mp4\""))
    }

    @Test
    fun csvExport_handlesEmptyList() {
        val out = ByteArrayOutputStream()
        val result = ManifestExporter.export(
            format = ManifestExportFormat.CSV,
            items = emptyList(),
            outputStream = out
        )

        assertTrue(result is ManifestExportResult.Success)
        val csvStr = out.toString(Charsets.UTF_8.name())
        val lines = csvStr.trim().lines()
        assertEquals(1, lines.size) // Only header row
        assertTrue(lines[0].startsWith("id,taskId,sourceUrlRedacted"))
    }

    @Test
    fun export_handlesStreamErrorsGracefully() {
        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Disk full or stream closed")
            }
        }

        val result = ManifestExporter.export(
            format = ManifestExportFormat.JSON,
            items = listOf(createSampleItem()),
            outputStream = failingStream
        )

        assertTrue(result is ManifestExportResult.Failure)
        val failure = result as ManifestExportResult.Failure
        assertTrue(failure.message.contains("Disk full or stream closed"))
    }

    @Test
    fun jsonEscape_handlesControlCharactersProperly() {
        val raw = "Line 1\nLine 2\tTabbed \"Quoted\" \\Backslash"
        val escaped = ManifestExporter.escapeJson(raw)
        assertEquals("Line 1\\nLine 2\\tTabbed \\\"Quoted\\\" \\\\Backslash", escaped)
    }

    @Test
    fun csvEscape_wrapsWhenNecessary() {
        assertEquals("simple", ManifestExporter.escapeCsv("simple"))
        assertEquals("\"has,comma\"", ManifestExporter.escapeCsv("has,comma"))
        assertEquals("\"has\"\"quotes\"\"\"", ManifestExporter.escapeCsv("has\"quotes\""))
        assertEquals("\"has\nnewline\"", ManifestExporter.escapeCsv("has\nnewline"))
    }
}
