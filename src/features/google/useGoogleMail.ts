import { useCallback, useEffect, useState } from 'react'
import type { GoogleMailBrief, GoogleMailConnection, GoogleMailState } from '../../lib/googleMail'

const unavailable = 'This build needs MITRA’s direct Google connection. Reinstall the latest MITRA Home app.'

export function useGoogleMail() {
  const [state, setState] = useState<GoogleMailState>(() => window.MitraGoogle ? 'setup' : 'offline')
  const [message, setMessage] = useState(() => window.MitraGoogle ? 'Connect Google to read new mail privately in MITRA.' : unavailable)
  const [email, setEmail] = useState('')
  const [profileName, setProfileName] = useState('')
  const [messages, setMessages] = useState<GoogleMailBrief[]>([])

  useEffect(() => {
    window.mitraGoogleConnection = (payload) => {
      try {
        const connection = JSON.parse(payload) as GoogleMailConnection
        setState(connection.state)
        setMessage(connection.message)
        setEmail(connection.email || '')
        setProfileName(connection.name || '')
      } catch {
        setState('offline')
        setMessage('Google mail connection returned data MITRA could not read.')
      }
    }
    window.mitraGoogleMail = (payload) => {
      try {
        const result = JSON.parse(payload) as { email?: string; name?: string; messages?: GoogleMailBrief[] }
        setEmail(result.email || '')
        setProfileName(result.name || '')
        setMessages(Array.isArray(result.messages) ? result.messages : [])
      } catch {
        setMessage('Gmail returned data MITRA could not read.')
      }
    }
    window.mitraGoogleError = (error) => {
      setState('offline')
      setMessage(error)
    }
    window.MitraGoogle?.refresh()
    return () => {
      delete window.mitraGoogleConnection
      delete window.mitraGoogleMail
      delete window.mitraGoogleError
    }
  }, [])

  const connect = useCallback(() => {
    if (!window.MitraGoogle) {
      setState('offline')
      setMessage(unavailable)
      return
    }
    setState('authorizing')
    setMessage('Opening Google’s secure permission screen.')
    window.MitraGoogle.connect()
  }, [])

  const refresh = useCallback(() => window.MitraGoogle?.refresh(), [])
  const disconnect = useCallback(() => {
    setMessages([])
    window.MitraGoogle?.disconnect()
  }, [])

  return { state, message, email, profileName, messages, connect, refresh, disconnect }
}
