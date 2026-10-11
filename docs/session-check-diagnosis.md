# Session Validation Diagnosis

## Error Under Investigation

```
Session validation check failed: unable to verify session with server: network error.
Please make sure you are logged in to your Terabox account.
```

## Root Cause

**Two distinct issues combined to produce the misleading error:**

### 1. Catch-All Error Classification (Code Bug)

The original `DefaultSessionValidator` threw all exceptions — transport failures *and* server-side auth rejections — into a single `Exception` catch block. The UI then displayed a single generic message that always ended with "make sure you are logged in", regardless of whether the problem was network connectivity or an actual authentication failure.

**Evidence:** `SessionValidator.kt` (before fix) had one `catch (e: Exception)` that returned a flat string error. `InteractiveAuthViewModel.kt` unconditionally displayed that string with "make sure you are logged in" appended.

### 2. Terabox.com Unreachable (Regional Blocking)

The primary validation endpoint `https://www.terabox.com/api/user/getinfo` was unreachable due to DNS/ISP-level blocking in the user's region. This caused a `SocketTimeoutException` on every validation attempt, which was then misclassified as a login problem.

**Evidence:** Timeout on `www.terabox.com` in < 5s, while `www.terabox.app` and `1024tera.com` responded immediately with `errno: 0` (authenticated).

### 3. Case-Sensitive Endpoint Path (Minor)

The original code used `/api/user/getInfo` (camelCase). The actual Terabox endpoint is `/api/user/getinfo` (all lowercase). On some server mirrors, the wrong casing returned HTTP 404 or redirected to the login page, triggering a false "not logged in" result.

## Code Paths That Produced the Error

| File | Location | Original Behavior |
|------|----------|-------------------|
| `SessionValidator.kt` | `validateSession()` catch block | All exceptions → single string error |
| `InteractiveAuthViewModel.kt` | `onDone()` → validation failed branch | Showed "make sure you are logged in" for *all* failures |
| `AppContainer.kt` | `onSessionValidationFailed()` | Purged saved session on *any* error, including network |

## Fix Applied

1. **Typed result hierarchy**: `SessionValidationResult` now has `Valid`, `NotLoggedIn`, `InvalidTokens`, `NetworkError`, `ServerError`, `UnexpectedResponse`.
2. **Multi-host mirror failover**: Validator tries `[session domain, www.terabox.app, 1024tera.com, terabox.fun, teraboxapp.com, www.terabox.com]` in order. Network errors try the next host; auth errors return immediately.
3. **Retry-preserving UI**: `NetworkError` and `ServerError` keep the candidate session alive and show a "Retry" button. Only `NotLoggedIn` shows "make sure you are logged in."
4. **Lowercase endpoint**: Fixed to `/api/user/getinfo`.
5. **Session not purged on network failure**: `AppContainer` only clears saved session on explicit `NotLoggedIn`.

## Verification

- 344+ unit tests pass (including new transport/retry/failover tests in `InteractiveAuthValidationTest.kt`).
- `assembleDebug` builds cleanly.
- Real device validation succeeded on first mirror (`www.terabox.app`).
