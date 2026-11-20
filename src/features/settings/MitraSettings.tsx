import { BellRing, Brain, CalendarDays, Camera, Check, ChevronRight, CircleUserRound, CloudSun, Cog, FolderOpen, House, KeyRound, Link2, LockKeyhole, Mail, Mic2, Palette, ShieldCheck, SlidersHorizontal, Sparkles, Volume2 } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import type { ConnectionState } from '../../lib/homeAssistant'
import type { GoogleMailBrief, GoogleMailState } from '../../lib/googleMail'
import type { DrivePhotoFolderStatus } from '../../lib/photoLibrary'
import type { MitraBrain } from '../assistant/useMitraBrain'
import type { MitraVoice } from '../assistant/useMitraVoice'
import './MitraSettings.css'

export type MitraPreferences = {
  appName: string
  profileName: string
  hour24: boolean
  theme: 'dark' | 'light' | 'system'
  accent: string
  density: 'comfortable' | 'compact'
  standbyEnabled: boolean
  standbyMinutes: number
  language: 'English'
  responseStyle: 'balanced' | 'concise' | 'detailed'
  conversationHistory: boolean
  memoryEnabled: boolean
  aiModel: string
  webSearch: boolean
  smartHomePermission: boolean
}

export type IntegrationStatus = {
  calendars: number
  reminders: number
  cameras: number
  weather: boolean
}

export const defaultMitraPreferences: MitraPreferences = {
  appName: 'MITRA',
  profileName: '',
  hour24: false,
  theme: 'dark',
  accent: '#ffbe24',
  density: 'comfortable',
  standbyEnabled: true,
  standbyMinutes: 5,
  language: 'English',
  responseStyle: 'balanced',
  conversationHistory: true,
  memoryEnabled: true,
  aiModel: 'gpt-5-mini',
  webSearch: false,
  smartHomePermission: true,
}

type SettingsSection = 'general' | 'assistant' | 'smart-home' | 'voice' | 'integrations' | 'privacy'

type Props = {
  preferences: MitraPreferences
  onPreferencesChange: (change: Partial<MitraPreferences>) => void
  connection: ConnectionState
  message: string
  deviceCount: number
  roomCount: number
  sceneCount: number
  integrations: IntegrationStatus
  googleMail: { state: GoogleMailState; message: string; email: string; messages: GoogleMailBrief[]; connect: () => void; refresh: () => void; disconnect: () => void }
  photoLibrary: {
    available: boolean
    count: number
    choose: () => void
    drive: DrivePhotoFolderStatus
    connectDriveAccount: () => void
    disconnectDriveAccount: () => void
    chooseDriveFolder: () => void
    refreshDriveFolder: () => void
    disconnectDriveFolder: () => void
  }
  brain: MitraBrain
  voice: MitraVoice
  onOpenHomeAssistant: () => void
  onNavigate: (view: 'home' | 'rooms' | 'scenes') => void
}

const sections: Array<{ id: SettingsSection; label: string; icon: typeof Cog }> = [
  { id: 'general', label: 'General & Appearance', icon: Cog },
  { id: 'assistant', label: 'AI Assistant', icon: Brain },
  { id: 'smart-home', label: 'Smart Home', icon: House },
  { id: 'voice', label: 'Voice & Notifications', icon: Mic2 },
  { id: 'integrations', label: 'Integrations', icon: Link2 },
  { id: 'privacy', label: 'Privacy & System', icon: LockKeyhole },
]

