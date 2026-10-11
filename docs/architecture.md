# Teledown Architecture Specification

**Package Root:** `com.tera.down`  
**Application ID:** `com.tera.down`  
**Target SDK:** 35 | **Min SDK:** 26  

---

## 1. High-Level Architecture

Teledown is architected using **Clean Architecture** principles and **Unidirectional Data Flow (UDF)**. Business logic, storage, networking, and UI layers are strictly decoupled through interfaces and dependency injection.

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│  Jetpack Compose Screens & ViewModels (StateFlow / UDF)     │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
┌──────────────▼───────────────────────────────▼──────────────┐
│                      Domain & Feature                       │
│  DownloadQueueCoordinator, StorageManager, RecoveryManager   │
│  PlaybackPositionTracker, Formatters, Error Handlers        │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
┌──────────────▼───────────────────────────────▼──────────────┐
│                      Repository Layer                       │
│  DownloadQueueRepository, LibraryRepository,                 │
│  ManifestRepository, SettingsRepository                     │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
┌──────────────▼───────────────┐ ┌─────────────▼──────────────┐
│      Local Persistence       │ │      Network & System       │
│  Room DB (SQLite), DataStore │ │  OkHttp, ForegroundService, │
│  Internal / Public Storage   │ │  Media3 ExoPlayer Engine    │
└──────────────────────────────┘ └────────────────────────────┘
```

---

## 2. Package Structure

```
com.tera.down
├── TeradownApplication.kt          # Application class, initializes DI container
├── MainActivity.kt                 # Single Activity entry point with Compose Host
├── core
│   ├── classifier                  # Media file extension classifier (Video, Audio, Image, Doc)
│   ├── database
│   │   ├── dao                     # Room DAOs (DownloadTaskDao, LibraryMediaDao, ManifestDao)
│   │   ├── entity                  # Room Entities (DownloadTaskEntity, etc.)
│   │   └── AppDatabase.kt          # Room Database definition & schema versioning
│   ├── di                          # Manual DI (AppContainer, DefaultAppContainer)
│   ├── manifest                    # Shared manifest models & error category enums
│   ├── model                       # Core domain models (DownloadStatus, MediaType, ShareLink)
│   ├── network                     # SafeRedirectInterceptor, NetworkConnectivityMonitor
│   ├── player                      # PlayableMedia models, MediaUrlSanitizer, PlayerValidator
│   └── util                        # LocalFileActions (Open, Share via FileProvider)
├── feature
│   ├── downloader                  # DownloadEngine, OkHttpDownloadEngine, StorageManager,
│   │                               # DownloadQueueCoordinator, DownloadForegroundService,
│   │                               # DownloadRecoveryManager, DownloadNotificationManager
│   ├── manifest                    # ManifestReporter, ManifestRepository, ManifestExporter
│   ├── player                      # PlaybackPositionTracker, PlayerViewModel, PlayerScreen
│   ├── queue                       # DownloadQueueRepository, LibraryRepository, StateValidator
│   ├── resolver                    # TeraboxShareLinkResolver, ShareLinkParser, FolderTreeScanner
│   └── settings                    # SettingsRepository (DataStore implementation)
└── ui
    ├── browse                      # BrowseShareScreen & BrowseShareViewModel
    ├── components                  # Reusable UI widgets (TopBar, EmptyState, Formatters)
    ├── downloads                   # DownloadsScreen & DownloadsViewModel
    ├── home                        # HomeScreen & HomeViewModel
    ├── library                     # LibraryScreen & LibraryViewModel
    ├── navigation                  # Screen routes & TeledownNavHost navigation graph
    ├── settings                    # SettingsScreen & SettingsViewModel
    └── theme                       # TeledownTheme, Color schemes, Typography
