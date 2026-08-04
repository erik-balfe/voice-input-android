# Project state

Last updated: 2026-08-05  
Version: **0.3.4** (versionCode 25)  
VCS: `main` — ready to push origin + tag `v0.3.4` (logo deferred to next release).

## Shipped (0.3.4)

- Progressive AAC, OAuth / API key (explicit; no silent fallback)
- Multi-take IME (keep ready at 0:00 by default after ✓)
- History via `SessionPersistence` + `RecordingStore` (IME + RecognitionService)
- Hide/lock: keep ≥1 s takes → auto-transcribe via `PendingSttQueue`
- History + Settings on IME; progress ring; product Settings
- Pure helpers: `ImeUi`, `ProcessingProgress`, `SessionAudio`

## Deferred (next releases)

| Item | Notes |
|------|--------|
| **Cancel / restart take (IME)** | Explicit control while listening/paused: discard junk and return to ready **or** stop+save to History for later STT. See FEATURE_DESIGN F6 / REQUIREMENTS. |
| **Silence cut (VAD)** | Trim leading/trailing silence before upload; pairs with cancel/restart for cleaner takes |
| **Production launcher logo** | Replace generic blue icon; user will supply art |
| Wire Advanced **Mic mode** into `PcmRecorder` (or hide radios) | Setting is saved but capture still VOICE_RECOGNITION→MIC |
| History list live-refresh while open | Background auto-STT can finish without UI update |
| History delete confirm dialog | Spec says confirm; UI deletes immediately |
| Opus progressive encode | Optional |
| `SessionCoordinator` extract | If IME class grows again |
| Release CI real keystore | Without secrets, tagged APK is debug-signed |

## Release notes draft (v0.3.4)

- Progressive AAC while you speak (low wait after ✓)
- Multi-take voice keyboard; optional close after insert
- History of takes (copy / retranscribe / delete); auto-transcribe after lock/home save
- Sign in with xAI (OAuth) or paste API key
- Pause/resume, newline, switch to typing keyboard

## Verify

```bash
./scripts/check.sh
```

## Publish (when approved)

```bash
# after local commit is on main:
jj git push
# or: git push origin main
git tag -a v0.3.4 -m "v0.3.4: progressive AAC, multi-take IME, History, OAuth"
git push origin v0.3.4
```
