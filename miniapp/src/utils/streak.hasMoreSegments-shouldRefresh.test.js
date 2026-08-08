/**
 * Feature: streak-system, Property 15: miniapp 纯逻辑（翻页 / 节流）— hasMoreSegments 与 shouldRefresh 的属性测试
 *
 * 任务 9.3 第三支：翻页与节流判定的边界。
 *
 * `hasMoreSegments(loadedCount, total)` 的不变式：语义为「已加载条数 < 总条数」（需求 9.9、9.18 的
 * 分页停止条件）。入参经非负整数化（负数 / NaN / Infinity / 非数值 / 空值一律折成 0）后比较，
 * 故结果恒为布尔值、永不抛出；`loaded >= total` 时恒为 false（含等于与大于）。
 *
 * `shouldRefresh(lastRequestAtMs, nowMs)` 的不变式：距上次请求发出已满
 * STREAK_REFRESH_THROTTLE_MS (3000ms) 才放行（需求 9.12）。边界在恰好 3000ms（>= 判定，取等号即放行）；
 * lastRequestAtMs 不可解析（尚未请求过）恒放行；nowMs 不可解析恒安全降级为 false。
 * 结果恒为布尔值、永不抛出、永不用 NaN 差值误判。
 *
 * Validates: Requirements 9.6, 9.7
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { hasMoreSegments, shouldRefresh, STREAK_REFRESH_THROTTLE_MS } from './streak'

/** 与被测实现同构的参考：非负整数化——非数字 / NaN / Infinity / 负数一律折成 0。 */
function toCountRef(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

/** 任意「数值型字段」取值族：有限数、边界、非有限、字符串数字、非数值文本、空值、非标量。 */
const anyNumberish = fc.oneof(
  fc.integer({ min: -(10 ** 9), max: 10 ** 9 }),
  fc.double({ min: -(10 ** 9), max: 10 ** 9, noNaN: false }),
  fc.constantFrom(0, -0, 1, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
  fc.constantFrom('0', '1', '3.5', '-2', 'abc', '12ab', '', '   ', 'NaN', 'Infinity'),
  fc.constantFrom(null, undefined, {}, [], true, false)
)

/** 时间戳取值族：合理毫秒数、0、负数、非有限、字符串数字、非数值文本、空值。 */
const anyTimestamp = fc.oneof(
  fc.integer({ min: 0, max: 4 * 10 ** 12 }),
  fc.double({ min: -(10 ** 12), max: 4 * 10 ** 12, noNaN: false }),
  fc.constantFrom(0, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
  fc.constantFrom('0', '1000', '-500', 'abc', '', '   ', 'NaN'),
  fc.constantFrom(null, undefined, {}, [], true, false)
)

describe('任务 9.3 / Property 15: hasMoreSegments', () => {
  it('恒返回布尔值，且等于「非负整数化(loaded) < 非负整数化(total)」', () => {
    fc.assert(
      fc.property(anyNumberish, anyNumberish, (loaded, total) => {
        const r = hasMoreSegments(loaded, total)
        expect(typeof r).toBe('boolean')
        expect(r).toBe(toCountRef(loaded) < toCountRef(total))
      }),
      { numRuns: 250 }
    )
  })

  it('loaded == total（合法非负整数）恒返回 false', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 10 ** 7 }), (n) => {
        expect(hasMoreSegments(n, n)).toBe(false)
      }),
      { numRuns: 150 }
    )
  })

  it('loaded > total（合法非负整数）恒返回 false', () => {
    const arb = fc
      .tuple(fc.integer({ min: 0, max: 10 ** 7 }), fc.integer({ min: 1, max: 10 ** 7 }))
      .map(([total, extra]) => ({ total, loaded: total + extra }))
    fc.assert(
      fc.property(arb, ({ loaded, total }) => {
        expect(hasMoreSegments(loaded, total)).toBe(false)
      }),
      { numRuns: 150 }
    )
  })

  it('loaded < total（合法非负整数）恒返回 true', () => {
    const arb = fc
      .tuple(fc.integer({ min: 0, max: 10 ** 7 }), fc.integer({ min: 1, max: 10 ** 7 }))
      .map(([loaded, extra]) => ({ loaded, total: loaded + extra }))
    fc.assert(
      fc.property(arb, ({ loaded, total }) => {
        expect(hasMoreSegments(loaded, total)).toBe(true)
      }),
      { numRuns: 150 }
    )
  })

  it('负数 / 非有限 / 非数值 loaded 折成 0 后比较，结果等价于 0 < 非负整数化(total)', () => {
    fc.assert(
      fc.property(fc.constantFrom(-1, -100, Number.NaN, Number.POSITIVE_INFINITY, null, undefined, 'x', '', {}, []), anyNumberish, (badLoaded, total) => {
        expect(hasMoreSegments(badLoaded, total)).toBe(0 < toCountRef(total))
      }),
      { numRuns: 150 }
    )
  })

  it('边界：0/0->false；0/1->true；1/1->false；2/1->false', () => {
    expect(hasMoreSegments(0, 0)).toBe(false)
    expect(hasMoreSegments(0, 1)).toBe(true)
    expect(hasMoreSegments(1, 1)).toBe(false)
    expect(hasMoreSegments(2, 1)).toBe(false)
  })
})

