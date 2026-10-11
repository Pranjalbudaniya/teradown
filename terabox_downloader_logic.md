# Terabox Share-Link Downloader: Full Logic Spec

Personal-use tool for downloading videos and photos from Terabox share links (`terabox.com/s/...`) that you own or were given access to.

---

## 1. Scope and ground rules

**In scope**
- Share links to a single file, a flat folder, or nested folders
- Videos and photos (other file types are skipped and counted)
- Resumable downloads, automatic link refresh, restart-safe state
- Clear handling for unavailable, blocked, expired, or password-protected content
- Watching a video without saving it, through a local streaming proxy (section 16)

**Out of scope (deliberately not implemented)**
- Login, captcha, or paywall bypass
- Speed-limit circumvention (parallel range splitting of one file, account rotation, proxy rotation)
- Stream-ripping when no direct download link is offered
- Using anyone else's session cookie

**Known risk:** Terabox web endpoints are undocumented. Parameter names, signing, and error codes change without notice. All endpoint-specific logic must live in one isolated module (the *resolver*) so it can be fixed without touching the rest.

---

## 2. Architecture

| Module | Responsibility |
|---|---|
| `resolver` | Link parsing, share info, folder listing, per-file download-link resolution. The only module that knows Terabox URLs, params, and signing. |
| `classifier` | Decides video / photo / other from extension and category. |
| `downloader` | Resumable, verified download of one URL to one file. |
| `queue` | Concurrency, pause/resume, state persistence. |
| `manifest` | Per-share JSON state, keyed by `fs_id`. |
| `reporter` | Summary and final report. |
| `streamer` | Local HTTP proxy that lets a player watch a video without saving it (section 16). Reuses `resolver`. |
| `cli` | `download`, `list`, `doctor`, `retry-failed`, `watch`. |

---

## 3. Configuration

| Setting | Default | Notes |
|---|---|---|
| `TERABOX_COOKIE` | none | Env var or gitignored config file. Never hardcode, never log. |
| `out_dir` | `./out` | Output root. |
| `want` | `{video, photo}` | Which media types to download. |
| `layout` | `keep_tree` | `keep_tree` mirrors the share structure; `flatten` puts everything in one folder. |
| `video_workers` | 2 | Max 4. |
| `photo_workers` | 4 | |
| `chunk_size` | 1 to 4 MB | Streaming chunk. |
| `max_tries` | 8 | Per file, for network/5xx errors. |
| `max_link_refreshes` | 3 | Per file. |
| `max_depth` | 30 | Folder recursion guard. |
| `page_size` | 100 | Folder listing page size. |
| `persist_every` | ~8 MB | How often to fsync and write offset to the manifest. |
| `dedupe` | off | Optional same-size + same-md5 dedupe within a run. |

---

## 4. Status model

### Share-level states (from share info)

| State | Meaning | Action |
|---|---|---|
| `OK` | Share is reachable | Continue |
| `NEEDS_PASSWORD` | Extraction code required | Use `pwd` from URL, else prompt (max 3 tries) |
| `WRONG_PASSWORD` | Code rejected | Re-prompt; after 3 failures, stop |
| `NOT_LOGGED_IN` | Cookie missing/expired | Run interactive auth (section 17); if it still fails, stop |
| `EXPIRED` | Share expired | Stop with message |
| `CANCELLED` | Owner cancelled the share | Stop with message |
| `BLOCKED` | Share blocked by Terabox | Stop with message |
| `CAPTCHA_OR_RATELIMIT` | Site is demanding a human check or throttling | Run interactive auth so the user can complete the check (section 17); if it recurs, stop and retry later |

### File-level statuses (stored in manifest)

| Status | Retryable? | Meaning |
|---|---|---|
| `PENDING` | n/a | Not started |
| `DOWNLOADING` | n/a | In progress (resume on restart) |
| `DONE` | no | Verified and renamed |
| `SKIPPED_EXISTS` | no | File already present with the correct size |
| `SKIPPED_EMPTY` | no | Size is 0 |
| `SKIPPED_TYPE` | no | Not video/photo (or not in `want`) |
| `NO_DIRECT_LINK` | yes | Share offers no download link for this file (preview/stream only) |
| `MISSING` | no | File deleted or 404 |
| `BLOCKED` | no | File flagged or blocked by Terabox |
| `FAILED_LINK_EXPIRED` | yes | Link kept expiring past the refresh limit |
| `FAILED_SIZE` | yes | Size mismatch after retry |
| `FAILED_NETWORK` | yes | Retries exhausted |
| `QUOTA_PAUSED` | yes | Daily quota or rate limit hit |
| `NEEDS_AUTH` | yes | Cookie rejected mid-run |
| `DISK_FULL` | yes | Out of space |
| `LIST_FAILED` | yes | Folder listing failed (applies to the folder entry) |

### Errno mapping (fill in during discovery)

Terabox `errno` values are not documented. During discovery, record each observed value and map it to a state above.

| Observed `errno` | Endpoint | Meaning (observed) | Mapped state |
|---|---|---|---|
| _fill in_ | | | |

---

## 5. Main flow

```text
main(link, out_dir, want={video,photo}, layout=keep_tree):
    surl, pwd = parse_link(link)
        # handles /s/1XXXX, ?surl=XXXX, ?pwd=, mirror domains, redirects
        # try surl with and without the leading "1"
    session = load_cookie()              # missing -> interactive auth (section 17)
    share = fetch_share_info(session, surl)
        # returns state (see share-level states)
        # plus shareid, uk, sign, timestamp, tokens, title

    switch share.state:
        NEEDS_PASSWORD:        use pwd from URL, else prompt (max 3), then verify
        CAPTCHA_OR_RATELIMIT:  interactive auth (section 17); if it recurs, stop
        NOT_LOGGED_IN:         interactive auth (section 17); if it fails, stop
        EXPIRED/CANCELLED/BLOCKED: stop with clear message

    entries  = walk_share(share)
    entries  = classify_and_filter(entries, want)
    manifest = load_or_create(out_dir/surl/manifest.json)
    manifest.merge(entries)              # keyed by fs_id; keeps DONE items
    print_summary()                      # counts by type, total size, skipped
    check_free_space(remaining_bytes * 1.05)
    run_queue(manifest.pending)
    write_report()
```

### Link parsing details
- Accepts `/s/1XXXX`, `/wap/share/filelist?surl=XXXX`, and any Terabox mirror domain.
- The leading `1` in `/s/1XXXX` is sometimes part of the API `surl` param and sometimes not. Try both.
- Extract `pwd` from the query string if present.
- Normalize to the domain on which the session cookie is valid.
- Follow redirects between mirror domains before extracting.

