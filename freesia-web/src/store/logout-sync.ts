import { initializeBrowserSession } from '@/utils/http/browser-session'

/** Persisted user snapshots are never a credential-sync source. */
export function setupLogoutSync(): void {
  initializeBrowserSession()
}
