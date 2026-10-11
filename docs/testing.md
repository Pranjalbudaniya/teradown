# Teledown Testing Guide

This document describes the testing strategy, test categories, execution commands, fixture usage, and manual verification procedures for the **Teledown** Android project (`com.tera.down`).

---

## 1. Test Strategy & Structure

Teledown enforces a comprehensive, multi-layer testing pyramid. Normal CI test runs **never depend on live Terabox servers or real user credentials**. All network and database interactions in automated tests are executed against local mock servers and in-memory test databases.

```
┌─────────────────────────────────────────────────────────────┐
│             End-to-End Workflows (Phase 14)                 │
│  Multi-component integration tests (MockWebServer + Room)  │
├─────────────────────────────────────────────────────────────┤
│                 UI & ViewModel State Tests                  │
│  StateFlow assertions, user intents, navigation arguments   │
├─────────────────────────────────────────────────────────────┤
│               Repository & Coordinator Tests                │
│  State validation, concurrency throttling, recovery logic   │
├─────────────────────────────────────────────────────────────┤
│               Room DAOs & Persistence Tests                 │
│  Schema validity, insert/update/delete, queries             │
├─────────────────────────────────────────────────────────────┤
│                 Pure Logic & Unit Tests                     │
│  URL parsing, sanitization, formatters, classifiers         │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Test Execution Commands

### Run All Unit and Integration Tests
```bash
./gradlew testDebugUnitTest
```
Executes all 259 tests across 36 test classes.

### Run Specific Test Suite
```bash
./gradlew testDebugUnitTest --tests "com.tera.down.EndToEndReleaseCandidateWorkflowTest"
./gradlew testDebugUnitTest --tests "com.tera.down.OkHttpDownloadEngineTest"
./gradlew testDebugUnitTest --tests "com.tera.down.NetworkAndConcurrencySettingsTest"
```

### Run Static Analysis (Android Lint)
```bash
./gradlew lintDebug
```
Inspect HTML report at:
`app/build/reports/lint-results-debug.html`

---

## 3. Test Suites and Categories

| Category | Key Test Classes | Description |
| :--- | :--- | :--- |
| **End-to-End Workflows** | `EndToEndReleaseCandidateWorkflowTest` | Exercises Workflows A through H: startup, resolution, folder browsing, download streaming, crash recovery, library management, and redacted manifest export. |
| **Download Engine & Streaming** | `OkHttpDownloadEngineTest`, `DownloadUrlValidatorTest`, `FilenameSanitizerTest` | Validates MockWebServer downloads, chunked transfer, range resumption, progress callbacks, and atomic file moves. |
| **Queue & Concurrency** | `DownloadQueueRepositoryTest`, `DownloadQueueStateValidatorTest`, `DownloadQueueCoordinatorTest`, `NetworkAndConcurrencySettingsTest` | Validates state machine legality, duplicate prevention, and concurrency limits (1–5). |
| **Crash Recovery & Reconciliation** | `DownloadRecoveryManagerTest` | Tests recovery after simulated crashes with complete, partial, and missing staging files. |
| **Resolver & URL Parsing** | `TeraboxUrlParserTest`, `ShareLinkParserTest`, `TeraboxShareLinkResolverTest`, `FolderTreeScannerTest` | Tests parsing of `terabox.com`, `terabox.app`, `1024tera.com`, password encoding, and breadcrumb navigation. |
| **Media & Playback** | `MediaClassifierTest`, `MediaUrlSanitizerTest`, `PlayerMediaValidatorTest`, `PlaybackPositionTrackerTest` | Tests MIME classification, URL sanitization, video validation, and debounced playback position persistence. |
| **Library Management** | `LibraryManagementAndFileActionsTest`, `LibraryViewModelTest` | Verifies search, filter, sort, truthful storage calculation, and separation of record removal vs. physical deletion. |
| **Manifest & Settings** | `ManifestRepositoryTest`, `ManifestSerializationTest`, `ManifestViewModelTest`, `SettingsRepositoryTest` | Tests manifest event recording, DataStore settings, and JSON/CSV export with query token redaction. |
| **Security & Hardening** | `Phase13ReliabilityAndSecurityHardeningTest` | Tests path traversal prevention, dangerous filename sanitization, error categorization, and HTTPS enforcement. |

---

## 4. Use of Fakes and Mock Servers

### MockWebServer for Network Tests
The download engine is tested using OkHttp's `MockWebServer`. Tests simulate:
- HTTP 200 responses with known `Content-Length`.
- Chunked responses with unknown length (`expectedSize = -1L`).
- HTTP 206 `Partial Content` with valid `Content-Range: bytes 500-999/1000`.
- Server errors: HTTP 403 Forbidden, HTTP 404 Not Found, HTTP 500 Internal Error.
- Connection interruptions mid-stream.

> **Note:** In tests running against `MockWebServer` on `127.0.0.1`, `OkHttpDownloadEngine` is instantiated with `allowHttpForTesting = true` to allow local loopback traffic while keeping the default production behavior HTTPS-only.

### In-Memory Database Fakes
- `FakeDownloadTaskDao`, `FakeLibraryMediaDao`, and `FakeDownloadManifestDao` emulate Room SQLite operations in memory with full Flow emission support, enabling high-speed, deterministic unit testing without database locks.

---

## 5. Manual Verification Procedures

Features that involve physical hardware, device operating system UI, or external server state require manual verification:

### A. Android Notification Actions
1. Queue a large file on a physical device or emulator.
2. Background the app.
3. Verify the ongoing transfer notification appears with accurate progress and speed.
4. Tap the **Pause** notification action $\rightarrow$ verify download pauses immediately.
5. Tap **Resume** $\rightarrow$ verify transfer resumes from previous byte position.

### B. Scoped Storage & File Provider Sharing
1. In Library, tap on a downloaded video $\rightarrow$ verify it plays smoothly in the built-in Media3 player.
2. Tap the overflow menu $\rightarrow$ tap **Share** $\rightarrow$ verify Android Sharesheet opens and external apps (e.g., VLC, WhatsApp) can stream the file via `FileProvider`.
3. Select a custom external download folder in Settings $\rightarrow$ verify system SAF directory picker prompt appears.

### C. Live Terabox Resolution Verification
1. Connect device to a live network.
2. Enter a legitimate, publicly accessible Terabox share link without password.
3. Tap **Inspect Share** $\rightarrow$ verify folder contents and filenames populate truthfully.
4. If a password is required, verify the password prompt dialog appears.
5. If the share link is invalid or expired, verify the error message is displayed cleanly without crashing.