---

## 6. Folder walk (nested folders, pagination)

```text
walk_share(share):
    stack = ["/"]; seen = {}
    while stack:
        d = stack.pop()
        if d in seen or depth(d) > max_depth: continue     # loop/runaway guard
        seen.add(d)
        page = 1
        loop:
            resp = list_dir(d, page, page_size)             # retry transient errors
            if resp is empty: break                         # empty folder is fine
            for item in resp.list:
                if item.isdir: stack.push(item.path)
                else: yield File(fs_id, name, size, relpath, category, md5?)
            if len(resp.list) < page_size: break
            page += 1
```

| Case | Handling |
|---|---|
| Single-file share | Root listing returns the file directly; same code path handles it. |
| Relative paths | Strip the share-root prefix so output mirrors the share structure. |
| Folder with no media | Log "0 matching files" and continue. |
| Empty folder | Fine; nothing to do. |
| Listing fails halfway | Keep what was collected, mark that folder `LIST_FAILED`, retry only that folder next run. |
| Very large shares | Stream entries into the manifest as they arrive; do not hold the full list in memory. |
| Circular or duplicate paths | `seen` set plus `max_depth` guard. |

---

## 7. Classify videos vs photos

```text
VIDEO_EXT = mp4 mkv mov avi webm m4v ts flv wmv 3gp
PHOTO_EXT = jpg jpeg png webp heic gif bmp tiff

type(f):
    if ext in VIDEO_EXT: video
    elif ext in PHOTO_EXT: photo
    elif category == 1: video        # verify category numbers during discovery
    elif category == 3: photo
    else: other -> SKIPPED_TYPE (counted in report)
```

- Check the extension first; `category` is sometimes missing or wrong.
- Live-photo pairs (a `.jpg` plus a short `.mov`) stay as two separate files.
- Case-insensitive extension match.

---

## 8. Download one file

```text
download(entry):
    final = safe_path(out_dir, entry.relpath)
    if exists(final):
        if size(final) == entry.size: return SKIPPED_EXISTS
        final = unique_name(final)                   # name (1).mp4
    if entry.size == 0: return SKIPPED_EMPTY

    url = resolve_link(entry)        # fresh dlink + follow redirects
    if url is None: return diagnose_unavailable(entry)   # section 9

    probe = GET url, Range: bytes=0-0     # more reliable than HEAD here
    if probe.content_type is html/json:
        parse body as an error; re-resolve once; else classify failure
    total = size from Content-Range (or Content-Length)
    resumable = (probe.status == 206)

    part = final + ".part"
    offset = size(part) if resumable and exists(part) else 0
    if offset > total: delete(part); offset = 0
    if offset == total: goto verify

    refreshes = 0; tries = 0
    loop:
        r = GET url, Range: bytes=offset-
        switch r.status:
            206: stream-append to part
            200 and offset > 0: truncate part; offset = 0; stream from zero
            200 and offset == 0: stream
            403/410 or expired: if refreshes < max_link_refreshes:
                                    url = resolve_link(); refreshes++
                                else FAIL_LINK_EXPIRED
            404: return MISSING                      # no retry
            416: if offset == total: goto verify
                 else delete part; offset = 0
            401 / redirect to login: pause queue -> NEEDS_AUTH
            429 / 503: sleep(Retry-After or backoff)
            5xx / network error: backoff 1,2,4..60s; tries++ (max max_tries)
        while streaming:
            if first bytes look like html/json: abort, treat as error body
            every persist_every: flush, fsync, persist offset to manifest
            on disk-full: pause, keep part, status DISK_FULL

    verify:
        size(part) == total, else retry once from offset, else FAILED_SIZE
        magic bytes match ext (mp4 "ftyp" at byte 4, jpg FFD8, png 89504E47)
        md5 vs listing: WARN only (listing md5 is not always the true file hash)
        atomic rename part -> final
        return DONE
```

### Why each step exists

| Step | Reason |
|---|---|
| `Range: bytes=0-0` probe instead of `HEAD` | HEAD is often unsupported or returns different headers on these hosts. |
| Check `Content-Type` for html/json | Expired or rejected links often return a 200 with an error page that would otherwise be saved as the "file". |
| 200 on a ranged request | Server ignored the range; appending would corrupt the file, so restart from zero. |
| 416 handling | Either the file is already complete or the `.part` is invalid. |
| Magic-byte check | Catches error pages and truncated downloads that match in size by coincidence. |
| md5 is warn-only | The listing md5 can differ from the real file hash. |
| Atomic rename | A file at its final name is always complete. |

### Backoff policy
`delay = min(60, 2^n)` seconds with small random jitter, `n` starting at 0. Honor `Retry-After` when present. Reset the counter after any successful chunk.

---

## 9. When a file is not available

```text
diagnose_unavailable(entry):
    re-fetch share info once (sign/tokens may be stale), retry resolve once
    still no dlink -> classify per the table below
```

| Situation | Status | Action |
|---|---|---|
| No `dlink` returned (stream/preview only) | `NO_DIRECT_LINK` | Skip, log, retry on a later run. No stream-ripping fallback. |
| dlink request says file deleted, or 404 | `MISSING` | Permanent. No retry. |
| File flagged/blocked by Terabox | `BLOCKED` | Permanent. No retry. |
| Daily quota or rate limit hit | `QUOTA_PAUSED` | Pause the whole queue, save state, retry later. Do not rotate accounts. |
| Cookie rejected mid-queue | `NEEDS_AUTH` | Pause the queue, ask for a new cookie, resume where it stopped. |
| Share expired mid-run | `EXPIRED` | Stop. Keep all completed files. |

**Queue-wide pauses:** one bad file never stops the queue, except `NEEDS_AUTH`, `QUOTA_PAUSED`, `DISK_FULL`, and `EXPIRED`, which pause everything.

---

## 10. Other edge cases

