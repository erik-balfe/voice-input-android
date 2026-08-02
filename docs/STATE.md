# Project state

Last updated: 2026-08-03  
Version: **0.3.2** (cleanup next)

## Product status

Production-ready for everyday dogfooding:

- Progressive AAC during capture, OAuth SuperGrok / API key
- IME multi-take (default keep open → ready at 0:00)
- Controls: ⌨️ typing IME · ↵ newline · orb listen/pause · ✓ transcribe · ⚙ settings
- History store (M4A + meta, prune caps); also filled from system RecognitionService
- Progress ring (optimistic, monotonic, never snaps back)
- Lean Settings (account, test connection, language, keep-IME; Advanced for mic/limits/logs)

## Deferred (next release candidates)

- Silence cut before upload (VAD; careful false negatives)
- Optional Opus progressive encode (AAC is fine for STT today)

## Architecture notes

- Product audio on disk: **M4A only** (`RecordingStore`)
- PCM only in RAM during capture
- Encrypted prefs **cached** (avoid UI jank)
- Debug WAV / verbose paths gated; not product defaults

## Docs

- [REQUIREMENTS.md](./REQUIREMENTS.md)
- [FEATURE_DESIGN.md](./FEATURE_DESIGN.md) (may lag latest IME control map slightly)
- [PLAN.md](./PLAN.md)
