import { API_BASE, STORAGE_KEYS } from './config'
import { createOfflineHttp } from './offline/offlineHttp'

/**
 * 统一请求封装（基于 uni.request，小程序/H5 通用）。
 *
 * - 自动拼接 API_BASE 与携带 Authorization: Bearer <token>
 * - 2xx 返回响应体 data；否则以后端统一错误体 {code,message,field} 抛出
 * - 401 视为登录态失效：清除本地 token 并跳回登录页
 */
export function request(options) {
  const { url, method = 'GET', data, auth = true, ledgerId, noLedger = false } = options
  // 每次构造独立 header，避免重试时残留过期的 X-Ledger-Id。
  const header = { ...(options.header || {}) }

  const token = uni.getStorageSync(STORAGE_KEYS.token)
  if (auth && token) {
    header.Authorization = `Bearer ${token}`
  }
  // 当前账本：后端据此做多账本隔离。
  // 优先用调用方显式传入的 ledgerId（在「全部」视图下按某笔流水/账户自己的账本路由读写）；
  // 否则用全局当前账本；「全部」(all) 是聚合视图，不发送单账本头。noLedger=true 时不带头（兜底重试用）。
  let sentLedger = null
  if (!noLedger) {
    const stored = uni.getStorageSync(STORAGE_KEYS.ledgerId)
    const effectiveLedger =
      ledgerId != null ? ledgerId : stored && String(stored) !== 'all' ? stored : null
    if (effectiveLedger != null) {
      header['X-Ledger-Id'] = String(effectiveLedger)
      sentLedger = effectiveLedger
    }
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
        // 失效账本兜底：当前账本(X-Ledger-Id)不存在或无权访问时，清除本地过期账本 id，
        // 不带账本头重试一次（后端回退默认账本），避免整页空白。只重试一次，防止死循环。
        if (
          statusCode === 404 &&
          body && body.code === 'LEDGER_NOT_ACCESSIBLE' &&
          sentLedger != null && !noLedger
        ) {
          uni.removeStorageSync(STORAGE_KEYS.ledgerId)
          request({ ...options, ledgerId: null, header: undefined, noLedger: true })
            .then(resolve)
            .catch(reject)
          return
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

// 离线中间件：装饰 GET（可缓存接口先网络后缓存）与 POST（收支创建离线入队 + 乐观上屏 + 幂等键）。
// 在线成功路径行为零改变；PUT / DELETE 不参与离线，直通底层 request。
// 同步引擎重放用未装饰的 request（见 offline/sync.js），避免「重放失败又入队」。
const offline = createOfflineHttp({ rawRequest: request })

export const http = {
  get: (url, opts) => offline.get(url, opts),
  post: (url, data, opts) => offline.post(url, data, opts),
  put: (url, data, opts) => request({ url, method: 'PUT', data, ...opts }),
  del: (url, opts) => request({ url, method: 'DELETE', ...opts })
}

/** 供同步引擎重放使用的离线中间件实例（其 replay 走底层 request）。 */
export const offlineHttp = offline
