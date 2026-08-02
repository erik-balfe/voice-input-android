# Grok Voice Input (Android)

System-wide voice dictation for Android using [xAI Grok STT](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text).

Works as a **voice keyboard (IME)** and as Android’s `RecognitionService` (system mic affordance where supported).

Desktop sibling: [cosmic-scribe / voice-input](https://github.com/erik-balfe/voice-input) (COSMIC / Linux).

## Features

- **Progressive AAC** while you speak → almost no wait after ✓  
- **Multi-take**: after insert, stay ready at 0:00 (or close keyboard — Settings)  
- **History** of takes (copy, retranscribe, delete) with size/count caps  
- **OAuth** (SuperGrok / subscription) or **API key**  
- Living level orb, pause, newline, switch to typing keyboard  

Product notes: [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) · [docs/FEATURE_DESIGN.md](docs/FEATURE_DESIGN.md)

## Setup

1. Install the APK (Releases or local build).  
2. Open **Grok Voice Input** → **Sign in with xAI** (or paste an API key).  
3. Grant microphone.  
4. **Settings → System → Languages & input → On-screen keyboard → Manage keyboards → Grok Voice Input → ON**.  
5. Switch to it from the keyboard picker (🌐).

## Keyboard controls

| Control | Action |
|---------|--------|
| Center orb | Start / pause / resume |
| ✓ | Transcribe with Grok and insert |
| ↵ | New line |
| ⌨️ | Typing keyboard |
| ⚙ | App settings |

## Build

Requires Android SDK, JDK 17–21, `ANDROID_HOME`.

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=~/.local/jdks/jdk-21.0.11+10   # example
./scripts/check.sh   # unit tests + lint + APK checks
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`  
Release: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/`

## Privacy

Audio is sent to xAI for transcription. Tokens live in `EncryptedSharedPreferences`.  
Diagnostic logs stay on-device until you share them (Advanced → Share log).

## License

MIT
