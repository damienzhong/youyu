/**
 * 读缓存（Offline_Sync_System 纯内核之一）：GET 响应的本地快照，仅作离线兜底展示。
 *
 * 设计约束（与 utils/invite.js 的存储容错哲学一致）：
 * - 只缓存明确列入白名单的读接口（按月流水、分类列表），不缓存鉴权 / 导出 / 二维码等敏感或大体积接口。
 * - 缓存按「账本 + 接口路径」隔离：不同账本的同路径快照互不覆盖、互不读取。
 * - 所有存储读写一律 try/catch 吞异常：缓存故障绝不抛出、绝不阻断记账主路径。
 * - 缓存不是事实源：金额最终口径永远以服务端返回为准。
 *
 * 仅依赖 uni 的同步存储 API（getStorageSync / setStorageSync / removeStorageSync / getStorageInfoSync），
 * 从而能在 node 环境下用 vitest 直接测（测试 mock 全局 uni）。
 */

const CACHE_PREFIX = 'youyu_cache_'

/**
 * 读缓存白名单：命中任一匹配器即可缓存。
 * 覆盖「打开看数据」的核心读接口：按月流水（含全部账本聚合）、分类列表（含聚合）。
 */
const WHITELIST = [
  (path) => path.startsWith('/transactions?month='),
  (path) => path.startsWith('/all/transactions?month='),
  (path) => path === '/categories' || path.startsWith('/categories?'),
  (path) => path === '/all/categories' || path.startsWith('/all/categories?')
]

/** 判断某 GET 请求 url 是否可缓存。 */
export function isCacheable(url) {
  if (typeof url !== 'string' || !url) return false
  return WHITELIST.some((m) => {
    try {
      return m(url)
    } catch (e) {
      return false
    }
  })
}

/** 把 url 归一为可用于存储键的片段（去掉不利于键的字符）。 */
function pathKey(url) {
  return String(url).replace(/[^a-zA-Z0-9]/g, '_')
}

/** 构造缓存键：youyu_cache_<ledgerId>_<pathKey>。ledgerId 缺省用 'default'。 */
export function cacheKey(url, ledgerId) {
  const lid = ledgerId == null || ledgerId === '' ? 'default' : String(ledgerId)
  return `${CACHE_PREFIX}${lid}_${pathKey(url)}`
}

/**
 * 写入快照：{ at: 时间戳, data: 响应体 }。存储异常吞掉（返回 false）。
 * 仅当 url 可缓存时才写；非白名单直接忽略（返回 false）。
 */
export function putCache(url, ledgerId, data) {
  if (!isCacheable(url)) return false
  try {
    uni.setStorageSync(cacheKey(url, ledgerId), { at: Date.now(), data })
    return true
  } catch (e) {
    return false
  }
}

/**
 * 读取快照：命中返回 { at, data }，未命中 / 异常返回 null。
 */
export function getCache(url, ledgerId) {
  try {
    const v = uni.getStorageSync(cacheKey(url, ledgerId))
    if (v && typeof v === 'object' && 'data' in v) return v
    return null
  } catch (e) {
    return null
  }
}

/** 枚举所有缓存键（异常时返回空数组）。 */
function allCacheKeys() {
  try {
    const info = uni.getStorageInfoSync()
    const keys = (info && info.keys) || []
    return keys.filter((k) => typeof k === 'string' && k.startsWith(CACHE_PREFIX))
  } catch (e) {
    return []
  }
}

/**
 * 清理全部读缓存（仅删 CACHE_PREFIX 快照，绝不触碰 Outbox 等其它存储键）。
 * 返回被删除的键数量。
 */
export function clearCache() {
  const keys = allCacheKeys()
  let n = 0
  for (const k of keys) {
    try {
      uni.removeStorageSync(k)
      n++
    } catch (e) {
      // 单键删除失败忽略，继续清理其余
    }
  }
  return n
}

/** 缓存占用概况：{ count: 键数, bytes: 近似字节数 }。异常时返回安全默认。 */
export function cacheSize() {
  const keys = allCacheKeys()
  let bytes = 0
  for (const k of keys) {
    try {
      const v = uni.getStorageSync(k)
      bytes += JSON.stringify(v || '').length
    } catch (e) {
      // 单键读取失败忽略
    }
  }
  return { count: keys.length, bytes }
}
