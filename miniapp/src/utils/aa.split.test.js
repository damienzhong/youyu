/**
 * AA 前端计算工具单元 + 属性测试：均分守恒、自定义校验、本笔影响拆分。
 * 与后端 AaMath 口径一致（分守恒、余数校正）。
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  toCents,
  splitEvenCents,
  evenSharesByUser,
  isValidCustomSplit,
  sumShares,
  payerImpact
} from './aa'

describe('toCents', () => {
  it('rounds yuan to cents', () => {
    expect(toCents(260)).toBe(26000)
    expect(toCents('12.34')).toBe(1234)
    expect(toCents(0.1 + 0.2)).toBe(30) // 浮点安全
    expect(toCents('abc')).toBe(0)
  })
})

describe('splitEvenCents', () => {
  it('splits 260.00 among 4 evenly (65 each)', () => {
    expect(splitEvenCents(26000, 4)).toEqual([6500, 6500, 6500, 6500])
  })

  it('corrects remainder onto the first shares', () => {
    // 1000 / 3 = 333 余 1 → 前一份 +1
    expect(splitEvenCents(1000, 3)).toEqual([334, 333, 333])
    // 10 / 3 = 3 余 1
    expect(splitEvenCents(10, 3)).toEqual([4, 3, 3])
  })

  it('returns empty for invalid n or negative total', () => {
    expect(splitEvenCents(100, 0)).toEqual([])
    expect(splitEvenCents(-1, 3)).toEqual([])
  })
})

describe('evenSharesByUser', () => {
  it('maps shares by participant id preserving order and conservation', () => {
    const map = evenSharesByUser(26000, [7, 8, 9, 10])
    expect(map).toEqual({ 7: 6500, 8: 6500, 9: 6500, 10: 6500 })
  })
})

describe('isValidCustomSplit', () => {
  it('accepts shares summing to total', () => {
    expect(isValidCustomSplit(26000, { 1: 8000, 2: 6000, 3: 6000, 4: 6000 })).toBe(true)
  })
  it('rejects mismatched sum or negative share', () => {
    expect(isValidCustomSplit(26000, { 1: 8000, 2: 6000 })).toBe(false)
    expect(isValidCustomSplit(26000, { 1: -100, 2: 26100 })).toBe(false)
  })
})

describe('payerImpact', () => {
  it('splits deduction into my consumption and lent', () => {
    // 均分：付款人自摊 65，借出 195
    expect(payerImpact(26000, 6500)).toEqual({
      accountDeductCents: 26000,
      myConsumptionCents: 6500,
      lentCents: 19500
    })
  })
  it('lent is 0 when payer consumes the whole amount', () => {
    expect(payerImpact(26000, 26000)).toEqual({
      accountDeductCents: 26000,
      myConsumptionCents: 26000,
      lentCents: 0
    })
  })
})

describe('property: even split conserves total (Validates: Requirements 3.3)', () => {
  it('sum of even shares equals total for any total/participant count', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 100_000_00 }),
        fc.integer({ min: 1, max: 50 }),
        (totalCents, n) => {
          const parts = splitEvenCents(totalCents, n)
          expect(parts).toHaveLength(n)
          expect(sumShares(parts)).toBe(totalCents)
          // 每份差异不超过 1 分
          const min = Math.min(...parts)
          const max = Math.max(...parts)
          expect(max - min).toBeLessThanOrEqual(1)
        }
      ),
      { numRuns: 500 }
    )
  })
})

describe('property: payer impact conserves deduction (Validates: Requirements 3.6)', () => {
  it('my consumption + lent = account deduction', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 100_000_00 }),
        fc.integer({ min: 0, max: 100_000_00 }),
        (totalCents, shareCents) => {
          const { accountDeductCents, myConsumptionCents, lentCents } = payerImpact(
            totalCents,
            Math.min(shareCents, totalCents)
          )
          expect(myConsumptionCents + lentCents).toBe(accountDeductCents)
          expect(lentCents).toBeGreaterThanOrEqual(0)
        }
      ),
      { numRuns: 500 }
    )
  })
})
