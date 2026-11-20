import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ConnectionState, HomeAssistantEntity } from '../../lib/homeAssistant'
import type { DeviceKind, Scene, SmartDevice } from '../dashboard/data'

type LiveDevice = SmartDevice & { controllable: boolean }
type HomeAssistantApiState = { entity_id: string; state: string; attributes: Record<string, unknown> }
export type WeatherSnapshot = { temperature: string; condition: string } | null
export type HomeAssistantIntegrations = { calendars: number; reminders: number; cameras: number; weather: boolean }

// Keep the dashboard focused on accessories that can be used as Home controls.
// Diagnostic sensors and binary entities stay in Home Assistant instead of cluttering the hub.
const visibleDomains = new Set(['light', 'switch', 'fan', 'climate', 'media_player', 'cover', 'lock', 'input_boolean'])
const controllableDomains = new Set(['light', 'switch', 'fan', 'input_boolean', 'media_player', 'cover', 'lock'])

function domainOf(entityId: string) {
  return entityId.split('.', 1)[0]
}

function isActive(state: string) {
  return !['off', 'unavailable', 'unknown', 'idle', 'standby'].includes(state)
}

function deviceKind(domain: string): DeviceKind {
  if (domain === 'light') return 'light'
  if (domain === 'switch' || domain === 'fan' || domain === 'input_boolean') return 'plug'
  if (domain === 'media_player') return 'media'
  return 'sensor'
}

function detailFor(entity: HomeAssistantEntity) {
  if (entity.state === 'unavailable') return 'Unavailable'
  if (entity.attributes.brightness && typeof entity.attributes.brightness === 'number') return `${Math.round((entity.attributes.brightness / 255) * 100)}%`
  if (entity.attributes.current_temperature) return `${entity.attributes.current_temperature}°`
  if (entity.attributes.unit_of_measurement) return `${entity.state} ${entity.attributes.unit_of_measurement}`
  return entity.state.replaceAll('_', ' ')
}

