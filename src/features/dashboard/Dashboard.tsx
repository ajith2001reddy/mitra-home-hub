import { BedDouble, Bell, CalendarDays, ChevronLeft, ChevronRight, CloudSun, Home, Image as ImageIcon, Layers3, Lightbulb, Lock, Mail, Monitor, PlugZap, ScanLine, Settings2, Speaker, Thermometer, Wifi, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type CSSProperties, type ReactNode } from 'react'
import { useHomeAssistant } from '../homeassistant/useHomeAssistant'
import { MatterQrScanner } from '../matter/MatterQrScanner'
import { defaultMitraPreferences, MitraSettings, type MitraPreferences } from '../settings/MitraSettings'
import { useGoogleMail } from '../google/useGoogleMail'
import { AssistantPanel } from '../assistant/AssistantPanel'
import { useMitraBrain } from '../assistant/useMitraBrain'
import { useMitraVoice } from '../assistant/useMitraVoice'
import { usePhotoLibrary } from '../photos/usePhotoLibrary'
import type { PersonalPhoto } from '../../lib/photoLibrary'
import type { Scene, SmartDevice } from './data'
import './MitraMail.css'

type LiveDevice = SmartDevice & { controllable: boolean }
type View = 'home' | 'rooms' | 'scenes' | 'inbox' | 'settings'
type CalendarCell = { date: Date; inCurrentMonth: boolean; isToday: boolean }

const hiddenKey = 'jarvis-hidden-accessories'
const preferencesKey = 'mitra-preferences'
const selectedPhotoKey = 'mitra-selected-photo'

