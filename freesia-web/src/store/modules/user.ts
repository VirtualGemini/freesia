/** Account/auth state is memory-only; persistence is a positive language allowlist. */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { LanguageEnum } from '@/enums/appEnum'
import { router } from '@/router'
import { useSettingStore } from './setting'
import { useWorktabStore } from './worktab'
import type { AppRouteRecord } from '@/types/router'
import { setPageTitle } from '@/utils/router'
import { resetRouterState } from '@/router/guards/beforeEach'
import { useMenuStore } from './menu'
import { StorageConfig } from '@/utils/storage/storage-config'
import { userPreferencesStorage } from '@/utils/storage/user-preferences'
import { fetchGetUserInfo, fetchLogout, fetchRefreshSession } from '@/api/auth'
import { HttpError, showError } from '@/utils/http/error'
import {
  browserAccessToken,
  captureSession,
  clearBrowserSession,
  isSameSession,
  SessionChangedError,
  subscribeBrowserSession
} from '@/utils/http/browser-session'
import i18n from '@/locales'
import { RoutesAlias } from '@/router/routesAlias'
import { clearSwrCache } from '@/hooks/core/useSwrCache'

export const useAccountStore = defineStore(
  'userStore',
  () => {
    // Old account-center snapshots are not an authenticated restore source.
    clearSwrCache()
    const language = ref(LanguageEnum.ZH)
    const isLogin = ref(false)
    const isLock = ref(false)
    const lockPassword = ref('')
    const info = ref<Partial<Api.Auth.AccountInfo>>({})
    const activeUserId = ref('')
    const isPostLoginNavigating = ref(false)
    const pendingDeletionLogin = ref<Partial<Api.Auth.LoginResponse> | null>(null)
    const searchHistory = ref<AppRouteRecord[]>([])
    const accessToken = browserAccessToken
    const sessionStatus = ref<'unknown' | 'restoring' | 'ready' | 'anonymous' | 'pending'>(
      'unknown'
    )
    let restoreFlight: Promise<void> | undefined
    let hydrationFlight: { key: string; promise: Promise<Api.Auth.AccountInfo> } | undefined
    // This tab's worktabs retain their owner even when a sibling hydrates another
    // account and updates the shared browser-restart marker first.
    let worktabOwnerId = localStorage.getItem(StorageConfig.LAST_USER_ID_KEY) || ''

    const getAccountInfo = computed(() => info.value)
    const getUserInfo = getAccountInfo
    const canReadUserCache = computed(() => isLogin.value && !!activeUserId.value)
    const getSettingState = computed(() => useSettingStore().$state)
    const getWorktabState = computed(() => useWorktabStore().$state)

    const setAccountInfo = (newInfo: Partial<Api.Auth.AccountInfo>) => {
      info.value = { ...info.value, ...newInfo }
    }
    const setUserInfo = setAccountInfo
    const setLoginStatus = (status: boolean) => {
      isLogin.value = status
    }
    const setLanguage = (lang: LanguageEnum) => {
      language.value = lang
      setPageTitle(router.currentRoute.value)
    }
    const setSearchHistory = (list: AppRouteRecord[]) => {
      searchHistory.value = list
    }
    const setLockStatus = (status: boolean) => {
      isLock.value = status
    }
    const setLockPassword = (password: string) => {
      lockPassword.value = password
    }
    // Compatibility with existing optional auth screens: only accept an already-issued
    // in-memory credential. A string/snapshot can never create a browser session.
    const setToken = (token: string) => {
      if (captureSession()?.token !== token) throw new SessionChangedError()
    }
    const setPostLoginNavigating = (status: boolean) => {
      isPostLoginNavigating.value = status
    }
    const setPendingDeletionLogin = (payload: Partial<Api.Auth.LoginResponse> | null) => {
      pendingDeletionLogin.value = payload
    }

    const rememberWorktabOwner = () => {
      if (info.value.accountId) {
        worktabOwnerId = info.value.accountId
      }
    }
    const checkAndClearWorktabs = () => {
      const current = info.value.accountId
      if (!current) return
      // Unknown legacy ownership is not a reason to expose another account's worktabs.
      if (worktabOwnerId !== current) useWorktabStore().clearAll()
      worktabOwnerId = current
      // Retain ownership across browser restart, not just explicit logout.
      localStorage.setItem(StorageConfig.LAST_USER_ID_KEY, current)
    }

    const hydrateAccountInfo = async (options?: { force?: boolean }) => {
      const snapshot = captureSession()
      if (!snapshot) throw new SessionChangedError()
      if (!options?.force && info.value.accountId && activeUserId.value) {
        return info.value as Api.Auth.AccountInfo
      }
      const key = `${snapshot.epoch}:${snapshot.sessionId}`
      if (hydrationFlight?.key === key) return hydrationFlight.promise
      const promise = (async () => {
        const data = await fetchGetUserInfo()
        if (!isSameSession(snapshot)) throw new SessionChangedError()
        info.value = data
        activeUserId.value = data.accountId
        checkAndClearWorktabs()
        const remoteLang = data.language as LanguageEnum | undefined
        if (remoteLang && Object.values(LanguageEnum).includes(remoteLang)) {
          setLanguage(remoteLang)
          const locale = i18n.global.locale
          if (typeof locale === 'string') i18n.global.locale = remoteLang
          else locale.value = remoteLang
        }
        return data
      })()
      hydrationFlight = { key, promise }
      try {
        return await promise
      } finally {
        if (hydrationFlight?.promise === promise) hydrationFlight = undefined
      }
    }
    const hydrateUserInfo = hydrateAccountInfo

    const clearLocalState = () => {
      rememberWorktabOwner()
      isLogin.value = false
      info.value = {}
      activeUserId.value = ''
      isLock.value = false
      lockPassword.value = ''
      isPostLoginNavigating.value = false
      pendingDeletionLogin.value = null
      searchHistory.value = []
      clearSwrCache()
      sessionStorage.removeItem('iframeRoutes')
      useMenuStore().setHomePath('')
      resetRouterState(0)
    }
    const clearAuthState = () => clearBrowserSession()

    const restoreSession = async () => {
      if (sessionStatus.value === 'ready' || sessionStatus.value === 'anonymous') return
      if (restoreFlight) return restoreFlight
      const promise = (async () => {
        sessionStatus.value = 'restoring'
        try {
          const restored = await fetchRefreshSession()
          if (!restored) {
            sessionStatus.value = 'anonymous'
            return
          }
          await hydrateAccountInfo({ force: true })
          if (!isSameSession(restored)) throw new SessionChangedError()
          isLogin.value = true
          sessionStatus.value = 'ready'
        } catch (error) {
          if (sessionStatus.value !== 'anonymous') sessionStatus.value = 'pending'
          throw error
        }
      })()
      restoreFlight = promise
      try {
        await promise
      } finally {
        if (restoreFlight === promise) restoreFlight = undefined
      }
    }

    const logOut = async (options?: { remote?: boolean; broadcast?: boolean }) => {
      if (options?.remote === false) {
        clearBrowserSession()
        return true
      }
      try {
        return await fetchLogout()
      } catch (error) {
        // Retain account/page/input on remote failure; never report successful logout.
        if (error instanceof HttpError) showError(error)
        return false
      }
    }

    subscribeBrowserSession(({ type, peer }) => {
      if (type === 'logout') {
        const hadContext = isLogin.value || !!activeUserId.value || !!info.value.accountId
        clearLocalState()
        sessionStatus.value = 'anonymous'
        if (hadContext && router.currentRoute.value.path !== RoutesAlias.Login) {
          void router.push({
            name: 'Login',
            query: { redirect: router.currentRoute.value.fullPath }
          })
        }
      } else if (type === 'boundary') {
        clearLocalState()
        sessionStatus.value = 'unknown'
      } else if (peer && !activeUserId.value && sessionStatus.value !== 'restoring') {
        // A peer login is not a persisted identity snapshot: re-read account + permissions.
        const snapshot = captureSession()
        if (!snapshot) return
        sessionStatus.value = 'pending'
        void hydrateAccountInfo({ force: true })
          .then(() => {
            if (!isSameSession(snapshot)) return
            isLogin.value = true
            sessionStatus.value = 'ready'
            // Re-enter the route guard to fetch navigation and revalidate worktabs/permissions.
            return router.replace(router.currentRoute.value.fullPath)
          })
          .catch(() => {
            /* Pending context remains recoverable; never fake logout. */
          })
      }
    })

    return {
      language,
      isLogin,
      isLock,
      lockPassword,
      info,
      activeUserId,
      isPostLoginNavigating,
      pendingDeletionLogin,
      searchHistory,
      accessToken,
      sessionStatus,
      getAccountInfo,
      getUserInfo,
      canReadUserCache,
      getSettingState,
      getWorktabState,
      setAccountInfo,
      setUserInfo,
      setLoginStatus,
      setLanguage,
      setSearchHistory,
      setLockStatus,
      setLockPassword,
      setToken,
      hydrateAccountInfo,
      hydrateUserInfo,
      clearAuthState,
      setPostLoginNavigating,
      setPendingDeletionLogin,
      logOut,
      checkAndClearWorktabs,
      restoreSession
    }
  },
  {
    persist: {
      key: 'user',
      storage: userPreferencesStorage,
      pick: ['language']
    }
  }
)

export const useUserStore = useAccountStore
