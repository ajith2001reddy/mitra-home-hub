import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { BrainActivity, BrainCitation, BrainConfirmation, BrainStatus } from '../../lib/mitraBrain'

export type ConversationMessage = {
  id: string
  role: 'user' | 'assistant'
  text: string
  createdAt: number
  citations?: BrainCitation[]
}

export type PersonalMemory = { id: string; text: string; createdAt: number }

type BrainPreferences = {
  aiModel: string
  responseStyle: 'balanced' | 'concise' | 'detailed'
  webSearch: boolean
  smartHomePermission: boolean
  conversationHistory: boolean
  memoryEnabled: boolean
}

const conversationKey = 'mitra-brain-conversation-v1'
const memoryKey = 'mitra-brain-memory-v1'

function loadList<T>(key: string): T[] {
  try {
    const raw = JSON.parse(localStorage.getItem(key) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch { return [] }
}

function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export function useMitraBrain(preferences: BrainPreferences) {
  const nativeAvailable = Boolean(window.MitraBrain)
  const [status, setStatus] = useState<BrainStatus>(() => ({
    available: nativeAvailable,
    provider: 'OpenAI',
    configured: false,
    model: preferences.aiModel,
    webSearch: preferences.webSearch,
    smartHome: preferences.smartHomePermission,
    chatScope: 'lenovo-only',
  }))
  const [conversation, setConversation] = useState<ConversationMessage[]>(() => preferences.conversationHistory ? loadList<ConversationMessage>(conversationKey) : [])
  const [memories, setMemories] = useState<PersonalMemory[]>(() => preferences.memoryEnabled ? loadList<PersonalMemory>(memoryKey) : [])
  const [activity, setActivity] = useState<BrainActivity[]>([])
  const [pendingConfirmation, setPendingConfirmation] = useState<BrainConfirmation | null>(null)
  const [busy, setBusy] = useState(false)
  const [voiceReply, setVoiceReply] = useState<string | null>(null)
  const speakAfterReply = useRef(false)
  const [message, setMessage] = useState(() => nativeAvailable ? 'Connect OpenAI to begin a private MITRA conversation.' : 'MITRA Brain is available only in the Lenovo Duet native app.')

  useEffect(() => {
    window.mitraBrainStatus = (payload) => {
      try {
        const next = JSON.parse(payload) as BrainStatus
        setStatus(next)
        setMessage(next.configured ? 'MITRA Brain is ready on this Lenovo Duet.' : 'Add your OpenAI API key securely to activate MITRA Brain.')
      } catch { setMessage('MITRA Brain returned configuration data it could not read.') }
    }
    window.mitraBrainResponse = (payload) => {
      try {
        const response = JSON.parse(payload) as { text?: string; citations?: BrainCitation[]; confirmation?: BrainConfirmation }
        const text = response.text?.trim() || 'MITRA did not return a response.'
        setConversation((current) => [...current, { id: createId(), role: 'assistant', text, createdAt: Date.now(), citations: Array.isArray(response.citations) ? response.citations : [] }])
        setPendingConfirmation(response.confirmation || null)
        if (speakAfterReply.current) {
          setVoiceReply(text)
          speakAfterReply.current = false
        }
        setBusy(false)
      } catch {
        setBusy(false)
        setMessage('MITRA returned a response it could not read.')
      }
    }
    window.mitraBrainError = (error) => {
      setBusy(false)
      setMessage(error)
      setConversation((current) => [...current, { id: createId(), role: 'assistant', text: error, createdAt: Date.now() }])
      if (speakAfterReply.current) {
        setVoiceReply(error)
        speakAfterReply.current = false
      }
    }
    window.mitraBrainActivityLog = (payload) => {
      try { setActivity(JSON.parse(payload) as BrainActivity[]) } catch { setActivity([]) }
    }
    window.MitraBrain?.getStatus()
    window.MitraBrain?.getActivityLog()
    return () => {
      delete window.mitraBrainStatus
      delete window.mitraBrainResponse
      delete window.mitraBrainError
      delete window.mitraBrainActivityLog
    }
  }, [])

  useEffect(() => {
    if (preferences.conversationHistory) localStorage.setItem(conversationKey, JSON.stringify(conversation.slice(-80)))
    else localStorage.removeItem(conversationKey)
  }, [conversation, preferences.conversationHistory])

  useEffect(() => {
    if (preferences.memoryEnabled) localStorage.setItem(memoryKey, JSON.stringify(memories.slice(-40)))
    else localStorage.removeItem(memoryKey)
  }, [memories, preferences.memoryEnabled])

  useEffect(() => {
    if (!window.MitraBrain) return
    window.MitraBrain.configure(JSON.stringify({
      model: preferences.aiModel,
      responseStyle: preferences.responseStyle,
      webSearch: preferences.webSearch,
      smartHome: preferences.smartHomePermission,
    }))
  }, [preferences.aiModel, preferences.responseStyle, preferences.webSearch, preferences.smartHomePermission])

  useEffect(() => {
    if (!preferences.conversationHistory) setConversation([])
  }, [preferences.conversationHistory])

  useEffect(() => {
    if (!preferences.memoryEnabled) setMemories([])
  }, [preferences.memoryEnabled])

  const send = useCallback((text: string, options?: { speak?: boolean }) => {
    const cleaned = text.trim()
    if (!cleaned || busy) return
    if (!window.MitraBrain) {
      setMessage('MITRA Brain is available only inside the Lenovo Duet native app.')
      return
    }
    if (!status.configured) {
      setMessage('Connect OpenAI in MITRA Settings before chatting.')
      return
    }
    const normalized = cleaned.toLowerCase()
    if (pendingConfirmation) {
      if (/^(yes|yeah|yep|confirm|go ahead|do it|okay|ok)\b/.test(normalized)) {
        speakAfterReply.current = Boolean(options?.speak)
        setBusy(true)
        window.MitraBrain.confirmPendingAction()
        return
      }
      if (/^(no|cancel|stop|don'?t|do not)\b/.test(normalized)) {
        speakAfterReply.current = Boolean(options?.speak)
        setBusy(true)
        window.MitraBrain.cancelPendingAction()
        return
      }
      setMessage('Please confirm or cancel the pending sensitive action before a new request.')
      return
    }
    const next: ConversationMessage = { id: createId(), role: 'user', text: cleaned, createdAt: Date.now() }
    speakAfterReply.current = Boolean(options?.speak)
    setConversation((current) => {
      const history = current.slice(-16)
      window.MitraBrain?.ask(JSON.stringify({ message: cleaned, history, memories: preferences.memoryEnabled ? memories : [] }))
      return [...current, next]
    })
    setPendingConfirmation(null)
    setBusy(true)
  }, [busy, memories, pendingConfirmation, preferences.memoryEnabled, status.configured])

  const confirm = useCallback(() => {
    if (!window.MitraBrain || !pendingConfirmation) return
    setBusy(true)
    window.MitraBrain.confirmPendingAction()
  }, [pendingConfirmation])

  const cancel = useCallback(() => {
    if (!window.MitraBrain || !pendingConfirmation) return
    window.MitraBrain.cancelPendingAction()
    setPendingConfirmation(null)
  }, [pendingConfirmation])

  const connectOpenAi = useCallback(() => window.MitraBrain?.configureApiKey(), [])
  const removeOpenAi = useCallback(() => window.MitraBrain?.clearApiKey(), [])
  const clearConversation = useCallback(() => { setConversation([]); setPendingConfirmation(null) }, [])
  const addMemory = useCallback((text: string) => {
    const cleaned = text.trim()
    if (!cleaned) return
    setMemories((current) => [...current, { id: createId(), text: cleaned.slice(0, 500), createdAt: Date.now() }])
  }, [])
  const deleteMemory = useCallback((id: string) => setMemories((current) => current.filter((memory) => memory.id !== id)), [])
  const clearMemories = useCallback(() => setMemories([]), [])
  const clearActivity = useCallback(() => window.MitraBrain?.clearActivityLog(), [])
  const consumeVoiceReply = useCallback(() => setVoiceReply(null), [])

  return useMemo(() => ({
    available: nativeAvailable,
    status,
    message,
    busy,
    voiceReply,
    conversation,
    memories,
    activity,
    pendingConfirmation,
    send,
    confirm,
    cancel,
    connectOpenAi,
    removeOpenAi,
    clearConversation,
    addMemory,
    deleteMemory,
    clearMemories,
    clearActivity,
    consumeVoiceReply,
  }), [activity, addMemory, busy, cancel, clearActivity, clearConversation, clearMemories, confirm, connectOpenAi, consumeVoiceReply, conversation, deleteMemory, memories, message, nativeAvailable, pendingConfirmation, removeOpenAi, send, status, voiceReply])
}

export type MitraBrain = ReturnType<typeof useMitraBrain>
