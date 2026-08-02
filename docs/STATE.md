# Project state

Last updated: 2026-08-03

## Where we are

- **STT path works** on device: progressive AAC, OAuth SuperGrok, M4A upload.
- **Phase 0 + History backbone shipped:**
  - [FEATURE_DESIGN.md](./FEATURE_DESIGN.md) per-feature UI/UX + arch
  - `RecordingStore` (M4A + JSON meta, prune 50 / 500 MB defaults)
  - IME cancel / back / hide → **save keep-worthy** takes (`cancelled_saved`)
  - Stop → save `pending` → Processing → `ok` / `failed`
  - `HistoryActivity`: list, copy, retry STT, delete; open from Settings + IME
  - Duration on listening status; **Processing…** label

## Docs

- [REQUIREMENTS.md](./REQUIREMENTS.md)  
- [PLAN.md](./PLAN.md)  
- [FEATURE_DESIGN.md](./FEATURE_DESIGN.md)  

## Deferred (not in this slice)

- Pause/resume mic mid-take  
- Determinate processing progress bar  
- Meter visual redesign  
- FG continue-under-lock  
- Advanced settings UI for history limits (prefs exist; defaults enforced)

## Next

Phase 2: pause + duration polish; Phase 3 progress estimate; Phase 4 meter.

## Audio note

**PCM** = RAM only while capturing. **Product storage** = **M4A** only.
