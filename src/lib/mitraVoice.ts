export type VoiceState = 'idle' | 'listening' | 'processing' | 'speaking' | 'error' | 'unavailable'
export type VoiceStatus = { state: VoiceState; message: string }

export type NativeMitraVoiceBridge = {
  getStatus(): void
  startListening(): void
  stopListening(): void
  speak(text: string): void
  stopSpeaking(): void
}

declare global {
  interface Window {
    MitraVoice?: NativeMitraVoiceBridge
    mitraVoiceState?: (payload: string) => void
    mitraVoiceTranscript?: (text: string) => void
    mitraVoiceError?: (message: string) => void
  }
}
