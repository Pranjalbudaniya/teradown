# Release Candidate Verification & Distribution Checklist

This checklist must be reviewed and completed by a repository maintainer before producing and distributing a production release of **Teledown**.

---

## 1. Project & Application Identity

- [x] **App Name:** `Teledown` (defined in `res/values/strings.xml`).
- [x] **Application ID:** `com.tera.down` (defined in `app/build.gradle.kts`).
- [x] **Namespace:** `com.tera.down`.
- [x] **Kotlin Package:** All classes reside under `com.tera.down.*`.
- [x] **FileProvider Authority:** `${applicationId}.fileprovider` matching `com.tera.down.fileprovider`.
- [x] **Launcher Activity:** `MainActivity` marked as `exported="true"` with `android.intent.action.MAIN` and `android.intent.category.LAUNCHER`.

---

## 2. Versioning Configuration

- [x] **Version Code:** `1` (positive incrementing integer).
- [x] **Version Name:** `1.0.0` (semantic version string).
- [x] **Minimum SDK:** `26` (Android 8.0 Oreo).
- [x] **Target SDK:** `35` (Android 15).
- [x] **Compile SDK:** `35`.

---

## 3. Automated Test & Static Analysis Verification

- [x] **Unit & Integration Tests:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
  *Result: 259 tests passing, 0 failures, 0 skipped.*
- [x] **Static Analysis (Lint):**
  ```bash
  ./gradlew lintDebug
  ```
  *Result: 0 errors.*
- [x] **Debug APK Compilation:**
  ```bash
  ./gradlew assembleDebug
  ```
  *Result: `app/build/outputs/apk/debug/app-debug.apk` built successfully.*
- [x] **Release APK Build (Unsigned):**
  ```bash
  ./gradlew assembleRelease
  ```
  *Result: `app/build/outputs/apk/release/app-release-unsigned.apk` built successfully.*

---

## 4. Permissions & Manifest Audit

Verify that only necessary permissions are declared in `app/src/main/AndroidManifest.xml`:

- [x] `android.permission.INTERNET`: Required for HTTPS share link resolution and file streaming.
- [x] `android.permission.ACCESS_NETWORK_STATE`: Required for Wi-Fi only network monitoring.
- [x] `android.permission.POST_NOTIFICATIONS`: Required for Android 13+ background download progress notifications.
- [x] `android.permission.FOREGROUND_SERVICE`: Required for ongoing background transfers.
- [x] `android.permission.FOREGROUND_SERVICE_DATA_SYNC`: Required under Android 14+ for data transfer foreground services.

---

## 5. Security & Privacy Audit

- [x] **No Hardcoded Credentials:** Verified that no personal passwords, access tokens, or private share cookies exist in repository files or fixtures.
- [x] **Token Redaction:** Verified that sensitive URL query parameters (`token`, `sign`, `key`, `auth`, `password`, `session`) are masked in exported manifests.
- [x] **Scoped Storage:** Verified that file operations conform to Android 15 storage boundaries without broad storage permissions.
- [x] **Dependency Check:** Verified that no test or debug dependencies leak into production `implementation` blocks.
- [x] **Git Exclusions (`.gitignore`):** Keystores (`*.jks`, `*.keystore`), local properties (`local.properties`), environment files (`.env*`), and build artifacts are strictly ignored.

---

## 6. Steps for Maintainer Production Signing

The release APK built by Gradle (`app-release-unsigned.apk`) is unsigned by design. Maintainers should sign the artifact using their private keystore via `apksigner`:

```bash
# 1. Align the release APK (if not already aligned)
zipalign -v -p 4 app/build/outputs/apk/release/app-release-unsigned.apk teledown-1.0.0-aligned.apk

# 2. Sign with your production keystore
apksigner sign --ks /path/to/your-release-key.jks \
  --ks-key-alias your-key-alias \
  --out teledown-1.0.0-signed.apk \
  teledown-1.0.0-aligned.apk

# 3. Verify signature
apksigner verify --verbose teledown-1.0.0-signed.apk
```

---

## 7. GitHub Repository Status

- [x] **Remote URL:** `git@github.com:Pranjalbudaniya/teradown.git`.
- [x] **Default Branch:** `main`.
- [x] **Working Tree Cleanliness:** Only purposeful code, tests, and documentation staged.
