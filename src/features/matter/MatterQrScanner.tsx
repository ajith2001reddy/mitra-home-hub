import { BrowserMultiFormatReader } from '@zxing/browser'
import { Camera, Copy, ScanLine, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

type ScannerControls = { stop: () => void }

export function MatterQrScanner({ open, onClose, onScanned }: { open: boolean; onClose: () => void; onScanned: (code: string) => void }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const [status, setStatus] = useState('Opening the Duet camera…')
  const [matterCode, setMatterCode] = useState('')

  useEffect(() => {
    if (!open || !videoRef.current) return

    const reader = new BrowserMultiFormatReader()
    let controls: ScannerControls | undefined
    let stopped = false

    reader.decodeFromConstraints(
      { audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } } },
      videoRef.current,
      (result) => {
        if (result && !stopped) {
          const scanned = result.getText()
          if (!scanned.startsWith('MT:')) {
            setStatus('That is not a Matter setup QR code. Point the camera at the Matter code on the plug or its box.')
            return
          }
          controls?.stop()
          setMatterCode(scanned)
          setStatus('Matter setup code captured. Home Assistant will perform the secure pairing.')
          onScanned(scanned)
        }
      },
    ).then((newControls) => {
      controls = newControls
      if (!stopped) setStatus('Point the Duet camera at the Matter QR code.')
    }).catch(() => {
      setStatus('Camera permission was not granted. Allow Camera for MITRA Home, then scan again.')
    })

    return () => { stopped = true; controls?.stop() }
  }, [onScanned, open])

  if (!open) return null

  const copyMatterCode = async () => {
    try {
      await navigator.clipboard.writeText(matterCode)
      setStatus('Matter setup code copied. Continue pairing from Home Assistant.')
    } catch {
      setStatus('Keep this screen open and use the shown code in Home Assistant.')
    }
  }

  return <div className="scanner-backdrop" role="dialog" aria-modal="true" aria-labelledby="matter-scan-title"><section className="scanner-sheet"><button className="setup-close" onClick={onClose} aria-label="Close scanner"><X size={19} /></button><p className="eyebrow">MATTER ONBOARDING</p><h2 id="matter-scan-title">Scan your plug’s QR code</h2><p className="setup-copy">Allow camera access, then hold the Matter QR code inside the frame. The code stays on this Duet.</p><div className="scanner-frame"><video ref={videoRef} muted playsInline /><span className="scanner-corner top-left" /><span className="scanner-corner top-right" /><span className="scanner-corner bottom-left" /><span className="scanner-corner bottom-right" />{!matterCode && <div className="scanner-hint"><Camera size={18} /><span>Point at Matter QR code</span></div>}</div><p className={`scanner-status ${matterCode ? 'captured' : ''}`}><ScanLine size={16} />{status}</p>{matterCode && <div className="matter-code"><code>{matterCode}</code><button className="secondary-action" onClick={copyMatterCode}><Copy size={15} />Copy code</button></div>}<button className="primary-action" onClick={onClose}>{matterCode ? 'Done' : 'Cancel scanner'}</button></section></div>
}
