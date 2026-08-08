/**
 * Feature: growth-level-system, 任务 12.1: `levelProgress` 与 `badgeProgressText` 的属性测试
 *
 * `levelProgress(overview)` 的不变式：结果恒落在 [0, 1] 的闭区间内，永不返回 NaN / Infinity /
 * 负数 / >1。覆盖未满级正常值、分母为 0、`nextLevelExp` 为 null（满级取 1）、字段缺失、
 * `NaN`、`Infinity`、负数、字符串数字、非数值文本等畸形入参。
 *
 * `badgeProgressText(badge)` 的不变式：未点亮恒返回 `${current} / ${target}`，
 * 已点亮（`unlocked === true`）恒返回 `''`。
 *
 * 本任务只覆盖 `utils/growth.js` 的两个纯函数（页面渲染由手工验收清单覆盖）。
 *
 * Validates: Requirements 13.5, 13.6, 13.7
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { levelProgress, badgeProgressText } from './growth'

/** 任意「数值型字段」取值族：有限数、边界、非有限、字符串数字、非数值文本、空值、非标量。 */
const anyNumberish = fc.oneof(
  fc.integer({ min: -(10 ** 9), max: 10 ** 9 }),
  fc.double({ min: -(10 ** 9), max: 10 ** 9, noNaN: false }),
  fc.constantFrom(0, -0, 1, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
  fc.constantFrom('0', '1', '3.5', '-2', 'abc', '12ab', '', '   ', 'NaN', 'Infinity'),
  fc.constantFrom(null, undefined, {}, [], true, false)
)

/** 任意 overview 对象：每个数值字段独立取自畸形族，maxLevelReached 亦覆盖各种取值。 */
const anyOverview = fc.record({
  expInCurrentLevel: anyNumberish,
  currentLevelExp: anyNumberish,
  nextLevelExp: fc.oneof(anyNumberish, fc.constant(null)),
  maxLevelReached: fc.constantFrom(true, false, null, undefined, 'true', 1, 0)
})

describe('任务 12.1: levelProgress 与 badgeProgressText', () => {
  it('levelProgress 恒落在 [0, 1]，永不返回 NaN / Infinity / 负数 / >1', () => {
    fc.assert(
      fc.property(anyOverview, (overview) => {
        const r = levelProgress(overview)
        expect(typeof r).toBe('number')
        expect(Number.isFinite(r)).toBe(true)
        expect(r).toBeGreaterThanOrEqual(0)
        expect(r).toBeLessThanOrEqual(1)
      }),
      { numRuns: 250 }
    )
  })

  it('levelProgress 对 null / undefined / 非对象入参安全降级为有限数且落在 [0, 1]', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, 0, '', 'x', 42, true, [], NaN), (bad) => {
        const r = levelProgress(bad)
        expect(Number.isFinite(r)).toBe(true)
        expect(r).toBeGreaterThanOrEqual(0)
        expect(r).toBeLessThanOrEqual(1)
      }),
      { numRuns: 100 }
    )
  })

  it('levelProgress 满级（maxLevelReached 为真 + nextLevelExp 为 null/undefined）恒返回 1', () => {
    fc.assert(
      fc.property(anyNumberish, anyNumberish, fc.constantFrom(null, undefined), (numerator, base, next) => {
        const r = levelProgress({
          maxLevelReached: true,
          nextLevelExp: next,
          expInCurrentLevel: numerator,
          currentLevelExp: base
        })
        expect(r).toBe(1)
      }),
      { numRuns: 100 }
    )
  })

  it('levelProgress 未满级正常值等于 clamp(分子/分母, 0, 1)', () => {
    // 分母 >0 的正常区间：base < next，分子任意非负有限数。
    const arb = fc
      .tuple(
        fc.integer({ min: 0, max: 10 ** 6 }), // base
        fc.integer({ min: 1, max: 10 ** 6 }), // span (=> next = base + span > base)
        fc.integer({ min: 0, max: 2 * 10 ** 6 }) // numerator
      )
      .map(([base, span, numerator]) => ({ base, next: base + span, numerator }))
    fc.assert(
      fc.property(arb, ({ base, next, numerator }) => {
        const r = levelProgress({
          maxLevelReached: false,
          expInCurrentLevel: numerator,
          currentLevelExp: base,
          nextLevelExp: next
        })
        const expected = Math.min(1, Math.max(0, numerator / (next - base)))
        expect(r).toBeCloseTo(expected, 10)
      }),
      { numRuns: 150 }
    )
  })

  it('levelProgress 分母 <= 0（含 0 与负数）返回 0，不产生 Infinity / NaN', () => {
    const arb = fc
      .tuple(fc.integer({ min: -(10 ** 6), max: 10 ** 6 }), fc.integer({ min: 0, max: 10 ** 6 }), anyNumberish)
      .map(([base, back, numerator]) => ({ base, next: base - back, numerator })) // next <= base => 分母 <= 0
    fc.assert(
      fc.property(arb, ({ base, next, numerator }) => {
        const r = levelProgress({
          maxLevelReached: false,
          expInCurrentLevel: numerator,
          currentLevelExp: base,
          nextLevelExp: next
        })
        expect(r).toBe(0)
      }),
      { numRuns: 150 }
    )
  })

  it('badgeProgressText: 未点亮恒为 `${current} / ${target}`，已点亮恒为 ""', () => {
    const scalar = fc.oneof(
      fc.integer({ min: -100, max: 10 ** 6 }),
      fc.double({ noNaN: false }),
      fc.constantFrom(0, 1, Number.NaN, Number.POSITIVE_INFINITY, null, undefined, '3', 'x')
    )
    fc.assert(
      fc.property(scalar, scalar, fc.constantFrom(true, false, null, undefined, 'true', 1, 0), (current, target, unlocked) => {
        const text = badgeProgressText({ current, target, unlocked })
        if (unlocked === true) {
          expect(text).toBe('')
        } else {
          expect(text).toBe(`${current} / ${target}`)
        }
      }),
      { numRuns: 150 }
    )
  })

  it('badgeProgressText: null / undefined / 非对象入参不抛出且返回字符串', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, 0, '', 'x', 42, true, []), (bad) => {
        const text = badgeProgressText(bad)
        expect(typeof text).toBe('string')
      }),
      { numRuns: 50 }
    )
  })
})
