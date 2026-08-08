import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import { wxLogin as apiWxLogin, emailLogin as apiEmailLogin, fetchMe as apiFetchMe } from '../api/auth'
import { takePendingInviteCode, clearPendingInviteCode } from '../utils/invite'

/**
 * 登录态：持有 token 与用户摘要，负责微信登录与登出。
 * token/user 同步落地到本地存储，冷启动时可直接恢复。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: uni.getStorageSync(STORAGE_KEYS.token) || '',
    user: uni.getStorageSync(STORAGE_KEYS.user) || null,
    /**
     * 最近一次登录的邀请绑定结果 { bound, reason }，未登录过时为 null。
     * 只作后续增长埋点 / 提示的落点，本期 UI 不据此展示任何内容。
     * 不落地本地存储：语义只覆盖「本次登录」，冷启动后没有意义。
     */
    lastInviteBind: null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token
  },

  actions: {
    /**
     * 微信一键登录：wx.login 拿 code -> 后端换 token -> 落地登录态。
     * 携带待绑定邀请码（'' 表示不携带），返回用户摘要，失败则抛出后端统一错误体。
     */
    async loginWithWeixin() {
      const code = await getWxLoginCode()
      const inviteCode = takePendingInviteCode()
      const res = await apiWxLogin(code, inviteCode)
      this.setSession(res.token, res.user)
      this.recordInviteBind(res)
      return res.user
    },

    /**
     * 邮箱验证码登录（登录/注册合一）：邮箱 + 验证码 -> 后端换 token -> 落地登录态。
     * 首次登录的邮箱由后端自动建号，返回结构与微信登录一致。
     * 同样携带待绑定邀请码（'' 表示不携带）。
     */
    async loginWithEmail(email, code) {
      const inviteCode = takePendingInviteCode()
      const res = await apiEmailLogin(email, code, inviteCode)
      this.setSession(res.token, res.user)
      this.recordInviteBind(res)
      return res.user
    },

    /**
     * 登录成功后记录绑定结果并清除暂存。
     *
     * 三条约束（需求 4.8、4.12）：
     * - 只在**请求返回成功之后**清除：失败 / 网络错误 / 超时时 apiXxxLogin 直接抛出，
     *   本方法根本不会执行，暂存与写入时刻原样保留供重试继续携带。
     * - 无论 inviteBound 真假都清：未绑定的原因（已是老用户、码不存在、自邀、已绑定）
     *   在重试时不会变，留着只会让后续每次登录都白带一个注定失败的码。
     * - 服务端字段可能缺失（老服务端 / 任务 8.2 未上线）：一律降级为 { bound: false, reason: null }，
     *   不抛错、不影响登录主路径。
     */
    recordInviteBind(res) {
      const payload = res || {}
      this.lastInviteBind = {
        bound: !!payload.inviteBound,
        reason: payload.inviteUnboundReason || null
      }
      clearPendingInviteCode()
    },

    setSession(token, user) {
      this.token = token
      this.user = user
      uni.setStorageSync(STORAGE_KEYS.token, token)
      uni.setStorageSync(STORAGE_KEYS.user, user)
      // 成功登录即解除「主动退出」标记，恢复小程序端自动静默登录。
      uni.removeStorageSync(STORAGE_KEYS.signedOut)
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
      // 记录「主动退出」，避免小程序端在登录页自动静默登录把用户又登回去。
      uni.setStorageSync(STORAGE_KEYS.signedOut, '1')
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
