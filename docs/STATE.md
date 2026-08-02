# Project state

Last updated: 2026-08-03

## Where we are

- **STT path works** on device: progressive AAC, OAuth SuperGrok, M4A upload.
- **History backbone:** RecordingStore, cancel/back save, HistoryActivity.
- **v0.2.4 UX (this install):**
  - Pause / resume on IME (mic drain while paused; same progressive encode)
  - Processing progress bar + % estimate
  - Meter: waveform bars + paused amber state
  - Advanced settings: history max items / max MB
  - Hints under status line

## Docs

- [REQUIREMENTS.md](./REQUIREMENTS.md)  
- [PLAN.md](./PLAN.md)  
- [FEATURE_DESIGN.md](./FEATURE_DESIGN.md)  

## Still deferred

- FG continue-under-lock (v1 remains **stop + save** on hide/lock)
- Byte-accurate upload progress (time-based estimate now)

## Audio note

**PCM** = RAM only while capturing. **Product storage** = **M4A** only.
