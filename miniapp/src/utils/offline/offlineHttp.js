/**
 * 离线中间件（Offline_Sync_System 编排层）：装饰底层 request，为读加缓存回落、为收支创建加
 * 「离线入队 + 乐观上屏 + 客户端幂等键」。在线成功路径行为零改变（仅额外写读缓存 / 额外带 clientToken）。
 *
 * 完全依赖注入（rawRequest / online / ledgerResolver），可在 node 下用 vitest 直接测。
 * 同步引擎重放时使用 rawRequest（绕过本装饰），避免「重放失败又入队」的递归。
 */
import { isCacheable, putCache, getCache } from './cache'
import * as outbox from './outbox'
import { newClientToken, newLocalId, buildOptimisticTx } from './token'
import { isOnline as defaultIsOnline } from './netState'
import { STORAGE_KEYS } from '../config'

/** 从 uni 存储解析当前生效账本 id（与 request.js 的 X-Ledger-Id 口径一致）；'all' 表示聚合视图。 */
function defaultLedgerResolver(opts) {
  if (opts && opts.ledgerId != null) return opts.ledgerId
  try {
    const stored = uni.getStorageSync(STORAGE_KEYS.ledgerId)
    return stored && String(stored) !== 'all' ? stored : 'all'
  } catch (e) {
    return 'all'
  }
}

/** 判断某 POST 是否属于离线放开范围：仅 /transactions 的 expense / income 创建。 */
export function isOfflineWritable(url, data) {
  return url === '/transactions' && !!data && (data.type === 'expense' || data.type === 'income')
}

/**
 * @param {object} deps
 * @param {(options:object)=>Promise<any>} deps.rawRequest 底层请求（miniapp/src/utils/request.js 的 request）
 * @param {()=>boolean} [deps.online] 在线态读取
 * @param {(opts:object)=>*} [deps.ledgerResolver] 生效账本解析
 */
export function createOfflineHttp({ rawRequest, online = defaultIsOnline, ledgerResolver = defaultLedgerResolver } = {}) {

  function isNetworkError(err) {
    return !!err && err.code === 'NETWORK_ERROR'
  }

  /** 离线 / 弱网入队并返回乐观记录；入队写存储失败则抛出（不产生半条脏数据）。 */
  function enqueueOptimistic(payload, ledgerId) {
    const localId = newLocalId()
    outbox.enqueue({ clientToken: payload.clientToken, localId, ledgerId, payload })
    return buildOptimisticTx(payload, { clientToken: payload.clientToken, localId })
  }

  async function get(url, opts) {
    if (!isCacheable(url)) {
      return rawRequest({ url, method: 'GET', ...opts })
    }
    const ledgerId = ledgerResolver(opts)
    try {
      const body = await rawRequest({ url, method: 'GET', ...opts })
      putCache(url, ledgerId, body)
      return body
    } catch (err) {
      if (isNetworkError(err)) {
        const snap = getCache(url, ledgerId)
        if (snap) {
          const data = snap.data
          // 附缓存标记（不可枚举，避免污染业务字段 / 序列化）
          try {
            Object.defineProperty(data, '__fromCache', { value: true, enumerable: false, configurable: true })
            Object.defineProperty(data, '__cachedAt', { value: snap.at, enumerable: false, configurable: true })
          } catch (e) {
            // 基本类型无法挂标记时忽略
          }
          return data
        }
      }
      throw err
    }
  }

  async function post(url, data, opts) {
    if (!isOfflineWritable(url, data)) {
      // 放开范围外：直通底层（离线时底层将以 NETWORK_ERROR 失败，天然不入队）
      return rawRequest({ url, method: 'POST', data, ...opts })
    }
    const clientToken = data.clientToken || newClientToken()
    const payload = { ...data, clientToken }
    const ledgerId = ledgerResolver(opts)

    if (!online()) {
      return enqueueOptimistic(payload, ledgerId)
    }
    try {
      return await rawRequest({ url, method: 'POST', data: payload, ...opts })
    } catch (err) {
      if (isNetworkError(err)) {
        return enqueueOptimistic(payload, ledgerId)
      }
      throw err
    }
  }

  /** 同步引擎重放单条 Outbox 项：走底层请求（绕过本装饰）。 */
  function replay(item) {
    return rawRequest({ url: '/transactions', method: 'POST', data: item.payload, ledgerId: item.ledgerId })
  }

  return { get, post, replay, isNetworkError }
}

/**
 * 联网守卫：供页面在触发「放开范围外」的写操作（编辑 / 删除 / 转账 / 余额校准 / AA / 借贷）前调用。
 * 离线时提示并抛出，阻止操作、不入队。
 */
export function guardOnlineOnly(online = defaultIsOnline) {
  if (!online()) {
    try {
      uni.showToast({ title: '该操作需要联网', icon: 'none' })
    } catch (e) {}
    throw { code: 'OFFLINE_UNSUPPORTED', message: '该操作需要联网' }
  }
}
