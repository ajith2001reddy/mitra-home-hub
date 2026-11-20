# MITRA Home Hub

MITRA is a tablet-first personal home hub for Android and ChromeOS tablets. It combines a full-screen dashboard, real Home Assistant control, on-device photo display, Google mail summaries, voice input, and an optional AI assistant.

The project is designed for a dedicated landscape display such as a Lenovo Duet. It is also a normal React application in a native Android WebView, so the interface can be developed in a browser while privileged actions stay inside the Android app.

> **Privacy first:** this repository intentionally contains no Home Assistant URLs or tokens, AI keys, Google tokens, personal photographs, APKs, signing keys, account names, or network addresses. Do not commit them.

## What MITRA does

- Shows a responsive full-screen dashboard with live time, date, weather, device accessories, rooms, scenes, calendar, notifications, and photo carousel.
- Discovers and controls actual Home Assistant entities through the native bridge. It does not use demo device states.
- Supports lights, switches, fans, covers, locks, media players, scenes, and other Home Assistant entities exposed by the dashboard.
- Verifies smart-home state after an AI-initiated action before reporting success.
- Displays a local private photo library selected from the device. Chosen photos are copied into MITRA's app-private storage.
- Lets you choose one Google Drive folder through Android's folder picker; only image files from that selected folder are copied into MITRA's private photo library.
- Uses Google Identity to request Gmail read-only access from the signed-in user, then shows summaries in MITRA. Gmail data is not sent to Home Assistant.
- Provides push-to-talk speech recognition and spoken replies through Android. An always-listening wake word is deliberately **not** included.
- Provides optional MITRA Brain chat using the OpenAI Responses API, with web search and controlled smart-home functions.
- Provides Matter QR scanning through the device camera where the platform and integration support it.
- Supports a lower-brightness standby view after an idle period configured in Settings.
- Keeps the dashboard screen awake on a dedicated tablet. Remote Home Assistant access can be provided by a user-managed secure network such as Tailscale.

## Architecture

```text
                    ┌─────────────────────────────┐
                    │       MITRA dashboard         │
                    │ React + TypeScript + Vite UI  │
                    └──────────────┬──────────────┘
                                   │ WebView bridge
┌──────────────────────────────────┼──────────────────────────────────┐
│ Native Android app (Java)        │                                  │
│                                  │                                  │
│  HomeAssistantBridge ────────────┼──── Home Assistant REST API      │
│  MitraBrainBridge ───────────────┼──── OpenAI Responses API         │
│  GmailBridge ────────────────────┼──── Google Identity / Gmail API  │
│  PhotoLibraryBridge ─────────────┼──── app-private device storage   │
│  VoiceBridge ────────────────────┼──── Android speech / TTS         │
│  Camera / Matter scanner ────────┼──── Matter setup flow            │
└──────────────────────────────────┴──────────────────────────────────┘
```

The browser UI never receives the Home Assistant access token or OpenAI key. Sensitive network calls are made from the native bridges.

## Repository layout

```text
.
├── src/                              # React dashboard and settings UI
│   ├── features/assistant/           # MITRA Brain chat and voice controls
│   ├── features/dashboard/           # Dashboard, rooms, calendar, standby view
│   ├── features/google/              # Gmail UI integration
│   ├── features/homeassistant/       # Live entity UI and device controls
│   ├── features/matter/              # Matter QR scanner
│   ├── features/photos/              # Personal-photo carousel UI
│   └── features/settings/            # Six-category settings interface
├── native-android/
│   ├── src/com/mitrahome/hub/        # Native Android WebView bridges
│   ├── app/build.gradle              # Android application module
│   ├── AndroidManifest.xml           # Runtime permissions and activity config
│   └── build-apk.ps1                 # Portable local Android debug build helper
├── public/                           # Web manifest, icon, service worker
├── scripts/verify-mitra-brain.mjs    # Structural security/regression checks
├── MITRA-BRAIN.md                    # AI operation and functional checks
└── README.md
```

## Prerequisites

### Dashboard development

- Node.js 20 or newer
- npm 10 or newer

### Android application build

- Android Studio with Android SDK Platform 35
- A supported JDK (JDK 17 is recommended for Android builds)
- Gradle available on `PATH` if using `native-android/build-apk.ps1`
- An Android/ChromeOS tablet with Android app support for installation and device-only integrations

### Integrations (only when you need them)

- A reachable Home Assistant server and a separate long-lived access token
- An OpenAI API key for MITRA Brain
- Google Play services and a Google account for Gmail access
- A Matter-compatible Home Assistant setup for Matter commissioning
- A secure remote network solution, such as Tailscale, for access outside the home network

## Run the dashboard locally

From the cloned project folder:

```powershell
npm ci
npm run dev
```

Open the local Vite address shown in the terminal. The web version is useful for UI work, but native-only features (encrypted credentials, voice, local photos, QR camera, Gmail identity) require the Android app.

