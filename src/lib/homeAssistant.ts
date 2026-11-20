export type ConnectionState = 'connected' | 'reconnecting' | 'offline' | 'setup'

export type HomeAssistantConfig = {
  baseUrl: string
  accessToken: string
}

export type HomeAssistantEntity = {
  entityId: string
  state: string
  attributes: Record<string, unknown>
}

export type NativeHomeAssistantBridge = {
  getLastUrl(): string
  restoreConnection(): void
  connect(baseUrl: string, accessToken: string): void
  fetchStates(): void
  callService(domain: string, service: string, entityIdsJson: string): void
}

declare global {
  interface Window {
    JarvisNative?: NativeHomeAssistantBridge
    jarvisNativeStates?: (rawStates: string) => void
    jarvisNativeError?: (message: string) => void
    jarvisNativeConnection?: (state: 'connecting' | 'connected' | 'offline') => void
  }
}

export interface HomeAssistantClient {
  getStates(): Promise<HomeAssistantEntity[]>
  callService(domain: string, service: string, target: { entityId: string[] }): Promise<void>
}

/**
 * The production WebSocket client belongs here in Milestone 3. Keeping this
 * interface in place lets the dashboard use live data without being rewritten.
 */
export class UnconfiguredHomeAssistantClient implements HomeAssistantClient {
  async getStates(): Promise<HomeAssistantEntity[]> {
    throw new Error('Home Assistant is not configured yet.')
  }

  async callService(): Promise<void> {
    throw new Error('Home Assistant is not configured yet.')
  }
}
