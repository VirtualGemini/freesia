import { StorageConfig } from './storage-config'

/** Only non-auth preferences may ever enter the persisted user Pinia key. */
function preferences(raw: string | null): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(raw || '{}')
    if (typeof value !== 'object' || value === null) return {}
    const language = (value as Record<string, unknown>).language
    return language === 'zh' || language === 'en' ? { language } : {}
  } catch {
    return {}
  }
}

export const userPreferencesStorage = {
  getItem(key: string): string | null {
    const raw = localStorage.getItem(key)
    if (raw === null) return null
    const safe = JSON.stringify(preferences(raw))
    localStorage.setItem(key, safe)
    return safe
  },
  setItem(key: string, raw: string): void {
    localStorage.setItem(key, JSON.stringify(preferences(raw)))
  }
}

export function removeLegacyUserCredentials() {
  // Run before Pinia/i18n can migrate or read any previous user snapshot.
  for (const storage of [localStorage, sessionStorage]) {
    for (const key of Object.keys(storage)) {
      if (key === 'user' || StorageConfig.createKeyPattern('user').test(key)) {
        storage.setItem(key, JSON.stringify(preferences(storage.getItem(key))))
      } else if (/^freesia-admin-v[^-]+$/.test(key)) {
        // Old combined snapshot includes auth/account data and is not a restore source.
        storage.removeItem(key)
      }
    }
  }
}