function timeGreeting(date: Date) {
  const hour = date.getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

function loadHiddenDevices() {
  try {
    const stored = JSON.parse(localStorage.getItem(hiddenKey) || '[]')
    return Array.isArray(stored) ? stored.filter((id): id is string => typeof id === 'string') : []
  } catch { return [] }
}

function loadPreferences(): MitraPreferences {
  try {
    const stored = JSON.parse(localStorage.getItem(preferencesKey) || '{}') as Partial<MitraPreferences>
    return { ...defaultMitraPreferences, ...stored }
  } catch { return defaultMitraPreferences }
}

export function Dashboard() {
  const [now, setNow] = useState(() => new Date())
  const [activeView, setActiveView] = useState<View>('home')
  const [setupOpen, setSetupOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const [calendarOffset, setCalendarOffset] = useState(0)
  const photoLibrary = usePhotoLibrary()
  const [browserPhotos, setBrowserPhotos] = useState<PersonalPhoto[]>([])
  const [selectedPhotoId, setSelectedPhotoId] = useState(() => localStorage.getItem(selectedPhotoKey) || '')
  const [isStandby, setIsStandby] = useState(false)
  const lastActivity = useRef(Date.now())
  const photoInput = useRef<HTMLInputElement>(null)
  const [hiddenIds, setHiddenIds] = useState<string[]>(loadHiddenDevices)
  const [preferences, setPreferences] = useState<MitraPreferences>(loadPreferences)
  const home = useHomeAssistant()
  const googleMail = useGoogleMail()
  const brain = useMitraBrain(preferences)
  const voice = useMitraVoice({ onTranscript: (text) => brain.send(text, { speak: true }) })
  const registerActivity = useCallback(() => {
    lastActivity.current = Date.now()
    setIsStandby(false)
  }, [])

  useEffect(() => {
    if (!brain.voiceReply) return
    voice.speak(brain.voiceReply)
    brain.consumeVoiceReply()
  }, [brain.voiceReply, brain.consumeVoiceReply, voice.speak])

  useEffect(() => {
    // The dashboard shows hours and minutes, not seconds. Updating every
    // second repainted the full glass UI on the Duet's software renderer.
    const timer = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  useEffect(() => {
    if (!preferences.standbyEnabled) {
      setIsStandby(false)
      return
    }
    const events: Array<keyof WindowEventMap> = ['pointerdown', 'touchstart', 'keydown', 'wheel', 'focus']
    events.forEach((event) => window.addEventListener(event, registerActivity, { passive: true, capture: true }))
    const timer = window.setInterval(() => {
      if (Date.now() - lastActivity.current >= preferences.standbyMinutes * 60_000) setIsStandby(true)
    }, 1000)
    return () => {
      events.forEach((event) => window.removeEventListener(event, registerActivity, true))
      window.clearInterval(timer)
    }
  }, [preferences.standbyEnabled, preferences.standbyMinutes, registerActivity])
  useEffect(() => { localStorage.setItem(hiddenKey, JSON.stringify(hiddenIds)) }, [hiddenIds])
  useEffect(() => {
    localStorage.setItem(preferencesKey, JSON.stringify(preferences))
    document.title = `${preferences.appName || 'MITRA'} Home`
  }, [preferences])
  useEffect(() => {
    if (!googleMail.profileName || googleMail.state !== 'connected') return
    setPreferences((current) => current.profileName === googleMail.profileName ? current : { ...current, profileName: googleMail.profileName })
  }, [googleMail.profileName, googleMail.state])

  const photos = photoLibrary.available ? photoLibrary.photos : browserPhotos
  const selectedPhoto = photos.find((photo) => photo.id === selectedPhotoId) || photos[0]
  useEffect(() => {
    if (!selectedPhoto) {
      if (selectedPhotoId) setSelectedPhotoId('')
      return
    }
    if (selectedPhoto.id !== selectedPhotoId) setSelectedPhotoId(selectedPhoto.id)
  }, [selectedPhoto, selectedPhotoId])
  useEffect(() => {
    if (selectedPhotoId) localStorage.setItem(selectedPhotoKey, selectedPhotoId)
    else localStorage.removeItem(selectedPhotoKey)
  }, [selectedPhotoId])

  const devices = home.devices.filter((device) => !hiddenIds.includes(device.id))
  const activeDevices = devices.filter((device) => device.active).length
  const rooms = useMemo(() => {
    const grouped = new Map<string, LiveDevice[]>()
    devices.forEach((device) => grouped.set(device.room, [...(grouped.get(device.room) || []), device]))
    return [...grouped.entries()]
  }, [devices])
  const hideDevice = (id: string) => setHiddenIds((current) => current.includes(id) ? current : [...current, id])
  const formattedTime = now.toLocaleTimeString([], { hour: preferences.hour24 ? '2-digit' : 'numeric', minute: '2-digit', hour12: !preferences.hour24 })
  const formattedDate = now.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })
  const calendarMonth = useMemo(() => new Date(now.getFullYear(), now.getMonth() + calendarOffset, 1), [now, calendarOffset])
  const calendar = useMemo(() => createCalendar(calendarMonth, now), [calendarMonth, now])
  const calendarMonthLabel = calendarMonth.toLocaleDateString([], { month: 'short', year: 'numeric' })
  const displayName = preferences.profileName.trim() || 'Your home'
  const pageStyle = { '--mitra-accent': preferences.accent, '--mitra-personal-photo': selectedPhoto ? `url("${selectedPhoto.url}")` : undefined } as CSSProperties
  const changePreferences = (change: Partial<MitraPreferences>) => setPreferences((current) => ({ ...current, ...change }))
  const openView = (view: 'home' | 'rooms' | 'scenes') => { setEditing(false); setActiveView(view) }
  const importPhotos = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files || []).filter((file) => file.type.startsWith('image/'))
    if (!files.length) return
    const next = files.map((file, index) => ({ id: `${Date.now()}-${index}-${file.name}`, url: URL.createObjectURL(file), createdAt: Date.now() }))
    setBrowserPhotos((current) => [...next, ...current])
    setSelectedPhotoId(next[0].id)
    event.target.value = ''
  }
  const choosePhotos = () => {
    if (photoLibrary.available) photoLibrary.choose()
    else photoInput.current?.click()
  }
  const removePhoto = (id: string) => {
    if (photoLibrary.available) photoLibrary.remove(id)
    else setBrowserPhotos((current) => current.filter((photo) => photo.id !== id))
  }

  return <main className={`mountain-hub ${activeView === 'home' ? 'mountain-hub--reference-home' : ''} ${selectedPhoto ? 'mountain-hub--has-personal-photo' : ''}`} data-theme={preferences.theme} data-density={preferences.density} style={pageStyle}>
    <input ref={photoInput} className="reference-photo-picker" type="file" accept="image/*" multiple onChange={importPhotos} aria-label="Choose photos for the MITRA display" />
    <aside className="hub-sidebar">
      <button className="hub-brand" onClick={() => openView('home')} aria-label={`${preferences.appName} home`}><strong>{preferences.appName}</strong><span>Home Hub</span></button>
      <nav className="hub-nav" aria-label="Dashboard navigation">
        <NavButton active={activeView === 'home'} icon={<Home size={24} />} label="Home" onClick={() => openView('home')} />
        <NavButton active={activeView === 'rooms'} icon={<BedDouble size={24} />} label="Rooms" onClick={() => openView('rooms')} />
        <NavButton active={activeView === 'scenes'} icon={<Layers3 size={24} />} label="Scenes" onClick={() => openView('scenes')} />
        <NavButton active={activeView === 'inbox'} icon={<Mail size={24} />} label="Inbox" onClick={() => { setEditing(false); setActiveView('inbox') }} />
      </nav>
      <button className={`hub-settings ${activeView === 'settings' ? 'active' : ''}`} onClick={() => setActiveView('settings')}><Settings2 size={24} /><span>Settings</span></button>
    </aside>

    <section className="hub-main">
      <header className={`hub-header ${activeView === 'home' ? 'hub-reference-header' : ''}`}>
        {activeView === 'home' ? <>
          <div className="reference-top-time"><time>{formattedTime}</time><i /><span>{formattedDate}</span></div>
          <div className="reference-top-status">{home.weather && <span className="reference-top-weather"><CloudSun size={30} /><strong>{home.weather.temperature}</strong></span>}<span className={`reference-top-network ${home.connection}`} title={home.message}><Wifi size={25} /><i /></span></div>
        </> : <><div><p>{activeView === 'settings' ? `${preferences.appName.toUpperCase()} SETTINGS` : timeGreeting(now)}</p><h1>{activeView === 'settings' ? 'Personalize MITRA' : displayName}</h1><span>{activeView === 'settings' ? 'Your MITRA preferences and real connection status.' : home.connection === 'connected' ? `${activeDevices} active · ${devices.length} accessories` : home.message}</span></div><div className={`hub-network ${home.connection}`}><i />{home.connection === 'connected' ? 'Hub online' : 'Connecting' }<time>{formattedTime}</time></div></>}
      </header>

      {activeView === 'home' && <section className="hub-section hub-home-screen">
        <div className="reference-home-grid">
          <div className="reference-center-column">
            <PhotoStage photos={photos} selectedId={selectedPhoto?.id || ''} onSelect={setSelectedPhotoId} onChoose={choosePhotos} onRemove={removePhoto} nativeLibrary={photoLibrary.available} />
            <div className="reference-lower-grid">
              <CalendarPanel label={calendarMonthLabel} calendar={calendar} onPrevious={() => setCalendarOffset((value) => value - 1)} onNext={() => setCalendarOffset((value) => value + 1)} />
              <NotificationPanel messages={googleMail.messages} connected={googleMail.state === 'connected'} onViewAll={() => { setEditing(false); setActiveView('inbox') }} />
            </div>
          </div>
          <aside className="reference-device-panel" aria-label="Live Home Assistant accessories">
            <header><div><small>HOME ASSISTANT</small><strong>Accessories</strong></div><div className="reference-device-actions"><button onClick={() => openView('rooms')}>All {devices.length}</button><button onClick={() => setEditing((value) => !value)}>{editing ? 'Done' : 'Manage'}</button></div></header>
            {devices.length ? <div className={`reference-device-list ${devices.length > 4 ? 'many' : ''}`}>{devices.map((device) => <DeviceCard key={device.id} device={device} editing={editing} onToggle={() => home.toggle(device)} onRemove={() => hideDevice(device.id)} />)}</div> : <EmptyState onConnect={() => setSetupOpen(true)} />}
            {editing && hiddenIds.length > 0 && <button className="hub-restore" onClick={() => setHiddenIds([])}>Restore hidden accessories</button>}
          </aside>
        </div>
        <AssistantPanel brain={brain} voice={voice} />
      </section>}

      {activeView === 'rooms' && <section className="hub-section">
        <div className="hub-section-title"><h2>Rooms</h2><span>{rooms.length} available</span></div>
        {rooms.length ? <div className="hub-room-stack">{rooms.map(([room, roomDevices]) => <section className="hub-room" key={room}><header><h3>{titleCase(room)}</h3><span>{roomDevices.filter((device) => device.active).length} active</span></header><div className="hub-device-grid">{roomDevices.map((device) => <DeviceCard key={device.id} device={device} editing={editing} onToggle={() => home.toggle(device)} onRemove={() => hideDevice(device.id)} />)}</div></section>)}</div> : <EmptyState onConnect={() => setSetupOpen(true)} />}
      </section>}

      {activeView === 'scenes' && <section className="hub-section"><div className="hub-section-title"><h2>Scenes</h2><span>{home.scenes.length} available</span></div>{home.scenes.length ? <div className="hub-scene-grid">{home.scenes.map((scene) => <SceneCard key={scene.id} scene={scene} onActivate={() => home.activateScene(scene)} />)}</div> : <div className="hub-empty">Create scenes in Home Assistant and they will appear here.</div>}</section>}

      {activeView === 'inbox' && <section className="hub-section hub-inbox-screen">
        <div className="hub-section-title"><h2>Inbox</h2><span>{googleMail.state === 'connected' ? `${googleMail.messages.length} recent messages` : 'Not connected'}</span></div>
        {googleMail.state === 'connected' ? <MailBrief email={googleMail.email} messages={googleMail.messages} onRefresh={googleMail.refresh} /> : <div className="hub-empty"><p>Connect Gmail directly to MITRA to see private mail summaries here.</p><button onClick={() => setActiveView('settings')}>Open settings <ChevronRight size={17} /></button></div>}
      </section>}

      {activeView === 'settings' && <section className="hub-section hub-settings-screen"><MitraSettings preferences={preferences} onPreferencesChange={changePreferences} connection={home.connection} message={home.message} deviceCount={devices.length} roomCount={rooms.length} sceneCount={home.scenes.length} integrations={home.integrations} googleMail={googleMail} brain={brain} voice={voice} photoLibrary={{ available: photoLibrary.available, count: photos.length, choose: choosePhotos }} onOpenHomeAssistant={() => setSetupOpen(true)} onNavigate={openView} /></section>}

    </section>
    <HomeAssistantSetup open={setupOpen} onClose={() => setSetupOpen(false)} initialUrl={home.serverUrl} message={home.message} onConnect={home.connect} />
    {isStandby && <StandbyScreen now={now} photo={selectedPhoto} weather={home.weather?.temperature || ''} onWake={registerActivity} />}
  </main>
}

