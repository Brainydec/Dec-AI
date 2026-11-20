# DEC AI ("Hey Deck")

Personal voice assistant for Android (built for a Redmi 13C, 4 GB RAM) with a small Python backend.
Everything can be built and deployed from a phone: GitHub Actions builds the APK, Render hosts the backend.

```
android/         Kotlin + Jetpack Compose app (foreground service, wake word, STT/TTS, phone actions)
backend/         FastAPI brain (Gemini free tier -> Groq fallback), Dockerfile, tests
github-workflows/  build-apk.yml (cloud APK build) and unpack.yml (one-time phone upload helper)
render.yaml      One-click backend deployment blueprint
docs/            INSTALL.md (start here), TESTING.md (report + checklist + troubleshooting)
```

## Architecture

```
 "Hey Deck" -> Porcupine (offline, on-device) -> Android SpeechRecognizer (offline if possible)
      -> Brain: online? -> HTTPS POST /v1/chat -> FastAPI -> Gemini / Groq  -> {reply, actions[]}
                offline? -> OfflineParser (rule-based, no internet needed)
      -> Executor (on phone): emergency stop -> tool switch -> offline switch -> Android permission
                              -> Category B? spoken/on-screen approval -> run -> log to Task history
      -> TextToSpeech (tap the orb to interrupt)
```

The model only *requests* actions. The phone decides whether they run, so a wrong or manipulated model
reply can never place a call or send a text without your approval.

**Category A (run immediately):** flashlight, volume, brightness, open apps, alarms, timers, memory.
**Category B (approval every time, silence = no):** calls, answering/rejecting calls, SMS send/read, WhatsApp.

## Platform limits (not hidden)

- No Android app can listen after you **force-stop** it. The wake word works while DEC's foreground
  service runs (persistent notification). MIUI is aggressive: set Autostart on and Battery saver to
  "No restrictions" (the Access tab has shortcuts). Android 14+ will not let a mic service start from
  boot, so open DEC once after each reboot.
- Always-on wake-word listening costs battery (Porcupine is light, but expect a few % per hour). Use Stop DEC when not needed.
- Launching apps/alarms/calls from the background needs "Display over other apps" (Access tab).
- WhatsApp cannot be auto-sent without an Accessibility service. DEC opens WhatsApp with the message ready; you tap Send.
- Render's free tier sleeps after ~15 min idle (first reply can take up to ~60 s) and has an ephemeral disk,
  so your memories live encrypted on the phone and are sent with each request.
- Offline mode is rule-based. A local LLM is not included: it won't run well on 4 GB RAM.

## Not implemented yet

Notification reading, Accessibility-based UI navigation, call-history access, camera/file/Bluetooth/Wi-Fi tools,
persistent chat history across app restarts, local LLM, Windows companion and smart-home modules (the
tool/action protocol in `backend/app/brain.py` and `Executor.kt` is where they plug in), InCallService
(answer/reject uses `TelecomManager`, which works without being the default dialer), a WebSocket client in the app
(the `/ws` endpoint exists; the app uses HTTPS POST).
