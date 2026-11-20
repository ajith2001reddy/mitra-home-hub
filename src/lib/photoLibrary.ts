export type PersonalPhoto = {
  id: string
  url: string
  createdAt: number
}

export type DrivePhotoFolderState = 'setup' | 'authorizing' | 'selecting' | 'syncing' | 'connected' | 'error' | 'offline'

export type DrivePhotoFolderStatus = {
  state: DrivePhotoFolderState
  accountEmail: string
  accountName: string
  folderName: string
  count: number
  message: string
}

export type NativePhotoLibraryBridge = {
  list(): string
  choose(): void
  remove(id: string): void
  driveStatus(): string
  connectDriveAccount(): void
  disconnectDriveAccount(): void
  chooseDriveFolder(): void
  refreshDriveFolder(): void
  disconnectDriveFolder(): void
}

declare global {
  interface Window {
    MitraPhotos?: NativePhotoLibraryBridge
    mitraPhotoLibrary?: (payload: string) => void
    mitraDrivePhotoStatus?: (payload: string) => void
  }
}