function NavButton({ active, icon, label, onClick }: { active: boolean; icon: ReactNode; label: string; onClick: () => void }) { return <button className={active ? 'active' : ''} onClick={onClick}>{icon}<span>{label}</span></button> }
function titleCase(value: string) { return value.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase()) }
function EmptyState({ onConnect }: { onConnect: () => void }) { return <div className="hub-empty"><p>Connect Home Assistant to load your real accessories.</p><button onClick={onConnect}>Connect Home Assistant <ChevronRight size={17} /></button></div> }

function DeviceCard({ device, editing, onToggle, onRemove }: { device: LiveDevice; editing: boolean; onToggle: () => void; onRemove: () => void }) {
  const Icon = device.kind === 'light' ? Lightbulb : device.kind === 'plug' ? PlugZap : device.kind === 'media' ? Monitor : device.name.toLowerCase().includes('lock') ? Lock : device.name.toLowerCase().includes('fan') ? Speaker : Thermometer
  return <button className={`hub-device-card ${device.active ? 'on' : ''} ${editing ? 'editing' : ''}`} disabled={!device.controllable && !editing} onClick={editing ? onRemove : onToggle}><span className="hub-device-icon"><Icon size={30} /></span><span className="hub-device-copy"><strong>{device.name}</strong><small>{device.detail}</small></span>{editing ? <span className="hub-remove"><X size={16} /></span> : device.controllable && <span className="hub-switch"><i /></span>}</button>
}