| Case | Handling |
|---|---|
| Illegal characters, `..`, absolute paths in names | Sanitize each path segment; resolve the final path and assert it is inside `out_dir`. |
| Windows reserved names (`CON`, `NUL`, etc.) | Prefix the name; keep the extension. |
| Names over 255 bytes | Truncate the stem; keep the extension. |
| Trailing dots or spaces in names (Windows) | Strip them. |
| Same filename in different folders | `keep_tree` avoids collisions. `flatten` appends ` (n)`. |
| Duplicate content (same size + md5) in one run | Optional dedupe, off by default. |
| `.part` larger than real size | Delete and restart. |
| Ctrl-C | Flush, persist offsets, keep `.part` files. |
| Network down | Backoff, pause, auto-resume. |
| Restart later | Re-fetch share info (sign expires), match items by `fs_id` and path, skip `DONE`. |
| Share changed between runs (files added/removed) | Merge by `fs_id`; new files become `PENDING`; vanished files keep their old status and are noted in the report. |
| Thousands of files | Stream the listing into the manifest; do not hold it all in memory. |
| Link needs a different domain than the one given | Normalize to the domain the cookie works on. |
| Clock skew breaking `timestamp`/`sign` | Always re-fetch share info rather than reusing stale values. |

### Concurrency rules
- Videos: 2 at a time (max 4). Photos: up to 4.
- One connection per file.
- Do not split a single file into parallel ranges (speed-limit circumvention).

---

## 11. Persistence

### Directory layout

```text
out/<surl>/<original folder tree>/file.ext
out/<surl>/manifest.json
out/<surl>/report.json
```

### `manifest.json` (per file)

```json
{
  "fs_id": "123456789",
  "relpath": "Trip/Day1/clip01.mp4",
  "size": 734003200,
  "md5": "optional",
  "type": "video",
  "status": "DOWNLOADING",
  "offset": 209715200,
  "refreshes": 1,
  "tries": 0,
  "error": null,
  "updated_at": "ISO-8601"
}
```

Folder entries that failed to list are stored with `status: LIST_FAILED` and the folder path, so only they are retried.

### `report.json`
Counts and per-file reasons for: `DONE`, `SKIPPED_*`, `MISSING`, `BLOCKED`, `NO_DIRECT_LINK`, `FAILED_*`, plus totals (bytes downloaded, bytes remaining, elapsed time).

---

## 12. CLI

```text
cli download <link> [--out DIR] [--select all|1,3,5-7] [--types video,photo]
                    [--layout keep_tree|flatten] [--workers N]
cli list <link>                # walk and print indexed table, no download
cli doctor <link>              # run parse -> share info -> list -> resolve, report which step fails
cli retry-failed <out/surl>    # re-queue only retryable statuses
cli watch <link> [--select N|1,3,5-7] [--player mpv|vlc|browser]
                 [--buffer MB] [--sub PATH_OR_AUTO]
                               # stream through a local proxy, nothing saved (section 16)
```

`retry-failed` re-queues `NO_DIRECT_LINK`, `FAILED_*`, `QUOTA_PAUSED`, `NEEDS_AUTH`, `DISK_FULL`, `LIST_FAILED`. It leaves `MISSING` and `BLOCKED` alone.

---

## 13. Discovery step (do this first)

Because endpoints and params drift:

1. Open a share link in a browser with your logged-in session.
2. Watch the Network tab through: share page load, password verify (if any), folder listing, and one download.
3. Record, for each request: URL, method, params, required headers, and how `sign`, `timestamp`, and any page token are obtained.
4. Record observed `errno` values and what each meant (fill the table in section 4).
5. Record which `category` numbers correspond to video and photo.
6. Save the findings in `docs/endpoints.md`, then implement the resolver from it.
7. If stuck, export a HAR file of one working download and use it as input.

---

## 14. Test checklist

**Unit tests**
- `surl` parsing (with/without leading `1`, mirror domains, `pwd` extraction)
- Range/resume math (offset vs total, 200 vs 206, 416)
- Backoff sequence and cap
- Filename sanitizing (traversal, reserved names, length, unicode)
- Classifier (extension beats category)
- Magic-byte verification

**Resolver tests (recorded fixture responses)**
- OK, expired, cancelled, needs password, wrong password, not logged in, captcha/ratelimit
- Nested folders, pagination boundary (exactly `page_size` items), empty folder
- Missing `dlink`, deleted file, blocked file

**Behavior tests**
- Interrupt mid-download, rerun, confirm it resumes without restarting
- Expired link mid-download refreshes and continues from the same offset
- Disk-full pauses and resumes cleanly
- HTML error body saved as a file is detected and rejected
- Restart skips `DONE` files and retries only retryable ones

---

## 15. Acceptance criteria

- Resumes interrupted downloads without restarting from zero.
- Refreshes expired links automatically (up to the limit).
- Handles nested folders and paginated listings.
- Never writes outside `out_dir`.
- Never saves an error page as a media file.
- One failed file does not stop the queue, except the four queue-wide pause states.
- The final report accounts for every file in the share.

---

## 16. Watch without downloading (local streaming proxy)

### Approach
Run a small HTTP server on `127.0.0.1`. The player (mpv, VLC, or a browser `<video>` tag) is pointed at the local URL. Each time the player asks for a byte range, the proxy asks Terabox for the same range using the resolved direct link and pipes the bytes straight through. Nothing is written to disk.

### Why a proxy instead of giving the player the direct link
- The direct link needs your session cookie and specific headers. Players can't send them, and putting a cookie on a player's command line exposes it in the process list.
- Direct links expire. A player can't refresh one; the proxy can, mid-playback, without the player noticing.
- Browsers block cross-origin media requests that need cookies.

### Limits that still apply
- This uses the same direct link as `download`. It has the same account quotas and speed limits, and nothing here works around them.
- If the link's speed is below the video's bitrate, playback will buffer. The fix is `download` then play, not parallel ranges or extra accounts.
- No fallback to Terabox's preview/HLS stream endpoints. Consistent with section 9: no direct link means `NO_DIRECT_LINK`; open the share in Terabox's own player instead.

### Flow

```text
watch(link, select, player, buffer_mb):
    share, entries = same steps as main(): parse, share info, walk, classify
    entries = videos only (photos can use the same proxy with image MIME types)
    picks = select(entries)                     # interactive table or --select
    server = LocalServer(host=127.0.0.1, port=0, token=random_128bit)
    for each pick: sessions[token_i] = StreamSession(pick)   # NOT resolved yet
    if len(picks) > 1: serve /playlist.m3u listing local URLs for all picks
    launch player with server URL (or open /watch page for browser)
    serve until: player process exits | Ctrl-C | idle timeout (default 10 min,
                 counted only when no request is active)
    shutdown: close upstream connections, stop server, no files left behind
```

Resolve each file lazily on its first request, not when the playlist is built. Links expire, so an upfront resolve for item 12 would be stale by the time you reach it.

### Stream session (per file)