```

---

## 3. Dependency Injection (`core/di`)

Dependency injection is managed cleanly via the `AppContainer` interface without heavy reflection-based frameworks:
- `DefaultAppContainer` instantiates singletons:
  - `AppDatabase` (Room)
  - `OkHttpClient` with connection timeouts and redirect safety
  - `StorageManager`
  - `DownloadEngine`
  - `DownloadQueueRepository`
  - `LibraryRepository`
  - `ManifestRepository`
  - `SettingsRepository`
  - `DownloadQueueCoordinator`
  - `NetworkConnectivityMonitor`
  - `DownloadRecoveryManager`
- `TeradownApplication` holds a reference to `AppContainer`, providing repositories to ViewModel factories.

---

## 4. UI Layer & Compose Navigation (`ui/`)

### Single-Activity Architecture
- `MainActivity` initializes edge-to-edge layout, sets `TeledownTheme`, and hosts `TeledownMainScreen`.
- Top-level bottom navigation supports:
  - **Home:** Share URL input, clipboard paste, history/recents.
  - **Downloads:** Active queue, speed gauges, pause/resume/cancel controls, and completed history.
  - **Library:** Grid/list of downloaded files, search, filtering, and video launcher.
  - **Settings:** Concurrency, Wi-Fi only, theme, and storage preferences.
- Secondary destinations:
  - **BrowseShare:** Parameterized with URL and optional password (`Screen.BrowseShare.createRoute(url, pwd)`).
  - **Player:** Parameterized with file path and video title (`Screen.Player.createRoute(path, title)`).
  - **Manifest:** Full-screen manifest inspection and JSON/CSV export view.

### Safe Navigation Encoding
All route string arguments (URLs, file paths, passwords) are safely percent-encoded using standard URL codecs (`Screen.BrowseShare.createRoute`) and decoded in `TeledownNavHost` to prevent malformed URI crashes on characters like `&`, `?`, or `/`.

---

## 5. Local Persistence & Room Schema (`core/database`)

### Entities
1. `DownloadTaskEntity` (`download_tasks` table):
   - Key: `id` (UUID string)
   - Fields: `originalUrl`, `surl`, `fsId`, `name`, `relPath`, `size`, `downloadedBytes`, `speedBytesPerSec`, `status`, `mediaType`, `destinationPath`, `errorMessage`, `createdAt`, `updatedAt`.
2. `LibraryMediaEntity` (`library_media` table):
   - Key: `id` (UUID string)
   - Fields: `taskId`, `name`, `path`, `size`, `mediaType`, `durationMs`, `lastPositionMs`, `dateAdded`, `fsId`, `surl`.
3. `DownloadManifestEntity` (`download_manifest` table):
   - Key: `id` (UUID string)
   - Fields: `taskId`, `sourceUrl`, `fileName`, `fileSize`, `mediaCategory`, `destinationPath`, `status`, `bytesReceived`, `resumed`, `attemptCount`, `errorCategory`, `errorMessage`, `enqueuedAt`, `startedAt`, `completedAt`.

### State Machine & Validation
`DownloadQueueStateValidator` enforces legal status transitions:
- `QUEUED` $\rightarrow$ `DOWNLOADING`, `PAUSED`, `CANCELLED`
- `DOWNLOADING` $\rightarrow$ `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`
- `PAUSED` $\rightarrow$ `QUEUED`, `CANCELLED`
- `FAILED` $\rightarrow$ `QUEUED`, `CANCELLED`
- `COMPLETED` and `CANCELLED` are terminal states.

---

## 6. Download Engine & Lifecycle (`feature/downloader`)

### Two-Stage Atomic File Finalization
1. **Staging:** In-flight downloads write to `.staging_<taskId>` inside the app's internal or configured staging directory (`storageManager.getTempFile(taskId)`).
2. **Atomic Move:** Upon HTTP completion, the downloaded byte count and file integrity are validated. The temporary file is atomically renamed to the final destination (`storageManager.getDestinationFile(sanitizedFilename)`).
3. **Cancellation Cleanup:** If the user cancels or an unrecoverable failure occurs, temporary staging files are immediately deleted to conserve user disk space.

### HTTP Streaming & Range Resume
- Uses `OkHttpDownloadEngine`.
- Supports standard HTTP 206 `Partial Content` via `Range: bytes=<offset>-`.
- If the remote server responds with HTTP 200 instead of HTTP 206, the engine safely resets offset to 0 and streams from the beginning.
- Throttles progress emissions to avoid UI thread starvation.

---

## 7. Crash Recovery & Reconciliation (`DownloadRecoveryManager`)

On application startup, `DownloadRecoveryManagerImpl.reconcileAndRecover()` executes:
1. Queries all tasks left in `DOWNLOADING` (indicating unexpected process death).
2. If the destination file already exists and matches expected size:
   - Marks task as `COMPLETED`.
   - Indexes into the library.
   - Reports `recordCompleted` in manifest.
3. If a partial temporary staging file exists on disk:
   - Transitions task to `PAUSED` with recovered `downloadedBytes = tempFile.length()`.
   - Never falsely marks incomplete files as complete.
   - Marks manifest entry as `INTERRUPTED`.
4. If no partial file is found:
   - Resets task to `PAUSED` with 0 bytes downloaded.

---

## 8. Background Work & Foreground Service (`DownloadForegroundService`)

- Concurrency is managed in memory by `DownloadQueueCoordinatorImpl`, bound to `maxConcurrentDownloads` (1 to 5).
- Transfers run in an Android `ForegroundService` with `foregroundServiceType="dataSync"`.
- Real-time notification updates report task progress, byte totals, and speed.
- `DownloadNotificationActionReceiver` handles notification button taps (`Pause`, `Resume`, `Cancel`) targeting specific task IDs.

---

## 9. Media3 Local Video Playback (`feature/player`)

- Integrated with **AndroidX Media3 ExoPlayer** (`1.4.1`).
- `PlayerMediaValidator` validates local video files before opening (checks file existence, readability, positive file size, and video MIME extension).
- `PlaybackPositionTracker` caches playback position in memory and debounces writes to Room.
- Positions within 3 seconds of the video end or $\ge 95\%$ auto-reset to 0 to provide a clean start upon replay.

---

## 10. Settings Persistence (`feature/settings`)

- Persisted in **Jetpack DataStore Preferences** (`settings.preferences_pb`).
- Exposes reactive `Flow<AppSettings>`:
  - `maxConcurrentDownloads` (Int, 1..5)
  - `wifiOnly` (Boolean)
  - `themeMode` (SYSTEM, LIGHT, DARK)
  - `downloadDirectory` (String?)
  - `autoResumeInterrupted` (Boolean)
