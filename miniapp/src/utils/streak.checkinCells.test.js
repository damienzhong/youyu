/**
 * Feature: streak-system, Property 15: miniapp 纯逻辑（打卡格子）— checkinCells 的属性测试
 *
 * 任务 9.3 第一支：`checkinCells(nowMs, segments)` 的不变式。
 *
 * `segments` 数组（0–50 项，含重叠 / 乱序（endDate<startDate）/ 缺字段 / 非法日期串）×
 * `nowMs` × `process.env.TZ ∈ {UTC, America/New_York, Asia/Shanghai}`，断言：
 * - 恒返回 30 项；
 * - 30 格日期两两不同、按自然日升序、相邻恰差 1 天；
 * - 每格 checked 恰为布尔值；
 * - 某格已打卡 ⟺ 该格自然日落在某个可解析区间项的 [startDate, endDate] 闭区间内
 *   （乱序项按 min/max 归一，畸形项被跳过）；
 * - 末格已打卡 ⟺ 判定日落在某区间内（todayDone 语义：为真时末格已打卡、为假时未打卡）；
 * - 设备时区切换后 30 格的日期与打卡状态逐项不变（末格日期尤其不随时区漂移）。
 *
 * 判定日一律以 `Asia/Shanghai` 固定 UTC+8 偏移换算，故本函数对 process.env.TZ 完全不敏感——
 * 本测试正是这条「不随设备时区变化」的机器化防线。
 *
 * Validates: Requirements 9.4, 9.6, 9.7, 9.15
 */
import { describe, it, expect, afterEach } from 'vitest'
import fc from 'fast-check'
import { checkinCells, STREAK_CELL_COUNT } from './streak'

const MS_PER_DAY = 86400000
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000

/** 与被测实现同构的参考：把毫秒时刻折算为 Asia/Shanghai 自然日的 epoch day。 */
function lastEpochDayRef(nowMs) {
  return Math.floor((nowMs + SHANGHAI_OFFSET_MS) / MS_PER_DAY)
}

/** 与被测实现同构的参考：把 epoch day 转回 `YYYY-MM-DD`（借 UTC 午夜，与设备时区无关）。 */
function epochDayToStr(epochDay) {
  const dt = new Date(epochDay * MS_PER_DAY)
  const y = dt.getUTCFullYear()
  const mo = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const d = String(dt.getUTCDate()).padStart(2, '0')
  return `${y}-${mo}-${d}`
}

/** nowMs 取值：0 到约公元 2096 年的合理毫秒数，保证折算出的日期都可 round-trip。 */
const nowMsArb = fc.integer({ min: 0, max: 4 * 10 ** 12 })

/**
 * 单个 segment 条目：返回 { seg, range }。
 * - range 非空 ⟺ 该条目可被实现解析为一个有效闭区间（乱序项也算，按 min/max 归一）；
 * - range 为 null ⟺ 该条目应被实现跳过（缺字段 / 非法日期串 / 非对象）。
 */
const validRangeArb = fc
  .tuple(fc.integer({ min: 10000, max: 25000 }), fc.integer({ min: 0, max: 60 }))
  .map(([start, len]) => ({ start, end: start + len }))

const segEntryArb = fc.oneof(
  // ① 有效、正序
  validRangeArb.map(({ start, end }) => ({
    seg: { startDate: epochDayToStr(start), endDate: epochDayToStr(end) },
    range: [start, end]
  })),
  // ② 有效、乱序（endDate 早于 startDate）——实现按 min/max 归一，仍是同一区间
  validRangeArb.map(({ start, end }) => ({
    seg: { startDate: epochDayToStr(end), endDate: epochDayToStr(start) },
    range: [start, end]
  })),
  // ③ 各类畸形，一律应被跳过
  fc.constantFrom(
    { seg: { startDate: '2024-02-30', endDate: '2024-03-05' }, range: null }, // 越界日
    { seg: { startDate: 'garbage', endDate: '2024-03-05' }, range: null }, // 非日期串
    { seg: { startDate: '2024-03-05' }, range: null }, // 缺 endDate
    { seg: { endDate: '2024-03-05' }, range: null }, // 缺 startDate
    { seg: {}, range: null }, // 空对象
    { seg: null, range: null }, // null 元素
    { seg: 42, range: null }, // 非对象元素
    { seg: { startDate: '2024-13-01', endDate: '2024-13-02' }, range: null } // 非法月份
  )
)

/** 0–50 项混合条目数组。 */
const entriesArb = fc.array(segEntryArb, { maxLength: 50 })

/** 从条目数组抽出实际下发给函数的 segments 与参考区间集合。 */
function split(entries) {
  return {
    segments: entries.map((e) => e.seg),
    ranges: entries.filter((e) => e.range !== null).map((e) => e.range)
  }
}

const TIMEZONES = ['UTC', 'America/New_York', 'Asia/Shanghai']

