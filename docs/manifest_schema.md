# Download Manifest & Task Reporting Specification

## 1. Overview

The Download Manifest system in Teradown provides an audit trail of download requests, outcomes, byte counts, and diagnostics. The manifest is a historical record of application activity, not proof of download success. All fields reflect actual outcomes reported by the download coordinator and download engine.

---

## 2. Manifest Schema (Version 1)

Manifest records are stored in the Room database table `download_manifest` (Database Version 3, migrated from Version 2 via `MIGRATION_2_3`).

| Field Name | Type | Nullable | Description |
| :--- | :--- | :--- | :--- |
| `id` | `TEXT` | No | Primary key, uniquely generated UUID for the manifest record. |
| `taskId` | `TEXT` | No | Foreign identifier connecting the record to the queue task (Indexed). |
| `sourceUrlRedacted` | `TEXT` | No | Source URL sanitized via `MediaUrlSanitizer` (all tokens, credentials, and signatures masked). |
| `fileName` | `TEXT` | No | Display file name requested for download. |
| `fileSize` | `INTEGER` | No | Declared file size in bytes (or 0 if unknown). |
| `mediaType` | `TEXT` | No | Media classification (`VIDEO`, `PHOTO`, `DOCUMENT`, `OTHER`). |
| `destinationPath` | `TEXT` | Yes | Local absolute storage path after finalization. |
| `status` | `TEXT` | No | Outcome status: `QUEUED`, `DOWNLOADING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED` (Indexed). |
| `bytesReceived` | `INTEGER` | No | Monotonically recorded bytes transferred to storage. |
| `resumed` | `INTEGER` | No | Boolean flag (`1`/`0`) confirmed by HTTP 206 partial content headers from the download engine. |
| `errorCategory` | `TEXT` | Yes | Typed error classification (`NETWORK_ERROR`, `SERVER_ERROR`, `HTTP_ERROR`, `INSUFFICIENT_STORAGE`, `FILE_IO_ERROR`, `INVALID_URL`, `TIMEOUT`, `UNKNOWN`). |
| `errorMessage` | `TEXT` | Yes | Non-sensitive, safe diagnostic summary message when a task fails. |
| `checksumSha256` | `TEXT` | Yes | Integrity hash, recorded only when genuinely verified. |
| `createdAt` | `INTEGER` | No | Epoch timestamp (ms) when the task was initially enqueued (Indexed). |
| `completedAt` | `INTEGER` | Yes | Epoch timestamp (ms) when the task transitioned to a terminal status. |
| `attemptCount` | `INTEGER` | No | Counter tracking attempts across retries (starts at 1). |
| `schemaVersion` | `INTEGER` | No | Schema version integer (`1`). |

---

## 3. Lifecycle Event Flow

The manifest reporting layer (`ManifestReporter`) receives structured domain events without parsing UI strings or blocking the main thread:

1. **Enqueue Event (`recordEnqueued`)**:
   - Invoked when user queues single or batch tasks via `DownloadQueueRepository`.
   - URL is sanitized immediately with `MediaUrlSanitizer.sanitize(url)`.
   - Idempotent: checks for existing `taskId` before inserting to prevent duplicate records.
   - Status: `QUEUED`, `attemptCount: 1`.

2. **Start Event (`recordStarted`)**:
   - Invoked when `DownloadQueueCoordinator` starts downloading a worker.
   - Status: `DOWNLOADING`.

3. **Progress Event (`recordProgress`)**:
   - Periodically streams bytes received (throttled to 256 KB increments to preserve IO performance).
   - Updates `bytesReceived = max(existing, new)`.

4. **Completion Event (`recordCompleted`)**:
   - Invoked only after `DownloadEngine` confirms successful transfer, content-length validation, and atomic finalization to the destination file.
   - Status: `COMPLETED`, records `destinationPath`, `bytesReceived`, `resumed`, and `completedAt`.