```text
StreamSession:
    entry, url, total_size, content_type, accepts_ranges, expires_hint
    lock                                  # single-flight link refresh

ensure_ready():
    if url is None: url = resolve_link(entry)     # NO_DIRECT_LINK -> fail
    probe = GET url, Range: bytes=0-0
    if probe.content_type is html/json: re-resolve once, else fail (error body)
    total_size = from Content-Range
    accepts_ranges = (probe.status == 206)
    content_type = MIME from file extension      # don't trust upstream
                                                 # mp4 video/mp4, mkv video/x-matroska,
                                                 # webm video/webm, mov video/quicktime

refresh_url():
    with lock:                            # a burst of 403s causes ONE refresh
        if url changed since caller read it: return
        url = resolve_link(entry); refreshes++
        if refreshes > max_link_refreshes: fail
    # also refresh proactively when expires_hint is within 60 s
```

### Request handler

```text
on GET|HEAD /s/<token>:
    reject if token unknown, or Host header != 127.0.0.1:<port>   # DNS-rebinding guard
    session.ensure_ready()

    range = parse Range header:
        none               -> start=0, end=total-1
        bytes=N-           -> start=N, end=total-1
        bytes=N-M          -> start=N, end=min(M, total-1)
        bytes=-N (suffix)  -> start=total-N, end=total-1    # MP4 players read the tail first
        start >= total     -> 416 with "Content-Range: bytes */<total>"

    send headers:
        206 if a Range was requested, else 200
        Content-Range: bytes start-end/total
        Content-Length: end-start+1
        Accept-Ranges: bytes
        Content-Type: session.content_type
    if HEAD: return                       # no upstream call

    pos = start
    while pos <= end:
        upstream = open_upstream(pos, end)         # GET url, Range: bytes=pos-end
        for chunk in upstream (64-256 KB):
            send chunk to player                   # via bounded read-ahead buffer
            pos += len(chunk)
            on client disconnect: close upstream immediately; return
        on 403/410/expired:    refresh_url(); continue     # resume at pos, invisible to player
        on 429/503:            sleep(Retry-After or short backoff); continue
        on 401/login redirect: abort; print NEEDS_AUTH
        on network error:      short backoff (1,2,4 s), max 3 tries, then close the
                               player connection so it re-requests from its own position
        on 404:                abort; print MISSING
```

### Read-ahead buffer
- Bounded in-memory buffer (default 16 MB, `--buffer`) between upstream and player, so brief network stalls don't stall playback.
- One upstream connection per request. Do not prefetch with parallel ranges.
- Optional temp-file cache is deliberately left out of the default, since the goal is to save nothing. If added later, bound it in size and delete it on exit.

### Seeking and multiple connections
- Seeking: the player drops its connection and sends a new Range request. The handler closes the old upstream right away and opens one at the new offset.
- Some players open a second connection for metadata or the index. Allow up to 2 concurrent upstream connections per session; when a third arrives, close the oldest.
- MP4 files with the index (`moov`) at the end start slowly, because the player must read the tail before playing. This is a property of the file, not a bug.

### Edge cases

| Case | Handling |
|---|---|
| Upstream ignores Range (returns 200) | If `start == 0`, stream from zero and send `Accept-Ranges: none` (seeking disabled). If `start > 0`, discard bytes up to `start` only when under a cap (default 256 MB); otherwise respond 502 and tell the user to `download` instead. |
| First bytes of the first response are html/json | Error page, never passed to the player. Re-resolve once, else fail with the parsed reason. |
| First bytes don't match the extension's magic bytes | Warn and stop; likely a bad link. |
| Container or codec the player can't decode | Browser `<video>` handles mp4 (H.264/AAC) and webm. mkv, avi, mov, hevc need mpv or VLC. Transcoding is out of scope; suggest `--player mpv`. |
| Link expires mid-playback | Single-flight `refresh_url()`, resume at the current byte. Player sees no gap beyond a brief buffer. |
| Refresh limit exceeded | Stop; report `FAIL_LINK_EXPIRED`; suggest re-running `watch`. |
| Cookie rejected mid-playback | Return 502 to the player, print `NEEDS_AUTH`, keep the proxy up so playback can resume after a new cookie. |
| Quota or rate limit hit | Stop streaming, report `QUOTA_PAUSED`. Do not rotate accounts. |
| File missing, blocked, or no direct link | Report `MISSING`, `BLOCKED`, or `NO_DIRECT_LINK`. In a playlist, skip to the next item rather than ending the session. |
| Password-protected or expired share | Same handling as `download` (section 5), before the server starts. |
| Subtitles (`.srt`, `.ass`, `.vtt` with the same stem in the same folder) | List them during the walk (they are tiny). Serve via `/sub/<n>`; mpv can load by URL. For VLC, write to a temp dir and pass `--sub-file`, deleting the temp files on exit. `--sub PATH` overrides with a local file. |
| Player not installed | Fall back to the next one (mpv, VLC, browser); if none, print the local URL for manual opening. |
| Paused browser playback (no requests for a while) | Idle timeout only counts when no request is active and no requests arrive for the configured period. |
| Very long Ctrl-C or crash | Server and upstream sockets close with the process; nothing to clean up on disk except optional subtitle temp files. |

### Security of the local server
- Bind to `127.0.0.1` only, never `0.0.0.0`.
- Random, unguessable token in the path; anything else returns 404.
- Check the `Host` header to block DNS-rebinding.
- No wildcard CORS headers.
- The cookie and the Terabox URL never reach the player, the browser, or logs. Redact tokens in log output.

### Tests
- Range parsing: none, `N-`, `N-M`, `-N`, out of range, reversed range.
- 206 vs 200 responses and correct `Content-Range` / `Content-Length`.
- Seek: a new Range request closes the previous upstream.
- A 403 mid-stream triggers exactly one refresh under a burst of concurrent requests.
- Resume at the correct byte after a refresh or network error (no duplicated or skipped bytes).
- HTML error body is never forwarded to the player.
- Upstream that ignores Range: both `start == 0` and `start > 0` paths.
- Wrong token and wrong `Host` header are rejected.
- Playlist: later items resolved lazily; a failed item is skipped.

### Acceptance criteria
- A video starts playing without writing any media file to disk.
- Seeking works in both directions.
- An expired link is refreshed mid-playback without restarting the player.
- The cookie is never visible in the player's command line, the browser, or the logs.
- The proxy is unreachable from other machines on the network.

---

## 17. Fixing "server returned a web page instead of metadata" (login / browser check)

