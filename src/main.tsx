import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { Dashboard } from './features/dashboard/Dashboard'

if ('serviceWorker' in navigator && !navigator.userAgent.includes('JarvisHomeNative')) {
  window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'))
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Dashboard />
  </StrictMode>,
)