function SceneCard({ scene, onActivate }: { scene: Scene; onActivate: () => void }) { return <button className="hub-scene-card" onClick={onActivate}><Layers3 size={26} /><strong>{scene.title}</strong><small>{scene.caption}</small><ChevronRight size={19} /></button> }

function PhotoStage({ photos, selectedId, onSelect, onChoose, onRemove, nativeLibrary }: { photos: PersonalPhoto[]; selectedId: string; onSelect: (id: string) => void; onChoose: () => void; onRemove: (id: string) => void; nativeLibrary: boolean }) {
  const hasPhotos = photos.length > 0
  const active = photos.find((photo) => photo.id === selectedId) || photos[0]
  return <section className={`reference-photo-stage ${hasPhotos ? 'has-photos' : ''}`} aria-label="MITRA photo display">
    <button className="reference-photo-add" onClick={onChoose} title="Add your personal photos" aria-label="Add photos"><ImageIcon size={27} /></button>
    <div className="reference-photo-thumbnails" aria-hidden={!hasPhotos}>{hasPhotos ? photos.slice(0, 2).map((photo) => <button key={photo.id} className={photo.id === active?.id ? 'active' : ''} onClick={() => onSelect(photo.id)}><img src={photo.url} alt="" /></button>) : <><span /><span /></>}</div>
    <div className="reference-photo-main">{hasPhotos ? <><img src={active.url} alt="Selected personal photo" /><button className="reference-photo-remove" onClick={() => onRemove(active.id)} aria-label="Remove selected photo"><X size={16} /></button></> : <div className="reference-photo-empty"><ImageIcon size={34} /><strong>Photo display</strong><span>{nativeLibrary ? 'Add photos from the Lenovo. They stay private on this device.' : 'Choose photos to personalize MITRA.'}</span><button onClick={onChoose}>Add photos</button></div>}</div>
    {hasPhotos && <div className="reference-photo-pagination" aria-label="Selected photo">{photos.slice(0, 5).map((photo, index) => <button key={photo.id} className={photo.id === active?.id ? 'active' : ''} onClick={() => onSelect(photo.id)} aria-label={`Show photo ${index + 1}`} />)}</div>}
  </section>
}