To make a production web bundle:

```powershell
npm run build
npm run preview
```

The generated `dist/` folder is deliberately ignored by Git.

## Build the Android app

1. Build the web assets first:

   ```powershell
   npm ci
   npm run build
   ```

2. Build a debug APK from `native-android`:

   ```powershell
   Set-Location native-android
   gradle :app:assembleDebug --no-daemon
   ```

   Or, from the repository root, run the helper after installing Gradle and setting `JAVA_HOME`:

   ```powershell
   .\native-android\build-apk.ps1
   ```

3. Install `native-android/app/build/outputs/apk/debug/app-debug.apk` on your Android-capable device.

The helper copies the result to `native-android/build/mitra-home-hub-debug.apk`; both APK locations are ignored by Git.

### Signing a release build

The repository never includes a signing key. Create and keep your own release keystore outside the repository, then configure Android/Gradle locally or in secure CI secrets. Never add a `.keystore`, `.jks`, password, or `local.properties` file to Git.

The public package ID is `com.mitrahome.hub`. It differs from any locally installed development package with another ID, so a build from this repository installs as a separate app unless you migrate it deliberately.

For a private upgrade of an existing installation that uses a different Android package ID, pass `-PmitraApplicationId=<existing-package-id>` and provide the matching signing material through local-only build inputs: `-PmitraKeystorePath=<path>` plus `MITRA_KEY_ALIAS` and `MITRA_KEYSTORE_PASSWORD` environment variables. Do not put the existing package ID, keystore path, alias, or password in this repository.

## Initial MITRA setup

Open the Android app and use the MITRA Settings navigation. Settings are organized as:

1. **General & Appearance** — app name, language, clock/date display, theme, colors, layout, and standby duration.
2. **AI Assistant** — provider/model selection, API key prompt, response style, web search, history, memory, and smart-home permissions.
3. **Smart Home** — Home Assistant connection, entity synchronization, areas/rooms, scenes, automations, and diagnostics.
4. **Voice & Notifications** — microphone, push-to-talk behavior, speech voice/rate/volume, notification preferences, and quiet hours.
5. **Integrations** — Gmail, calendar, weather, personal photos, and integration permissions.
6. **Privacy & System** — data retention, activity/error log, backups, diagnostics, and advanced configuration.

### Home Assistant

1. In Home Assistant, create a dedicated long-lived access token under your user profile's security settings. Use a dedicated account with only the permissions MITRA needs.
2. In **MITRA Settings → Smart Home**, enter the Home Assistant base URL and the token.
3. Save and wait for the connection indicator to show connected.
4. Create Home Assistant Areas such as `Bedroom`, then assign devices to those areas. MITRA reads those areas for room controls.
5. Create scenes and automations in Home Assistant. MITRA displays and invokes the entities Home Assistant exposes.

MITRA's Android bridge encrypts the Home Assistant token with an Android Keystore key before storing it locally. The dashboard does not receive the token.

### Remote access

For access outside the local network, keep Home Assistant and the tablet on a secure, user-managed private network. A mesh VPN such as Tailscale is one option. Do not publicly expose an unsecured Home Assistant URL, and do not commit a private IP address, device hostname, or remote-network credential.

### Google mail

Open **MITRA Settings → Integrations → Gmail** and choose Connect. Android opens Google's consent screen and requests only the Gmail read-only scope plus basic profile/email identity. Gmail access tokens stay in the native process's memory; disconnecting clears them and requests token revocation.

Google OAuth is not a Home Assistant integration: MITRA does not send mail content or Google tokens to Home Assistant. App registration and OAuth consent-screen requirements are controlled by Google and may vary by build/distribution method.

### Personal photos

Open the photo section and choose photos from the system picker. On iPhone, transfer selected photos to the tablet first (for example with a permitted local transfer or cloud photo provider), then import them through the Android picker. MITRA copies imported images into its own app-private storage, uses them in the carousel/background, and never uploads them by itself.

### Google Drive photo folder

In **MITRA Settings → Integrations → Google Drive photo folder**, select **Choose folder**. Android opens its folder picker; choose the Google Drive account and the one folder that contains the photos you want MITRA to display. MITRA receives persistent read access only to the folder you select, not to the rest of your Google Drive. It imports direct image files from that folder into app-private storage, where they can be used by the carousel, blurred dashboard background, and standby display.

Use **Sync now** after adding new photos to that folder. Choosing a different Drive folder replaces the previously imported Drive photos; manually removing a Drive photo in MITRA hides it so it will not return on the next sync. **Disconnect** removes MITRA's permission to the selected folder and deletes its locally imported Drive copies, while preserving photos that you imported directly from the device.

### Voice

Tap the microphone control and speak a request. Android captures speech, MITRA processes it, optionally sends a verified Home Assistant command, and reads its response aloud when text-to-speech is available.

