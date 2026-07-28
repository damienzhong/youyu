import { API_BASE, STORAGE_KEYS } from './config'

/**
 * 统一请求封装（基于 uni.request，小程序/H5 通用）。
 *
 * - 自动拼接 API_BASE 与携带 Authorization: Bearer <token>
 * - 2xx 返回响应体 data；否则以后端统一错误体 {code,message,field} 抛出
 * - 401 视为登录态失效：清除本地 token 并跳回登录页
 */
export function request(options) {
  const { url, method = 'GET', data, header = {}, auth = true, ledgerId } = options

  const token = uni.getStorageSync(STORAGE_KEYS.token)
  if (auth && token) {
    header.Authorization = `Bearer ${token}`
  }
  // 当前账本：后端据此做多账本隔离。
  // 优先用调用方显式传入的 ledgerId（在「全部」视图下按某笔流水/账户自己的账本路由读写）；
  // 否则用全局当前账本；「全部」(all) 是聚合视图，不发送单账本头。
  const stored = uni.getStorageSync(STORAGE_KEYS.ledgerId)
  const effectiveLedger =
    ledgerId != null ? ledgerId : stored && String(stored) !== 'all' ? stored : null
  if (effectiveLedger != null) {
    header['X-Ledger-Id'] = String(effectiveLedger)
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE}${url}`,
      method,
      data,
      header,
      success(res) {
        const { statusCode, data: body } = res
        if (statusCode >= 200 && statusCode < 300) {
          resolve(body)
          return
        }
        if (statusCode === 401) {
          uni.removeStorageSync(STORAGE_KEYS.token)
          uni.removeStorageSync(STORAGE_KEYS.user)
          uni.reLaunch({ url: '/pages/login/login' })
        }
        // 后端统一错误体：{ code, message, field }
        reject(body || { code: 'HTTP_' + statusCode, message: '请求失败' })
      },
      fail() {
        reject({ code: 'NETWORK_ERROR', message: '网络异常，请稍后重试' })
      }
    })
  })
}

export const http = {
  get: (url, opts) => request({ url, method: 'GET', ...opts }),
  post: (url, data, opts) => request({ url, method: 'POST', data, ...opts }),
  put: (url, data, opts) => request({ url, method: 'PUT', data, ...opts }),
  del: (url, opts) => request({ url, method: 'DELETE', ...opts })
}
