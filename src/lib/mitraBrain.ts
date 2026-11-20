export type BrainCitation = { title: string; url: string }
export type BrainConfirmation = { label: string; action: string }
export type BrainResponse = { text: string; citations: BrainCitation[]; confirmation?: BrainConfirmation }
export type BrainStatus = {
  available: boolean
  provider: string
  configured: boolean
  model: string
  webSearch: boolean
  smartHome: boolean
  chatScope: 'lenovo-only'
}
export type BrainActivity = { time: number; type: string; detail: string; status: string }

export type NativeMitraBrainBridge = {
  getStatus(): void
  configure(rawConfiguration: string): void
  configureApiKey(): void
  clearApiKey(): void
  ask(rawRequest: string): void
  confirmPendingAction(): void
  cancelPendingAction(): void
  getActivityLog(): void
  clearActivityLog(): void
}

declare global {
  interface Window {
    MitraBrain?: NativeMitraBrainBridge
    mitraBrainStatus?: (payload: string) => void
    mitraBrainResponse?: (payload: string) => void
    mitraBrainError?: (message: string) => void
    mitraBrainActivityLog?: (payload: string) => void
  }
}