function StandbyScreen({ now, photo, weather, onWake }: { now: Date; photo?: PersonalPhoto; weather: string; onWake: () => void }) {
  const time = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  const date = now.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })
  return <section className="mitra-standby" role="button" tabIndex={0} aria-label="MITRA standby. Touch to wake." onPointerDown={onWake} onKeyDown={onWake}>
    {photo && <img className="mitra-standby-photo" src={photo.url} alt="" />}
    <div className="mitra-standby-shade" />
    <div className="mitra-standby-content"><p>{date}</p><strong>{time}</strong>{weather && <span><CloudSun size={31} />{weather}</span>}<small><Wifi size={29} /> MITRA standby</small></div>
    <div className="mitra-standby-wake">Touch to wake</div>
  </section>
}

function CalendarPanel({ label, calendar, onPrevious, onNext }: { label: string; calendar: CalendarCell[]; onPrevious: () => void; onNext: () => void }) {
  const weekdayLabels = calendar.slice(0, 7).map((cell) => cell.date.toLocaleDateString([], { weekday: 'short' }))
  return <section className="reference-calendar" aria-label="Calendar">
    <header><div><CalendarDays size={22} /><strong>{label}</strong></div><span><button onClick={onPrevious} aria-label="Previous month"><ChevronLeft size={22} /></button><button onClick={onNext} aria-label="Next month"><ChevronRight size={22} /></button></span></header>
    <div className="reference-calendar-weekdays">{weekdayLabels.map((label, index) => <span key={`${label}-${index}`}>{label}</span>)}</div>
    <div className="reference-calendar-days">{calendar.map((cell) => <span key={cell.date.toISOString()} className={`${cell.inCurrentMonth ? '' : 'outside'} ${cell.isToday ? 'today' : ''}`}>{cell.date.getDate()}</span>)}</div>
  </section>
}