### What the error means
The app expected JSON and got HTML. That HTML is one of:

1. **Login page.** The cookie is missing, expired, or was issued for a different domain.
2. **Human-verification / browser-check page.** The cookie may be fine, but the site wants a person to confirm, usually because the request came from a plain HTTP client, a new IP, or after many requests.
3. **Rate-limit or block page.** Same look as 2, but caused by request volume.
4. **A redirect chain that ended somewhere unexpected** (login host, verify host, mirror domain).

A pasted cookie often fails for a quieter reason: it is tied to the browser, User-Agent, or IP that created it. Using it from a different client can trigger 1 or 2 even when the cookie is "valid".

### Quick diagnosis (before changing code)
1. Open the same share link in a normal browser where you are logged in.
2. Loads fine: the problem is your cookie or your client's headers (User-Agent, Referer). Section 17.4 fixes both.
3. The browser also shows a check: complete it once there, then use 17.4 so the app reuses that session.
4. The check comes back every few requests: it's a rate/behavior limit. See 17.7.

### 17.1 Response classifier (in `resolver`'s HTTP wrapper, applied to every response)

```text
classify_response(resp):
    html_like = content_type contains "html"
                or first non-space byte of body is "<"
                or JSON parse fails
    if not html_like:
        data = parse JSON
        return map_errno(data)              # section 4 table -> OK or a named state

    final = resp.final_url                  # after redirects
    body  = lowercase(resp.body[:20000])

    if final host/path looks like login (login, signin, passport)
       or body has a password input inside a login form:
        return NOT_LOGGED_IN
    if body contains human-check markers
       (captcha, verify you are human, security check, checking your browser,
        challenge, robot):
        return CAPTCHA_OR_RATELIMIT
    save redacted first 2 KB to debug/last_html.txt    # cookies/tokens stripped
    return UNKNOWN_HTML                      # treated as CAPTCHA_OR_RATELIMIT, plus the debug dump
```

The marker lists are starting points. During discovery (section 13), record the exact markers and redirect URLs you see for each case and update the lists. Never rely on `Content-Type` alone: some endpoints return HTML with a 200, or JSON labeled as HTML.

### 17.2 Interactive auth ("mini browser")
A real browser window where **you** log in and complete any check yourself. The app then reuses that session.

```text
interactive_auth(share_url):
    if no display available: return manual_cookie_paste()        # 17.3
    open a VISIBLE browser window
        with a persistent profile dir (~/.terabox-dl/browser-profile)
    go to share_url
    show: "Log in / complete the check in this window. The app continues automatically."
    on every navigation, and when the user clicks "Done":
        if page is a share page (not login/verify) and session_check() passes:
            break
    on window closed early: raise NEEDS_AUTH (cancelled)
    capture:
        all cookies for the Terabox domains you used (including httpOnly ones,
        and any cookie set by the password verify step)
        the browser's real User-Agent string
        Accept-Language
        the final domain
    save_session(cookies, user_agent, domain)                    # 17.5
    close window (keep the profile so the next run usually passes without asking)
    return OK
```

Why a persistent profile: the next run often needs no interaction, because the browser already holds a trusted session.

**Mini-browser options by platform**

| Platform | Component | Reading cookies |
|---|---|---|
| Python desktop (assumed) | Playwright, headed Chromium, `launch_persistent_context(profile_dir, headless=False)` | `context.cookies()` |
| Python desktop (alternative) | PyQt6-WebEngine `QWebEngineView` | `QWebEngineProfile.cookieStore()` signals |
| Electron / Node | `BrowserWindow` with `session.fromPartition("persist:terabox")` | `session.cookies.get({})` |
| Android | `WebView` | `CookieManager.getInstance().getCookie(url)`; UA from `WebSettings.getUserAgentString()` |
| iOS | `WKWebView` | `WKHTTPCookieStore` |
| Flutter | `flutter_inappwebview` | its `CookieManager` |
| Any, no embedded browser | System browser plus manual cookie paste | 17.3 |

### 17.3 Manual cookie paste (no display, or embedded browser unavailable)
1. Open the share in a normal browser and log in (complete any check).
2. Copy the cookie header for the Terabox domain (DevTools > Application > Cookies, or "Copy as cURL").
3. The app prompts for **both the cookie string and the browser's User-Agent** (the UA must match).
4. Run `session_check()`; save only if it passes.

Treat the cookie like a password. Never log it or commit it.

