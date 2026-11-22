/**
 * No general retries. Only HTTP 401 / 10002 (explicitly unexecuted) permits one
 * refresh + replay, fenced to the original account/session. 10003 is never replayed.
 */
import axios, { type AxiosRequestConfig } from 'axios'
import { useUserStore } from '@/store/modules/user'
import { HttpError, handleError, showError, showSuccess, type ErrorResponse } from './error'
import { ApiStatus } from './status'
import { $t } from '@/locales'
import type { BaseResponse } from '@/types'
import {
  assertSessionNotLoggingOut,
  captureSession,
  isSameSession,
  refreshBrowserSession,
  SessionChangedError,
  validateInvalidBrowserSession,
  type SessionSnapshot
} from './browser-session'

interface ExtendedAxiosRequestConfig extends AxiosRequestConfig {
  showErrorMessage?: boolean
  showSuccessMessage?: boolean
}

const axiosInstance = axios.create({
  timeout: 15000,
  baseURL: import.meta.env.VITE_API_URL,
  // Same-origin cookies are sent normally; never opt into cross-origin credentials/CORS.
  withCredentials: false
})

function normalizeError(error: unknown): HttpError {
  if (error instanceof HttpError) return error
  if (axios.isAxiosError<ErrorResponse>(error)) {
    try {
      handleError(error)
    } catch (normalized) {
      if (normalized instanceof HttpError) return normalized
    }
  }
  return new HttpError($t('httpMsg.requestFailed'), ApiStatus.error)
}

async function send<T>(config: ExtendedAxiosRequestConfig, snapshot: SessionSnapshot | null) {
  const userStore = useUserStore()
  const headers = {
    ...config.headers,
    Authorization: snapshot?.token || undefined,
    'X-Time-Zone': Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    'Accept-Language': userStore.language || 'zh'
  }
  const response = await axiosInstance.request<BaseResponse<T>>({ ...config, headers })
  if (response.data.code !== ApiStatus.success) {
    throw new HttpError(response.data.msg || $t('httpMsg.requestFailed'), response.data.code, {
      httpStatus: response.status,
      traceId: response.headers['x-trace-id'],
      url: config.url,
      method: config.method
    })
  }
  return response.data
}

async function request<T = unknown>(config: ExtendedAxiosRequestConfig): Promise<T> {
  const normalized = { ...config }
  if (
    ['POST', 'PUT'].includes(normalized.method?.toUpperCase() || '') &&
    normalized.params &&
    normalized.data === undefined
  ) {
    normalized.data = normalized.params
    normalized.params = undefined
  }
  try {
    assertSessionNotLoggingOut()
    const original = captureSession()
    let attempted = original
    const isAuthRequest = /\/api\/auth(?:\/|$)/.test(normalized.url || '')
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const response = await send<T>(normalized, attempted)
        if (original && !isSameSession(original)) throw new SessionChangedError()
        if (config.showSuccessMessage && response.msg) showSuccess(response.msg)
        return response.data as T
      } catch (raw) {
        const error = normalizeError(raw)
        if (!isAuthRequest && attempted && error.httpStatus === 401) {
          if (error.code === 10002 && attempt === 0 && isSameSession(attempted)) {
            await refreshBrowserSession(attempted)
            if (!original || !isSameSession(original)) throw new SessionChangedError()
            attempted = captureSession()
            if (!attempted) throw new SessionChangedError()
            continue
          }
          if (error.code === 10003) {
            // Validation must not replace this operation's status/code/trace, nor replay
            // a business operation whose execution status was not explicitly established.
            await validateInvalidBrowserSession(attempted).catch(() => {})
          }
        }
        throw error
      }
    }
    throw new Error('Unreachable request replay')
  } catch (raw) {
    const error = normalizeError(raw)
    showError(error, config.showErrorMessage !== false)
    throw error
  }
}

export default {
  get<T>(config: ExtendedAxiosRequestConfig) {
    return request<T>({ ...config, method: 'GET' })
  },
  post<T>(config: ExtendedAxiosRequestConfig) {
    return request<T>({ ...config, method: 'POST' })
  },
  put<T>(config: ExtendedAxiosRequestConfig) {
    return request<T>({ ...config, method: 'PUT' })
  },
  del<T>(config: ExtendedAxiosRequestConfig) {
    return request<T>({ ...config, method: 'DELETE' })
  },
  request<T>(config: ExtendedAxiosRequestConfig) {
    return request<T>(config)
  }
}