5. **Failure Event (`recordFailed`)**:
   - Invoked when the engine reports structured `DownloadResult.Error`.
   - Maps `DownloadErrorType` to `ManifestErrorCategory`.
   - Status: `FAILED`, records `errorCategory`, safe `errorMessage`, preserves partial bytes.

6. **Cancellation Event (`recordCancelled`)**:
   - Invoked on user cooperative cancel.
   - Status: `CANCELLED`, cleans up temporary `.part` files.

7. **Interruption Event (`recordInterrupted` / `reconcileInterruptedTasks`)**:
   - Invoked on cooperative pause or app process termination.
   - On app startup, `reconcileInterruptedTasks()` scans for records left in `DOWNLOADING` and marks them as `INTERRUPTED` with a safe diagnostic message.

8. **Retry Event (`recordRetrying`)**:
   - Connects retries directly to the original manifest record.
   - Increments `attemptCount`, resets status to `QUEUED`, clears previous error messages and completion timestamp.

---

## 4. Export Formats

Manifest records can be exported via the Storage Access Framework (SAF) without holding the entire payload in memory.

### 4.1 JSON Export (`application/json`)
Document envelope adhering to schema version 1:
```json
{
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "exportedAt": 1700000000000,
  "totalRecords": 1,
  "records": [
    {
      "id": "c6a2e4b1-87dc-4a6c-92e1-45f8e6c43312",
      "taskId": "task-883",
      "sourceUrlRedacted": "https://terabox.com/s/1abc?token=***&pwd=***",
      "fileName": "lecture_01.mp4",
      "fileSize": 104857600,
      "mediaType": "VIDEO",
      "destinationPath": "/storage/emulated/0/Download/lecture_01.mp4",
      "status": "COMPLETED",
      "bytesReceived": 104857600,
      "resumed": true,
      "errorCategory": null,
      "errorMessage": null,
      "checksumSha256": null,
      "createdAt": 1700000000000,
      "completedAt": 1700000030000,
      "attemptCount": 1,
      "schemaVersion": 1
    }
  ]
}
```

### 4.2 CSV Export (`text/csv`)
Standard RFC 4180 CSV with UTF-8 encoding and double-quote escaping:
```csv
id,taskId,sourceUrlRedacted,fileName,fileSizeBytes,mediaType,destinationPath,status,bytesReceived,resumed,errorCategory,errorMessage,checksumSha256,createdAt,completedAt,attemptCount,schemaVersion
"c6a2e4b1-87dc-4a6c-92e1-45f8e6c43312","task-883","https://terabox.com/s/1abc?token=***&pwd=***","lecture_01.mp4",104857600,"VIDEO","/storage/emulated/0/Download/lecture_01.mp4","COMPLETED",104857600,true,"","","",1700000000000,1700000030000,1,1
```

---

## 5. Privacy Protections

- **Query Parameters**: All credentials, tokens, session cookies, passwords, and signature query parameters (`token`, `auth`, `signature`, `sig`, `key`, `access_token`, `pwd`, `password`, `sign`, `secret`, `credential`) are masked (`***`) by `MediaUrlSanitizer`.
- **Userinfo**: User credentials in authority (`http://user:pass@host/`) are stripped.
- **Headers & Cookies**: Authorization headers and session cookies are never written to the manifest database or export streams.
- **Local Isolation**: Manifest records are strictly stored in local Room SQLite storage and never uploaded to any remote server or analytics service.

---

## 6. Data Retention Rules

1. **Queue Independence**: `download_manifest` is a distinct table without cascading deletes to `download_tasks`. When users tap "Clear Done" in the Downloads queue, manifest records remain completely intact.
2. **Explicit Clearing**: Users can explicitly clear manifest history via the Manifest screen trash button with a confirmation dialog. Clearing manifest history never deletes downloaded files or library entries.
3. **Database Migration**: Schema updates use Room `MIGRATION_2_3`. Destructive migrations are disabled.