export function MitraSettings({ preferences, onPreferencesChange, connection, message, deviceCount, roomCount, sceneCount, integrations, googleMail, brain, voice, photoLibrary, onOpenHomeAssistant, onNavigate }: Props) {
  const [section, setSection] = useState<SettingsSection>('general')
  const update = (change: Partial<MitraPreferences>) => onPreferencesChange(change)

  return <section className="settings-shell" aria-label="MITRA Settings">
    <nav className="settings-nav" aria-label="MITRA settings sections">
      <p>MITRA SETTINGS</p>
      {sections.map(({ id, label, icon: Icon }) => <button key={id} className={section === id ? 'active' : ''} onClick={() => setSection(id)}><Icon size={19} /><span>{label}</span><ChevronRight size={16} /></button>)}
    </nav>
    <div className="settings-panel">
      {section === 'general' && <General preferences={preferences} update={update} />}
      {section === 'assistant' && <Assistant preferences={preferences} update={update} brain={brain} />}
      {section === 'smart-home' && <SmartHome connection={connection} message={message} deviceCount={deviceCount} roomCount={roomCount} sceneCount={sceneCount} onOpenHomeAssistant={onOpenHomeAssistant} onNavigate={onNavigate} />}
      {section === 'voice' && <Voice voice={voice} />}
      {section === 'integrations' && <Integrations data={integrations} googleMail={googleMail} photoLibrary={photoLibrary} />}
      {section === 'privacy' && <Privacy connection={connection} message={message} brain={brain} />}
    </div>
  </section>
}

function General({ preferences, update }: { preferences: MitraPreferences; update: (change: Partial<MitraPreferences>) => void }) {
  return <SettingsPage eyebrow="PERSONALIZE MITRA" title="General & Appearance" description="These settings stay on this Duet and apply immediately.">
    <SettingsGroup title="Identity">
      <SettingRow title="App name" hint="Shown on the home hub and Android app."><input value={preferences.appName} maxLength={24} onChange={(event) => update({ appName: event.target.value || 'MITRA' })} /></SettingRow>
      <SettingRow title="Your name" hint="Used in the greeting. A future linked Google profile can fill this automatically."><input value={preferences.profileName} placeholder="Your name" maxLength={48} onChange={(event) => update({ profileName: event.target.value })} /></SettingRow>
      <SettingRow title="Language" hint="More languages can be added with an assistant language pack."><select value={preferences.language} onChange={(event) => update({ language: event.target.value as 'English' })}><option>English</option></select></SettingRow>
    </SettingsGroup>
    <SettingsGroup title="Display">
      <SettingRow title="Time format" hint="Changes the clock on the dashboard."><Segmented current={preferences.hour24 ? '24-hour' : '12-hour'} options={['12-hour', '24-hour']} onChange={(value) => update({ hour24: value === '24-hour' })} /></SettingRow>
      <SettingRow title="Theme" hint="Changes the glass treatment while preserving your background."><select value={preferences.theme} onChange={(event) => update({ theme: event.target.value as MitraPreferences['theme'] })}><option value="dark">Dark</option><option value="light">Light</option><option value="system">Use device setting</option></select></SettingRow>
      <SettingRow title="Accent color" hint="Used by active controls and highlights."><div className="accent-picker">{['#ffbe24', '#7c8cff', '#e778c4', '#53d69a'].map((color) => <button key={color} className={preferences.accent === color ? 'selected' : ''} style={{ background: color }} onClick={() => update({ accent: color })} aria-label={`Choose ${color} accent`}>{preferences.accent === color && <Check size={15} />}</button>)}</div></SettingRow>
      <SettingRow title="Dashboard layout" hint="Controls the spacing of real device cards."><Segmented current={preferences.density === 'compact' ? 'Compact' : 'Comfortable'} options={['Comfortable', 'Compact']} onChange={(value) => update({ density: value === 'Compact' ? 'compact' : 'comfortable' })} /></SettingRow>
      <SettingRow title="Standby display" hint="After no touch or keyboard activity, MITRA shows a calm full-screen clock until you touch it again."><Toggle checked={preferences.standbyEnabled} onChange={(standbyEnabled) => update({ standbyEnabled })} /></SettingRow>
      <SettingRow title="Standby after" hint="The Duet stays on and switches to MITRA standby after this idle time."><select value={preferences.standbyMinutes} disabled={!preferences.standbyEnabled} onChange={(event) => update({ standbyMinutes: Number(event.target.value) })}><option value={1}>1 minute</option><option value={3}>3 minutes</option><option value={5}>5 minutes</option><option value={10}>10 minutes</option></select></SettingRow>
    </SettingsGroup>
  </SettingsPage>
}