describe('任务 9.3 / Property 15: checkinCells', () => {
  const originalTz = process.env.TZ

  afterEach(() => {
    if (originalTz === undefined) delete process.env.TZ
    else process.env.TZ = originalTz
  })

  it('恒返回 30 项，日期两两不同、升序、相邻恰差 1 天，末格为判定日', () => {
    fc.assert(
      fc.property(nowMsArb, entriesArb, (nowMs, entries) => {
        const { segments } = split(entries)
        const cells = checkinCells(nowMs, segments)
        expect(cells.length).toBe(STREAK_CELL_COUNT)
        const last = lastEpochDayRef(nowMs)
        expect(cells[STREAK_CELL_COUNT - 1].date).toBe(epochDayToStr(last))
        expect(cells[0].date).toBe(epochDayToStr(last - (STREAK_CELL_COUNT - 1)))
        const seen = new Set()
        for (let i = 0; i < cells.length; i++) {
          const expectedDay = last - (STREAK_CELL_COUNT - 1 - i)
          expect(cells[i].date).toBe(epochDayToStr(expectedDay))
          expect(typeof cells[i].checked).toBe('boolean')
          expect(seen.has(cells[i].date)).toBe(false)
          seen.add(cells[i].date)
        }
        expect(seen.size).toBe(STREAK_CELL_COUNT)
      }),
      { numRuns: 400 }
    )
  })

  it('某格已打卡 ⟺ 该格自然日落在某可解析区间的闭区间内（重叠 / 乱序 / 畸形混合）', () => {
    fc.assert(
      fc.property(nowMsArb, entriesArb, (nowMs, entries) => {
        const { segments, ranges } = split(entries)
        const cells = checkinCells(nowMs, segments)
        const last = lastEpochDayRef(nowMs)
        cells.forEach((cell, idx) => {
          const epochDay = last - (STREAK_CELL_COUNT - 1 - idx)
          const inRange = ranges.some(([s, e]) => epochDay >= s && epochDay <= e)
          expect(cell.checked).toBe(inRange)
        })
      }),
      { numRuns: 400 }
    )
  })

  it('segments 全为畸形项时 30 格全部未打卡', () => {
    const junkArb = fc.array(
      fc.constantFrom({}, null, 42, { startDate: 'x' }, { startDate: '2024-02-30', endDate: '2024-02-30' }, { endDate: '2024-01-01' }),
      { maxLength: 50 }
    )
    fc.assert(
      fc.property(nowMsArb, junkArb, (nowMs, junk) => {
        const cells = checkinCells(nowMs, junk)
        expect(cells.length).toBe(STREAK_CELL_COUNT)
        expect(cells.every((c) => c.checked === false)).toBe(true)
      }),
      { numRuns: 200 }
    )
  })

  it('末格已打卡 ⟺ 判定日落在某区间内（todayDone 为真末格已打卡、为假未打卡）', () => {
    fc.assert(
      fc.property(nowMsArb, fc.integer({ min: 0, max: 30 }), (nowMs, span) => {
        const last = lastEpochDayRef(nowMs)
        // 覆盖判定日的区间 → 末格已打卡
        const covering = [{ startDate: epochDayToStr(last - span), endDate: epochDayToStr(last) }]
        expect(checkinCells(nowMs, covering)[STREAK_CELL_COUNT - 1].checked).toBe(true)
        // 结束于判定日前一日的区间 → 末格未打卡
        const notCovering = [{ startDate: epochDayToStr(last - span - 1), endDate: epochDayToStr(last - 1) }]
        expect(checkinCells(nowMs, notCovering)[STREAK_CELL_COUNT - 1].checked).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('落在当前段（含判定日的区间）内的每一格都已打卡', () => {
    fc.assert(
      fc.property(nowMsArb, fc.integer({ min: 0, max: 40 }), (nowMs, span) => {
        const last = lastEpochDayRef(nowMs)
        const start = last - span
        const cells = checkinCells(nowMs, [{ startDate: epochDayToStr(start), endDate: epochDayToStr(last) }])
        cells.forEach((cell, idx) => {
          const epochDay = last - (STREAK_CELL_COUNT - 1 - idx)
          if (epochDay >= start && epochDay <= last) {
            expect(cell.checked).toBe(true)
          }
        })
      }),
      { numRuns: 300 }
    )
  })

  it('设备时区切换后 30 格的日期与打卡状态逐项不变', () => {
    fc.assert(
      fc.property(nowMsArb, entriesArb, (nowMs, entries) => {
        const { segments } = split(entries)
        const results = TIMEZONES.map((tz) => {
          process.env.TZ = tz
          return checkinCells(nowMs, segments)
        })
        expect(results[1]).toEqual(results[0])
        expect(results[2]).toEqual(results[0])
        // 末格日期尤其不随时区漂移
        expect(results[0][STREAK_CELL_COUNT - 1].date).toBe(epochDayToStr(lastEpochDayRef(nowMs)))
      }),
      { numRuns: 300 }
    )
  })

  it('nowMs 不可解析时降级用设备当前时刻，仍恒返回 30 项且不抛出', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, '', 'abc', 'NaN', Number.NaN, Number.POSITIVE_INFINITY, {}, []), (badNow) => {
        const cells = checkinCells(badNow, [])
        expect(cells.length).toBe(STREAK_CELL_COUNT)
        expect(cells.every((c) => typeof c.checked === 'boolean')).toBe(true)
      }),
      { numRuns: 100 }
    )
  })
})
