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
| `NOT_LOGGED_IN` | Cookie missing/expired | Stop; ask for a fresh cookie |
| `EXPIRED` | Share expired | Stop with message |
| `CANCELLED` | Owner cancelled the share | Stop with message |
| `BLOCKED` | Share blocked by Terabox | Stop with message |
| `CAPTCHA_OR_RATELIMIT` | Site is demanding a human check or throttling | Stop; tell the user to open the link in a browser, then retry later |

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
    session = load_cookie()              # missing -> NEEDS_AUTH, stop
    share = fetch_share_info(session, surl)
        # returns state (see share-level states)
        # plus shareid, uk, sign, timestamp, tokens, title

    switch share.state:
        NEEDS_PASSWORD:        use pwd from URL, else prompt (max 3), then verify
        CAPTCHA_OR_RATELIMIT:  stop; user opens link in browser, retries later
        NOT_LOGGED_IN:         stop; ask for fresh cookie
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