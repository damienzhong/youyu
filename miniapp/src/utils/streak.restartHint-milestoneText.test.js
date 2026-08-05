/**
 * Feature: streak-system, Property 15: miniapp 纯逻辑（文案）— restartHint 与 milestoneText 的属性测试
 *
 * 任务 9.3 第二支：断链文案与里程碑文案的边界。
 *
 * `restartHint(overview)` 的不变式：
 * - `broken` 非真 → 恒返回 ''；
 * - `broken` 为真且 `lastStreakDays` 不可解析（null / undefined / 非数字文本 / NaN / Infinity）→ ''；
 * - `broken` 为真且 `lastStreakDays` 可解析为有限数 → 文案含该数值；
 * - 输出恒为字符串、永不抛出、恒不含 STREAK_FORBIDDEN_WORDS 任一禁词（反挫败感靠文案不靠改数字）。
 *
 * `milestoneText(overview)` 的不变式：
 * - `nextMilestone` 不可解析（含 null）→ 「已达成全部里程碑」；
 * - `nextMilestone` 可解析且 `daysToNextMilestone >= 1` → 文案含下一里程碑与还需天数两个数值；
 * - `daysToNextMilestone` 为 0 / 负数 / null / 不可解析 → 按空处理，返回 '' 不展示任何数值；
 * - 输出恒为字符串、永不抛出、恒不含任一禁词；里程碑数值一律取入参，不写死 7/30/100/365。
 *
 * Validates: Requirements 9.4, 9.8, 9.16
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { restartHint, milestoneText, STREAK_FORBIDDEN_WORDS } from './streak'

/** 断言文本不含任何禁词。 */
function expectNoForbiddenWord(text) {
  for (const w of STREAK_FORBIDDEN_WORDS) {
    expect(text.includes(w)).toBe(false)
  }
}

/** 真正不可解析为有限数的取值：toFiniteOrNull 对它们返回 null。 */
const unparseable = fc.constantFrom(null, undefined, '', 'abc', '12ab', 'NaN', 'Infinity', {}, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY)

/** 可解析为有限数的取值族（含字符串数字、边界）。 */
const finiteNumberish = fc.oneof(
  fc.integer({ min: -(10 ** 6), max: 10 ** 6 }),
  fc.constantFrom(0, 1, -1, 7, 30, 100, 365),
  fc.constantFrom('0', '1', '7', '365', '3.5', '-2')
)

/** broken 字段的各种取值（只有严格 === true 才算断链）。 */
const brokenish = fc.constantFrom(true, false, null, undefined, 'true', 1, 0, 'false')

describe('任务 9.3 / Property 15: restartHint', () => {
  it('恒返回字符串、不抛出、不含禁词', () => {
    fc.assert(
      fc.property(fc.oneof(fc.record({ broken: brokenish, lastStreakDays: fc.oneof(finiteNumberish, unparseable) }), fc.constantFrom(null, undefined, 0, 'x', [])), (overview) => {
        const text = restartHint(overview)
        expect(typeof text).toBe('string')
        expectNoForbiddenWord(text)
      }),
      { numRuns: 500 }
    )
  })

  it('broken 非严格 true 时恒返回 ""', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(false, null, undefined, 'true', 1, 0, 'false'),
        fc.oneof(finiteNumberish, unparseable),
        (broken, lastStreakDays) => {
          expect(restartHint({ broken, lastStreakDays })).toBe('')
        }
      ),
      { numRuns: 300 }
    )
  })

  it('broken 为真但 lastStreakDays 不可解析时返回 ""', () => {
    fc.assert(
      fc.property(unparseable, (lastStreakDays) => {
        expect(restartHint({ broken: true, lastStreakDays })).toBe('')
      }),
      { numRuns: 200 }
    )
  })

  it('broken 为真且 lastStreakDays 可解析时文案含该数值且不含禁词', () => {
    fc.assert(
      fc.property(finiteNumberish, (lastStreakDays) => {
        const text = restartHint({ broken: true, lastStreakDays })
        expect(text).toContain(String(Number(lastStreakDays)))
        expect(text).toContain('重新开始')
        expectNoForbiddenWord(text)
      }),
      { numRuns: 300 }
    )
  })
})

describe('任务 9.3 / Property 15: milestoneText', () => {
  it('恒返回字符串、不抛出、不含禁词', () => {
    fc.assert(
      fc.property(
        fc.oneof(
          fc.record({ nextMilestone: fc.oneof(finiteNumberish, unparseable), daysToNextMilestone: fc.oneof(finiteNumberish, unparseable) }),
          fc.constantFrom(null, undefined, 0, 'x', [])
        ),
        (overview) => {
          const text = milestoneText(overview)
          expect(typeof text).toBe('string')
          expectNoForbiddenWord(text)
        }
      ),
      { numRuns: 500 }
    )
  })

  it('nextMilestone 不可解析（含 null）时返回「已达成全部里程碑」', () => {
    fc.assert(
      fc.property(unparseable, fc.oneof(finiteNumberish, unparseable), (nextMilestone, daysToNextMilestone) => {
        expect(milestoneText({ nextMilestone, daysToNextMilestone })).toBe('已达成全部里程碑')
      }),
      { numRuns: 200 }
    )
  })

  it('nextMilestone 可解析且 daysToNextMilestone >= 1 时文案含两个数值', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 10 ** 6 }), fc.integer({ min: 1, max: 10 ** 6 }), (next, days) => {
        const text = milestoneText({ nextMilestone: next, daysToNextMilestone: days })
        expect(text).toContain(String(next))
        expect(text).toContain(String(days))
        expectNoForbiddenWord(text)
      }),
      { numRuns: 300 }
    )
  })

  it('daysToNextMilestone 为 0 / 负数 / null / 不可解析时按空处理返回 ""', () => {
    const nonPositiveOrUnparseable = fc.oneof(
      fc.integer({ min: -(10 ** 6), max: 0 }),
      fc.constantFrom(0, -1, '0', '-5'),
      unparseable
    )
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 10 ** 6 }), nonPositiveOrUnparseable, (next, days) => {
        expect(milestoneText({ nextMilestone: next, daysToNextMilestone: days })).toBe('')
      }),
      { numRuns: 300 }
    )
  })

  it('里程碑数值取自入参，不写死 7/30/100/365', () => {
    // 用一组与常见门槛不同的数值验证文案直接回显入参
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 999 }).filter((n) => ![7, 30, 100, 365].includes(n)), fc.integer({ min: 1, max: 999 }), (next, days) => {
        const text = milestoneText({ nextMilestone: next, daysToNextMilestone: days })
        expect(text).toContain(String(next))
        expect(text).toContain(String(days))
      }),
      { numRuns: 200 }
    )
  })
})
