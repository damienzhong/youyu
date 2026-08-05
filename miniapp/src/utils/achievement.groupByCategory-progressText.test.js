/**
 * Feature: achievement-system, 任务 9.3: `groupByCategory`、`achievementProgressText`
 * 与 `unlockedDateLabel` 的属性测试
 *
 * `groupByCategory(achievements)` 的不变式：分组保序——组的顺序取分类在响应中的首现顺序、
 * 组内项的顺序取响应中的相对顺序；分类两两不重复；全部分组项数之和等于可分组项数；
 * 以服务端清单（16 项、同分类连续）为入参时组恰为「起步 / 坚持 / 积累 / 协作 / 主题」五组且顺序固定。
 *
 * `achievementProgressText(a)` 的不变式：已解锁（`unlocked === true`）恒返回 `''`（不出进度文案）；
 * 未解锁时形如 `${当前值} / ${门槛数值}` 且当前值恒落在 `[0, 门槛数值]`（不渲染「17 / 16」这类自相矛盾的进度）。
 *
 * `unlockedDateLabel(a)` 的不变式：未解锁（`unlocked === false`）恒返回 `''`（不出解锁日期）；
 * 否则恒返回 `YYYY-MM-DD` 形状的字符串或 `''`，取值为解锁时刻所属自然日的年月日三项。
 *
 * 三个函数对畸形入参一律安全降级（`[]` / `''`）且绝不抛出。
 * 本任务只覆盖 `utils/achievement.js` 的纯函数（分组渲染、图标灰度与滚动高亮由手工验收清单覆盖）。
 *
 * Validates: Requirements 9.3, 9.4, 9.5
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { groupByCategory, achievementProgressText, unlockedDateLabel } from './achievement'

/** 服务端下发的五个分类中文展示名，按需求 1 第 8 条的首现顺序。 */
const CATEGORY_LABELS = ['起步', '坚持', '积累', '协作', '主题']

/** 需求 1 第 1 条的 16 枚成就（编码 / 名称 / 分类 / 门槛），同分类连续、顺序即展示顺序。 */
const CATALOG = [
  ['FIRST_RECORD', '开张', '起步', 1],
  ['STREAK_7', '七日不辍', '坚持', 7],
  ['STREAK_30', '卅日成习', '坚持', 30],
  ['STREAK_100', '百日不辍', '坚持', 100],
  ['STREAK_365', '岁岁有余', '坚持', 365],
  ['RECORD_10', '小有账目', '积累', 10],
  ['RECORD_100', '百笔有余', '积累', 100],
  ['RECORD_500', '五百笔在册', '积累', 500],
  ['RECORD_1000', '千笔如一', '积累', 1000],
  ['DAYS_100', '百日记账', '积累', 100],
  ['INVITE_1', '同行有余', '协作', 1],
  ['COLLAB_1', '共账之始', '协作', 1],
  ['BUDGET_MET', '预算达标', '主题', 1],
  ['BUDGET_MASTER', '预算达人', '主题', 3],
  ['SAVING_MASTER', '储蓄达人', '主题', 3],
  ['TRAVEL_MASTER', '旅行达人', '主题', 10]
]

/** 参考实现：可分组项判定（普通对象且 `category` 去首尾空白后非空）。 */
function isGroupableRef(a) {
  if (typeof a !== 'object' || a === null || Array.isArray(a)) return false
  const c = a.category === null || a.category === undefined ? '' : String(a.category).trim()
  return c.length > 0
}

