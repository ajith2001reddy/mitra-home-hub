import { AnimatePresence, motion } from 'framer-motion'
import { ArrowUp, Check, ExternalLink, Mic, ShieldCheck, Sparkles, Square, Trash2, X } from 'lucide-react'
import { FormEvent, useMemo, useState } from 'react'
import type { MitraBrain } from './useMitraBrain'
import type { MitraVoice } from './useMitraVoice'

export function AssistantPanel({ brain, voice }: { brain: MitraBrain; voice: MitraVoice }) {
  const [input, setInput] = useState('')
  const latest = useMemo(() => [...brain.conversation].reverse().find((item) => item.role === 'assistant'), [brain.conversation])
  const voicePlaceholder = voice.status.state === 'listening' ? 'Listening…' : voice.status.state === 'processing' ? 'MITRA is thinking…' : brain.status.configured ? 'Ask MITRA anything…' : 'Connect OpenAI in Settings first'

  if (!brain.available) return null

  const send = (event: FormEvent) => {
    event.preventDefault()
    brain.send(input)
    setInput('')
  }

  return <section className="assistant-shell mitra-brain-shell" aria-label="MITRA Brain conversation">
    <div className="assistant-orb-wrap" aria-hidden="true">
      <motion.div className="assistant-orb" animate={brain.busy ? { scale: [1, 1.12, 1], opacity: [.86, 1, .86] } : { scale: [1, 1.04, 1] }} transition={{ duration: brain.busy ? .9 : 4, repeat: Infinity, ease: 'easeInOut' }}>
        <motion.div className="orb-core" animate={{ rotate: 360 }} transition={{ duration: 14, repeat: Infinity, ease: 'linear' }} />
      </motion.div>
    </div>
    <div className="assistant-copy">
      <p className="eyebrow"><Sparkles size={13} /> MITRA BRAIN · LENOVO ONLY</p>
      <h2>{brain.status.configured ? 'How can I help?' : 'Connect MITRA Brain'}</h2>
      <AnimatePresence mode="wait"><motion.p key={latest?.id || brain.message} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} className="assistant-response">{latest?.text || brain.message}</motion.p></AnimatePresence>
      {latest?.citations && latest.citations.length > 0 && <div className="brain-citations">{latest.citations.slice(0, 3).map((citation) => <a key={citation.url} href={citation.url} target="_blank" rel="noreferrer"><ExternalLink size={12} />{citation.title}</a>)}</div>}
    </div>
    <form className="assistant-input" onSubmit={send}>
      <span className="brain-secure"><ShieldCheck size={17} /></span>
      <input value={input} onChange={(event) => setInput(event.target.value)} disabled={!brain.status.configured || brain.busy} placeholder={voicePlaceholder} aria-label="Ask MITRA" />
      <button className={`mic-button ${voice.status.state === 'listening' ? 'listening' : ''}`} type="button" onClick={voice.toggleListening} disabled={!brain.status.configured || !voice.available || (brain.busy && voice.status.state !== 'speaking')} aria-label={voice.status.state === 'speaking' ? 'Stop MITRA speaking' : voice.status.state === 'listening' ? 'Stop listening' : 'Speak to MITRA'} title={voice.status.state === 'speaking' ? 'Stop speaking' : 'Speak to MITRA'}>{voice.status.state === 'speaking' ? <Square size={14} /> : <Mic size={17} />}</button>
      <button className="send-button" type="submit" disabled={!input.trim() || !brain.status.configured || brain.busy} aria-label="Send message"><ArrowUp size={17} /></button>
    </form>
    <div className="assistant-status">
      {brain.pendingConfirmation ? <div className="brain-confirmation"><span>Confirm: {brain.pendingConfirmation.action} {brain.pendingConfirmation.label}</span><button onClick={brain.confirm} disabled={brain.busy}><Check size={14} />Confirm</button><button onClick={brain.cancel} disabled={brain.busy}><X size={14} />Cancel</button></div> : <><span>{voice.status.state !== 'idle' && voice.status.state !== 'unavailable' ? voice.status.message : brain.busy ? 'MITRA is thinking…' : brain.status.configured ? 'Tap the microphone to speak · encrypted key · real Home Assistant verification' : 'API key stays in the Lenovo native app'}</span>{brain.conversation.length > 0 && <button className="brain-clear" onClick={brain.clearConversation} aria-label="Clear local conversation"><Trash2 size={14} />Clear</button>}</>}
    </div>
  </section>
}
