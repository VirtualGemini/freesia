/**
 * Access lives only in this page or ephemeral channel messages. The server alone owns
 * the HttpOnly Refresh cookie. Web Locks serialize session mutations, not business calls.
 * Storage holds a non-secret generation/revision, never credentials/account snapshots.
 */
import { computed, shallowRef } from 'vue'
import i18n, { $t } from '@/locales'
import { HttpError } from './error'
import { StorageConfig } from '@/utils/storage/storage-config'

type FenceStatus = 'active' | 'logging-in' | 'login-uncertain' | 'logging-out' | 'logged-out'
interface SessionFence {
  epoch: string
  revision: number
  status: FenceStatus
  sessionId: string
  uncertainty?: string
}

export interface SessionSnapshot extends Api.Auth.SessionCredentials {
  epoch: string
  revision: number
  uncertainty?: string
}

interface SessionEvent {
  type: 'credentials' | 'boundary' | 'logout'
  peer: boolean
}

const LOCK_NAME = 'freesia-admin-browser-session'
const CHANNEL_NAME = 'freesia-admin-browser-session-v1'
const INITIAL_FENCE: SessionFence = {
  epoch: 'initial',
  revision: 0,
  status: 'active',
  sessionId: ''
}
const credentials = shallowRef<SessionSnapshot | null>(null)
const fenceState = shallowRef<SessionFence>(INITIAL_FENCE)
const listeners = new Set<(event: SessionEvent) => void>()
const peerWaiters = new Map<string, () => void>()
let channel: BroadcastChannel | undefined
let initialized = false
let refreshFlight: { epoch: string; promise: Promise<SessionSnapshot | null> } | undefined
let logoutFlight: Promise<boolean> | undefined

export const browserAccessToken = computed(() => credentials.value?.token || '')
export const isSessionLoggingOut = computed(() => fenceState.value.status === 'logging-out')

export class SessionChangedError extends HttpError {
  constructor() {
    super($t('httpMsg.sessionChanged'), 409)
  }
}