function Assistant({ preferences, update, brain }: { preferences: MitraPreferences; update: (change: Partial<MitraPreferences>) => void; brain: MitraBrain }) {
  if (!brain.available) return <SettingsPage eyebrow="LENOVO DUET ONLY" title="AI Assistant" description="MITRA Brain is intentionally unavailable in browser or phone control views.">
    <SettingsGroup title="MITRA Brain">
      <SettingRow title="Private chat" hint="Chat, memories, API configuration, and AI controls are restricted to the Lenovo Duet native MITRA app."><Status label="Lenovo only" tone="good" /></SettingRow>
    </SettingsGroup>
  </SettingsPage>
  return <SettingsPage eyebrow="YOUR ASSISTANT" title="AI Assistant" description="MITRA Brain runs only in this Lenovo Duet app. OpenAI and Home Assistant keys never enter dashboard code.">
    <SettingsGroup title="AI connection">
      <SettingRow title="AI provider" hint="MITRA Brain currently uses the OpenAI Responses API through the trusted native bridge."><Status label={brain.status.provider || 'OpenAI'} tone="good" /></SettingRow>
      <SettingRow title="OpenAI API configuration" hint={brain.status.configured ? 'Your key is encrypted with Android Keystore on this Duet. It is not shared with Home Assistant.' : 'The key is entered in a native Android password dialog, never in a dashboard field.'}><Status label={brain.status.configured ? 'Securely configured' : 'Not configured'} tone={brain.status.configured ? 'good' : 'warning'} />{brain.status.configured ? <button className="settings-action" onClick={brain.removeOpenAi}>Remove key</button> : <button className="settings-action" onClick={brain.connectOpenAi}>Connect OpenAI</button>}</SettingRow>
      <SettingRow title="AI model" hint="Use a model available to your OpenAI API project. The default can be changed any time."><input value={preferences.aiModel} maxLength={80} onChange={(event) => update({ aiModel: event.target.value || 'gpt-5-mini' })} placeholder="gpt-5-mini" /></SettingRow>
    </SettingsGroup>
    <SettingsGroup title="Response & access preferences">
      <SettingRow title="Response style" hint="Applied to new MITRA conversations."><select value={preferences.responseStyle} onChange={(event) => update({ responseStyle: event.target.value as MitraPreferences['responseStyle'] })}><option value="balanced">Balanced</option><option value="concise">Concise</option><option value="detailed">Detailed</option></select></SettingRow>
      <SettingRow title="Web search" hint="Allow MITRA to use OpenAI web search only when current information is needed. Web answers include source links."><Toggle checked={preferences.webSearch} onChange={(checked) => update({ webSearch: checked })} /></SettingRow>
      <SettingRow title="Conversation history" hint="Keeps recent chat only on this Lenovo Duet. Turn off to clear it."><Toggle checked={preferences.conversationHistory} onChange={(checked) => update({ conversationHistory: checked })} /></SettingRow>
      <SettingRow title="Long-term memory" hint="Only user-approved memories are stored locally. You can review or delete every item below."><Toggle checked={preferences.memoryEnabled} onChange={(checked) => update({ memoryEnabled: checked })} /></SettingRow>
      <SettingRow title="Smart-home permissions" hint="When enabled, MITRA can request registered Home Assistant actions. Locks and covers always require confirmation."><Toggle checked={preferences.smartHomePermission} onChange={(checked) => update({ smartHomePermission: checked })} /></SettingRow>
    </SettingsGroup>
    <MemoryManager brain={brain} enabled={preferences.memoryEnabled} />
  </SettingsPage>
}

