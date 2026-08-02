# Grok Voice Input (Android)

System-wide voice dictation for Android using [xAI Grok STT](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text). Works as a **voice keyboard (IME)** and as Android’s `RecognitionService` (system mic affordance where supported).

Desktop sibling: [cosmic-scribe / voice-input](https://github.com/erik-balfe/voice-input) (COSMIC / Linux).

## What it does

1. Records speech when you open the Grok voice keyboard (16 kHz mono capture).
2. **Encodes AAC/M4A while you speak** (progressive encode → near-zero wait after stop).
3. Uploads compressed audio to `POST https://api.x.ai/v1/stt`.
4. Inserts the transcript into the focused field.

STT uses `format=true` (inverse text normalization where supported) and your chosen `language` code (e.g. `ru`, `en`).

Product direction (History, pause, never lose a take): see [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) and [docs/PLAN.md](docs/PLAN.md).

## Install

**GitHub Releases:** [github.com/erik-balfe/voice-input-android/releases](https://github.com/erik-balfe/voice-input-android/releases) — download `grok-voice-input-<version>.apk`, install on your phone.

Or build locally (see below).

## Setup on your phone

1. Install the APK (from Releases or local build).
2. Open **Grok Voice Input**:
   - **Preferred:** **Sign in with xAI** (device-code browser login) — STT uses SuperGrok / Premium+ **subscription quota**.
   - **Fallback:** paste an [xAI API key](https://console.x.ai/) (pay-per-token credits).
3. Grant microphone.
4. **Enable the keyboard**:  
   **Settings → System → Languages & input → On-screen keyboard → Manage keyboards → Grok Voice Input → ON**
5. Switch to it from the keyboard switcher (🌐), or set **Default voice input method** to Grok Voice Input for the system mic button.

## Build

Requires Android SDK, JDK 17–21 (JDK 25 is not supported by Gradle yet), and `ANDROID_HOME`.

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=~/.local/jdks/jdk-21.0.11+10   # example
./scripts/check.sh   # build + unit tests + lint + APK sanity checks
```

Or just `./gradlew :app:assembleRelease`.

APK: `app/build/outputs/apk/release/app-release.apk`

Install: `adb install -r app/build/outputs/apk/release/app-release.apk`

## Diagnostics

On-device log of UI events, mic path, encode, HTTP latency, and errors (for support).

1. Open **Grok Voice Input** → **Diagnostics**.
2. Optionally enable **Show live status on keyboard** (off by default).
3. Optionally enable **verbose** diagnostics if you need a debug WAV of the last take.
4. Reproduce once → **Share diagnostic log**.

Mic path (**Microphone / audio path**): try **VoIP / communication** if the phone mic is quiet or odd; **Unprocessed** for a raw path.

Logcat tag: `GrokVoiceDiag`.

## Privacy

Audio is sent to xAI for transcription. API keys and OAuth tokens are stored locally with `EncryptedSharedPreferences`. This app keeps **its own** xAI OAuth session (not shared with other apps). Diagnostic logs stay on-device until you share them (they may include timing and short transcript previews, not full secrets).

Compressed M4A is the product audio format. Raw PCM is only used in memory during capture (not kept as a full-quality archive).

## License

MIT
