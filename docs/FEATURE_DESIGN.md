# Per-feature UI/UX + architecture

Aligned with [REQUIREMENTS.md](./REQUIREMENTS.md). Implementers should not invent product rules beyond this file.

---

## F1 — History / session persistence

### UX
| State | User sees |
|-------|-----------|
| Empty History | “No recordings yet. Use the voice keyboard, then return here.” |
| Row | Relative time · duration · status badge · transcript preview (or “No transcript”) |
| Actions per row | **Copy** (if text) · **Retry** (if audio, re-run STT) · **Delete** (confirm) |
| After cancel/back with keep | Toast optional: “Saved to History” |

### Architecture
- Dir: `context.filesDir/recordings/`
- Per id (`yyyyMMdd_HHmmss_SSS` or UUID):
  - `{id}.m4a` — product audio (required for retranscribe)
  - `{id}.json` — meta (see schema)
- **No PCM/WAV** in product store.
- `RecordingStore(root: File)` — pure FS API; Android wrapper only for path.

### Meta schema (`{id}.json`)
```json
{
  "id": "…",
  "createdAtMs": 0,
  "durationMs": 0,
  "status": "pending|ok|failed|cancelled_saved",
  "language": "en",
  "sourcePackage": "org.telegram…|null",
  "text": "…|null",
  "error": "…|null",
  "audioBytes": 0,
  "sampleRate": 16000
}
```

### Status transitions
```
(save) → pending | cancelled_saved
pending + STT ok → ok (+ text)
pending|cancelled_saved|failed + Retry STT ok → ok
any + STT fail → failed (+ error)
```

---

## F2 — Cancel / back / hide keep rules

### UX
| Event | Behavior |
|-------|----------|
| Center stop | Finalize → save `pending` → **Processing** → insert text → `ok` or `failed` |
| ✕ cancel | Finalize → if keep-worthy save `cancelled_saved` → leave IME (no insert) |
| Back / edge swipe / `onFinishInputView` | Same as cancel if recording and not already processing |
| Duration &lt; keep min (400 ms) | Discard; no History row |
| Processing in flight | Do not cancel mic (already stopped); allow job to finish if possible; still update History |

### Architecture
- Single path: `SessionCoordinator.end(reason)`  
  - `reason`: `PROCESS` | `SAVE_ONLY` | `DISCARD`  
- Always prefer `recorder.stop()` (finalize M4A) over `cancel()` when duration may be keep-worthy.
- `cancel()` only after explicit discard of too-short or failed open.
- Keep min = `VoicePipeline.MIN_DURATION_MS` (400 ms).

---

## F3 — Pause / duration / open App (design; pause deferred if needed)

### UX (target)
| Control | Behavior |
|---------|----------|
| Status | `Listening · m:ss` or `Paused · m:ss` or `Processing…` |
| ⏸ / ▶ | Pause = stop feeding mic, keep session; Resume = continue same take |
| ↗ App | `SAVE_ONLY` then start `HistoryActivity` |
| Duration | Always visible while listening/paused (not debug-only) |

### Architecture (pause — Phase 2 if not in this goal slice)
- Spike: pause AudioRecord + stop progressive feed; on resume reopen or continue encoder (multi-segment merge if needed).
- **This goal:** duration on status when listening; optional App button → History; full pause may ship with coordinator hooks reserved.

### Open App
- `Intent(HistoryActivity)` with `FLAG_ACTIVITY_NEW_TASK` from IME.
- Before launch: end session with `SAVE_ONLY` if recording.

---

## F4 — Processing indicator

### UX
| Element | Spec |
|---------|------|
| Label | **Processing…** (never “Sending” as primary) |
| Meter | Switch center control to transcribing/spinner mode (existing `TRANSCRIBING`) |
| Progress bar | Optional later; estimate from size + throughput (REQUIREMENTS) |
| Fail | Error string + Retry; audio remains in History as `failed` |
| Offline | “Network required. Recording saved — open History to process later.” |

### Architecture
- `onPhase` callbacks stay internal/debug; IME main status stays `Processing…` unless debug overlay.
- STT result writes `RecordingStore.markOk` / `markFailed`.

---

## F5 — Storage limits

### UX
- Defaults: **50** items, **500 MB** total M4A.
- User changes only under Advanced settings (UI can land later; prefs + prune run now).
- Prune after each save: delete oldest by `createdAtMs` until under both caps.
- Prefer never leaving zero space for the session just saved: prune others first.

### Architecture
```kotlin
Prefs.getHistoryMaxItems() // default 50
Prefs.getHistoryMaxBytes() // default 500L * 1024 * 1024
RecordingStore.prune(maxItems, maxBytes)
```

---

## Module map

| Type | Role |
|------|------|
| `RecordingStore` | FS CRUD + prune |
| `RecordingMeta` / `SessionStatus` | Models |
| `SessionCoordinator` (or methods on IME) | end reasons → store + optional STT |
| `VoicePipeline` | STT; accept M4A-only clips via duration hint |
| `HistoryActivity` | List + copy + retry + delete |
| `GrokVoiceInputMethodService` | UI + call coordinator |

---

## Deferred (explicit)
- Waveform meter redesign  
- Determinate progress bar estimate  
- FG continue-under-lock  
- Advanced settings UI for limits (prefs defaults enforced in code)