function MemoryManager({ brain, enabled }: { brain: MitraBrain; enabled: boolean }) {
  const [draft, setDraft] = useState('')
  const save = () => { brain.addMemory(draft); setDraft('') }
  return <SettingsGroup title="Personal memory">
    <SettingRow title="Add a memory" hint="Save only facts or preferences you want MITRA to use later. This stays on the Lenovo Duet."><input value={draft} disabled={!enabled} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); save() } }} placeholder={enabled ? 'Example: I prefer concise answers.' : 'Turn on Long-term memory first'} /><button className="settings-action" disabled={!enabled || !draft.trim()} onClick={save}>Save memory</button></SettingRow>
    {brain.memories.length ? <div className="memory-list">{brain.memories.map((memory) => <div key={memory.id}><span>{memory.text}</span><button className="settings-action" onClick={() => brain.deleteMemory(memory.id)}>Delete</button></div>)}<button className="settings-action memory-clear" onClick={brain.clearMemories}>Delete all memories</button></div> : <div className="settings-empty">No long-term memories are saved.</div>}
  </SettingsGroup>
}

function SmartHome({ connection, message, deviceCount, roomCount, sceneCount, onOpenHomeAssistant, onNavigate }: Omit<Props, 'preferences' | 'onPreferencesChange' | 'integrations' | 'googleMail' | 'brain' | 'voice' | 'photoLibrary'>) {
  const isConnected = connection === 'connected'
  return <SettingsPage eyebrow="HOME CONTROL" title="Smart Home" description="Live status comes directly from your Home Assistant hub.">
    <SettingsGroup title="Hub connection">
      <SettingRow title="Home Assistant" hint={message}><Status label={isConnected ? 'Connected' : 'Needs attention'} tone={isConnected ? 'good' : 'warning'} /><button className="settings-action" onClick={onOpenHomeAssistant}>{isConnected ? 'Change connection' : 'Connect'}</button></SettingRow>
      <SettingRow title="Device synchronization" hint="MITRA refreshes Home Assistant device states every few seconds."><Status label={isConnected ? `${deviceCount} live devices` : 'Waiting for hub'} tone={isConnected ? 'good' : 'muted'} /></SettingRow>
    </SettingsGroup>
    <SettingsGroup title="Home organization">
      <SettingRow title="Room management" hint="Assign Home Assistant Areas to put devices in the correct room."><Status label={`${roomCount} rooms shown`} tone="muted" /><button className="settings-action" onClick={() => onNavigate('rooms')}>Open rooms</button></SettingRow>
      <SettingRow title="Scenes & automations" hint="Scenes appear in MITRA once they are created in Home Assistant. Automations keep running on the hub."><Status label={`${sceneCount} scenes found`} tone={sceneCount ? 'good' : 'muted'} /><button className="settings-action" onClick={() => onNavigate('scenes')}>Open scenes</button></SettingRow>
      <SettingRow title="Device management" hint="Remove or restore dashboard cards without deleting your Home Assistant devices."><button className="settings-action" onClick={() => onNavigate('home')}>Manage devices</button></SettingRow>
    </SettingsGroup>
  </SettingsPage>
}

function Voice({ voice }: { voice: MitraVoice }) {
  const isListening = voice.status.state === 'listening'
  const isSpeaking = voice.status.state === 'speaking'
  const ready = voice.available && voice.status.state !== 'error'
  return <SettingsPage eyebrow="PUSH-TO-TALK" title="Voice & Notifications" description="Tap to speak to MITRA. It listens only after you choose the microphone—there is no background recording.">
    <SettingsGroup title="Voice">
      <SettingRow title="Microphone" hint={voice.status.message}><Status label={isListening ? 'Listening now' : ready ? 'Ready' : voice.available ? 'Needs attention' : 'Lenovo only'} tone={isListening || ready ? 'good' : 'warning'} /><button className="settings-action" onClick={voice.toggleListening} disabled={!voice.available}>{isSpeaking ? 'Stop speaking' : isListening ? 'Stop listening' : 'Test microphone'}</button></SettingRow>
      <SettingRow title="Voice commands" hint="Ask normal questions or say a device command. MITRA uses the same verified Home Assistant controls as typed chat."><Status label={voice.available ? 'Enabled on Lenovo' : 'Unavailable'} tone={voice.available ? 'good' : 'muted'} /></SettingRow>
      <SettingRow title="Assistant voice" hint="MITRA replies using the Duet’s built-in text-to-speech voice. Device volume controls the loudness."><Status label={voice.available ? 'Built in' : 'Unavailable'} tone={voice.available ? 'good' : 'muted'} /></SettingRow>
      <SettingRow title="Wake word" hint="Always-on wake listening is removed. MITRA listens only when you tap the microphone."><Status label="Removed" tone="muted" /></SettingRow>
    </SettingsGroup>
    <SettingsGroup title="Notifications">
      <SettingRow title="Notification preferences" hint="No notification service is connected to MITRA yet."><Status label="Not configured" tone="muted" /></SettingRow>
      <SettingRow title="Quiet hours" hint="Available after notifications are connected."><Status label="Not configured" tone="muted" /></SettingRow>
    </SettingsGroup>
  </SettingsPage>
}

