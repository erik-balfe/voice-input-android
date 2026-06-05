# Grok Voice Input (Android)

System-wide voice dictation for Android using [xAI Grok STT](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text). Works anywhere the microphone / voice-input affordance appears (keyboard, browser, apps) by implementing Android’s `RecognitionService`.

Desktop sibling: [voice-input](https://github.com/erik-balfe/voice-input) (COSMIC / Linux).

## What it does

1. Records speech when the system starts voice input (16 kHz mono PCM).
2. Trims leading/trailing silence (simple RMS threshold).
3. Sends WAV to `POST https://api.x.ai/v1/stt`.
4. Returns the transcript to the focused field.

## Setup on your phone

1. Install the APK (debug or release).
2. Open **Grok Voice Input** → save your [xAI API key](https://console.x.ai/) → grant microphone.
3. **Enable the keyboard** (this is where Whisper-style apps show up):  
   **Settings → System → Languages & input → On-screen keyboard → Manage keyboards → Grok Voice Input → ON**
4. Switch to it from the keyboard switcher (🌐/mic), or set **Default voice input method** to Grok Voice Input for the system mic button.

## Build

Requires Android SDK, JDK 17–21 (JDK 25 is not supported by Gradle yet), and `ANDROID_HOME`.

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=~/.local/jdks/jdk-21.0.11+10   # example
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Privacy

Audio is sent to xAI for transcription. The API key is stored locally with `EncryptedSharedPreferences`.

## License

MIT