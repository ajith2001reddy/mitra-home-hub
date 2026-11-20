import { useCallback, useEffect, useState } from 'react'
import type { DrivePhotoFolderStatus, PersonalPhoto } from '../../lib/photoLibrary'

function readPhotos(payload: string | null | undefined): PersonalPhoto[] {
  try {
    const parsed = JSON.parse(payload || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((photo): photo is PersonalPhoto =>
      typeof photo?.id === 'string' && typeof photo?.url === 'string' && typeof photo?.createdAt === 'number',
    )
  } catch {
    return []
  }
}

const unavailableDriveStatus: DrivePhotoFolderStatus = {
  state: 'offline',
  driveAppAvailable: false,
  accountEmail: '',
  accountName: '',
  folderName: '',
  count: 0,
  message: 'Google Drive photos are available only in the native MITRA app on the Lenovo.',
}

function readDriveStatus(payload: string | null | undefined): DrivePhotoFolderStatus {
  try {
    const parsed = JSON.parse(payload || '{}') as Partial<DrivePhotoFolderStatus>
    const validState = ['setup', 'authorizing', 'selecting', 'syncing', 'connected', 'error', 'offline'].includes(parsed.state || '')
    return {
      state: validState ? parsed.state as DrivePhotoFolderStatus['state'] : 'setup',
      driveAppAvailable: typeof parsed.driveAppAvailable === 'boolean' ? parsed.driveAppAvailable : false,
      accountEmail: typeof parsed.accountEmail === 'string' ? parsed.accountEmail : '',
      accountName: typeof parsed.accountName === 'string' ? parsed.accountName : '',
      folderName: typeof parsed.folderName === 'string' ? parsed.folderName : '',
      count: typeof parsed.count === 'number' && Number.isFinite(parsed.count) ? Math.max(0, parsed.count) : 0,
      message: typeof parsed.message === 'string' ? parsed.message : 'Choose one Google Drive folder that contains your photos.',
    }
  } catch {
    return { ...unavailableDriveStatus, state: 'error', message: 'MITRA could not read the Google Drive folder status.' }
  }
}

/** The native bridge keeps the chosen pictures in the Duet's private app storage. */
export function usePhotoLibrary() {
  const [photos, setPhotos] = useState<PersonalPhoto[]>(() => readPhotos(window.MitraPhotos?.list()))
  const [drive, setDrive] = useState<DrivePhotoFolderStatus>(() => window.MitraPhotos ? readDriveStatus(window.MitraPhotos.driveStatus()) : unavailableDriveStatus)
  const available = Boolean(window.MitraPhotos)

  const refresh = useCallback(() => setPhotos(readPhotos(window.MitraPhotos?.list())), [])

  useEffect(() => {
    window.mitraPhotoLibrary = (payload) => setPhotos(readPhotos(payload))
    window.mitraDrivePhotoStatus = (payload) => setDrive(readDriveStatus(payload))
    refresh()
    return () => {
      delete window.mitraPhotoLibrary
      delete window.mitraDrivePhotoStatus
    }
  }, [refresh])

  const choose = useCallback(() => window.MitraPhotos?.choose(), [])
  const remove = useCallback((id: string) => window.MitraPhotos?.remove(id), [])
  const installGoogleDrive = useCallback(() => window.MitraPhotos?.installGoogleDrive(), [])
  const connectDriveAccount = useCallback(() => {
    if (!window.MitraPhotos) return
    setDrive((current) => ({ ...current, state: 'authorizing', message: 'Opening Google sign-in. Choose the account for your Drive photos.' }))
    window.MitraPhotos.connectDriveAccount()
  }, [])
  const disconnectDriveAccount = useCallback(() => {
    if (!window.MitraPhotos) return
    setDrive((current) => ({ ...current, state: 'syncing', message: 'Removing your Google account and Drive folder from MITRA.' }))
    window.MitraPhotos.disconnectDriveAccount()
  }, [])
  const chooseDriveFolder = useCallback(() => {
    if (!window.MitraPhotos) return
    setDrive((current) => ({ ...current, state: 'selecting', message: 'Choose your Google Drive photo folder.' }))
    window.MitraPhotos.chooseDriveFolder()
  }, [])
  const refreshDriveFolder = useCallback(() => {
    if (!window.MitraPhotos) return
    setDrive((current) => ({ ...current, state: 'syncing', message: 'Syncing your selected Google Drive folder.' }))
    window.MitraPhotos.refreshDriveFolder()
  }, [])
  const disconnectDriveFolder = useCallback(() => {
    if (!window.MitraPhotos) return
    setDrive((current) => ({ ...current, state: 'syncing', message: 'Removing Google Drive folder access.' }))
    window.MitraPhotos.disconnectDriveFolder()
  }, [])

  return { available, photos, choose, remove, refresh, drive, installGoogleDrive, connectDriveAccount, disconnectDriveAccount, chooseDriveFolder, refreshDriveFolder, disconnectDriveFolder }
}