function Integrations({ data, googleMail, photoLibrary }: { data: IntegrationStatus; googleMail: Props['googleMail']; photoLibrary: Props['photoLibrary'] }) {
  const gmailConnected = googleMail.state === 'connected'
  const gmailConnecting = googleMail.state === 'authorizing'
  const driveSignedIn = Boolean(photoLibrary.drive.accountEmail)
  const driveConnected = photoLibrary.drive.state === 'connected'
  const driveBusy = photoLibrary.drive.state === 'authorizing' || photoLibrary.drive.state === 'selecting' || photoLibrary.drive.state === 'syncing'
  const drivePhotoLabel = `${photoLibrary.drive.count} Drive photo${photoLibrary.drive.count === 1 ? '' : 's'}`
  return <SettingsPage eyebrow="CONNECTED SERVICES" title="Integrations" description="Only services with a real account or Home Assistant connection are marked as live.">
    <SettingsGroup title="Account & productivity">
      <IntegrationRow icon={<Mail />} title="Gmail" detail={gmailConnected ? `${googleMail.email || 'Google account'} is connected directly to MITRA. ${googleMail.message}` : 'Google mail connects directly to MITRA and is never shared with Home Assistant.'} status={gmailConnected ? 'Connected' : gmailConnecting ? 'Waiting for Google' : 'Not connected'} good={gmailConnected} action={gmailConnected ? <><button className="settings-action" onClick={googleMail.refresh}>Refresh</button><button className="settings-action" onClick={googleMail.disconnect}>Disconnect</button></> : <button className="settings-action" disabled={gmailConnecting} onClick={googleMail.connect}>{gmailConnecting ? 'Opening Google…' : 'Connect Google'}</button>} />
      <IntegrationRow icon={<CalendarDays />} title="Calendar" detail={data.calendars ? `${data.calendars} Home Assistant calendar${data.calendars === 1 ? '' : 's'} detected.` : 'No Home Assistant calendar detected yet.'} status={data.calendars ? 'Available in hub' : 'Not connected'} good={Boolean(data.calendars)} />
      <IntegrationRow icon={<BellRing />} title="Reminders" detail={data.reminders ? `${data.reminders} Home Assistant to-do list${data.reminders === 1 ? '' : 's'} detected.` : 'No reminders source detected yet.'} status={data.reminders ? 'Available in hub' : 'Not connected'} good={Boolean(data.reminders)} />
    </SettingsGroup>
    <SettingsGroup title="Home & media">
      <IntegrationRow icon={<CloudSun />} title="Weather" detail={data.weather ? 'Live weather is coming from Home Assistant.' : 'Add a weather integration in Home Assistant to show live conditions.'} status={data.weather ? 'Connected' : 'Not connected'} good={data.weather} />
      <IntegrationRow icon={<Camera />} title="Camera feeds" detail={data.cameras ? `${data.cameras} Home Assistant camera${data.cameras === 1 ? '' : 's'} detected. Feed controls are the next safe step.` : 'No Home Assistant camera entities are detected.'} status={data.cameras ? 'Ready to add' : 'Not connected'} good={Boolean(data.cameras)} />
      <IntegrationRow icon={<CircleUserRound />} title="Google Drive account" detail={driveSignedIn ? `${photoLibrary.drive.accountEmail} is connected only for choosing your photo folder. MITRA does not request broad Google Drive access.` : photoLibrary.drive.state === 'error' ? photoLibrary.drive.message : 'Sign in to the Google account that contains your photos. This is separate from Gmail and Home Assistant.'} status={driveSignedIn ? 'Signed in' : photoLibrary.drive.state === 'authorizing' ? 'Waiting for Google' : photoLibrary.available ? 'Not connected' : 'Lenovo only'} good={driveSignedIn} action={photoLibrary.available ? (driveSignedIn ? <button className="settings-action" onClick={photoLibrary.disconnectDriveAccount}>Disconnect</button> : <button className="settings-action" disabled={driveBusy} onClick={photoLibrary.connectDriveAccount}>{photoLibrary.drive.state === 'authorizing' ? 'Opening Google…' : 'Sign in with Google'}</button>) : undefined} />
      <IntegrationRow icon={<FolderOpen />} title="Google Drive photo folder" detail={!driveSignedIn ? 'First sign in to Google above. Then choose exactly one folder from that Google Drive account.' : driveConnected ? `${drivePhotoLabel} copied privately from ${photoLibrary.drive.folderName || 'your selected folder'}. MITRA can read only this chosen folder, not the rest of your Drive.` : photoLibrary.drive.state === 'error' ? photoLibrary.drive.message : 'Choose the one Google Drive folder with your photos. MITRA receives a persistent read grant only to that folder and never shares photos with Home Assistant.'} status={driveConnected ? 'Connected' : driveBusy && photoLibrary.drive.state !== 'authorizing' ? 'Syncing' : driveSignedIn ? 'Choose a folder' : 'Sign in first'} good={driveConnected || driveSignedIn} action={photoLibrary.available && driveSignedIn ? (driveConnected ? <><button className="settings-action" onClick={photoLibrary.refreshDriveFolder}>Sync now</button><button className="settings-action" onClick={photoLibrary.disconnectDriveFolder}>Remove folder</button></> : <button className="settings-action" disabled={driveBusy} onClick={photoLibrary.chooseDriveFolder}>{photoLibrary.drive.state === 'selecting' ? 'Opening folder…' : 'Choose folder'}</button>) : undefined} />
      <IntegrationRow icon={<CircleUserRound />} title="Personal photos" detail={photoLibrary.available ? (photoLibrary.count ? `${photoLibrary.count} private photo${photoLibrary.count === 1 ? '' : 's'} saved on this Lenovo. Home Assistant cannot access them.` : 'Add pictures from the Lenovo photo picker. To use iPhone photos, first send or download them to this Lenovo.') : 'Personal photos are available only in the native MITRA app on the Lenovo.'} status={photoLibrary.count ? `${photoLibrary.count} saved` : photoLibrary.available ? 'Ready to add' : 'Lenovo only'} good={photoLibrary.count > 0} action={photoLibrary.available ? <button className="settings-action" onClick={photoLibrary.choose}>Add photos</button> : undefined} />
    </SettingsGroup>
  </SettingsPage>
}

