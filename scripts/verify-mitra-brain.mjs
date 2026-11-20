import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const read = (path) => readFile(resolve(root, path), 'utf8')

const [brain, homeAssistant, activity, dashboard, frontend, voiceBridge, manifest, photoBridge] = await Promise.all([
  read('native-android/src/com/mitrahome/hub/MitraBrainBridge.java'),
  read('native-android/src/com/mitrahome/hub/HomeAssistantBridge.java'),
  read('native-android/src/com/mitrahome/hub/MainActivity.java'),
  read('src/features/dashboard/Dashboard.tsx'),
  read('src/lib/mitraBrain.ts'),
  read('native-android/src/com/mitrahome/hub/VoiceBridge.java'),
  read('native-android/AndroidManifest.xml'),
  read('native-android/src/com/mitrahome/hub/PhotoLibraryBridge.java'),
])

for (const name of ['get_devices', 'get_device_state', 'turn_on_device', 'turn_off_device', 'control_room']) {
  assert.match(brain, new RegExp(`function\\(\\"${name}\\"`), `${name} must be registered as a Responses API function`)
}
assert.match(brain, /"strict", true/, 'function schemas must use strict validation')
assert.match(brain, /"parallel_tool_calls", false/, 'tool calls must be sequential')
assert.match(brain, /\.put\("store", false\)/, 'Responses API storage must stay disabled')
assert.match(brain, /appendResponseOutput\(toolTranscript/, 'tool results must continue with the local transcript')
assert.doesNotMatch(brain, /\.put\("previous_response_id"/, 'tool results must not rely on stored API responses')
assert.match(brain, /"type", "web_search"/, 'web search must use the current Responses API tool')
assert.match(brain, /setPositiveButton\("Save securely"/, 'API key entry must be native')
assert.match(brain, /AndroidKeyStore/, 'API key must use Android Keystore')
assert.doesNotMatch(frontend, /sk-[A-Za-z0-9]/i, 'dashboard code must not contain an OpenAI API key')
assert.match(homeAssistant, /runVerifiedControl/, 'smart-home actions must use a verification path')
assert.match(homeAssistant, /Thread\.sleep\(450\)/, 'smart-home state must be re-read after an action')
assert.match(homeAssistant, /matchesRequestedState/, 'smart-home results must be checked against requested state')
assert.match(homeAssistant, /confirmation_required/, 'sensitive actions must require confirmation')
assert.match(activity, /if \(isLenovoHubScreen\(\)\) \{[\s\S]*addJavascriptInterface\(mitraBrainBridge, "MitraBrain"\)[\s\S]*addJavascriptInterface\(voiceBridge, "MitraVoice"\)/, 'Brain and voice must be gated to the Lenovo hub screen')
assert.match(activity, /onRenderProcessGone[\s\S]*recreateDashboard/, 'MITRA must recover if ChromeOS reclaims the WebView renderer')
assert.match(activity, /WebView\.setWebContentsDebuggingEnabled\(false\)/, 'release hub must not retain a memory-heavy debuggable WebView')
assert.match(manifest, /android\.permission\.RECORD_AUDIO/, 'voice input must request microphone access')
assert.match(manifest, /android:hardwareAccelerated="false"/, 'MITRA must avoid the Duet Vulkan WebView crash path')
assert.doesNotMatch(manifest, /FOREGROUND_SERVICE_MICROPHONE|MitraWakeService/, 'always-on wake code must not be packaged')
assert.match(manifest, /android\.speech\.RecognitionService/, 'voice input must declare its speech service query')
assert.match(voiceBridge, /SpeechRecognizer\.createSpeechRecognizer/, 'voice input must use Android speech recognition')
assert.match(voiceBridge, /TextToSpeech/, 'voice replies must use Android text-to-speech')
assert.match(voiceBridge, /startListening/, 'voice bridge must start push-to-talk listening')
assert.doesNotMatch(voiceBridge, /WakeWord|MitraWakeService/, 'voice bridge must not retain wake-word behavior')
assert.match(photoBridge, /Intent\.ACTION_OPEN_DOCUMENT_TREE/, 'Drive photos must use Android’s folder picker')
assert.match(photoBridge, /takePersistableUriPermission/, 'Drive folder permission must persist across restarts')
assert.match(photoBridge, /buildChildDocumentsUriUsingTree/, 'Drive sync must enumerate only the selected folder tree')
assert.doesNotMatch(photoBridge, /drive\.readonly|drive\.file|www\.googleapis\.com\/auth\/drive/, 'Drive photos must not request broad Google Drive OAuth scopes')
assert.match(photoBridge, /copyToPrivateLibrary\(file, sourceKey\)/, 'Drive files must be copied into MITRA private storage before display')
assert.doesNotMatch(activity, /DisplayBridge|setStandby\(isStandby\)/, 'MITRA must not use the unstable app-window brightness override')
assert.match(dashboard, /<AssistantPanel brain=\{brain\} voice=\{voice\} \/>/, 'dashboard must use the real MITRA Brain voice panel')
assert.match(dashboard, /setInterval\(\(\) => setNow\(new Date\(\)\), 60_000\)/, 'Duet dashboard clock must avoid expensive per-second full-screen repaints')

console.log('MITRA Brain structural security checks passed.')
