/**
 * 会话状态（Pinia）：持有当前用户与令牌。
 *
 * 令牌同时存于内存（本 store）与 localStorage（见 lib/http），
 * 刷新后可从 localStorage 恢复登录态；用户摘要则通过 GET /me 重新拉取。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken } from '@/lib/http'

export interface CurrentUser {
  id: number
  username: string
  plan: string
  role: string
  planStartedAt?: string
  planExpiresAt?: string
}

export const useSessionStore = defineStore('session', () => {
  const token = ref<string | null>(getToken())
  const user = ref<CurrentUser | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  /** 登录成功后写入令牌（内存 + localStorage），可选携带用户摘要。 */
  function signIn(newToken: string, currentUser?: CurrentUser) {
    token.value = newToken
    setToken(newToken)
    if (currentUser) user.value = currentUser
  }

  function setUser(currentUser: CurrentUser | null) {
    user.value = currentUser
  }

  /** 注销：清空令牌与用户信息。 */
  function signOut() {
    token.value = null
    user.value = null
    setToken(null)
  }

  /**
   * 会话引导：应用启动/刷新后若持有令牌但缺用户信息，则拉取 /me 恢复。
   * 令牌失效时（401）由 http 拦截器统一清理并跳登录，这里静默失败即可。
   */
  async function bootstrap() {
    if (!token.value || user.value) return
    try {
      const { fetchMe } = await import('@/lib/auth')
      user.value = await fetchMe()
    } catch {
      // 令牌无效或网络异常：拦截器已处理 401，其余情况保持未加载用户，不阻断应用启动。
    }
  }

  return { token, user, isLoggedIn, signIn, setUser, signOut, bootstrap }
})
