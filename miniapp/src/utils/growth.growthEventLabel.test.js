/**
 * Feature: growth-level-system, 任务 12.3: `growthEventLabel` 映射完备性的属性测试
 *
 * `growthEventLabel(eventType, eventKey)` 的不变式：
 * - 六个已知事件类型（FIRST_RECORD / DAILY_RECORD / STREAK / BUDGET_MET / FIRST_INVITE / BADGE）
 *   各自产出非空中文文案，且互不相同（映射为双射，不同类型不会撞同一文案）。
 * - 任一已知类型的文案都不泄漏原始枚举字符串（不出现大写 ASCII 的枚举名）。
 * - DAILY_RECORD 文案含 eventKey 冒号后半段的日期、BUDGET_MET 含月份（均从 eventKey 取，不另发请求）。
 * - 未知类型 / 空串 / null / undefined / 畸形 eventKey 一律走「成长记录」兜底，
 *   且绝不出现原始枚举字符串。
 *
 * 本任务只覆盖 `utils/growth.js` 的 `growthEventLabel` 纯函数（页面渲染由手工验收清单覆盖）。
 *
 * Validates: Requirements 13.10
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { growthEventLabel, GROWTH_EVENT_FALLBACK_LABEL } from './growth'

/** 六个已知事件类型（区分大小写，与需求 3.8 的枚举一致）。 */
const KNOWN_TYPES = ['FIRST_RECORD', 'DAILY_RECORD', 'STREAK', 'BUDGET_MET', 'FIRST_INVITE', 'BADGE']

/** 判定文案是否含中文字符（非空中文文案的必要条件）。 */
function hasChinese(s) {
  return /[\u4e00-\u9fff]/.test(s)
}

/** 判定文案是否泄漏了原始枚举字符串（任一已知类型名以原样出现即视为泄漏）。 */
function leaksEnum(label) {
  return KNOWN_TYPES.some((t) => label.includes(t))
}

/** 任意 eventType 取值族：已知类型 + 大小写变体 + 空值 + 随机字符串 + 非标量。 */
const anyEventType = fc.oneof(
  fc.constantFrom(...KNOWN_TYPES),
  fc.constantFrom('first_record', 'Badge', 'DAILY_record', 'streak', 'FOO', 'UNKNOWN', ''),
  fc.constantFrom(null, undefined, 0, 1, true, false, {}, []),
  fc.string()
)

/** 任意 eventKey 取值族：带冒号的日期/月份、无冒号、空值、随机字符串。 */
const anyEventKey = fc.oneof(
  fc.constantFrom('DAILY_RECORD:2025-06-01', 'BUDGET_MET:2025-05', 'FIRST_RECORD', 'no-colon', ':', 'x:'),
  fc.constantFrom(null, undefined, 0, 1, true, {}, []),
  fc.string()
)

describe('任务 12.3: growthEventLabel 映射完备性', () => {
  it('六个已知类型各产出非空中文文案，互不相同，且不泄漏原始枚举', () => {
    const labels = KNOWN_TYPES.map((t) => {
      // 对带冒号的类型给一个占位 key，验证「有 key 时也非空、不同」；无冒号时的兜底另有断言。
      const key = t === 'DAILY_RECORD' ? 'DAILY_RECORD:2025-06-01' : t === 'BUDGET_MET' ? 'BUDGET_MET:2025-05' : t
      return growthEventLabel(t, key)
    })
    // 非空中文
    for (const label of labels) {
      expect(typeof label).toBe('string')
      expect(label.length).toBeGreaterThan(0)
      expect(hasChinese(label)).toBe(true)
      expect(leaksEnum(label)).toBe(false)
    }
    // 互不相同（双射）
    expect(new Set(labels).size).toBe(KNOWN_TYPES.length)
    // 均不等于兜底文案（已知类型不应落到兜底）
    for (const label of labels) {
      expect(label).not.toBe(GROWTH_EVENT_FALLBACK_LABEL)
    }
  })

  it('任一已知类型、任意 eventKey 下文案恒为非空中文且不泄漏原始枚举', () => {
    fc.assert(
      fc.property(fc.constantFrom(...KNOWN_TYPES), anyEventKey, (type, key) => {
        const label = growthEventLabel(type, key)
        expect(typeof label).toBe('string')
        expect(label.length).toBeGreaterThan(0)
        expect(hasChinese(label)).toBe(true)
        expect(leaksEnum(label)).toBe(false)
      }),
      { numRuns: 500 }
    )
  })

  it('DAILY_RECORD 文案含 eventKey 冒号后的日期，BUDGET_MET 含月份', () => {
    // 生成合法日期/月份片段，断言其原样出现在文案里。
    const datePart = fc
      .tuple(fc.integer({ min: 2000, max: 2099 }), fc.integer({ min: 1, max: 12 }), fc.integer({ min: 1, max: 28 }))
      .map(([y, m, d]) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`)
    const monthPart = fc
      .tuple(fc.integer({ min: 2000, max: 2099 }), fc.integer({ min: 1, max: 12 }))
      .map(([y, m]) => `${y}-${String(m).padStart(2, '0')}`)

    fc.assert(
      fc.property(datePart, (date) => {
        const label = growthEventLabel('DAILY_RECORD', `DAILY_RECORD:${date}`)
        expect(label).toContain(date)
        expect(hasChinese(label)).toBe(true)
      }),
      { numRuns: 300 }
    )
    fc.assert(
      fc.property(monthPart, (month) => {
        const label = growthEventLabel('BUDGET_MET', `BUDGET_MET:${month}`)
        expect(label).toContain(month)
        expect(hasChinese(label)).toBe(true)
      }),
      { numRuns: 300 }
    )
  })

  it('DAILY_RECORD / BUDGET_MET 在 eventKey 无冒号或空值时退回不带日期/月份的中文文案', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('DAILY_RECORD', 'BUDGET_MET'),
        fc.constantFrom(null, undefined, '', 'no-colon', 0, {}, []),
        (type, key) => {
          const label = growthEventLabel(type, key)
          expect(typeof label).toBe('string')
          expect(label.length).toBeGreaterThan(0)
          expect(hasChinese(label)).toBe(true)
          expect(leaksEnum(label)).toBe(false)
        }
      ),
      { numRuns: 200 }
    )
  })

  it('未知类型 / 空串 / null / 畸形取值一律走「成长记录」兜底，且不出现原始枚举', () => {
    fc.assert(
      fc.property(anyEventType, anyEventKey, (type, key) => {
        // 只对「非六个已知类型」的取值断言兜底。
        fc.pre(!KNOWN_TYPES.includes(type))
        const label = growthEventLabel(type, key)
        expect(label).toBe(GROWTH_EVENT_FALLBACK_LABEL)
        expect(leaksEnum(label)).toBe(false)
        expect(hasChinese(label)).toBe(true)
      }),
      { numRuns: 500 }
    )
  })
})
