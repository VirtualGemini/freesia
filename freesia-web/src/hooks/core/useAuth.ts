/** Buttons use the current authenticated permission set in both navigation modes. */
import { useAccountStore } from '@/store/modules/user'

export const useAuth = () => {
  const account = useAccountStore()
  return {
    hasAuth: (permission: string): boolean => account.info.buttons?.includes(permission) ?? false
  }
}