### 17.4 Make the plain HTTP client look like the browser that passed
- Send the **same User-Agent** and `Accept-Language` captured in 17.2.
- Send `Referer` = the share URL; add `Origin` on POSTs.
- Use the same domain the cookies were issued for.
- Keep the same network (don't switch VPN/IP between logging in and downloading).

This is consistency, not disguise: the app is reusing the session you created.

### 17.5 Session store

```text
~/.terabox-dl/session.json        # permissions 600, gitignored, never synced
{
  "cookies": [{ name, value, domain, path, expires, httpOnly, secure }],
  "user_agent": "...",
  "accept_language": "...",
  "domain": "...",
  "saved_at": "ISO-8601",
  "last_ok_at": "ISO-8601"
}
~/.terabox-dl/browser-profile/    # persistent browser profile (17.2)
```

- At startup run `session_check()`: one cheap authenticated call. If it returns HTML, run `interactive_auth()` **before** walking the share.
- Warn at startup if cookies expire within 24 hours.
- Lock file on the profile dir so two app instances don't corrupt it.

### 17.6 Request wrapper (the actual fix)

```text
request(step, build_request):
    auths = 0
    loop:
        resp = http.send(build_request(), cookies, user_agent, referer)
        verdict = classify_response(resp)

        if verdict is OK: return parsed data

        if verdict in (NOT_LOGGED_IN, CAPTCHA_OR_RATELIMIT, UNKNOWN_HTML):
            if auths >= 2:                         # no loops
                raise CAPTCHA_OR_RATELIMIT or NOT_LOGGED_IN, with advice (17.7)
            pause_queue()                          # other workers wait, none retry
            interactive_auth(); auths += 1         # once per pause, even if 4 workers hit it
            refetch_share_info()                   # sign/tokens are tied to the session
            resume_queue()
            continue

        handle other states per section 4
```

- **Single-flight:** if several workers get the HTML at the same moment, only one opens the window; the rest wait for it.
- After re-auth, re-fetch share info (`sign`, `timestamp`, tokens), then retry the failed step. Mid-download, resume from the saved offset as usual.
- `NEEDS_AUTH` in section 9 now means "interactive auth failed or was cancelled", not "ask for a cookie and stop".

### 17.7 If the check still appears after you logged in

| Symptom | Likely cause | Action |
|---|---|---|
| Browser works, plain client gets HTML even with the right cookies, UA, and Referer | The site distinguishes real browsers from plain HTTP clients | **Browser-as-client mode** (below) |
| Check returns after a handful of requests | Request volume or behavior limit | Slow down: workers = 1, 1 to 3 s between listing/resolve calls. Stop after 2 re-auths and wait before retrying. |
| Check shows in the window but can't be completed (blank, loops) | Wrong mirror domain, VPN/proxy interference, outdated browser engine | Try another mirror domain, turn the VPN off, update the browser component, retry later |

**Browser-as-client mode:** the logged-in browser window is kept open and used as the HTTP client for the Terabox API calls.

```text
BrowserClient.fetch_json(url, params):
    run inside the logged-in page, on the Terabox origin:
        fetch(url + params, { credentials: "include" }) -> JSON
    return JSON to the app

used for: share info, folder listing, download-link resolution
media bytes: try the plain client with cookies + UA first (the media host
             is often a different host and may not apply the same check)
             if the media host also returns HTML: use the browser's own
             download mechanism (Playwright expect_download, Android
             DownloadListener) instead
```

Watch mode (section 16) needs the plain client to work against the media host. If only the browser can fetch the bytes, download first, then play.

### 17.8 What is deliberately not included
- Automatic captcha solving or paid solver services
- Stealth plugins, fingerprint spoofing, or anything that hides automation
- Proxy or IP rotation, multiple accounts, shared or borrowed cookies
- Retrying in a tight loop against a check page

A person completes every check in the visible window. If the site keeps blocking, the supported outcome is "stop and try later".

### 17.9 Edge cases

| Case | Handling |
|---|---|
| Cookie expires mid-run | Classifier returns `NOT_LOGGED_IN`, queue pauses, `interactive_auth()`, queue resumes from saved offsets. |
| User closes the window before finishing | Raise `NEEDS_AUTH` (cancelled); keep all state; rerun to continue. |
| Password-protected share | The password form can appear in the window; the verify step sets its own cookie, so capture **all** cookies after it is accepted. The in-app password prompt (section 5) still works when the window isn't used. |
| Mirror domain mismatch | Cookies are domain-scoped. Save the domain used in the window and normalize every request to it. |
| Cookies valid in browser, rejected by the client | UA/header mismatch or client fingerprinting: check 17.4 first, then browser-as-client mode. |
| IP changed between login and requests | Cookies may be IP-bound; re-auth. |
| Many shares queued | One session, authenticate once. |
| App crashes during auth | Profile persists; the next run often passes without interaction. |
| Check reappears within a minute of solving it | Counts as a rate/behavior limit: stop after 2 re-auths, then see 17.7. |
| `UNKNOWN_HTML` | Treated like a check page and written (redacted) to `debug/last_html.txt` so you can add new markers. |
| Headless server / no display | Manual cookie paste (17.3). |

### 17.10 Tests
- Classifier on recorded fixtures: valid JSON, login HTML, check HTML, redirect to login, empty body, JSON served as `text/html`, unknown HTML.
- Wrapper: first call returns HTML, `interactive_auth()` runs exactly once, retry returns JSON.
- Wrapper: four concurrent workers get HTML at once, the window opens once.
- Wrapper: after 2 failed re-auths it stops with advice and does not loop.
- Session store: round trip, file permissions, cookie expiry, per-domain scoping.
- Cookies, tokens, and User-Agent never appear in logs or `debug/last_html.txt`.
- Cancelling the window stops cleanly and keeps the manifest intact.

### 17.11 Acceptance criteria
- `cli doctor <link>` on a link that needs login or a check opens the window; after you finish, it continues without restarting.
- A second run uses the saved session and shows no window.
- A mid-run cookie expiry pauses all workers, re-authenticates once, and resumes from the saved offsets.
- If the site keeps blocking, the app stops with a clear message instead of looping.

---

## 18. Android: "download the app" screen instead of the share page

Assumed setup: the mini browser from section 17 is an Android `WebView` inside your app, and the Terabox page shows an app-download / "open in app" screen instead of the file list.

### 18.1 Why it happens
1. **Mobile User-Agent.** A WebView sends a mobile UA (it contains `; wv)` and `Mobile`). Terabox answers mobile clients with its mobile/wap experience, whose main goal is to push the app: a full-screen "Download app" overlay, or a "download only in the app" message.
2. **App redirects.** The page tries to leave the browser: `intent://`, `market://`, a `terabox://` deep link, or a Play Store URL. A WebView shows an error page or navigates away from the share.
3. **Your HTTP client gets the same page.** If the client sends a mobile UA or a library default (for example `okhttp/x.y`), the API calls can return the promo page instead of JSON. That produces the original "got a web page instead of metadata" error.
4. **Chrome Custom Tabs are not an alternative.** They are the real browser, but the app cannot read their cookies, so the WebView is the right component.

### 18.2 Fix layers (try in this order)

| # | Layer | What it does |
|---|---|---|
| A | **Desktop-mode WebView** | Same as Chrome's "Desktop site": send a desktop UA so Terabox serves the normal web page. |
| B | **Keep navigation inside the WebView** | Block `intent://`, `market://`, `terabox://`, and app-store URLs so the page cannot leave. |
| C | **Alternate URL forms** | Try other forms of the same share link (desktop `/s/` path, wap path, other mirror domain). |
| D | **User dismisses the screen** | Show the page; the user taps the close / "continue in browser" control. Optional auto-dismiss by matching its text (fragile; last resort). |
| E | **Import a session** | Capture the session on a desktop browser and import it (section 17.3). |

### 18.3 WebView configuration

```text
configure_webview(wv):
    s = wv.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.userAgentString = desktop_ua(from = s.userAgentString)      # layer A
    s.useWideViewPort = true                                      # desktop layout
    s.loadWithOverviewMode = true
    s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
    CookieManager.setAcceptCookie(true)
    CookieManager.setAcceptThirdPartyCookies(wv, true)
    wv.webViewClient = guard_client                               # layer B

desktop_ua(from):
    ver = regex "Chrome/([0-9.]+)" on `from`  (fallback: your app's own recent version)
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
           + "Chrome/" + ver + " Safari/537.36"
```

- Build the UA from the WebView's own Chrome version rather than a hardcoded one, so the string matches the engine actually running.
- **Store this exact UA** in `session.json` (section 17.5) and send it from your HTTP client too (an OkHttp interceptor that sets `User-Agent`). A cookie created under one UA and used under another is the mismatch described in section 17.4.

### 18.4 Navigation guard (layer B)

```text
guard_client.shouldOverrideUrlLoading(url):
    scheme = url.scheme
    if scheme not in (http, https):
        if scheme == "intent":
            fb = value of S.browser_fallback_url in the intent string
            if fb is http(s) and its host is a Terabox host you recognise:
                wv.loadUrl(fb)
        promo_hits += 1
        return true                      # consume it; stay on the page
    if url.host is an app-store host, or an app-install redirect host you
       observed during discovery:
        promo_hits += 1
        return true
    return false                         # normal navigation

guard_client.onReceivedError / onPageFinished:
    verdict = inspect_page(wv)           # 18.5
```

- The login-complete check must **not** depend on a redirect (a `terabox://` hand-off after login is blocked). Use `session_check()` (section 17.5) instead.
- Never launch an `Intent` to the Play Store or to an installed Terabox app from this WebView.

### 18.5 Detecting the promo screen

Add a verdict `APP_PROMO` alongside the verdicts in section 17.1.

```text
is_app_promo(final_url, body_or_visible_text):
    final host is an app store                                    -> true
    url scheme is intent/market/terabox                           -> true
    text/markup contains any of:
        "download app", "open in app", "get the app", "install the app",
        "scan to download", "download in the app", a smart-app-banner meta tag
        AND no file-list markers on the page                      -> true
    else                                                          -> false
```

- In the WebView, run this on `onPageFinished` using a small JS read of the visible text.
- In the HTTP client, run it on any HTML response.
- As in section 17.1, the marker lists are starting points. Record the exact wording and URLs you see during discovery (section 13) and update them.
- Check `APP_PROMO` **before** the login check, because promo overlays often contain the word "login".

### 18.6 Where it plugs into section 17

```text
classify_response (17.1): add before the login check:
    if is_app_promo(final, body): return APP_PROMO

request wrapper (17.6): handle APP_PROMO before opening any auth window:
    1. confirm the client's UA equals session.user_agent and is the desktop UA;
       if not, fix it and retry once
    2. retry once using the next alternate URL form (18.7)
    3. else interactive_auth(), which counts toward the 2-attempt cap, with the
       window showing the page so the user can dismiss it (layer D)
    4. still APP_PROMO or "download only in the app" -> raise APP_ONLY (below)

interactive_auth (17.2): on Android, use configure_webview() (18.3) and
    guard_client (18.4) instead of a plain WebView
```

### 18.7 Alternate URL forms (layer C)

```text
forms(surl, mirrors):
    for domain in [the domain given, then other mirrors]:
        https://<domain>/s/<surl>
        https://<domain>/s/1<surl>             # only if surl has no leading "1"
        https://<domain>/wap/share/filelist?surl=<surl>
```

Order them by what discovery shows loads without the promo screen, and remember the winner per domain in the session store.

### 18.8 When the site only offers it in the app

If, after layers A to D, the page or the download-link call still says the file is available only in the app, the verdict is **`APP_ONLY`**.

- Report it as `NO_DIRECT_LINK` (section 8/9 statuses) with the reason "offered in the app only".
- Stop retrying. Don't loop.
- The supported alternatives are the official app, or a desktop browser session imported through section 17.3.

### 18.9 What is deliberately not included
- Impersonating the Terabox app: its UA, device identifiers, or request signing
- Calling the app's private API, or reverse engineering it
- Tapping "install", or launching the Play Store or the app
- Working around "download only in the app" restrictions

Desktop mode is the standard "Request desktop site" behavior. It asks for the ordinary web page and does not hide anything.

### 18.10 Edge cases

| Case | Handling |
|---|---|
| Desktop UA still shows the promo screen | Try alternate URL forms (18.7), then let the user dismiss it, then import a session (17.3). |
| Desktop UA triggers a human check that the mobile page didn't | Handle it as in section 17.2. If it loops, switch UA mode (below) and use the mobile share path plus dismissal. |
| Switching UA mode (desktop to mobile or back) | Clear cookies and the stored UA first; a session is tied to the UA that created it. Never mix. |
| Page redirects to the Play Store on load | Intercepted by the guard; stay on the page; count `promo_hits`. |
| `intent://` with a `browser_fallback_url` | Load the fallback only if it is http(s) on a Terabox host; otherwise ignore. |
| `terabox://` hand-off after login | Blocked; completion is detected by `session_check()`. |
| Desktop layout is tiny on a phone | Wide viewport and zoom controls (18.3). |
| Terabox is embedded in an `<iframe>` or a web-app container | Terabox is likely to refuse framing. Use a top-level WebView instead. |
| `promo_hits` keeps rising with no progress | Stop after 3, go to the manual import path (17.3). |
| HTTP client UA differs from the WebView UA | Treat as a bug: both must come from `session.user_agent`. |

### 18.11 Tests
- `shouldOverrideUrlLoading`: `intent://` (with and without fallback), `market://`, `terabox://`, a Play Store URL, and a normal `https` Terabox URL.
- `desktop_ua()` on sample WebView UAs: output has no `wv`, `Mobile`, or `Android`, and keeps the Chrome version.
- Classifier fixtures: promo HTML, promo HTML containing the word "login", intent redirect, real share page.
- Wrapper sequence: promo, then UA fix, then alternate URL, then window, then success; the 2-attempt cap holds.
- The UA stored in `session.json` equals the UA the HTTP client sends.
- Changing UA mode clears cookies.
- `APP_ONLY` stops cleanly and writes `NO_DIRECT_LINK` with a reason.

### 18.12 Acceptance criteria
- On an Android phone, opening a share link in the app shows the share page or a login/check page, not a blocking app-download screen.
- If the promo screen does appear, the user can dismiss it and the app continues.
- The app never leaves for the Play Store or the Terabox app.
- API calls from the HTTP client return JSON, not the promo page.
- If the site only offers the file in its app, the app stops with a clear message.

### 18.13 Implementation Status (Phase 2 Completed)
- **Desktop WebView Configurator (`DesktopWebViewConfigurator`)**: Implemented per Section 18.3. Extracts the WebView engine's exact Chromium version, strips `; wv`, `Mobile`, and `Android` tokens, and applies desktop viewport and zoom controls.
- **Navigation Guard Policy (`NavigationGuardPolicy`)**: Pure, testable guard implementing Section 18.4. Intercepts non-HTTP schemes (`intent://`, `market://`, `terabox://`), parses safe `browser_fallback_url` pointing to recognized Terabox hosts, blocks app stores (`play.google.com`, `apps.apple.com`) and trackers (`app.adjust.com`, etc.), and enforces a 3-hit promo ceiling (`MaxPromoHitsExceeded`).
- **Detection & Classification (`TeraboxResponseClassifier`)**: Detects `APP_PROMO` and `APP_ONLY` verdicts evaluated strictly before login checks per Section 18.5, avoiding misclassification of promo overlays containing "login". Bounded read-only JS inspection wired to `InteractiveAuthScreen`'s `onPageFinished`.
- **Alternate URL Form Generator (`AlternateUrlFormGenerator`)**: Generates ordered, de-duplicated alternate forms per Section 18.7 (`originalUrl`, `/s/<surl>`, `/s/1<surl>`, `/wap/share/filelist?surl=...`, and verified mirrors).
- **Resolver Promo Recovery Sequence (`TeraboxShareLinkResolver`)**: Implemented per Section 18.6 with UA synchronization, alternate endpoint form attempts, 2-attempt capped interactive overlay dismissal, and terminal fallback to `ShareResolveResult.AppOnly`.
- **App-Only Content Handling (`BrowseShareViewModel`, `AppError`)**: Mapped to non-retryable user-facing error state preventing infinite loops.
- **Verification**: 304 unit tests pass (`BUILD SUCCESSFUL in 11s`), and `./gradlew assembleDebug` builds cleanly.

---

## 19. Phase 3 — End-to-End Integration, Recovery Testing, and Full Flow Validation

### 19.1 Android-Specific Implementation Choices
1. **Shared Response Classifier Pipeline (`TeraboxResponseClassifier`)**:
   - Strictly enforces the 7-stage evaluation pipeline:
     1. Validate response parseability and status code barriers.
     2. Detect `APP_PROMO` / app-store redirects *before* login classification.
     3. Detect `NOT_LOGGED_IN` (login forms, password inputs, passport endpoints).
     4. Detect `CAPTCHA_OR_RATELIMIT` (Cloudflare, Geetest, HTTP 429, WAF).
     5. Classify unknown HTML as `UNKNOWN_HTML` with bounded, redacted diagnostics.
     6. Parse valid JSON and map legitimate business errors (`errno`).
     7. Return typed failure results without misclassifying HTML as metadata.
2. **Single-Flight Authentication Coordinator (`AuthCoordinatorImpl`)**:
   - Concurrency barrier using coroutine `Mutex` and shared `CompletableDeferred`.
   - Prevents multiple concurrent workers from opening duplicate WebView instances.
   - Enforces strict 2-attempt cap per episode.
   - Preserves queue state upon cancellation.
3. **Session Store (`DefaultSessionRepository`)**:
   - Persists authenticated sessions in private-mode Android storage (`Context.MODE_PRIVATE` SharedPreferences).
   - Sensitive cookie payloads are Base64 encoded and never logged or serialized into manifests.
   - `switchUserAgent` guarantees that switching between desktop and mobile modes clears all cookies to avoid UA/cookie mismatch.
4. **Binary Stream Protection in Download Engine (`OkHttpDownloadEngine`)**:
   - Inspects Content-Type and initial payload chunks during download execution.
   - Explicitly rejects HTTP 200 HTML responses (e.g., expired dlink redirecting to HTML login page) when downloading media files, preventing file corruption.

### 19.2 Supported URL Formats & Mirrors
- Standard desktop: `https://<domain>/s/<surl>`
- 1-prefixed desktop: `https://<domain>/s/1<surl>`
- Wap filelist: `https://<domain>/wap/share/filelist?surl=<surlWithout1>`
- Recognized mirrors: `terabox.com`, `teraboxapp.com`, `1024tera.com`, `1024terabox.com`, `teraboxshare.com`, `teraboxlink.com`, `dubox.com`, `mirrobox.com`, `nephobox.com`, `4funbox.com`, `momerybox.com`.

### 19.3 Retry and Rate-Limit Policy
- Interactive recovery capped at **2 attempts** per episode.
- Background download retry capped at **3 attempts** with exponential backoff (2s up to 30s; 15s for HTTP 429).
- Navigation guard promo hits capped at **3 hits** (`MaxPromoHitsExceeded`).
- Permanent errors (`401`, `403`, `404`, `APP_ONLY`, `INVALID_URL`) are strictly non-retryable.

### 19.4 Test Coverage Matrix
- **Flow A (Normal Share Link)**: `flowA_normalShareLink_resolvesDirectlyWithoutAuthWindow`
- **Flow B (Login Required)**: `flowB_loginRequired_pausesSafely_userAuthenticates_retriesAndPreservesQueue`
- **Flow C (Human Verification)**: `flowC_humanVerification_promptsUser_stopsAfterLimitWithClearMessage`
- **Flow D (Promo Screen)**: `flowD_promoScreen_verifiesUa_retriesAlternateUrl_andDismissesOverlay`
- **Flow E (App-Only Content)**: `flowE_appOnlyContent_mapsToNoDirectLink_stopsRetrying_leavesQueueConsistent`
- **Flow F (Auth Cancelled)**: `flowF_authenticationCancelled_returnsNeedsAuth_manifestIntact_userCanResumeLater`
- **Flow G (Concurrent Failures)**: `flowG_concurrentResolverFailures_singleFlightCoordinator_onlyOneWindowOpens`
- **Flow H (HTML Guard)**: `flowH_downloadEngine_rejectsHtmlWebpageResponseForMediaFile`
- **Security & UA Switching**: `switchUserAgent_switchingBetweenDesktopAndMobile_clearsCookies`, `classify_diagnosticsManager_redactsSecrets`

### 19.5 Verification Status
- **Automated Tests**: 313 unit tests pass (`BUILD SUCCESSFUL in 12s`).
- **APK Packaging**: `./gradlew assembleDebug` succeeds cleanly (`app-debug.apk`).
- **Device Verification**: Installed and launched on Android device `10BF7Q150G000LE`.
  - Process PID 994 active with 0 crashes.
  - Interactive WebView rendered in desktop mode with zoom controls, in-app top bar, and intact navigation guard.
- **Unverified Behaviors**: Live bypass of novel, undocumented Cloudflare / Turnstile challenges without manual user solving remains unverified and is intentionally not bypassed automatically per Section 18.9.