Always-on wake-word listening is intentionally disabled: it caused false activations and is not included in the app. Push-to-talk is the supported, privacy-preserving mode.

### MITRA Brain

See [MITRA-BRAIN.md](MITRA-BRAIN.md) for the detailed AI setup and functional checks. In brief:

1. Go to **MITRA Settings → AI Assistant**.
2. Choose a model available to your own OpenAI project.
3. Enter the API key through the native secure prompt; do not put it in a frontend file or `.env` committed to Git.
4. Turn on only the capabilities you want: web search, conversation history, long-term memory, and smart-home control.

For smart-home requests, MITRA Brain exposes only controlled functions: `get_devices`, `get_device_state`, `turn_on_device`, `turn_off_device`, and `control_room`. Sensitive actions such as locks and covers require confirmation. The bridge validates entity IDs, calls Home Assistant, re-reads the state, and reports success only after verification.

## Security and privacy model

| Data or capability | Where it is handled | Source-control policy |
| --- | --- | --- |
| Home Assistant URL/token | Android native bridge; token encrypted using Android Keystore | Never commit it |
| OpenAI API key | Android native MITRA Brain bridge; encrypted using Android Keystore | Never commit it |
| Gmail access token | Google Play services/native process memory | Never commit it |
| Personal photos | App-private storage on the device | Never commit them |
| Conversation/history/memory | Local app preferences | Never commit exports |
| APKs and signing keys | Local build output / private signing storage | Ignored by `.gitignore` |
| Matter setup QR data | Camera/native setup flow | Do not capture in source or issues |

Additional safeguards implemented in the project:

- Browser-facing code has no OpenAI key.
- MITRA Brain uses the Responses API with server-side/native execution and disables provider-side response storage for its request flow.
- Function calls use strict schemas and run sequentially.
- Home Assistant actions are restricted to registered/supported entities and are verified after a command.
- Locks and covers require explicit confirmation.
- The Android WebView enables no remote debugging in the release hub.
- The app uses a software-rendering-compatible WebView configuration to avoid a known tablet GPU-renderer crash path.

## Testing

Run the checks before committing or publishing:

```powershell
npm ci
npm run build
npm test
```

`npm test` runs structural checks for the MITRA Brain security boundary, Home Assistant verification flow, Lenovo-only assistant bridge, renderer recovery, voice configuration, and removal of wake-word code.

Recommended manual checks on a non-critical device:

1. Connect to Home Assistant and confirm that real entity states load.
2. Toggle one test light/plug in MITRA and verify the physical device changes.
3. Ask MITRA Brain for a device state; then turn the same test device on and off.
4. Test a failed connection and confirm MITRA reports an error rather than a successful action.
5. Test a scene and an area/room command after assigning Home Assistant Areas.
6. Connect/disconnect Gmail and verify permissions are shown by Google.
7. Import and remove a non-sensitive test photo.
8. Use push-to-talk, confirm transcription and spoken response, then restart the app.
9. Let the screen idle into standby and tap to wake it.
10. Restart the Android app and verify that authorized local configuration restores without revealing credentials.

## Git and GitHub publishing checklist

Before any push, run:

```powershell
git status --ignored
git check-ignore -v native-android/example.keystore
git check-ignore -v dist
git check-ignore -v node_modules
```

The expected result is that keys, APKs, generated builds, dependencies, and local config are ignored. Also search the tracked source for a real e-mail address, IP address, token, private key, or your name before sharing the repository.

Keep the GitHub repository **private** until you have independently reviewed every committed file. Making the repository public is a separate decision from creating it.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| MITRA cannot reach Home Assistant | Confirm the URL includes `http://` or `https://`, the tablet can reach it, the token is valid, and any private network/VPN is connected. |
| Entities are missing | Verify they are exposed in Home Assistant, supported by MITRA, and assigned to an Area if you expect them under a room. |
| AI device command fails | Check MITRA Brain smart-home permission, Home Assistant connection, exact device name, and the activity/error log. |
| Gmail sign-in does not complete | Check Google Play services, device account, network access, and OAuth/consent-screen requirements. |
| Voice does not work | Grant microphone permission and verify Android speech recognition and text-to-speech services are available. |
| Android build fails | Confirm JDK, Android SDK Platform 35, Gradle, and `npm run build` completed before the Android build. |
| Tablet becomes slow | Reduce photo count, disable unneeded integrations, close memory-heavy Android apps, and use the app's low-cost standby mode. |

## Project status and scope

MITRA is a personal-home project, not a replacement for Home Assistant. Home Assistant remains the smart-home server and source of truth; MITRA is the tablet interface and AI-assisted controller. The application is deliberately modular so the dashboard, integrations, AI model, voice layer, and visual design can evolve independently.

No license has been selected for this repository yet. Until a license is added, others do not receive permission to reuse or redistribute the code.
