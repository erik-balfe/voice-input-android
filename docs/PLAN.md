# Implementation plan

Based on [REQUIREMENTS.md](./REQUIREMENTS.md). Order optimizes for **foundation → save-everything → UI polish**, so we never build chrome on a path that still discards audio.

---

## Phase 0 — Cleanup & foundation (this step)

**Goal:** Ship-ready core without debug noise; docs for the next work; no History yet.

| Task | Detail |
|------|--------|
| Requirements + plan docs | `docs/REQUIREMENTS.md`, `docs/PLAN.md`, `docs/STATE.md` |
| Debug overlay default **off** | `Prefs.isImeDebugOverlay` default false |
| No `last.wav` on hot path | Only if verbose diag; product audio = M4A |
| Phase copy | User-facing **Processing…** not “Send…” / “Upload…” as primary status |
| README | Match reality: progressive M4A, OAuth, diagnostics opt-in |
| Dead path notes | `AudioPreprocessor` unused in production (trim was removed); keep or delete in a later tidy |

**Exit:** Tests pass; normal dictate does not write multi‑MB WAV; IME looks less like a lab tool when debug is off.

---

## Phase 1 — Session store (History backbone)

**Goal:** Every keep-worthy take lands on disk as M4A + meta, even if STT never runs.

| Task | Detail |
|------|--------|
| `RecordingStore` | CRUD under `files/recordings/`; id stem; meta JSON |
| Pruner | Max items + max MB; settings keys + Advanced UI later |
| Finalize on stop | Write M4A (progressive file move/copy) before STT |
| Cancel / back / hide | Finalize + save `pending` / `cancelled_saved` instead of discard |
| Orphan recovery | Scan incomplete progressive temps on cold start |
| Wire STT success/fail | Update meta text/status |
| Minimal App shell | History list (even plain) + open from Settings |

**Exit:** Cancel mid-speech → open App → see take → Retry works.

---

## Phase 2 — IME controls & lifecycle policy

**Goal:** Pause/resume, duration, open App, explicit lifecycle.

| Task | Detail |
|------|--------|
| Pause / resume | Pause AudioRecord + progressive encoder flush policy (define: pause = stop write, resume = continue same muxer or new segment concat) |
| Duration on status | Always (not only debug) |
| Open App button | Intent to History; save session first |
| Back / cancel = save | Per REQUIREMENTS discard rules |
| Lock / hide policy v1 | Stop + save (no silent background mic) |
| Too-short / hard cap | Warnings + auto-save |

**Pause technical note:** Prefer single progressive M4A with pause = stop feeding encoder (codec may need EOS per segment). If MediaMuxer cannot pause cleanly, **concatenate PCM segments in memory** only while session open, or multi-part M4A + merge at stop — spike in Phase 2 start.

**Exit:** Pause works; cancel never loses ≥1 s audio.

---

## Phase 3 — Processing UX

**Goal:** Honest **Processing** state + estimated progress.

| Task | Detail |
|------|--------|
| Distinct processing indicator | Progress bar under status; not mic meter |
| Throughput estimator | EMA of upload B/s; heuristic server time f(size, duration) |
| Progress API | Callbacks from `GrokSttClient` (bytes written) if OkHttp supports; else time-based estimate |
| Offline / fail copy | “Saved to History — transcribe when online” |
| Retry on IME | Already partial; ensure uses stored session id when History exists |

**Exit:** User sees Processing with bar that usually finishes near 100% without long hangs at 99%.

---

## Phase 4 — Visual design & meter

**Goal:** Modern listening UI.

| Task | Detail |
|------|--------|
| Meter redesign | Waveform / bar strip; motion polish |
| State colors | Listening / Paused / Processing / Failed |
| Hints line | Short instructions |
| Dark/light polish | Spacing, type, icons |
| Haptics | Optional |

**Exit:** No longer “2005”; screenshots usable for store.

---

## Phase 5 — History UX complete + Advanced settings

| Task | Detail |
|------|--------|
| List polish | Cosmic-like rows: time, duration, preview, Copy, Delete, status |
| Detail screen | Full text, Retry, Delete |
| Advanced settings | Max items, max MB, duration caps |
| Empty states | Explain dictate → History |
| Optional play M4A | Nice-to-have |

---

## Phase 6 — Hardening

| Task | Detail |
|------|--------|
| FG service / notification | Only if we decide lock-screen **continue** recording |
| Process-death tests | Force-stop mid-record / mid-STT |
| Storage edge | Disk full, prune races |
| RecognitionService path | Same session store as IME (system mic button) |
| Release notes / version bump | |

---

## Suggested sequencing for coding sessions

1. **Now:** Phase 0 (docs + cleanup).  
2. **Next feature PR:** Phase 1 session store + cancel-saves + minimal History.  
3. **Then:** Phase 2 pause + duration + open App.  
4. **Then:** Phase 3 processing progress.  
5. **Parallel/later:** Phase 4 visual; Phase 5 settings polish.

Do **not** redesign the meter before sessions are persisted — recovery matters more than gloss.

---

## Open decisions (resolve at Phase 1–2 start)

1. **Pause encoding:** single muxer vs multi-segment merge.  
2. **Cancel status label:** `pending` vs `cancelled` (still retranscribable).  
3. **System RecognitionService:** share History or IME-only first.  
4. **Hard duration default:** 15 vs 20 minutes.  
5. **Lock policy v1 confirmed:** stop+save (recommended) vs continue with notification.

Default recommendations: multi-segment if single muxer is flaky; status `pending` for any untranscribed audio; History for both IME + RecognitionService; 15 min hard cap; lock = stop+save.
