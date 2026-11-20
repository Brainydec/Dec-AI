# Install DEC on your phone (no computer)

Total cost: free. You need: GitHub account (have), Render account (have), Google account (any Android phone has one).
Do the steps in order. Use Chrome on your phone; if a page looks cramped, tap ⋮ > "Desktop site".

## 1. Get the free AI key (Gemini)
1. Open https://aistudio.google.com/apikey, sign in, tap **Create API key**, copy it.
2. Optional fallback: https://console.groq.com/keys (free) -> create key.
Free-tier limits and model names change; if replies fail later, check the Render logs (see TESTING.md).

## 2. Put the project on GitHub
1. github.com -> **+** -> New repository. Name `dec-ai`, **Private**, tick "Add a README". Create.
2. In the repo: **Add file > Upload files**, choose `dec-ai.zip` (from Claude), tap **Commit changes**.
3. **Add file > Create new file**. Name: `.github/workflows/unpack.yml` (typing `/` creates folders).
   Paste the contents of `unpack.yml`. Commit.
4. **Actions** tab > "Unpack project" > **Run workflow**. Wait for the green tick. Your repo now has `android/`, `backend/`, etc.
5. **Add file > Create new file**: `.github/workflows/build-apk.yml`, paste `build-apk.yml`, commit.
   (GitHub forbids bots from adding workflow files, which is why steps 3 and 5 are manual.)

## 3. Deploy the backend on Render
1. dashboard.render.com > **New > Blueprint**, connect GitHub, pick `dec-ai`. It reads `render.yaml`.
   (If Blueprint isn't offered: **New > Web Service**, pick the repo, Runtime **Docker**,
   Dockerfile path `./backend/Dockerfile`, Docker context `./backend`, Instance type **Free**, health check `/health`.)
2. When asked, paste `GEMINI_API_KEY` (and `GROQ_API_KEY` if you made one).
3. After deploy: open the service > **Environment** > copy `DEC_API_TOKEN` (auto-generated; if you used the manual route, add your own long random value).
4. Copy the service URL (`https://dec-backend-xxxx.onrender.com`). Visit `<URL>/health`. It should show `{"status":"ok",...}` (first load may take ~1 min).

## 4. Build the APK
1. GitHub repo > **Actions > Build DEC APK > Run workflow**. First build takes ~5-10 min.
2. If it fails, open the run, copy the red error text and send it to Claude. The code has never been compiled before this step, so a first-build fix is plausible.
3. When green: repo > **Releases** > newest `DEC AI build N` > download `app-debug.apk`.

## 5. Install and set up
1. Open the downloaded APK. Allow "Install unknown apps" for Chrome/Files when asked. Play Protect may warn about an unknown developer; choose "Install anyway".
2. Open DEC > **Setup** tab: paste Backend URL and API token > **Test backend connection**.
3. Wake word: go to https://console.picovoice.ai (free account) > copy your **AccessKey** > **Porcupine** > create wake word,
   phrase `Hey Deck`, language English, **Platform: Android** > Train > download the `.ppn`.
   In DEC Setup: paste AccessKey, tap **Import .ppn**, pick the file.
   (Picovoice's free tier is for personal/non-commercial use; check their current terms.)
4. **Access** tab: tap Grant for each feature you want, then the four system rows
   (modify settings, overlay, battery, Xiaomi autostart).
5. For "call my brother": either name the contact "Brother", or tell DEC "remember that my brother is Sam".
6. **DEC** tab > **Start DEC** (allow microphone + notifications). Say "Hey Deck, turn on my flashlight."

Also install/enable **Google** app + "Speech Recognition & Synthesis" if speech recognition reports unavailable.