describe('任务 9.3 / Property 15: shouldRefresh', () => {
  it('恒返回布尔值，永不抛出', () => {
    fc.assert(
      fc.property(anyTimestamp, anyTimestamp, (last, now) => {
        expect(typeof shouldRefresh(last, now)).toBe('boolean')
      }),
      { numRuns: 250 }
    )
  })

  // 真正不可解析为有限数的取值（true→1、false→0、[]→0、'   '→0 均可解析，不在此列）。
  const unparseable = fc.constantFrom(null, undefined, '', 'abc', '12ab', 'NaN', 'Infinity', {})

  it('lastRequestAtMs 不可解析（尚未请求过）恒放行为 true', () => {
    fc.assert(
      fc.property(unparseable, anyTimestamp, (badLast, now) => {
        expect(shouldRefresh(badLast, now)).toBe(true)
      }),
      { numRuns: 150 }
    )
  })

  it('lastRequestAtMs 合法但 nowMs 不可解析恒安全降级为 false', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), unparseable, (last, badNow) => {
        expect(shouldRefresh(last, badNow)).toBe(false)
      }),
      { numRuns: 150 }
    )
  })

  it('两个取值都合法时等于「now - last >= 3000」', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), fc.integer({ min: -(10 ** 7), max: 10 ** 7 }), (last, delta) => {
        expect(shouldRefresh(last, last + delta)).toBe(delta >= STREAK_REFRESH_THROTTLE_MS)
      }),
      { numRuns: 250 }
    )
  })

  it('边界：恰好 3000ms 放行；2999ms 不放行；3001ms 放行（>= 取等号即放行）', () => {
    const last = 1_700_000_000_000
    expect(shouldRefresh(last, last + STREAK_REFRESH_THROTTLE_MS - 1)).toBe(false)
    expect(shouldRefresh(last, last + STREAK_REFRESH_THROTTLE_MS)).toBe(true)
    expect(shouldRefresh(last, last + STREAK_REFRESH_THROTTLE_MS + 1)).toBe(true)
  })

  it('边界：now 早于或等于 last（时钟回拨 / 同一时刻）不放行', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), fc.integer({ min: 0, max: 10 ** 7 }), (last, back) => {
        expect(shouldRefresh(last, last - back)).toBe(false)
      }),
      { numRuns: 150 }
    )
  })

  it('STREAK_REFRESH_THROTTLE_MS 常量为 3000', () => {
    expect(STREAK_REFRESH_THROTTLE_MS).toBe(3000)
  })
})
