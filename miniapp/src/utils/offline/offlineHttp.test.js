/**
 * Feature: offline-sync
 *  - Property 10: 在线等价性（在线成功时读写与直接 rawRequest 等价，仅多 clientToken / 写缓存）
 *  - Property 11: 放开范围守卫（离线下非收支创建不入队）
 *  - Property 12: 弱网 POST 转离线入队等价
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 3.1, 3.2, 3.4, 9.1, 9.2, 9.3
 */
import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import * as outbox from './outbox'
import { createOfflineHttp, isOfflineWritable } from './offlineHttp'

function installUniMock() {
  const store = new Map()
  globalThis.uni = {
    getStorageSync: (k) => (store.has(k) ? store.get(k) : ''),
    setStorageSync: (k, v) => store.set(k, v),
    removeStorageSync: (k) => store.delete(k),
    getStorageInfoSync: () => ({ keys: [...store.keys()] }),
    showToast: () => {}
  }
  return store
}

const NET_ERR = { code: 'NETWORK_ERROR', message: '网络异常' }

beforeEach(() => installUniMock())

describe('offlineHttp · Property 10：在线等价性', () => {
  it('可缓存 GET 在线成功：返回底层结果并写入缓存', async () => {
    await fc.assert(
      fc.asyncProperty(fc.jsonValue(), async (body) => {
        installUniMock()
        const calls = []
        const http = createOfflineHttp({
          online: () => true,
          rawRequest: (o) => { calls.push(o); return Promise.resolve(body) }
        })
        const got = await http.get('/transactions?month=2026-08', { ledgerId: 7 })
        expect(got).toStrictEqual(body)
        expect(calls[0].method).toBe('GET')
        expect(calls[0].url).toBe('/transactions?month=2026-08')
      }),
      { numRuns: 120 }
    )
  })

  it('非缓存 GET 直通底层，不读写缓存', async () => {
    const calls = []
    const http = createOfflineHttp({ online: () => true, rawRequest: (o) => { calls.push(o); return Promise.resolve({ ok: 1 }) } })
    await http.get('/transactions/search?q=x')
    expect(calls.length).toBe(1)
  })

  it('在线 POST 收支成功：透传底层，且注入 clientToken（其余字段不变）', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom('expense', 'income'),
        fc.integer({ min: 1, max: 999999 }),
        async (type, cents) => {
          installUniMock()
          let sent = null
          const http = createOfflineHttp({ online: () => true, rawRequest: (o) => { sent = o; return Promise.resolve({ id: 1 }) } })
          const data = { type, amount: `${cents}`, accountId: 3, categoryId: 5 }
          await http.post('/transactions', data, {})
          // 底层收到的 data 除多出 clientToken 外与原一致
          expect(sent.data.type).toBe(type)
          expect(sent.data.amount).toBe(`${cents}`)
          expect(sent.data.accountId).toBe(3)
          expect(typeof sent.data.clientToken).toBe('string')
          expect(sent.data.clientToken.startsWith('ct_')).toBe(true)
          // 未入队
          expect(outbox.count()).toBe(0)
        }
      ),
      { numRuns: 120 }
    )
  })

  it('非收支 POST 直通底层，不加 clientToken、不入队', async () => {
    let sent = null
    const http = createOfflineHttp({ online: () => true, rawRequest: (o) => { sent = o; return Promise.resolve({ ok: 1 }) } })
    await http.post('/accounts/transfer', { sourceAccountId: 1, destinationAccountId: 2, amount: '10' }, {})
    expect(sent.data.clientToken).toBeUndefined()
    expect(outbox.count()).toBe(0)
  })
})

