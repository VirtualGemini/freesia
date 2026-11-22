import { ElNotification } from 'element-plus'
import type { Router } from 'vue-router'
import { captureSession, isSameSession, SessionChangedError } from '@/utils/http/browser-session'
import type { useAccountStore } from '@/store/modules/user'

interface CompleteLoginOptions {
  accountStore: ReturnType<typeof useAccountStore>
  token: string
  redirect?: string
  router: Router
  successTitle: string
  successMessage: string
}

/**
 * 统一登录完成流程：
 * 校验内存会话 -> 拉取正式用户信息 -> 标记登录态 -> 跳转 -> 展示欢迎提示
 */
export async function completeLogin({
  accountStore,
  token,
  redirect,
  router,
  successTitle,
  successMessage
}: CompleteLoginOptions): Promise<void> {
  accountStore.setToken(token)
  const snapshot = captureSession()
  if (!snapshot) throw new SessionChangedError()

  try {
    const accountInfo = await accountStore.hydrateAccountInfo({ force: true })
    if (!isSameSession(snapshot)) throw new SessionChangedError()
    accountStore.sessionStatus = 'ready'
    accountStore.setPostLoginNavigating(true)
    accountStore.setLoginStatus(true)

    await router.push(redirect || '/')
    accountStore.setPostLoginNavigating(false)

    window.setTimeout(() => {
      if (!isSameSession(snapshot)) return
      ElNotification({
        title: successTitle,
        type: 'success',
        duration: 2500,
        zIndex: 10000,
        message: accountInfo.userName
          ? `${successMessage}, ${accountInfo.userName}!`
          : successMessage
      })
    }, 80)
  } catch (error) {
    // A real session may already exist. Account/menu initialization failure must not
    // discard it or erase the login form; the user can deliberately retry/reload.
    if (isSameSession(snapshot)) accountStore.sessionStatus = 'pending'
    accountStore.setPostLoginNavigating(false)
    throw error
  }
}
