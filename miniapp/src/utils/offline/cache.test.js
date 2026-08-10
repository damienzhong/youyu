/**
 * Feature: offline-sync
 *  - Property 8: 缓存往返一致 + 账本隔离
 *  - Property 9: 清缓存不伤队列
 *
 * Validates: Requirements 2.1, 2.2, 2.6, 2.7, 8.5
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import fc from 'fast-check'
import { isCacheable, cacheKey, putCache, getCache, clearCache, cacheSize } from './cache'

function installUniMock({ faults = {} } = {}) {
  const store = new Map()
  globalThis.uni = {
    getStorageSync(key) {
      if (faults.get) throw new Error('mock get failed')
      return store.has(key) ? store.get(key) : ''
    },
    setStorageSync(key, value) {
      if (faults.set) throw new Error('mock set failed')
      store.set(key, value)
    },
    removeStorageSync(key) {
      if (faults.remove) throw new Error('mock remove failed')
      store.delete(key)
    },
    getStorageInfoSync() {
      if (faults.info) throw new Error('mock info failed')
      return { keys: [...store.keys()] }
    }
  }
  return store
}

beforeEach(() => {
  installUniMock()
})

describe('offline/cache · 白名单', () => {
  it('按月流水与分类列表命中白名单', () => {
    expect(isCacheable('/transactions?month=2026-08')).toBe(true)
    expect(isCacheable('/all/transactions?month=2026-08')).toBe(true)
    expect(isCacheable('/categories')).toBe(true)
    expect(isCacheable('/all/categories')).toBe(true)
  })
  it('敏感/非白名单接口不缓存', () => {
    expect(isCacheable('/transactions/search?q=x')).toBe(false)
    expect(isCacheable('/transactions/recycle')).toBe(false)
    expect(isCacheable('/auth/login')).toBe(false)
    expect(isCacheable('/export')).toBe(false)
    expect(isCacheable('')).toBe(false)
    expect(isCacheable(null)).toBe(false)
  })
  it('非白名单 putCache 不写入', () => {
    expect(putCache('/auth/login', 1, { token: 'x' })).toBe(false)
    expect(getCache('/auth/login', 1)).toBe(null)
  })
})

describe('offline/cache · 容错', () => {
  it('存储抛错时 put 返回 false、get 返回 null，不抛异常', () => {
    installUniMock({ faults: { set: true } })
    expect(() => putCache('/categories', 1, [1, 2])).not.toThrow()
    expect(putCache('/categories', 1, [1, 2])).toBe(false)
    installUniMock({ faults: { get: true } })
    expect(() => getCache('/categories', 1)).not.toThrow()
    expect(getCache('/categories', 1)).toBe(null)
  })
})

describe('offline/cache · Property 8：往返一致 + 账本隔离', () => {
  const cacheableUrl = fc.constantFrom(
    '/transactions?month=2026-08',
    '/all/transactions?month=2026-08',
    '/categories'
  )
  const jsonData = fc.jsonValue()

  it('put 后 get 得到深相等副本', () => {
    fc.assert(
      fc.property(cacheableUrl, fc.integer({ min: 1, max: 999 }), jsonData, (url, ledgerId, data) => {
        installUniMock()
        expect(putCache(url, ledgerId, data)).toBe(true)
        const got = getCache(url, ledgerId)
        expect(got).not.toBe(null)
        expect(got.data).toStrictEqual(data)
        expect(typeof got.at).toBe('number')
      }),
      { numRuns: 150 }
    )
  })

  it('不同账本同路径互不覆盖、互不读取', () => {
    fc.assert(
      fc.property(
        cacheableUrl,
        fc.tuple(fc.integer({ min: 1, max: 500 }), fc.integer({ min: 501, max: 999 })),
        fc.jsonValue(),
        fc.jsonValue(),
        (url, [lidA, lidB], dataA, dataB) => {
          installUniMock()
          putCache(url, lidA, dataA)
          putCache(url, lidB, dataB)
          expect(getCache(url, lidA).data).toStrictEqual(dataA)
          expect(getCache(url, lidB).data).toStrictEqual(dataB)
          // 键不同
          expect(cacheKey(url, lidA)).not.toBe(cacheKey(url, lidB))
        }
      ),
      { numRuns: 150 }
    )
  })
})

describe('offline/cache · Property 9：清缓存不伤队列', () => {
  it('任意缓存 + Outbox 状态下 clearCache 后 youyu_outbox 逐字节不变', () => {
    fc.assert(
      fc.property(
        fc.array(fc.tuple(fc.constantFrom('/categories', '/transactions?month=2026-08'), fc.integer({ min: 1, max: 9 }), fc.jsonValue()), { maxLength: 20 }),
        fc.jsonValue(),
        (cacheEntries, outboxValue) => {
          const store = installUniMock()
          // 预置 Outbox
          store.set('youyu_outbox', outboxValue)
          const before = JSON.stringify(store.get('youyu_outbox'))
          // 预置缓存
          for (const [url, lid, data] of cacheEntries) putCache(url, lid, data)
          // 清缓存
          clearCache()
          // Outbox 逐字节不变
          expect(JSON.stringify(store.get('youyu_outbox'))).toBe(before)
          // 缓存键全部清空
          expect(cacheSize().count).toBe(0)
        }
      ),
      { numRuns: 150 }
    )
  })
})
