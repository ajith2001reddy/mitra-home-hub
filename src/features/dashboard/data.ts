export type DeviceKind = 'light' | 'plug' | 'sensor' | 'media'

export type SmartDevice = {
  id: string
  name: string
  room: string
  kind: DeviceKind
  active: boolean
  detail: string
}

export type Scene = {
  id: string
  title: string
  caption: string
  icon: 'sun' | 'moon' | 'film' | 'door-open'
}
