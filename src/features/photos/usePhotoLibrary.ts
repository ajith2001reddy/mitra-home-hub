import { useCallback, useEffect, useState } from 'react'
import type { PersonalPhoto } from '../../lib/photoLibrary'

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

/** The native bridge keeps the chosen pictures in the Duet's private app storage. */
export function usePhotoLibrary() {
  const [photos, setPhotos] = useState<PersonalPhoto[]>(() => readPhotos(window.MitraPhotos?.list()))
  const available = Boolean(window.MitraPhotos)

  const refresh = useCallback(() => setPhotos(readPhotos(window.MitraPhotos?.list())), [])

  useEffect(() => {
    window.mitraPhotoLibrary = (payload) => setPhotos(readPhotos(payload))
    refresh()
    return () => { delete window.mitraPhotoLibrary }
  }, [refresh])

  const choose = useCallback(() => window.MitraPhotos?.choose(), [])
  const remove = useCallback((id: string) => window.MitraPhotos?.remove(id), [])

  return { available, photos, choose, remove, refresh }
}