describe('offlineHttp · Property 11：放开范围守卫（离线不入队）', () => {
  it('离线下非收支创建 / 非 /transactions 写不入队（直通底层）', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom(
          ['/accounts/transfer', { sourceAccountId: 1, destinationAccountId: 2, amount: '10' }],
          ['/transactions/adjust', { accountId: 1, balance: '5' }],
          ['/transactions', { type: 'transfer', amount: '3' }],
          ['/loans', { amount: '9' }]
        ),
        async ([url, data]) => {
          installUniMock()
          const http = createOfflineHttp({ online: () => false, rawRequest: () => Promise.reject(NET_ERR) })
          expect(isOfflineWritable(url, data)).toBe(false)
          await expect(http.post(url, data, {})).rejects.toEqual(NET_ERR)
          // 一律不入队
          expect(outbox.count()).toBe(0)
        }
      ),
      { numRuns: 120 }
    )
  })

  it('离线收支创建则入队并乐观返回', async () => {
    installUniMock()
    const http = createOfflineHttp({ online: () => false, rawRequest: () => Promise.reject(NET_ERR) })
    const tx = await http.post('/transactions', { type: 'expense', amount: '28.00', accountId: 3, categoryId: 5 }, {})
    expect(tx.__pending).toBe(true)
    expect(tx.id.startsWith('local_')).toBe(true)
    expect(outbox.count()).toBe(1)
    expect(outbox.list()[0].payload.clientToken).toBe(tx.clientToken)
  })
})

describe('offlineHttp · Property 12：弱网 POST 转离线入队等价', () => {
  it('在线收支 POST 遇 NETWORK_ERROR → 一条 PENDING + 乐观返回，不抛错', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom('expense', 'income'),
        fc.integer({ min: 1, max: 999999 }),
        fc.integer({ min: 1, max: 99 }),
        async (type, cents, ledgerId) => {
          installUniMock()
          const http = createOfflineHttp({ online: () => true, rawRequest: () => Promise.reject(NET_ERR) })
          const data = { type, amount: `${cents}`, accountId: 3, categoryId: 5 }
          const tx = await http.post('/transactions', data, { ledgerId })
          // 乐观返回
          expect(tx.__pending).toBe(true)
          expect(tx.__local).toBe(true)
          // 恰一条 PENDING，payload 保留且带 clientToken、ledgerId 快照
          const items = outbox.list()
          expect(items.length).toBe(1)
          expect(items[0].status).toBe('PENDING')
          expect(items[0].ledgerId).toBe(ledgerId)
          expect(items[0].payload.type).toBe(type)
          expect(items[0].payload.clientToken).toBe(tx.clientToken)
        }
      ),
      { numRuns: 120 }
    )
  })

  it('在线收支 POST 遇业务错误 → 抛出，不入队', async () => {
    installUniMock()
    const bizErr = { code: 'AMOUNT_INVALID', message: '金额非法' }
    const http = createOfflineHttp({ online: () => true, rawRequest: () => Promise.reject(bizErr) })
    await expect(http.post('/transactions', { type: 'expense', amount: '0', accountId: 3, categoryId: 5 }, {})).rejects.toEqual(bizErr)
    expect(outbox.count()).toBe(0)
  })
})

describe('offlineHttp · 缓存回落', () => {
  it('可缓存 GET 网络失败且有快照 → 返回快照并带 __fromCache', async () => {
    installUniMock()
    let fail = false
    const http = createOfflineHttp({ online: () => true, rawRequest: () => (fail ? Promise.reject(NET_ERR) : Promise.resolve([{ id: 1 }])) })
    // 先在线成功写缓存
    await http.get('/transactions?month=2026-08', { ledgerId: 2 })
    // 再离线失败回落
    fail = true
    const got = await http.get('/transactions?month=2026-08', { ledgerId: 2 })
    expect(got).toStrictEqual([{ id: 1 }])
    expect(got.__fromCache).toBe(true)
  })

  it('可缓存 GET 网络失败且无快照 → 抛原 NETWORK_ERROR', async () => {
    installUniMock()
    const http = createOfflineHttp({ online: () => true, rawRequest: () => Promise.reject(NET_ERR) })
    await expect(http.get('/categories', {})).rejects.toEqual(NET_ERR)
  })
})