function emit(type: SessionEvent['type'], peer: boolean) {
  listeners.forEach((listener) => listener({ type, peer }))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isFence(value: unknown): value is SessionFence {
  return (
    isRecord(value) &&
    typeof value.epoch === 'string' &&
    typeof value.sessionId === 'string' &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 0 &&
    ['active', 'logging-in', 'login-uncertain', 'logging-out', 'logged-out'].includes(
      String(value.status)
    )
  )
}

function isCredentials(value: unknown): value is Api.Auth.SessionCredentials {
  return (
    isRecord(value) &&
    typeof value.token === 'string' &&
    value.token.length > 0 &&
    typeof value.sessionId === 'string' &&
    value.sessionId.length > 0 &&
    typeof value.accessExpiresAt === 'string' &&
    Number.isFinite(Date.parse(value.accessExpiresAt)) &&
    typeof value.sessionExpiresAt === 'string' &&
    Number.isFinite(Date.parse(value.sessionExpiresAt))
  )
}

function readFence(): SessionFence {
  const raw = localStorage.getItem(StorageConfig.LOGOUT_SIGNAL_KEY)
  if (!raw) return INITIAL_FENCE
  try {
    const value: unknown = JSON.parse(raw)
    if (isFence(value)) return value
  } catch {
    // Old timestamp logout signals are intentionally treated as a logged-out fence.
  }
  return { epoch: raw, status: 'logged-out', revision: 0, sessionId: '' }
}

function synchronizeFence(peer = false): SessionFence {
  const next = readFence()
  const previous = fenceState.value
  fenceState.value = next
  if (next.status === 'logged-out') {
    const changed = credentials.value !== null || previous.status !== 'logged-out'
    credentials.value = null
    if (changed) emit('logout', peer)
  } else if (next.status === 'active' && credentials.value) {
    if (credentials.value.sessionId !== next.sessionId) {
      credentials.value = null
      emit('boundary', peer)
    } else if (
      credentials.value.epoch !== next.epoch ||
      credentials.value.uncertainty !== next.uncertainty
    ) {
      // Failed logout resumes the same session with a NEW fence, never rolling it back.
      // Work queued before the logout must remain stale.
      credentials.value = {
        ...credentials.value,
        epoch: next.epoch,
        uncertainty: next.uncertainty
      }
    }
  } else if (
    next.status === 'active' &&
    (previous.epoch !== next.epoch || previous.sessionId !== next.sessionId)
  ) {
    emit('boundary', peer)
  }
  return next
}

function writeFence(next: SessionFence) {
  // Storage failure must stop the mutation before it is sent to the server.
  localStorage.setItem(StorageConfig.LOGOUT_SIGNAL_KEY, JSON.stringify(next))
  fenceState.value = next
}

function publishCredentials(snapshot: SessionSnapshot) {
  channel?.postMessage({ type: 'credentials', snapshot })
}

function install(
  value: Api.Auth.SessionCredentials,
  fence: SessionFence,
  peer: boolean
): SessionSnapshot {
  // Project the contract; never retain a legacy raw Refresh field.
  const snapshot: SessionSnapshot = {
    token: value.token,
    sessionId: value.sessionId,
    accessExpiresAt: value.accessExpiresAt,
    sessionExpiresAt: value.sessionExpiresAt,
    epoch: fence.epoch,
    revision: fence.revision,
    uncertainty: fence.uncertainty
  }
  credentials.value = snapshot
  emit('credentials', peer)
  return snapshot
}

export function initializeBrowserSession() {
  if (initialized) return
  initialized = true
  synchronizeFence()
  window.addEventListener('storage', (event) => {
    if (event.key === StorageConfig.LOGOUT_SIGNAL_KEY || event.key === null) {
      // Read the latest fence, not a potentially stale queued event value.
      synchronizeFence(true)
    }
  })
  if (typeof BroadcastChannel === 'undefined') return
  channel = new BroadcastChannel(CHANNEL_NAME)
  channel.addEventListener('message', ({ data }: MessageEvent<unknown>) => {
    if (!isRecord(data)) return
    const fence = synchronizeFence(true)
    if (data.type === 'snapshot-request' && typeof data.requestId === 'string') {
      const snapshot = captureSession()
      if (snapshot && snapshot.epoch === data.epoch && snapshot.revision === fence.revision) {
        channel?.postMessage({ type: 'credentials', snapshot, requestId: data.requestId })
      }
    } else if (data.type === 'credentials' && isCredentials(data.snapshot)) {
      const snapshot = data.snapshot as SessionSnapshot
      if (
        fence.status !== 'active' ||
        snapshot.epoch !== fence.epoch ||
        snapshot.revision !== fence.revision ||
        snapshot.uncertainty !== fence.uncertainty ||
        snapshot.sessionId !== fence.sessionId
      ) {
        return
      }
      install(snapshot, fence, true)
      if (typeof data.requestId === 'string') peerWaiters.get(data.requestId)?.()
    }
  })
}

export function subscribeBrowserSession(listener: (event: SessionEvent) => void) {
  initializeBrowserSession()
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function captureSession(): SessionSnapshot | null {
  const fence = synchronizeFence()
  const snapshot = credentials.value
  return fence.status === 'active' && snapshot?.epoch === fence.epoch ? snapshot : null
}

export function isSameSession(snapshot: SessionSnapshot): boolean {
  const fence = synchronizeFence()
  return (
    fence.status === 'active' &&
    fence.epoch === snapshot.epoch &&
    fence.sessionId === snapshot.sessionId &&
    credentials.value?.sessionId === snapshot.sessionId
  )
}

export function assertSessionNotLoggingOut() {
  const status = synchronizeFence().status
  if (status === 'logging-out') {
    throw new HttpError($t('httpMsg.sessionLogoutPending'), 409)
  }
  if (status === 'logging-in' || status === 'login-uncertain') {
    throw new HttpError($t('httpMsg.sessionLoginUncertain'), 409)
  }
}

function assertFence(epoch: string) {
  if (synchronizeFence().epoch !== epoch) throw new SessionChangedError()
  assertSessionNotLoggingOut()
}

async function withSessionLock<T>(action: () => Promise<T>): Promise<T> {
  initializeBrowserSession()
  if (!navigator.locks?.request) {
    throw new HttpError($t('httpMsg.sessionCoordinationUnavailable'), 400)
  }
  return navigator.locks.request(LOCK_NAME, { mode: 'exclusive' }, action)
}

async function readPeerSnapshot(epoch: string): Promise<void> {
  if (!channel) return
  const requestId = crypto.randomUUID()
  await new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      peerWaiters.delete(requestId)
      resolve()
    }
    const timer = window.setTimeout(finish, 100)
    peerWaiters.set(requestId, finish)
    channel?.postMessage({ type: 'snapshot-request', epoch, requestId })
  })
}

