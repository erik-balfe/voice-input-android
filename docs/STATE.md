# Project state

Last updated: 2026-08-03  
Version: **0.3.3**  
VCS: `main` at polished tip (cleanup + polish commits).

## Shipped

- Progressive AAC, OAuth / API key, multi-take IME  
- History via `SessionPersistence` + `RecordingStore` (IME + RecognitionService)  
- Progress ring, fixed-height IME, product Settings  
- Pure helpers: `ImeUi`, `ProcessingProgress`, `SessionAudio`  

## Deferred

- Silence cut (VAD) before upload  
- Opus progressive encode (optional)  
- Further IME class split (`SessionCoordinator`) if file grows again  

## Verify

```bash
./scripts/check.sh
```
