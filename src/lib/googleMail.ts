export type GoogleMailState = 'setup' | 'authorizing' | 'connected' | 'expired' | 'offline'

export type GoogleMailBrief = {
  id: string
  from: string
  subject: string
  preview: string
  important: boolean
}

export type GoogleMailConnection = {
  state: GoogleMailState
  message: string
  email: string
  name?: string
}

export type NativeGoogleMailBridge = {
  connect(): void
  refresh(): void
  disconnect(): void
}

declare global {
  interface Window {
    MitraGoogle?: NativeGoogleMailBridge
    mitraGoogleConnection?: (payload: string) => void
    mitraGoogleMail?: (payload: string) => void
    mitraGoogleError?: (message: string) => void
  }
}
