# Testing report and checklist

## What was actually verified (in the authoring sandbox, which had no network, no Android SDK and no FastAPI)
| Item | Status |
|---|---|
| Backend Python syntax (all files compile) | Verified |
| Model-output parsing, unknown-tool filtering (stubbed run of `brain.py`) | Verified |
| Offline command regexes (alarm, timer, call, SMS, WhatsApp, remember) | Verified (regex engine only) |
| Kotlin brace/paren balance in all 13 files | Verified |
| Backend running under FastAPI/uvicorn, `pytest` suite | **Not run** (needs `pip install -r requirements.txt`) |
| Kotlin compile / APK build | **Not run.** First real build happens in GitHub Actions |
| Docker image build, Render deployment, Gemini/Groq calls | **Not run** |
| Everything on a real Redmi 13C (voice, wake word, calls, SMS, battery) | **Not run** |

Nothing in this project has been deployed or tested on a device. Use the checklist below; report failures to Claude with the exact error.

## Device test checklist
| # | Test | How | Expect |
|---|---|---|---|
| 1 | Install | Open APK | Installs, icon "DEC AI" |
| 2 | Startup | Open app | Dark UI, orb, bottom tabs |
| 3 | Voice recognition | Start DEC, tap orb, speak | Your words appear in chat |
| 4 | Wake phrase | Standby, say "Hey Deck" | Beep, orb turns cyan |
| 5 | AI conversation | "What's the capital of France?" | Spoken answer; follow-up works without waking again |
| 6 | Online/offline | Airplane mode, "turn on flashlight" | Chip shows OFFLINE, torch works; "what is the news" gives offline notice |
| 7 | Flashlight | "Turn on / off the flashlight" | Torch toggles |
| 8 | Alarm | "Set an alarm for 7 30 am" | Alarm appears in Clock app |
| 9 | App launch | "Open Chrome", "open my browser" | App opens (needs overlay permission if DEC is in background) |
| 10 | Incoming call | Have someone call you | DEC announces caller; "answer" -> approval prompt -> answers only after "yes" |
| 11 | Outgoing call | "Call <contact>" | Approval dialog + spoken question; "no" or silence cancels |
| 12 | SMS | "Text <contact> saying hi" | Approval first; sends only on yes |
| 13 | Revocation | Access tab: switch off "Make calls", retry | DEC refuses; shown in Tasks |
| 13b | Emergency stop | Tap EMERGENCY STOP | Service stops, all actions refused until Resume |
| 14 | Memory | "Remember that my brother is Sam"; restart app | Still listed in Memory tab; "password" facts are refused |
| 15 | Backend | Setup > Test backend | "Backend OK" |
| 16 | Battery | Settings > Battery usage after a day on standby | Record the % for DEC |
| 17 | Recovery | Reboot, open DEC, Start | Works; wake word needs one manual start (Android 14 rule) |

Backend tests: `cd backend && pip install -r requirements.txt && pytest`.
API docs when running: `<URL>/docs`. Endpoints: `GET /health`, `POST /v1/chat`, `GET /v1/stats`, `WS /ws`.

## Troubleshooting
- **Backend test hangs/fails the first time:** Render free is waking up; retry after a minute.
- **401:** token in app differs from Render `DEC_API_TOKEN`. **502:** AI key missing/invalid or quota used; read Render > Logs.
- **"No speech recognition service installed":** install/enable the Google app and "Speech Recognition & Synthesis".
- **Wake word error text on the home screen:** wrong AccessKey, `.ppn` trained for another platform, or a Porcupine version mismatch. Retrain on console.picovoice.ai or tell Claude the exact message (the library version in `android/app/build.gradle.kts` may need changing).
- **Wake word stops after a while (MIUI):** Access tab > Xiaomi row, set Autostart on and Battery saver "No restrictions"; lock DEC in recent apps.
- **Alarm/app/call doesn't happen when screen is off:** grant "Display over other apps".
- **Brightness does nothing:** grant "Modify system settings".
- **WhatsApp opens but doesn't send:** by design; tap Send. Set your country code in Setup.
- **APK build fails in Actions:** copy the first red `error:` line to Claude.
