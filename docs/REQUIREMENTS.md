# Grok Voice Input — product requirements

Last updated: 2026-08-03  
Sibling reference: [cosmic-scribe](https://github.com/erik-balfe/voice-input) (desktop History, tray states, never lose a take).

## Principle

**Dictate fast. Never lose a take. Recovery always available in the app.**

Performance of STT (progressive AAC, OAuth, upload) is largely solved. The product work is **UI/UX, session lifecycle, History, and honest status**.

---

## Glossary

| Term | Meaning |
|------|---------|
| **IME** | The voice keyboard panel (hot path: speak → text in the focused field). |
| **App / History** | Full activity: past takes, copy, retranscribe, settings. |
| **PCM** | Raw uncompressed microphone samples (16-bit mono). Used **only in memory during capture** to feed the encoder and for rare encode fallbacks. **Not** the product storage format. |
| **M4A / AAC** | Compressed voice (~48 kbps). **What we keep on disk** for History and retranscribe. |
| **Session** | One listen attempt: from mic start until cancel, process complete, or process fail. |
| **Processing** | Everything after stop: auth (if needed), upload, STT, insert text. User-facing label is **Processing**, not “Sending”. |

---

## Goals

1. Feels modern on the keyboard (meter, states, duration, pause).
2. User can pause, think, resume the **same** take.
3. Cancel, back gesture, lock, process death → **audio (and later text) still recoverable** in History when we had anything worth keeping.
4. Clear states: Listening / Paused / Processing / Failed / Offline.
5. Estimated progress during processing (roughly real, not fake indeterminate forever).
6. History with caps (count + MB), advanced settings for limits.
7. No junk: no full-quality WAV in the normal path; diagnostics opt-in.

---

## Non-goals (for now)

- Streaming / partial STT while speaking (batch STT only).
- On-device STT offline.
- Multi-user accounts or cloud sync of History.
- Editing transcripts with LLM “AI fix” (Cosmic Scribe has this; optional later).

---

## Primary user stories

| ID | Story | Success |
|----|--------|---------|
| S1 | I open the voice keyboard and it immediately shows **Listening** and records | Clear state + meter within ~300 ms of panel show |
| S2 | I speak; the meter reacts smoothly to my voice | Continuous level, not binary blink; modern motion |
| S3 | I tap **pause**, think, then resume | Same session continues; duration continues; progressive encode resumes cleanly |
| S4 | I tap **stop** (center) | Mic stops; UI shows **Processing** with progress; text inserts into field; panel closes |
| S5 | I tap **cancel** or **system back** / edge swipe | Panel closes; if duration ≥ min keep threshold, session is **saved** (audio pending or completed); toast or short confirmation that it’s in History |
| S6 | Processing fails (network, 5xx, auth) | Audio kept; Failed state with Retry; entry in History “Pending / Failed” |
| S7 | No network | Cannot complete STT; message that network is required; audio saved for later; open App to retranscribe when online |
| S8 | I open the App | History is the home screen: newest first, preview, duration, status |
| S9 | I copy / retranscribe / delete from History | Actions work without reopening the IME |
| S10 | Text landed in the wrong field or was cleared | I recover from History (copy or re-insert if possible) |
| S11 | Long or forgotten recording | Soft warning at N minutes; auto-pause or auto-save at hard limit with notification |
| S12 | Screen locks or IME is hidden mid-listen | Explicit policy (see Lifecycle); user is not silently wiped |
| S13 | Storage grows | Automatic prune by max items + max MB; user can change in Advanced settings |
| S14 | I want diagnostics for a bug | Opt-in debug overlay + share log; not the default UX |

---

## IME UI requirements

### Layout (conceptual)

```
  [Listening · 1:42]                    [ ↗ App ]
  ────────────────────────────────────────────────
   [ ✕ ]           ( center control )        [ ⏸ ]
  ────────────────────────────────────────────────
  Optional one-line hint (first run or always short)
```

| Control | States | Behavior |
|---------|--------|----------|
| **Status** | Listening / Paused / Processing… / Failed | Large, readable; duration for listen/pause |
| **Center** | Listening → stop & process; Paused → resume; Processing → spinner/disabled; Failed → retry | Primary affordance |
| **Left ✕** | Always when not processing (or “close” during processing if we allow background) | Ends IME; **keeps** session if keep-threshold met (see Discard rules) |
| **Right ⏸/▶** | Pause when listening; Resume when paused | Same session |
| **↗ App** | Always | Pause or finalize capture → open History; never discard silently |

### Visual states

| State | Look |
|-------|------|
| **Listening** | Live meter / waveform; accent color; duration ticking |
| **Paused** | Frozen meter; distinct color (e.g. amber); “Paused · m:ss” |
| **Processing** | Separate indicator (not the mic meter): determinate-ish progress bar + “Processing…” |
| **Failed** | Error text + Retry visible; no fake progress |

### Processing progress (estimated)

- Label: **Processing** (not “Sending”).
- Subphases for accessibility / debug only if useful: Auth → Upload → Waiting for server → Done.
- **Progress bar**: estimate based on:
  - upload bytes known,
  - recent measured upload throughput (EMA of past runs + current bytes/time),
  - heuristic STT server time ∝ duration or upload size (calibrate from our own logs: ~1–3 s for multi-minute M4A is typical; long tails possible).
- Not 100% accurate; goal ~**reasonable** completion feel (~±20–30% typical), never stuck at 99% forever — if estimate exceeded, switch to indeterminate tail or “Still working…”.
- On offline / fail: bar stops; error UI.

### Hints

- Prefer short fixed hints: “✕ cancel · center stop · ⏸ pause”.
- First-run or Settings toggle for longer instructions.
- Never bury critical “saved to History” info only in debug monospace.

### Meter quality

- Modern: waveform bars or continuous level strip preferred over crude concentric rings alone.
- Fast attack, slower release; no hard blue/gray blink on voice flag.
- Distinct paused vs listening visuals.

---

## Discard / keep rules

**Minimum keep duration** (e.g. same as STT min ~400–500 ms, or slightly higher e.g. 1 s to avoid junk taps):

| Event | Duration &lt; min | Duration ≥ min |
|-------|-------------------|----------------|
| Center **stop** | Too short error; optional no History row | Process STT; always create History row |
| **Cancel / ✕** | Discard; no row | **Save audio** to History as `pending` or `cancelled_saved`; do **not** auto-insert text; optional toast “Saved to History” |
| **Back / edge swipe** / hide IME (user navigated away) | Discard if trivial | **Save audio** same as cancel; do **not** continue recording in secret unless we chose FG-service policy |
| Process success | — | History `ok` + text |
| Process fail | — | History `failed` + audio; Retry available |

**Explicit product choice (default):**  
On cancel/back/hide, we **do not keep listening** after the panel is gone. We **stop capture, finalize M4A, save to History**, and show that the take is safe. Continuing under lock without UI is easy to misunderstand; recovery beats silent background mic.

Optional later: “Continue recording under lock” with notification — only if user opt-in.

---

## Lifecycle & edge cases

| Event | Required behavior |
|-------|-------------------|
| **Back gesture / cancel** | Stop + save if keep-worthy; leave IME; History has entry |
| **Screen off / lock** | Stop + save if keep-worthy (same as hide), **or** (v2) FG service with notification “Recording… tap to return”. Default v1: **save and stop** so behavior is predictable |
| **Process killed mid-record** | Progressive M4A on disk → orphan recovery on next App/IME open: “Interrupted recording” → Retry STT |
| **Process killed mid-STT** | Audio already on disk; History `pending`/`failed`; retranscribe from App |
| **No network at stop** | Save audio; Failed/Offline UI; “Saved — open History to transcribe when online” |
| **No network at IME open** | Still allow record (offline capture); warn once if offline |
| **Auth missing** | Don’t start mic (or start then fail immediately with Settings link) |
| **Auth expired mid-process** | One refresh retry (existing); then Failed + saved |
| **Wrong field / field cleared** | History holds text; Copy from App |
| **Very long recording** | Soft warn @ 5 min; hard auto-stop+save @ configurable cap (e.g. 15–20 min) |
| **Storage full / over quota** | Prune oldest completed first; never prune the session just created without trying; surface error if cannot write |

---

## History (App)

Aligned with Cosmic Scribe ideas, mobile-sized.

### List row

- Relative or absolute time, duration, status badge (`ok` / `pending` / `failed` / `interrupted`).
- Transcript preview (or “No transcript yet”).
- Actions: **Copy** (if text), **Retry STT** (if audio), **Delete** (confirm).

### Detail

- Full text, copy, retranscribe, delete.
- Optional: play M4A (later).

### Storage format

Per session id (timestamp-based stem):

| File | Required |
|------|----------|
| `{id}.m4a` | Yes (compressed voice) |
| `{id}.json` meta | Yes: createdAt, durationMs, status, language, source package, error?, text? |
| `{id}.txt` | Optional convenience for text |
| PCM / WAV | **No** in product path |

### Limits (defaults; Advanced settings)

| Setting | Default | Notes |
|---------|---------|--------|
| Max History items | **50** | Oldest pruned first (prefer completed over pending if policy needs care — prefer prune oldest `ok` first, keep recent `failed`/`pending` longer) |
| Max audio storage | **500 MB** | Sum of M4A sizes |
| Soft duration warn | 5 min | Toast / status |
| Hard duration cap | 15–20 min | Auto stop + save |

User must open an **Advanced** section to change limits (not clutter main Settings).

---

## Network & failure UX

- App and IME must make clear: **transcription requires network**.
- Offline capture is allowed; STT is not.
- Failed entry always points to **History + Retry**.
- No silent credential fallback (existing rule: OAuth vs API key explicit).

---

## Audio architecture (product)

```
Mic → PCM (RAM only, live) ──┬── progressive AAC → M4A file (disk / session)
                             └── level meter
Stop/pause finalize ──► M4A is source of truth for History + STT
Fallback: if progressive failed, post-stop AAC from remaining PCM once, then drop PCM
```

- **Do not** write full WAV for every take.
- Debug: optional “share last audio” can export the **M4A** of the last session, or opt-in verbose diag.
- PCM exists only as capture buffer / progressive feed; not user-facing “original quality archive”.

---

## Diagnostics

| Default | Opt-in (Diagnostics / Advanced) |
|---------|----------------------------------|
| DiagLog file for support | IME debug overlay (sid, rms, …) |
| Reasonable error strings | Verbose per-chunk logs |
| Share diagnostic log | Share last M4A |

Debug overlay **defaults off**.

---

## Accessibility & polish

- Content descriptions on all IME controls.
- Large enough hit targets (≥48 dp).
- Dark mode consistent with system (already partially done).
- Haptics light on start/stop/pause (nice-to-have).

---

## Success metrics (qualitative)

- Multi-minute dictate: stop → text in ~few seconds when network healthy (already ~2–4 s progressive).
- User never asks “where did my recording go?” after cancel/back.
- History is the obvious recovery path.
- IME looks intentional, not a debug prototype.