function NotificationPanel({ messages, connected, onViewAll }: { messages: Array<{ id: string; from: string; subject: string; important: boolean }>; connected: boolean; onViewAll: () => void }) {
  const shown = messages.slice(0, 3)
  return <section className="reference-notifications" aria-label="Notifications">
    <header><div><Bell size={22} /><strong>Notifications</strong></div><button onClick={onViewAll}>View all</button></header>
    <div className="reference-notification-list">{shown.length ? shown.map((message) => <button key={message.id} className={message.important ? 'important' : ''} onClick={onViewAll}><span><Mail size={20} /></span><div><strong>{message.subject || 'No subject'}</strong><small>{message.from}</small></div></button>) : <div className="reference-notification-empty"><Mail size={20} /><span>{connected ? 'No unread Inbox messages.' : 'Connect Gmail to show private Inbox updates.'}</span></div>}</div>
  </section>
}

function createCalendar(month: Date, today: Date): CalendarCell[] {
  const first = new Date(month.getFullYear(), month.getMonth(), 1)
  const start = new Date(first)
  start.setDate(first.getDate() - first.getDay())
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start)
    date.setDate(start.getDate() + index)
    return {
      date,
      inCurrentMonth: date.getMonth() === month.getMonth() && date.getFullYear() === month.getFullYear(),
      isToday: date.getFullYear() === today.getFullYear() && date.getMonth() === today.getMonth() && date.getDate() === today.getDate(),
    }
  })
}

function MailBrief({ email, messages, onRefresh }: { email: string; messages: Array<{ id: string; from: string; subject: string; preview: string; important: boolean }>; onRefresh: () => void }) {
  const important = messages.filter((message) => message.important)
  const shown = (important.length ? important : messages).slice(0, 3)
  return <section className="mail-brief" aria-label="Private Gmail inbox summary">
    <header><span><Mail size={19} /><div><strong>New mail</strong><small>{email ? `Private summary for ${email}` : 'Private Gmail summary'}</small></div></span><button onClick={onRefresh}>Refresh</button></header>
    {shown.length ? <div className="mail-brief-list">{shown.map((message) => <article key={message.id} className={message.important ? 'important' : ''}><span>{message.important ? 'Important' : 'New'}</span><strong>{message.subject}</strong><small>{message.from}</small><p>{message.preview || 'No preview available.'}</p></article>)}</div> : <p className="mail-empty">No unread Inbox messages. MITRA checks Gmail directly; Home Assistant never receives this data.</p>}
  </section>
}

function HomeAssistantSetup({ open, onClose, initialUrl, message, onConnect }: { open: boolean; onClose: () => void; initialUrl: string; message: string; onConnect: (url: string, token: string) => void }) {
  const [url, setUrl] = useState(initialUrl)
  const [token, setToken] = useState('')
  const [scannerOpen, setScannerOpen] = useState(false)
  const [matterCode, setMatterCode] = useState('')
  useEffect(() => setUrl(initialUrl), [initialUrl])
  if (!open) return null
  return <><div className="setup-backdrop" role="dialog" aria-modal="true" aria-labelledby="ha-title"><section className="setup-sheet"><button className="setup-close" onClick={onClose} aria-label="Close setup">×</button><p className="eyebrow">HOME HUB CONNECTION</p><h2 id="ha-title">Connect Home Assistant</h2><p className="setup-copy">Use the local Hub URL and a long-lived access token. It is encrypted on this Duet so the hub reconnects after a restart.</p><label>Home Assistant URL<input value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://100.115.92.206:8123" autoCapitalize="none" autoCorrect="off" /></label><label>Long-lived access token<input type="password" value={token} onChange={(event) => setToken(event.target.value)} placeholder="Paste token" autoCapitalize="none" autoCorrect="off" /></label><div className="scanner-launch"><div><strong>Adding a Matter accessory?</strong><small>Use the Duet camera to scan its setup QR code.</small></div><button className="secondary-action" onClick={() => setScannerOpen(true)}><ScanLine size={16} />Scan QR</button></div>{matterCode && <p className="matter-captured">Matter QR captured — finish pairing in Home Assistant.</p>}<p className="setup-status">{message}</p><div className="button-row"><button className="primary-action" onClick={() => onConnect(url, token)}>Connect</button><button className="secondary-action" onClick={onClose}>Cancel</button></div><small>Home Assistant → Profile → Security → Long-Lived Access Tokens.</small></section></div><MatterQrScanner open={scannerOpen} onClose={() => setScannerOpen(false)} onScanned={setMatterCode} /></>
}