/** 任意「数值型字段」取值族：有限数、边界、非有限、字符串数字、非数值文本、空值、非标量。 */
const anyNumberish = fc.oneof(
  fc.integer({ min: -1000, max: 10 ** 6 }),
  fc.double({ min: -1000, max: 10 ** 6, noNaN: false }),
  fc.constantFrom(0, -0, 1, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
  fc.constantFrom('0', '10', '3.5', '-2', 'abc', '', '   '),
  fc.constantFrom(null, undefined, {}, [], true, false)
)

/** 分类字段取值族：五个合法中文名、带空白的等价变形，以及畸形取值。 */
const anyCategory = fc.oneof(
  { weight: 5, arbitrary: fc.constantFrom(...CATEGORY_LABELS) },
  { weight: 2, arbitrary: fc.constantFrom(...CATEGORY_LABELS).map((c) => `  ${c} `) },
  { weight: 1, arbitrary: fc.constantFrom('', '   ', null, undefined, 0, 42, {}, []) }
)

/** 任意成就视图项（含畸形项与非对象项）。 */
const anyAchievement = fc.oneof(
  {
    weight: 8,
    arbitrary: fc.record({
      code: fc.constantFrom(...CATALOG.map((r) => r[0])),
      name: fc.constantFrom(...CATALOG.map((r) => r[1])),
      description: fc.constantFrom('描述一', '描述二'),
      category: anyCategory,
      target: anyNumberish,
      current: anyNumberish,
      unlocked: fc.constantFrom(true, false, null, undefined, 'true', 1, 0),
      unlockedAt: fc.constantFrom('2025-06-01T12:00:00', '2025-01-31T00:00:00.000', null, undefined, '', 'x', 20250601)
    })
  },
  { weight: 1, arbitrary: fc.constantFrom(null, undefined, 'x', 0, 42, true, [], [1, 2]) }
)

const anyList = fc.array(anyAchievement, { minLength: 0, maxLength: 24 })

/** 服务端形状的清单：16 项、顺序即展示顺序、同分类连续，仅解锁状态与当前值随机。 */
const serverCatalog = fc
  .array(fc.tuple(fc.boolean(), fc.integer({ min: 0, max: 2000 })), { minLength: 16, maxLength: 16 })
  .map((flags) =>
    CATALOG.map(([code, name, category, target], i) => ({
      code,
      name,
      description: `${name}的描述`,
      category,
      target,
      current: flags[i][0] ? target : Math.min(flags[i][1], target),
      unlocked: flags[i][0],
      unlockedAt: flags[i][0] ? '2025-06-01T12:00:00' : null,
      eventId: flags[i][0] ? i + 1 : null
    }))
  )

describe('任务 9.3: groupByCategory 分组保序与进度 / 日期文案', () => {
  it('groupByCategory: 组的顺序为分类首现顺序、分类两两不同、组内保序、项数守恒', () => {
    fc.assert(
      fc.property(anyList, (list) => {
        const groups = groupByCategory(list)
        const groupable = list.filter(isGroupableRef)

        // 分类两两不同
        const categories = groups.map((g) => g.category)
        expect(new Set(categories).size).toBe(categories.length)

        // 组的顺序 == 分类在响应中的首现顺序
        const firstSeen = []
        for (const a of groupable) {
          const c = String(a.category).trim()
          if (!firstSeen.includes(c)) firstSeen.push(c)
        }
        expect(categories).toEqual(firstSeen)

        // 项数守恒 + 组内保序（组内项序列 == 该分类项在原列表中的相对顺序）
        expect(groups.reduce((n, g) => n + g.items.length, 0)).toBe(groupable.length)
        for (const g of groups) {
          expect(g.items).toEqual(groupable.filter((a) => String(a.category).trim() === g.category))
        }
      }),
      { numRuns: 400 }
    )
  })

  it('groupByCategory: 服务端 16 项清单恒分为「起步 / 坚持 / 积累 / 协作 / 主题」五组且各组项数固定', () => {
    fc.assert(
      fc.property(serverCatalog, (list) => {
        const groups = groupByCategory(list)
        expect(groups.map((g) => g.category)).toEqual(CATEGORY_LABELS)
        expect(groups.map((g) => g.items.length)).toEqual([1, 4, 5, 2, 4])
        expect(groups.reduce((n, g) => n + g.items.length, 0)).toBe(16)
        // 每项都带展示名称与描述（页面渲染的两项取值）
        for (const g of groups) {
          for (const a of g.items) {
            expect(typeof a.name).toBe('string')
            expect(typeof a.description).toBe('string')
          }
        }
      }),
      { numRuns: 200 }
    )
  })

  it('groupByCategory: 非数组入参返回 []，非对象项与空分类项被跳过且不抛出', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, 0, '', 'x', 42, true, {}), (bad) => {
        expect(groupByCategory(bad)).toEqual([])
      }),
      { numRuns: 100 }
    )
    expect(groupByCategory([null, undefined, 1, 'x', [], {}, { category: '   ' }])).toEqual([])
  })

  it('achievementProgressText: 已解锁恒为 ""；未解锁形如 "x / y" 且 0 <= x <= y', () => {
    fc.assert(
      fc.property(anyAchievement, (a) => {
        const text = achievementProgressText(a)
        expect(typeof text).toBe('string')
        if (typeof a === 'object' && a !== null && !Array.isArray(a) && a.unlocked === true) {
          expect(text).toBe('')
          return
        }
        if (text === '') return
        expect(text).toMatch(/^\d+ \/ \d+$/)
        const [current, target] = text.split(' / ').map(Number)
        // 需求 9.5 只要求「当前值不大于门槛数值」；两者均为非负整数写法。
        // 门槛为 0 到 1 之间的畸形小数时向下取整得到 `0 / 0`，属可接受的降级
        // （服务端保证门槛恒为 [1, 1000] 内的整数，前端只负责不渲染自相矛盾的进度）。
        expect(current).toBeGreaterThanOrEqual(0)
        expect(target).toBeGreaterThanOrEqual(0)
        expect(current).toBeLessThanOrEqual(target)
      }),
      { numRuns: 500 }
    )
  })

  it('achievementProgressText: 正常取值等于 `${min(max(current,0), target)} / ${target}`', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: -100, max: 5000 }),
        fc.integer({ min: 1, max: 1000 }),
        (current, target) => {
          const text = achievementProgressText({ current, target, unlocked: false })
          expect(text).toBe(`${Math.min(Math.max(current, 0), target)} / ${target}`)
        }
      ),
      { numRuns: 300 }
    )
  })

  it('unlockedDateLabel: 未解锁恒为 ""；否则恒为 YYYY-MM-DD 或 ""', () => {
    fc.assert(
      fc.property(anyAchievement, (a) => {
        const label = unlockedDateLabel(a)
        expect(typeof label).toBe('string')
        if (typeof a === 'object' && a !== null && !Array.isArray(a) && a.unlocked === false) {
          expect(label).toBe('')
          return
        }
        if (label !== '') expect(label).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      }),
      { numRuns: 500 }
    )
  })

  it('unlockedDateLabel: 合法 LocalDateTime 取解锁时刻所属自然日的年月日三项', () => {
    const iso = fc
      .tuple(
        fc.integer({ min: 2000, max: 2099 }),
        fc.integer({ min: 1, max: 12 }),
        fc.integer({ min: 1, max: 28 }),
        fc.integer({ min: 0, max: 23 }),
        fc.integer({ min: 0, max: 59 }),
        fc.constantFrom('', '.000', '.123456')
      )
      .map(([y, m, d, hh, mm, frac]) => {
        const p2 = (n) => String(n).padStart(2, '0')
        return { date: `${y}-${p2(m)}-${p2(d)}`, at: `${y}-${p2(m)}-${p2(d)}T${p2(hh)}:${p2(mm)}:00${frac}` }
      })
    fc.assert(
      fc.property(iso, fc.constantFrom(true, undefined, null), ({ date, at }, unlocked) => {
        expect(unlockedDateLabel({ unlocked, unlockedAt: at })).toBe(date)
        // 未解锁项一律不出解锁日期（需求 9.5）
        expect(unlockedDateLabel({ unlocked: false, unlockedAt: at })).toBe('')
      }),
      { numRuns: 300 }
    )
  })

  it('已解锁不出进度文案、未解锁不出解锁日期：服务端 16 项清单上逐项互斥', () => {
    fc.assert(
      fc.property(serverCatalog, (list) => {
        for (const a of list) {
          if (a.unlocked) {
            expect(achievementProgressText(a)).toBe('')
            expect(unlockedDateLabel(a)).toBe('2025-06-01')
          } else {
            expect(unlockedDateLabel(a)).toBe('')
            expect(achievementProgressText(a)).toBe(`${a.current} / ${a.target}`)
          }
        }
      }),
      { numRuns: 200 }
    )
  })

  it('三个函数对 null / undefined / 非对象入参均不抛出', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, 0, '', 'x', 42, true, [], Number.NaN), (bad) => {
        expect(groupByCategory(bad)).toEqual([])
        expect(achievementProgressText(bad)).toBe('')
        expect(unlockedDateLabel(bad)).toBe('')
      }),
      { numRuns: 100 }
    )
  })
})
