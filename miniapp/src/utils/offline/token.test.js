import { describe, it, expect } from 'vitest'
import { newClientToken, newLocalId, isLocalId, buildOptimisticTx } from './token'

describe('offline/token', () => {
  it('newClientToken 带 ct_ 前缀且高概率唯一', () => {
    const set = new Set()
    for (let i = 0; i < 1000; i++) {
      const t = newClientToken()
      expect(t.startsWith('ct_')).toBe(true)
      set.add(t)
    }
    expect(set.size).toBe(1000)
  })

  it('newLocalId 带 local_ 前缀且被 isLocalId 识别', () => {
    const set = new Set()
    for (let i = 0; i < 1000; i++) {
      const id = newLocalId()
      expect(id.startsWith('local_')).toBe(true)
      expect(isLocalId(id)).toBe(true)
      set.add(id)
    }
    expect(set.size).toBe(1000)
  })

  it('isLocalId 对服务端 id / 非字符串返回 false', () => {
    expect(isLocalId(123)).toBe(false)
    expect(isLocalId('42')).toBe(false)
    expect(isLocalId(null)).toBe(false)
    expect(isLocalId(undefined)).toBe(false)
  })

  it('buildOptimisticTx 保留 payload 字段并打本地/待同步标记', () => {
    const payload = { type: 'expense', amount: '28.00', accountId: 3, categoryId: 5, occurredAt: '2026-08-10T18:20:00', note: '晚餐' }
    const clientToken = 'ct_abc'
    const localId = 'local_xyz'
    const tx = buildOptimisticTx(payload, { clientToken, localId })
    expect(tx.type).toBe('expense')
    expect(tx.amount).toBe('28.00')
    expect(tx.accountId).toBe(3)
    expect(tx.categoryId).toBe(5)
    expect(tx.note).toBe('晚餐')
    expect(tx.id).toBe(localId)
    expect(tx.localId).toBe(localId)
    expect(tx.clientToken).toBe(clientToken)
    expect(tx.__local).toBe(true)
    expect(tx.__pending).toBe(true)
    // 不改动原 payload
    expect(payload.id).toBeUndefined()
    expect(payload.__local).toBeUndefined()
  })

  it('buildOptimisticTx 缺 occurredAt 时用当前时间兜底 createdAt', () => {
    const tx = buildOptimisticTx({ type: 'income', amount: '1.00' }, { clientToken: 'ct_1', localId: 'local_1' })
    expect(typeof tx.createdAt).toBe('string')
    expect(tx.createdAt.length).toBeGreaterThan(0)
  })
})
