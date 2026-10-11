# Manual Verification Checklist

> These steps require a real Terabox account and physical device.
> They **cannot** be covered by unit tests.

## Prerequisites

- TeraDown debug APK installed on device
- Active Terabox account (email/password or phone-based)
- Ability to toggle airplane mode / VPN

---

## Test 1: Fresh Login → Successful Session Validation

1. Open TeraDown, go to **Settings → Terabox Authentication**.
2. Confirm status shows **Not Logged In**.
3. Paste or enter a Terabox share link in the main screen.
4. App should prompt the Browser Verification screen.
5. Log in using **Terabox Email/Password** or **Phone number** (not Google).
6. After login, tap **Done**.
7. **Expected:** Banner says "Authentication verified successfully!", screen closes, and the download resolves.
8. Go to **Settings → Terabox Authentication** → status shows **Logged In** with domain.

**Pass / Fail: ___**

---

## Test 2: Network Error Shows Correct Message + Retry Works

1. Enable **Airplane Mode** on the device.
2. Go to **Settings → Terabox Authentication → Validate Session**.
3. **Expected:** Error says "Network error: ..." and mentions DNS or timeout. It does **NOT** say "make sure you are logged in."
4. A **Retry** button is visible.
5. Disable Airplane Mode, wait a few seconds, tap **Retry**.
6. **Expected:** Validation succeeds or shows a more specific error.

**Pass / Fail: ___**

---

## Test 3: VPN Toggle Behavior

1. With a valid session, connect to a VPN that routes through a region where `terabox.com` is blocked.
2. Go to **Settings → Validate Session**.
3. **Expected:** Validation still succeeds (mirrors like `www.terabox.app` or `1024tera.com` are tried as fallback). Or if all are blocked, a clear "Network error: Connection timed out" message appears with Retry.
4. Disconnect VPN, tap Retry.
5. **Expected:** Validation succeeds.

**Pass / Fail: ___**

---

## Test 4: Expired / Logged-Out Session Detection

1. With TeraDown showing a valid session, open a normal browser on the device.
2. Navigate to `https://www.terabox.app` and **log out** of your Terabox account.
3. Return to TeraDown → **Settings → Validate Session**.
4. **Expected:** Message says "Session validation failed: Account session is not authenticated" or "Server returned error code". It **should** say to log in again. The Retry button should **NOT** appear (this is an auth problem, not network).

**Pass / Fail: ___**

---

## Test 5: Google Sign-In Policy Notice

1. Start the Browser Verification flow (paste a share link to trigger auth).
2. On the Terabox login page, tap "Sign in with Google" or navigate to `accounts.google.com`.
3. **Expected:** A red notice card appears explaining "Google restricts sign-in inside embedded app WebViews" and suggesting Terabox Email/Password or QR Code login.
4. The notice provides an "Open in Browser" button (does not promise cookie import) and a "Terabox Login" button.
5. No User-Agent spoofing or bypass is attempted.

**Pass / Fail: ___**

---

## Test 6: Cookie File Import (Settings)

1. Prepare a Netscape-format cookie file containing Terabox cookies (export from a browser extension).
2. Go to **Settings → Terabox Authentication → Import Cookie File**.
3. Select the file via the system file picker.
4. **Expected:** File is parsed, validated, and session is established. Status shows "Logged In."
5. Try importing a malformed file (e.g. random text).
6. **Expected:** Clear error: "Invalid cookie file format."

**Pass / Fail: ___**

---

## Test 7: Logout Clears Everything

1. With an active session, go to **Settings → Terabox Authentication → Log Out**.
2. **Expected:** Session status changes to "Not Logged In."
3. Try to download a file from a share link.
4. **Expected:** App prompts the Browser Verification screen again.

**Pass / Fail: ___**

---

## Results Summary

| Test | Result | Notes |
|------|--------|-------|
| 1. Fresh Login | | |
| 2. Network Error + Retry | | |
| 3. VPN Toggle | | |
| 4. Expired Session | | |
| 5. Google Sign-In Notice | | |
| 6. Cookie File Import | | |
| 7. Logout | | |

**Tester:** ___  
**Date:** ___  
**APK Version:** ___  
**Device:** ___  
