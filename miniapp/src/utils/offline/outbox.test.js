/**
 * Feature: offline-sync, Property 2: 队列 FIFO 与按 token 出队不扰动其余
 *
 * 对任意入队序列：list() 顺序恒等于入队顺序；removeByToken 只移除对应项、其余相对顺序不变；
 * markSyncing/markFailed/retry 只改目标项状态、不改顺序、clientToken 恒定；存储抛错时不抛异常。
 *
 * Validates: Requirements 3.1, 3.5, 3.6, 3.7, 7.2, 7.3
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import fc from 'fast-check'
import * as outbox from './outbox'

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
    }
  }
  return store
}

beforeEach(() => installUniMock())

function makeItem(i) {
  return { clientToken: `ct_${i}`, localId: `local_${i}`, ledgerId: (i % 3) + 1, payload: { type: 'expense', amount: `${i}.00` } }
}

describe('offline/outbox · 基础', () => {
  it('enqueue 追加到队尾并初始化状态', () => {
    outbox.enqueue(makeItem(1))
    outbox.enqueue(makeItem(2))
    const items = outbox.list()
    expect(items.map((x) => x.clientToken)).toEqual(['ct_1', 'ct_2'])
    expect(items[0].status).toBe('PENDING')
    expect(items[0].retryCount).toBe(0)
    expect(items[0].payload.amount).toBe('1.00')
  })

  it('markFailed 记录原因并 retryCount+1；retry 置回 PENDING 清原因', () => {
    outbox.enqueue(makeItem(1))
    outbox.markSyncing('ct_1')
    expect(outbox.list()[0].status).toBe('SYNCING')
    outbox.markFailed('ct_1', '金额超出余额')
    let it = outbox.list()[0]
    expect(it.status).toBe('FAILED')
    expect(it.failReason).toBe('金额超出余额')
    expect(it.retryCount).toBe(1)
    outbox.retry('ct_1')
    it = outbox.list()[0]
    expect(it.status).toBe('PENDING')
    expect(it.failReason).toBe(null)
    expect(it.clientToken).toBe('ct_1')
  })

  it('计数辅助', () => {
    outbox.enqueue(makeItem(1))
    outbox.enqueue(makeItem(2))
    outbox.enqueue(makeItem(3))
    outbox.markFailed('ct_2')
    expect(outbox.count()).toBe(3)
    expect(outbox.failedCount()).toBe(1)
    expect(outbox.pendingCount()).toBe(2)
  })

  it('存储读取抛错时 list 返回空数组，不抛异常', () => {
    installUniMock({ faults: { get: true } })
    expect(() => outbox.list()).not.toThrow()
    expect(outbox.list()).toEqual([])
  })
})

describe('offline/outbox · Property 2：FIFO 与按 token 出队不扰动其余', () => {
  const ids = fc.uniqueArray(fc.integer({ min: 1, max: 999 }), { minLength: 1, maxLength: 30 })

  it('list 顺序恒等于入队顺序', () => {
    fc.assert(
      fc.property(ids, (arr) => {
        installUniMock()
        for (const i of arr) outbox.enqueue(makeItem(i))
        expect(outbox.list().map((x) => x.clientToken)).toEqual(arr.map((i) => `ct_${i}`))
      }),
      { numRuns: 150 }
    )
  })

  it('removeByToken 只移除目标，其余相对顺序不变', () => {
    fc.assert(
      fc.property(ids, fc.integer({ min: 0, max: 1000 }), (arr, pick) => {
        installUniMock()
        for (const i of arr) outbox.enqueue(makeItem(i))
        const target = arr[pick % arr.length]
        const expected = arr.filter((i) => i !== target).map((i) => `ct_${i}`)
        expect(outbox.removeByToken(`ct_${target}`)).toBe(true)
        expect(outbox.list().map((x) => x.clientToken)).toEqual(expected)
      }),
      { numRuns: 150 }
    )
  })

  it('状态变更不改顺序、不改 clientToken', () => {
    fc.assert(
      fc.property(ids, fc.integer({ min: 0, max: 1000 }), (arr, pick) => {
        installUniMock()
        for (const i of arr) outbox.enqueue(makeItem(i))
        const target = arr[pick % arr.length]
        outbox.markSyncing(`ct_${target}`)
        outbox.markFailed(`ct_${target}`, 'x')
        expect(outbox.list().map((x) => x.clientToken)).toEqual(arr.map((i) => `ct_${i}`))
        const it = outbox.list().find((x) => x.clientToken === `ct_${target}`)
        expect(it.status).toBe('FAILED')
      }),
      { numRuns: 150 }
    )
  })
})