function mapStates(raw: HomeAssistantEntity[]): { devices: LiveDevice[]; scenes: Scene[]; weather: WeatherSnapshot; integrations: HomeAssistantIntegrations } {
  const devices = raw
    .filter((entity) => visibleDomains.has(domainOf(entity.entityId)))
    .map((entity) => {
      const domain = domainOf(entity.entityId)
      return {
        id: entity.entityId,
        name: String(entity.attributes.friendly_name || entity.entityId.split('.').slice(1).join(' ').replaceAll('_', ' ')),
        room: String(entity.attributes.area_name || entity.attributes.room || domain.replaceAll('_', ' ')),
        kind: deviceKind(domain),
        active: isActive(entity.state),
        detail: detailFor(entity),
        controllable: controllableDomains.has(domain),
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))

  const scenes = raw
    .filter((entity) => domainOf(entity.entityId) === 'scene')
    .map((entity) => ({
      id: entity.entityId,
      title: String(entity.attributes.friendly_name || entity.entityId.split('.').slice(1).join(' ').replaceAll('_', ' ')),
      caption: 'Home Assistant scene',
      icon: 'sun' as const,
    }))

  const weatherEntity = raw.find((entity) => domainOf(entity.entityId) === 'weather')
  const temperature = weatherEntity?.attributes.temperature
  const unit = typeof weatherEntity?.attributes.temperature_unit === 'string' ? weatherEntity.attributes.temperature_unit : '°'
  const weather = weatherEntity && (typeof temperature === 'number' || typeof temperature === 'string')
    ? { temperature: `${temperature}${unit === '°' ? '°' : ` ${unit}`}`, condition: weatherEntity.state.replaceAll('_', ' ') }
    : null

  return {
    devices,
    scenes,
    weather,
    integrations: {
      calendars: raw.filter((entity) => domainOf(entity.entityId) === 'calendar').length,
      reminders: raw.filter((entity) => domainOf(entity.entityId) === 'todo').length,
      cameras: raw.filter((entity) => domainOf(entity.entityId) === 'camera').length,
      weather: Boolean(weather),
    },
  }
}

export function useHomeAssistant() {
  const [connection, setConnection] = useState<ConnectionState>(() => (window.JarvisNative ? 'setup' : 'offline'))
  const [serverUrl, setServerUrl] = useState(() => window.JarvisNative?.getLastUrl() || '')
  const [devices, setDevices] = useState<LiveDevice[]>([])
  const [scenes, setScenes] = useState<Scene[]>([])
  const [weather, setWeather] = useState<WeatherSnapshot>(null)
  const [integrations, setIntegrations] = useState<HomeAssistantIntegrations>({ calendars: 0, reminders: 0, cameras: 0, weather: false })
  const [message, setMessage] = useState(() => (window.JarvisNative ? 'Connect Home Assistant to load your real devices.' : 'This build needs the native connection bridge.'))
  const pendingChanges = useRef(new Map<string, { active: boolean; expiresAt: number }>())

  useEffect(() => {
    window.jarvisNativeStates = (payload) => {
      try {
        const parsed = JSON.parse(payload) as HomeAssistantApiState[]
        const states: HomeAssistantEntity[] = parsed.map((entity) => ({
          entityId: entity.entity_id,
          state: entity.state,
          attributes: entity.attributes || {},
        }))
        const next = mapStates(states)
        const timestamp = Date.now()
        const confirmedDevices = next.devices.map((device) => {
          const pending = pendingChanges.current.get(device.id)
          if (!pending) return device
          if (device.active === pending.active || timestamp >= pending.expiresAt) {
            pendingChanges.current.delete(device.id)
            return device
          }
          // An earlier poll can arrive after a tap. Keep the immediate tile
          // feedback visible until Home Assistant sends the newer state.
          return { ...device, active: pending.active, detail: pending.active ? 'On' : 'Off' }
        })
        setDevices(confirmedDevices)
        setScenes(next.scenes)
        setWeather(next.weather)
        setIntegrations(next.integrations)
        setConnection('connected')
        setMessage(`${next.devices.length} Home Assistant entities are live.`)
      } catch {
        setConnection('offline')
        setMessage('Home Assistant returned data the dashboard could not read.')
      }
    }
    window.jarvisNativeError = (error) => {
      setConnection('offline')
      setMessage(error)
    }
    window.jarvisNativeConnection = (state) => setConnection(state === 'connecting' ? 'reconnecting' : state)
    window.JarvisNative?.restoreConnection()
    return () => {
      delete window.jarvisNativeStates
      delete window.jarvisNativeError
      delete window.jarvisNativeConnection
    }
  }, [])

  useEffect(() => {
    if (connection !== 'connected') return
    const timer = window.setInterval(() => window.JarvisNative?.fetchStates(), 3000)
    return () => window.clearInterval(timer)
  }, [connection])

  const connect = useCallback((url: string, token: string) => {
    const normalized = url.trim().replace(/\/$/, '')
    if (!window.JarvisNative) {
      setMessage('Native bridge unavailable. Reinstall the current MITRA Home app.')
      return
    }
    if (!/^https?:\/\//.test(normalized) || !token.trim()) {
      setMessage('Enter an http(s) Home Assistant URL and a long-lived access token.')
      return
    }
    setServerUrl(normalized)
    setConnection('reconnecting')
    setMessage('Connecting to Home Assistant…')
    window.JarvisNative.connect(normalized, token.trim())
  }, [])

  const callService = useCallback((domain: string, service: string, entityIds: string[]) => {
    if (!window.JarvisNative || connection !== 'connected' || entityIds.length === 0) return
    window.JarvisNative.callService(domain, service, JSON.stringify(entityIds))
  }, [connection])

  const toggle = useCallback((device: LiveDevice) => {
    if (!device.controllable) return
    const domain = domainOf(device.id)
    const nextActive = !device.active
    pendingChanges.current.set(device.id, { active: nextActive, expiresAt: Date.now() + 8000 })
    // Update the card immediately; Home Assistant remains the source of truth
    // and the next response corrects it if the command was rejected.
    setDevices((current) => current.map((item) => item.id === device.id
      ? { ...item, active: nextActive, detail: nextActive ? 'On' : 'Off' }
      : item))
    callService(domain, device.active ? 'turn_off' : 'turn_on', [device.id])
  }, [callService])

  const lights = useMemo(() => devices.filter((device) => domainOf(device.id) === 'light'), [devices])
  const setAllLights = useCallback((turnOn: boolean) => callService('light', turnOn ? 'turn_on' : 'turn_off', lights.map((light) => light.id)), [callService, lights])
  const activateScene = useCallback((scene: Scene) => callService('scene', 'turn_on', [scene.id]), [callService])

  return { connection, serverUrl, devices, scenes, weather, integrations, message, connect, toggle, lights, setAllLights, activateScene }
}
