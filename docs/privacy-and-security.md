# Teledown Privacy and Security Architecture

This document outlines the privacy practices, data protection measures, and security boundaries implemented in the **Teledown** Android application (`com.tera.down`).

---

## 1. Local-First Data Storage Policy

Teledown is an entirely **local-first** application:
- **No Remote Servers:** The app does not connect to any custom backends, user registration services, or proprietary cloud endpoints.
- **No Analytics / Telemetry:** There are no analytics libraries (e.g., Firebase Analytics, Google Analytics), crash reporters, or advertising SDKs.
- **Device-Bound Data:** All application state is stored locally on the user's device:
  - **Download Queue & Manifest Logs:** Stored in the app's private SQLite database (`AppDatabase`, managed by Room).
  - **User Preferences:** Stored in private app storage via Jetpack DataStore (`settings.preferences_pb`).
  - **Temporary Files:** Stored in private cache/staging folders with random UUID naming and deleted immediately upon cancellation or failure.

---

## 2. Network Requests & Outbound Traffic

The application only communicates over the network when initiated by direct user action:

1. **Share Link Resolution:** An HTTPS request to the user-specified Terabox domain to inspect public metadata and directory contents.
2. **File Downloads:** HTTPS requests directly to CDN endpoints specified in authorized direct download URLs.

### HTTPS Enforcement & Cleartext Blocking
- `SafeRedirectInterceptor` strictly enforces that all file transfers and resolver requests use HTTPS (`https://`).
- Cleartext HTTP traffic is blocked by default across the entire application.

---

## 3. Credential & Query Parameter Privacy

### Sensitive Token Redaction
Terabox download and media URLs frequently contain sensitive authorization tokens, signatures, or session timestamps in their query strings (e.g. `&token=...`, `&sign=...`, `&auth=...`).

To protect user privacy and avoid accidental leaks:
- `ManifestExporter` and `MediaUrlSanitizer` automatically redact known sensitive query parameters when logging to the manifest or exporting records to JSON/CSV:
  - `token` $\rightarrow$ `***`
  - `sign` $\rightarrow$ `***`
  - `key` $\rightarrow$ `***`
  - `auth` $\rightarrow$ `***`
  - `password` $\rightarrow$ `***`
  - `session` $\rightarrow$ `***`
- Exported records display sanitized URLs (e.g., `https://d.terabox.com/file?token=***&sign=***`), preventing users from accidentally leaking short-lived download credentials when sharing logs.

### Minimizing Password Lifetimes
When a user enters a share link password:
- The password is used solely for the duration of resolving that specific share session.
- Passwords are not permanently written to persistent database records or manifest log entries.

---

## 4. File System & Storage Permissions

### Scoped Storage Compatibility
- Teledown targets Android 15 (API 35) and fully conforms to Android Scoped Storage.
- By default, files are downloaded into app-specific media directories (`Context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`).
- When a user chooses a custom directory, access is handled via Android's Storage Access Framework (SAF) using persistent URI permission grants.

### Path Traversal Protection
- All remote filenames are processed through `FilenameSanitizer.sanitize()`.
- Dangerous characters (`\ / : * ? " < > |`), directory traversal tokens (`../`, `..\`), null bytes (`\u0000`), and leading/trailing dots or spaces are stripped before any file is written to disk.

### Content Sharing via FileProvider
- When opening or sharing downloaded files with external media players, Teledown utilizes Android's `FileProvider` (`com.tera.down.fileprovider`).
- External apps receive temporary, grant-limited content URIs (`content://com.tera.down.fileprovider/...`) with `FLAG_GRANT_READ_URI_PERMISSION`. Raw internal filesystem paths are never exposed.

---

## 5. Security & Access Boundaries

Teledown is an open-source downloader utility designed for legitimate access to public and user-owned shares:
- **No Authentication Evasion:** Teledown does not attempt to bypass user authentication, paywalls, premium download speed gates, or device access limits.
- **No CAPTCHA Bypass:** If Terabox presents an interactive CAPTCHA, bot verification check, or SMS challenge, Teledown does not attempt algorithmic evasion. It returns a structured `AUTH_REQUIRED` or `UNSUPPORTED_ACCESS_FLOW` state.
- **Truthful Status Reporting:** The engine never fakes completion, fabricated file sizes, or artificial transfer success.
