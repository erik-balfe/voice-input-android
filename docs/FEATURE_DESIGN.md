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
| **✓ Done** | Finalize → save `pending` → **Processing** → insert text → `ok` or `failed` |
| Back / edge swipe / hide / lock | If keep-worthy (≥ **1 s**, not silence heuristic): finalize M4A → `cancelled_saved` → `PendingSttQueue` may auto-STT later; no insert into field |
| Duration &lt; hide keep min (1 s) | Discard; no History row |
| Duration &lt; finish min (400 ms) on ✓ | Too short; no STT |
| Processing in flight | Do not cancel mic (already stopped); allow job to finish if possible; still update History |

### Architecture
- End reasons: `PROCESS` \| `SAVE_ONLY` \| `DISCARD` (on IME methods today; optional `SessionCoordinator` later).
- Prefer `recorder.stop()` (finalize M4A) over `cancel()` when duration may be keep-worthy.
- `cancel()` only after explicit discard of too-short or failed open.
- Hide/cancel keep min = `SessionAudio.KEEP_HISTORY_MIN_MS` / `PendingSttQueue.MEANINGFUL_MIN_MS` (**1000 ms**).
- Finish min = `VoicePipeline.MIN_DURATION_MS` (**400 ms**).

---

## F3 — IME controls (shipped 0.3.x)

### Layout
```
[ ⌨️ typing IME ]     m:ss      [ History ] [ ⚙ settings ]
                      hint (always reserved height)
[ ↵ newline ]      ( living orb )      [ ✓ done ]
```

| Control | Behavior |
|---------|----------|
| **Orb (center)** | Ready → start listen; listening → pause; paused mid-take → resume |
| **✓** | Finish take → STT + insert (green highlight while take active) |
| **↵** | Insert `\n` into field (paragraph between takes) |
| **⌨️** | Switch to typing IME (previous/last/next/picker fallbacks) |
| **History** | Open History app (save mid-take first if recording) |
| **⚙** | Open Settings (save take first if recording) |
| **Timer** | Centered `m:ss` active listen time |
| **Hints** | Persistent while relevant (pause / transcribe / ready) |

**Not shipped yet:** explicit **Cancel take** / restart — see F6.

### After success
- Default **keep IME**: enter **ready** at `0:00` mic off (not auto-record).
- Optional setting: close and switch to typing keyboard.

### Architecture
- Pause drains AudioRecord without feeding progressive AAC; resume continues same encoder.
- `SessionPersistence.saveClip` shared with RecognitionService.
- Hide/back → `SAVE_ONLY` if keep-worthy; next show restarts or stays ready.

---

## F4 — Processing indicator (shipped)

### UX
| Element | Spec |
|---------|------|
| Timer | Frozen at take duration |
| Ring | Circular progress on orb; asymptotic ease to ~88% then crawl; never reverse |
| Done | Drop ring without forcing 100%; go to ready or typing IME |
| Fail | Message + Retry; audio in History as `failed` |

### Architecture
- `ProcessingProgress` estimates from M4A size + EMA throughput (log-calibrated).
- STT result → `RecordingStore.markOk` / `markFailed`.

---

## F5 — Storage limits

### UX
- Defaults: **50** items, **500 MB** total M4A.
- User changes under **Advanced…** in Settings.
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

## F6 — Cancel take / restart (deferred — next UX)

**Problem (user):** Often start the mic by mistake, or speak a few wrong words, and need a **fresh take** without inserting junk text and without treating hide as the only escape.

### Desired UX
| Action | Behavior |
|--------|----------|
| **Discard & restart** | Drop current audio (no History row, or drop even if ≥1 s). Return IME to **ready** at `0:00`, mic off — user taps orb again to record from scratch. Primary path for “I said the wrong thing.” |
| **Save for later** (optional long-press / second affordance) | Finalize M4A → History as `cancelled_saved` (or `pending`) **without** STT insert; return to ready. User can open History later to retranscribe. Same recovery idea as hide/lock, but **stays on the keyboard**. |
| While processing | Prefer no hard cancel of in-flight STT; optional “dismiss UI / keep job” later. |

Placement options (pick at implement time): top-bar ✕, or secondary under orb while listening/paused only (hidden in ready).

### Architecture
- New end reason or flags: `DISCARD_RESTART` vs `SAVE_ONLY_STAY` vs existing hide `SAVE_ONLY` (leave IME).
- Reuse `SessionAudio` keep heuristics for “save for later”; discard path uses `recorder.cancel()` / delete temp progressive.
- Do **not** auto-insert on either cancel path.
- Related later: **silence cut (VAD)** before upload (leading/trailing trim) — cleaner takes when user does want to keep audio; cancel/restart covers intentional “throw this take away.”

### Out of scope for F6
- FG continue-under-lock  
- Editing/trimming mid-waveform UI  

---

## Deferred (explicit)
- **F6 Cancel take / restart** (discard vs save-for-History)  
- **Silence cut (VAD)** before upload — pairs with cleaner History + STT  
- Production launcher logo  
- Wire Advanced mic mode into `PcmRecorder`  
- History list refresh while open + delete confirm  
- Waveform meter redesign  
- FG continue-under-lock  
- Opus progressive encode
