import { useCallback, useEffect, useRef, useState } from 'react'
import type { VoiceStatus } from '../../lib/mitraVoice'

type Options = { onTranscript: (text: string) => void }

export function useMitraVoice({ onTranscript }: Options) {
  const transcriptHandler = useRef(onTranscript)
  const available = Boolean(window.MitraVoice)
  const [status, setStatus] = useState<VoiceStatus>(() => ({
    state: available ? 'idle' : 'unavailable',
    message: available ? 'Tap the microphone and speak to MITRA.' : 'Voice is available only in the Lenovo Duet native app.',
  }))

  useEffect(() => { transcriptHandler.current = onTranscript }, [onTranscript])

  useEffect(() => {
    window.mitraVoiceState = (payload) => {
      try { setStatus(JSON.parse(payload) as VoiceStatus) }
      catch { setStatus({ state: 'error', message: 'MITRA voice returned data it could not read.' }) }
    }
    window.mitraVoiceTranscript = (text) => transcriptHandler.current(text)
    window.mitraVoiceError = (message) => setStatus({ state: 'error', message })
    window.MitraVoice?.getStatus()
    return () => {
      delete window.mitraVoiceState
      delete window.mitraVoiceTranscript
      delete window.mitraVoiceError
    }
  }, [])

  const toggleListening = useCallback(() => {
    if (!window.MitraVoice) return
    if (status.state === 'speaking') {
      window.MitraVoice.stopSpeaking()
      return
    }
    if (status.state === 'listening') {
      window.MitraVoice.stopListening()
      return
    }
    window.MitraVoice.startListening()
  }, [status.state])

  const speak = useCallback((text: string) => window.MitraVoice?.speak(text), [])
  const stopSpeaking = useCallback(() => window.MitraVoice?.stopSpeaking(), [])

  return { available, status, toggleListening, speak, stopSpeaking }
}

export type MitraVoice = ReturnType<typeof useMitraVoice>