async function postSession<T>(
  path: string,
  data?: unknown,
  selection?: { sessionId: string; token?: string }
): Promise<T> {
  const base = new URL(import.meta.env.VITE_API_URL || '/', window.location.origin)
  if (base.origin !== window.location.origin) {
    throw new HttpError($t('httpMsg.sessionSameOriginRequired'), 400)
  }
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 15000)
  try {
    const locale = i18n.global.locale
    const response = await fetch(new URL(path, base), {
      method: 'POST',
      credentials: 'same-origin',
      signal: controller.signal,
      headers: {
        'X-Freesia-CSRF': '1',
        'Accept-Language': typeof locale === 'string' ? locale : locale.value,
        'X-Time-Zone': Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
        ...(selection?.sessionId ? { 'X-Freesia-Session': selection.sessionId } : {}),
        ...(selection?.token ? { Authorization: selection.token } : {}),
        ...(data === undefined ? {} : { 'Content-Type': 'application/json' })
      },
      ...(data === undefined ? {} : { body: JSON.stringify(data) })
    })
    const payload: unknown = await response.json().catch(() => null)
    if (!response.ok || !isRecord(payload) || payload.code !== 200) {
      throw new HttpError(
        isRecord(payload) && typeof payload.msg === 'string'
          ? payload.msg
          : $t('httpMsg.requestFailed'),
        isRecord(payload) && typeof payload.code === 'number' ? payload.code : response.status,
        {
          httpStatus: response.status,
          traceId: response.headers.get('x-trace-id') || undefined,
          url: path,
          method: 'POST'
        }
      )
    }
    return payload.data as T
  } catch (error) {
    if (error instanceof HttpError) throw error
    throw new HttpError($t('httpMsg.networkError'), 400, {
      isNetworkError: true,
      url: path,
      method: 'POST'
    })
  } finally {
    clearTimeout(timer)
  }
}

function requireCredentials(value: unknown): Api.Auth.SessionCredentials {
  if (!isCredentials(value)) {
    throw new HttpError($t('httpMsg.sessionResponseUnknown'), 502, { httpStatus: 200 })
  }
  return value
}

function isInvalidSession(error: unknown): boolean {
  return error instanceof HttpError && error.httpStatus === 401 && error.code === 10003
}

export async function loginBrowserSession(
  path: string,
  data: unknown
): Promise<Api.Auth.LoginResponse> {
  const epoch = synchronizeFence().epoch
  if (readFence().status === 'logging-out') {
    throw new HttpError($t('httpMsg.sessionLogoutPending'), 409)
  }
  return withSessionLock(async () => {
    if (synchronizeFence().epoch !== epoch) throw new SessionChangedError()
    const previous = readFence()
    const pending: SessionFence = {
      ...previous,
      epoch: crypto.randomUUID(),
      status: 'logging-in'
    }
    writeFence(pending)
    try {
      const response = await postSession<Api.Auth.LoginResponse>(path, data)
      if (synchronizeFence().epoch !== pending.epoch) throw new SessionChangedError()
      if (response.mfaChallenge) {
        writeFence({ ...previous, epoch: pending.epoch })
        return response
      }
      const value = requireCredentials(response)
      const next: SessionFence = {
        epoch: pending.epoch,
        revision: 1,
        status: 'active',
        sessionId: value.sessionId
      }
      writeFence(next)
      publishCredentials(install(value, next, false))
      return response
    } catch (error) {
      if (readFence().epoch === pending.epoch) {
        const definitelyRejected =
          error instanceof HttpError &&
          ((error.httpStatus === 400 && error.code === 10001) ||
            error.httpStatus === 403 ||
            (error.httpStatus === 429 && error.code === 10004))
        // A timed-out login can still install a late cookie for an unknown session.
        // Never adopt it on next load; only explicit login/logout can resolve this fence.
        writeFence({
          ...previous,
          epoch: pending.epoch,
          status: definitelyRejected ? previous.status : 'login-uncertain'
        })
      }
      throw error
    }
  })
}

/**
 * No failed snapshot means real page/new-tab/browser-start cookie restore.
 * With a failed snapshot, a newer peer Access can satisfy 10002 without rotating again.
 */
