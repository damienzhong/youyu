import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import {
  wxLogin as apiWxLogin,
  passwordLogin as apiPasswordLogin,
  register as apiRegister
} from '../api/auth'

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

    /** 账号密码登录（浏览器/H5 联调或备用）。 */
    async loginWithPassword(username, password) {
      const res = await apiPasswordLogin(username, password)
      this.setSession(res.token, res.user)
      return res.user
    },

    /** 注册后自动登录，返回用户摘要。 */
    async registerAndLogin(username, password) {
      await apiRegister(username, password)
      return this.loginWithPassword(username, password)
    },

    setSession(token, user) {
      this.token = token
      this.user = user
      uni.setStorageSync(STORAGE_KEYS.token, token)
      uni.setStorageSync(STORAGE_KEYS.user, user)
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

/** 封装 uni.login，返回微信一次性 code。 */
function getWxLoginCode() {
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
