/**
 * Feature: offline-sync
 *  - Property 3: 串行重放-全成功出队
 *  - Property 4: 网络错误即停（后续保 PENDING、不再调用）
 *  - Property 5: 业务错误不阻塞（该项 FAILED，后续继续）
 *  - Property 6: token/localId 全程稳定
 *
 * Validates: Requirements 5.4, 5.5, 5.6, 7.1, 7.4, 7.5, 6.6
 */
import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import * as outbox from './outbox'
import { createSyncEngine, defaultIsNetworkError } from './syncEngine'

function installUniMock() {
  const store = new Map()
  globalThis.uni = {
    getStorageSync: (k) => (store.has(k) ? store.get(k) : ''),
    setStorageSync: (k, v) => store.set(k, v),
    removeStorageSync: (k) => store.delete(k)
  }
  return store
}

beforeEach(() => installUniMock())

const NET_ERR = { code: 'NETWORK_ERROR', message: '网络异常' }
function bizErr(msg) {
  return { code: 'AMOUNT_INVALID', message: msg }
}

function seed(n) {
  for (let i = 0; i < n; i++) {
    outbox.enqueue({ clientToken: `ct_${i}`, localId: `local_${i}`, ledgerId: 1, payload: { type: 'expense', amount: `${i + 1}.00` } })
  }
}

describe('syncEngine · Property 3：全成功出队', () => {
  it('全部成功后队列清空，replay 按顺序调用 N 次', async () => {
    await fc.assert(
      fc.asyncProperty(fc.integer({ min: 0, max: 25 }), async (n) => {
        installUniMock()
        seed(n)
        const calls = []
        const engine = createSyncEngine({
          outbox,
          replay: async (item) => {
            calls.push(item.clientToken)
            return { id: 900 + calls.length, clientToken: item.clientToken }
          }
        })
        const res = await engine.sync()
        expect(res.synced).toBe(n)
        expect(res.failed).toBe(0)
        expect(res.stopped).toBe(false)
        expect(outbox.count()).toBe(0)
        expect(calls).toEqual(Array.from({ length: n }, (_, i) => `ct_${i}`))
      }),
      { numRuns: 120 }
    )
  })
})

describe('syncEngine · Property 4：网络错误即停', () => {
  it('第 k 项网络错误→k 及其后保 PENDING、不再调用后续', async () => {
    await fc.assert(
      fc.asyncProperty(fc.integer({ min: 1, max: 20 }), fc.integer({ min: 0, max: 19 }), async (n, kRaw) => {
        installUniMock()
        seed(n)
        const k = kRaw % n
        const calls = []
        const engine = createSyncEngine({
          outbox,
          replay: async (item) => {
            calls.push(item.clientToken)
            if (item.clientToken === `ct_${k}`) throw NET_ERR
            return { id: 1, clientToken: item.clientToken }
          }
        })
        const res = await engine.sync()
        expect(res.stopped).toBe(true)
        // 前 k 项已出队，k 及其后仍在队列
        const remaining = outbox.list().map((x) => x.clientToken)
        expect(remaining).toEqual(Array.from({ length: n - k }, (_, i) => `ct_${i + k}`))
        // 第 k 项被置回 PENDING
        expect(outbox.list()[0].status).toBe('PENDING')
        // 后续项从未被调用
        expect(calls).toEqual(Array.from({ length: k + 1 }, (_, i) => `ct_${i}`))
      }),
      { numRuns: 120 }
    )
  })
})

describe('syncEngine · Property 5：业务错误不阻塞', () => {
  it('第 k 项业务错误→该项 FAILED、其余成功出队、token 不变', async () => {
    await fc.assert(
      fc.asyncProperty(fc.integer({ min: 1, max: 20 }), fc.integer({ min: 0, max: 19 }), async (n, kRaw) => {
        installUniMock()
        seed(n)
        const k = kRaw % n
        const engine = createSyncEngine({
          outbox,
          replay: async (item) => {
            if (item.clientToken === `ct_${k}`) throw bizErr('金额超出余额')
            return { id: 1, clientToken: item.clientToken }
          }
        })
        const res = await engine.sync()
        expect(res.failed).toBe(1)
        expect(res.synced).toBe(n - 1)
        expect(res.stopped).toBe(false)
        // 仅失败项留存且为 FAILED，token/原因保留
        const remaining = outbox.list()
        expect(remaining.map((x) => x.clientToken)).toEqual([`ct_${k}`])
        expect(remaining[0].status).toBe('FAILED')
        expect(remaining[0].failReason).toBe('金额超出余额')
      }),
      { numRuns: 120 }
    )
  })
})

describe('syncEngine · Property 6：token/localId 全程稳定', () => {
  it('入队→SYNCING→FAILED→retry→再同步，token/localId 恒定', async () => {
    await fc.assert(
      fc.asyncProperty(fc.integer({ min: 1, max: 900 }), async (i) => {
        installUniMock()
        outbox.enqueue({ clientToken: `ct_${i}`, localId: `local_${i}`, ledgerId: 1, payload: { type: 'expense', amount: '1.00' } })
        // 第一轮：业务失败
        let engine = createSyncEngine({ outbox, replay: async () => { throw bizErr('x') } })
        await engine.sync()
        let it = outbox.list()[0]
        expect(it.clientToken).toBe(`ct_${i}`)
        expect(it.localId).toBe(`local_${i}`)
        expect(it.status).toBe('FAILED')
        // 用户手动重试
        outbox.retry(`ct_${i}`)
        expect(outbox.list()[0].status).toBe('PENDING')
        // 第二轮：成功
        engine = createSyncEngine({ outbox, replay: async () => ({ id: 5 }) })
        await engine.sync()
        expect(outbox.count()).toBe(0)
      }),
      { numRuns: 120 }
    )
  })
})

describe('syncEngine · 防重入 & 回调', () => {
  it('running 时二次 sync 被跳过', async () => {
    installUniMock()
    seed(3)
    let resolveFirst
    const gate = new Promise((r) => { resolveFirst = r })
    const engine = createSyncEngine({
      outbox,
      replay: async () => { await gate; return { id: 1 } }
    })
    const p1 = engine.sync()
    const p2 = await engine.sync() // 立即返回（running）
    expect(p2.skipped).toBe(true)
    resolveFirst()
    await p1
  })

  it('onSynced 对每个成功项回调，异常不影响主流程', async () => {
    installUniMock()
    seed(2)
    const seen = []
    const engine = createSyncEngine({
      outbox,
      replay: async (item) => ({ id: 10, clientToken: item.clientToken }),
      onSynced: (item, tx) => { seen.push([item.clientToken, tx.id]); if (item.clientToken === 'ct_0') throw new Error('cb') }
    })
    const res = await engine.sync()
    expect(res.synced).toBe(2)
    expect(seen.length).toBe(2)
  })

  it('defaultIsNetworkError 仅对 NETWORK_ERROR 为真', () => {
    expect(defaultIsNetworkError({ code: 'NETWORK_ERROR' })).toBe(true)
    expect(defaultIsNetworkError({ code: 'AMOUNT_INVALID' })).toBe(false)
    expect(defaultIsNetworkError(null)).toBe(false)
  })
})