export function refreshBrowserSession(failed?: SessionSnapshot): Promise<SessionSnapshot | null> {
  const fence = synchronizeFence()
  if (failed && !isSameSession(failed)) return Promise.reject(new SessionChangedError())
  if (failed && failed.uncertainty !== fence.uncertainty) {
    return Promise.reject(new HttpError($t('httpMsg.sessionResponseUnknown'), 409))
  }
  if (fence.status === 'logged-out') return Promise.resolve(null)
  assertSessionNotLoggingOut()
  if (refreshFlight?.epoch === fence.epoch) return refreshFlight.promise
  const epoch = fence.epoch
  const expectedSessionId = fence.sessionId
  const uncertainty = fence.uncertainty
  const promise = withSessionLock(async () => {
    assertFence(epoch)
    if (readFence().uncertainty !== uncertainty) {
      throw new HttpError($t('httpMsg.sessionResponseUnknown'), 409)
    }
    if (failed) {
      await readPeerSnapshot(epoch)
      assertFence(epoch)
      if (!isSameSession(failed)) throw new SessionChangedError()
      const current = captureSession()
      const now = Date.now()
      if (
        current &&
        current.token !== failed.token &&
        Date.parse(current.accessExpiresAt) > now &&
        Date.parse(current.sessionExpiresAt) > now
      ) {
        return current
      }
    }
    try {
      const value = requireCredentials(
        await postSession<Api.Auth.SessionCredentials>('/api/auth/refresh', undefined, {
          sessionId: expectedSessionId
        })
      )
      assertFence(epoch)
      if (expectedSessionId && value.sessionId !== expectedSessionId) {
        // Late old Set-Cookie cannot be prevented in JS, but can never select an old
        // account after a different successful login. It may require signing in again.
        throw new SessionChangedError()
      }
      const next: SessionFence = {
        epoch,
        revision: readFence().revision + 1,
        status: 'active',
        sessionId: value.sessionId
      }
      writeFence(next)
      const snapshot = install(value, next, false)
      publishCredentials(snapshot)
      return snapshot
    } catch (error) {
      // Only explicit cookie/session invalidation under the lock signs everyone out.
      // Unknown outcomes / 503 / timeout preserve credentials and context.
      if (isInvalidSession(error) && readFence().epoch === epoch) clearBrowserSession()
      else if (readFence().epoch === epoch) {
        // Fence queued operations in *other tabs* as well as the page-level singleflight.
        // A new deliberate recovery can use the new marker; already-queued callers cannot.
        writeFence({ ...readFence(), uncertainty: crypto.randomUUID() })
        synchronizeFence()
      }
      throw error
    }
  })
  refreshFlight = { epoch, promise }
  void promise
    .finally(() => {
      if (refreshFlight?.promise === promise) refreshFlight = undefined
    })
    .catch(() => {})
  return promise
}

export async function validateInvalidBrowserSession(failed: SessionSnapshot): Promise<void> {
  if (!isSameSession(failed)) return
  await withSessionLock(async () => {
    if (!isSameSession(failed)) return
    await readPeerSnapshot(failed.epoch)
    const fence = synchronizeFence()
    const current = captureSession()
    // Backend keeps each unexpired Access valid under the live stable session. No
    // refresh is needed/permitted here. A delayed invalidation cannot clear a newer
    // Access, generation, account or a token update still awaiting channel delivery.
    if (
      isSameSession(failed) &&
      fence.revision === failed.revision &&
      fence.uncertainty === failed.uncertainty &&
      current?.token === failed.token
    ) {
      clearBrowserSession()
    }
  })
}

export function clearBrowserSession() {
  writeFence({
    epoch: crypto.randomUUID(),
    revision: 0,
    status: 'logged-out',
    sessionId: ''
  })
  credentials.value = null
  emit('logout', false)
}

export function logoutBrowserSession(): Promise<boolean> {
  if (logoutFlight) return logoutFlight
  if (!navigator.locks?.request) {
    return Promise.reject(new HttpError($t('httpMsg.sessionCoordinationUnavailable'), 400))
  }
  const previous = synchronizeFence()
  const selectedAccess =
    credentials.value?.sessionId === previous.sessionId ? credentials.value.token : undefined
  const pending: SessionFence = {
    ...previous,
    epoch: crypto.randomUUID(),
    revision: previous.revision,
    status: 'logging-out',
    sessionId: previous.sessionId
  }
  // Publish intent BEFORE waiting for in-flight login/refresh. Its late response
  // cannot resurrect this generation. Logout uses the latest cookie after that mutation.
  writeFence(pending)
  const promise = withSessionLock(async () => {
    if (readFence().epoch !== pending.epoch) return false
    try {
      // A late login/refresh cookie may belong to a different session. The selector
      // is not authentication; backend must match it to Access or cookie proof.
      await postSession<void>('/api/auth/logout', undefined, {
        sessionId: previous.sessionId,
        token: selectedAccess
      })
      if (readFence().epoch !== pending.epoch) return false
      clearBrowserSession()
      return true
    } catch (error) {
      if (readFence().epoch === pending.epoch) {
        const resumed: SessionFence = {
          ...pending,
          // Invalid selector/proof does not prove the selected session was revoked.
          // Keep unknown login/outcome non-restorable, even after unsuccessful logout.
          status: previous.status === 'logging-in' ? 'login-uncertain' : previous.status
        }
        writeFence(resumed)
        const current = credentials.value
        if (current && resumed.status === 'active') {
          publishCredentials(install(current, resumed, false))
        }
      }
      throw error
    }
  })
  logoutFlight = promise
  void promise
    .finally(() => {
      if (logoutFlight === promise) logoutFlight = undefined
    })
    .catch(() => {})
  return promise
}