function Privacy({ connection, message, brain }: Pick<Props, 'connection' | 'message' | 'brain'>) {
  return <SettingsPage eyebrow="SECURITY & MAINTENANCE" title="Privacy & System" description="MITRA keeps your hub connection local to this Duet unless you choose a remote-access service.">
    <SettingsGroup title="Security">
      <SettingRow title="Login & authentication" hint="Google mail remains a separate direct MITRA connection. The AI does not receive Gmail data by default."><Status label="Local controls" tone="good" /></SettingRow>
      <SettingRow title="Home Assistant token" hint="The Home Assistant connection token is encrypted with this Duet’s Android Keystore."><Status label="Encrypted on device" tone="good" /></SettingRow>
      <SettingRow title="OpenAI key" hint="The key is stored only in Android Keystore-backed local storage and is never exposed to dashboard JavaScript."><Status label={brain.status.configured ? 'Encrypted on device' : 'Not configured'} tone={brain.status.configured ? 'good' : 'muted'} /></SettingRow>
      <SettingRow title="AI permissions" hint="Only registered Home Assistant entities are available to MITRA. Locks and covers require a separate confirmation."><Status label={brain.status.smartHome ? 'Controlled access' : 'Disabled'} tone={brain.status.smartHome ? 'good' : 'muted'} /></SettingRow>
      <SettingRow title="Data retention" hint="Conversation history and user-approved memories stay on this Lenovo Duet. You can remove them in AI Assistant settings."><Status label="Local controls" tone="good" /></SettingRow>
    </SettingsGroup>
    <SettingsGroup title="System">
      <SettingRow title="Application status" hint={message}><Status label={connection === 'connected' ? 'Hub online' : 'Hub not connected'} tone={connection === 'connected' ? 'good' : 'warning'} /></SettingRow>
      <SettingRow title="Logs & diagnostics" hint="MITRA records AI, tool, confirmation, and error events locally without saving API keys or message contents."><Status label={`${brain.activity.length} local events`} tone="muted" /><button className="settings-action" onClick={brain.clearActivity} disabled={!brain.activity.length}>Clear logs</button></SettingRow>
      {brain.activity.length > 0 && <div className="activity-list">{brain.activity.slice(0, 6).map((item, index) => <div key={`${item.time}-${index}`}><span>{new Date(item.time).toLocaleString()}</span><strong>{item.type.replace('-', ' ')}</strong><small>{item.detail} · {item.status}</small></div>)}</div>}
      <SettingRow title="Backup & restore" hint="Export and restore are not set up yet. Do not rely on this app as your only Home Assistant backup."><Status label="Not configured" tone="warning" /></SettingRow>
      <SettingRow title="Advanced configuration" hint="Matter pairing and hub configuration are managed through Home Assistant."><Status label="Home Assistant managed" tone="muted" /></SettingRow>
    </SettingsGroup>
  </SettingsPage>
}

