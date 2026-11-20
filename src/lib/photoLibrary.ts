export type PersonalPhoto = {
  id: string
  url: string
  createdAt: number
}

export type NativePhotoLibraryBridge = {
  list(): string
  choose(): void
  remove(id: string): void
}

declare global {
  interface Window {
    MitraPhotos?: NativePhotoLibraryBridge
    mitraPhotoLibrary?: (payload: string) => void
  }
}
