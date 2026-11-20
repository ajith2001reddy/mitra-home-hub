export type PersonalPhoto = {
  id: string
  url: string
  createdAt: number
}

export type DrivePhotoFolderState = 'setup' | 'selecting' | 'syncing' | 'connected' | 'error' | 'offline'

export type DrivePhotoFolderStatus = {
  state: DrivePhotoFolderState
  folderName: string
  count: number
  message: string
}

export type NativePhotoLibraryBridge = {
  list(): string
  choose(): void
  remove(id: string): void
  driveStatus(): string
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