function SettingsPage({ eyebrow, title, description, children }: { eyebrow: string; title: string; description: string; children: ReactNode }) {
  return <><header className="settings-header"><p>{eyebrow}</p><h2>{title}</h2><span>{description}</span></header>{children}</>
}

function SettingsGroup({ title, children }: { title: string; children: ReactNode }) { return <section className="settings-group"><h3>{title}</h3>{children}</section> }
function SettingRow({ title, hint, children }: { title: string; hint: string; children: ReactNode }) { return <div className="setting-row"><div><strong>{title}</strong><small>{hint}</small></div><div className="setting-control">{children}</div></div> }
function Status({ label, tone }: { label: string; tone: 'good' | 'warning' | 'muted' }) { return <span className={`setting-status ${tone}`}><i />{label}</span> }
function Toggle({ checked, onChange, disabled = false }: { checked: boolean; onChange: (next: boolean) => void; disabled?: boolean }) { return <button className={`settings-toggle ${checked ? 'on' : ''}`} role="switch" aria-checked={checked} disabled={disabled} onClick={() => onChange(!checked)}><i /></button> }
function Segmented({ current, options, onChange }: { current: string; options: string[]; onChange: (value: string) => void }) { return <div className="segmented">{options.map((option) => <button key={option} className={current === option ? 'active' : ''} onClick={() => onChange(option)}>{option}</button>)}</div> }
function IntegrationRow({ icon, title, detail, status, good = false, action }: { icon: ReactNode; title: string; detail: string; status: string; good?: boolean; action?: ReactNode }) { return <div className="integration-row"><span className="integration-icon">{icon}</span><div><strong>{title}</strong><small>{detail}</small></div><div className="integration-state"><Status label={status} tone={good ? 'good' : 'muted'} />{action && <span className="integration-actions">{action}</span>}</div></div> }
