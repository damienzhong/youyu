import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import { wxLogin as apiWxLogin, emailLogin as apiEmailLogin, fetchMe as apiFetchMe } from '../api/auth'

/**
 * 登录态：持有 token 与用户摘要，负责微信登录与登出。
 * token/user 同步落地到本地存储，冷启动时可直接恢复。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: uni.getStorageSync(STORAGE_KEYS.token) || '',
    user: uni.getStorageSync(STORAGE_KEYS.user) || null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token
  },

  actions: {
    /**
     * 微信一键登录：wx.login 拿 code -> 后端换 token -> 落地登录态。
     * 返回用户摘要，失败则抛出后端统一错误体。
     */
    async loginWithWeixin() {
      const code = await getWxLoginCode()
      const res = await apiWxLogin(code)
      this.setSession(res.token, res.user)
      return res.user
    },

    /**
     * 邮箱验证码登录（登录/注册合一）：邮箱 + 验证码 -> 后端换 token -> 落地登录态。
     * 首次登录的邮箱由后端自动建号，返回结构与微信登录一致。
     */
    async loginWithEmail(email, code) {
      const res = await apiEmailLogin(email, code)
      this.setSession(res.token, res.user)
      return res.user
    },

    setSession(token, user) {
      this.token = token
      this.user = user
      uni.setStorageSync(STORAGE_KEYS.token, token)
      uni.setStorageSync(STORAGE_KEYS.user, user)
    },

    /**
     * 拉取最新用户摘要并刷新登录态（保留现有 token）。
     * 绑定/解绑等改动身份的操作后调用，保证 user 与后端一致。
     * 返回最新用户摘要。
     */
    async refreshUser() {
      const user = await apiFetchMe()
      this.user = user
      uni.setStorageSync(STORAGE_KEYS.user, user)
      return user
    },

    logout() {
      this.token = ''
      this.user = null
      uni.removeStorageSync(STORAGE_KEYS.token)
      uni.removeStorageSync(STORAGE_KEYS.user)
      uni.removeStorageSync(STORAGE_KEYS.ledgerId)
      uni.removeStorageSync('youyu_onboarded')
    }
  }
})

/** 封装 uni.login，返回微信一次性 code。绑定/注销时复用。 */
export function getWxLoginCode() {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success(res) {
        if (res.code) {
          resolve(res.code)
        } else {
          reject({ code: 'WX_LOGIN_NO_CODE', message: '未获取到微信登录凭证' })
        }
      },
      fail() {
        reject({ code: 'WX_LOGIN_FAIL', message: '微信登录调用失败' })
      }
    })
  })
}
