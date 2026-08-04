/**
 * Feature: growth-level-system, 任务 12.2: `hasMoreGrowthEvents` 与 `shouldRefresh` 的属性测试
 *
 * `hasMoreGrowthEvents(loaded, total)` 的不变式：语义为「已加载条数 < 总条数」（需求 13.10 的
 * 分页停止条件）。入参经非负整数化（负数 / NaN / Infinity / 非数值 / 空值一律折成 0）后比较，
 * 故结果恒为布尔值、永不抛出；`loaded >= total` 时恒为 false（含 loaded == total、loaded > total）。
 *
 * `shouldRefresh(lastRequestAt, now)` 的不变式：距上次请求发出已满 GROWTH_REFRESH_THROTTLE_MS
 * (3000ms) 才放行（需求 13.16、13.17）。边界在恰好 3000ms（>= 判定，取等号即放行）；
 * lastRequestAt 不可解析（尚未请求过）恒放行；now 不可解析恒安全降级为 false。结果恒为布尔值、
 * 永不抛出、永不用 NaN 差值误判。
 *
 * 本任务只覆盖 `utils/growth.js` 的这两个纯函数（页面渲染由手工验收清单覆盖）。
 *
 * Validates: Requirements 13.10, 13.16, 13.17
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { hasMoreGrowthEvents, shouldRefresh, GROWTH_REFRESH_THROTTLE_MS } from './growth'

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

describe('任务 12.2: hasMoreGrowthEvents', () => {
  it('恒返回布尔值，且等于「非负整数化(loaded) < 非负整数化(total)」', () => {
    fc.assert(
      fc.property(anyNumberish, anyNumberish, (loaded, total) => {
        const r = hasMoreGrowthEvents(loaded, total)
        expect(typeof r).toBe('boolean')
        expect(r).toBe(toCountRef(loaded) < toCountRef(total))
      }),
      { numRuns: 500 }
    )
  })

  it('loaded == total（合法非负整数）恒返回 false', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 10 ** 7 }), (n) => {
        expect(hasMoreGrowthEvents(n, n)).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('loaded > total（合法非负整数）恒返回 false', () => {
    const arb = fc
      .tuple(fc.integer({ min: 0, max: 10 ** 7 }), fc.integer({ min: 1, max: 10 ** 7 }))
      .map(([total, extra]) => ({ total, loaded: total + extra }))
    fc.assert(
      fc.property(arb, ({ loaded, total }) => {
        expect(hasMoreGrowthEvents(loaded, total)).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('loaded < total（合法非负整数）恒返回 true', () => {
    const arb = fc
      .tuple(fc.integer({ min: 0, max: 10 ** 7 }), fc.integer({ min: 1, max: 10 ** 7 }))
      .map(([loaded, extra]) => ({ loaded, total: loaded + extra }))
    fc.assert(
      fc.property(arb, ({ loaded, total }) => {
        expect(hasMoreGrowthEvents(loaded, total)).toBe(true)
      }),
      { numRuns: 300 }
    )
  })

  it('负数 / 非有限 / 非数值入参折成 0 后比较，永不抛出（loaded 归零时唯有 total>=1 才有更多）', () => {
    fc.assert(
      fc.property(fc.constantFrom(-1, -100, Number.NaN, Number.POSITIVE_INFINITY, null, undefined, 'x', '', {}, []), anyNumberish, (badLoaded, total) => {
        const r = hasMoreGrowthEvents(badLoaded, total)
        // 畸形 loaded 归零：结果等价于 0 < 非负整数化(total)
        expect(r).toBe(0 < toCountRef(total))
      }),
      { numRuns: 300 }
    )
  })

  it('边界：loaded=0,total=0 -> false；loaded=0,total=1 -> true', () => {
    expect(hasMoreGrowthEvents(0, 0)).toBe(false)
    expect(hasMoreGrowthEvents(0, 1)).toBe(true)
    expect(hasMoreGrowthEvents(1, 1)).toBe(false)
    expect(hasMoreGrowthEvents(2, 1)).toBe(false)
  })
})

describe('任务 12.2: shouldRefresh', () => {
  it('恒返回布尔值，永不抛出', () => {
    fc.assert(
      fc.property(anyTimestamp, anyTimestamp, (last, now) => {
        const r = shouldRefresh(last, now)
        expect(typeof r).toBe('boolean')
      }),
      { numRuns: 500 }
    )
  })

  // 真正不可解析为有限数的取值：toFiniteOrNull 对它们返回 null。
  // 注意 true→1、false→0、[]→0、'   '→0 均可解析为有限数，故不在此列（属正常时间戳分支）。
  const unparseable = fc.constantFrom(null, undefined, '', 'abc', '12ab', 'NaN', 'Infinity', {})

  it('lastRequestAt 不可解析（尚未请求过）恒放行为 true', () => {
    fc.assert(
      fc.property(unparseable, anyTimestamp, (badLast, now) => {
        expect(shouldRefresh(badLast, now)).toBe(true)
      }),
      { numRuns: 300 }
    )
  })

  it('lastRequestAt 合法但 now 不可解析恒安全降级为 false', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), unparseable, (last, badNow) => {
        expect(shouldRefresh(last, badNow)).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('两个取值都合法时等于「now - last >= 3000」', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), fc.integer({ min: -(10 ** 7), max: 10 ** 7 }), (last, delta) => {
        const now = last + delta
        expect(shouldRefresh(last, now)).toBe(delta >= GROWTH_REFRESH_THROTTLE_MS)
      }),
      { numRuns: 500 }
    )
  })

  it('边界：恰好 3000ms 放行；2999ms 不放行；3001ms 放行（>= 取等号即放行）', () => {
    const last = 1_700_000_000_000
    expect(shouldRefresh(last, last + GROWTH_REFRESH_THROTTLE_MS - 1)).toBe(false)
    expect(shouldRefresh(last, last + GROWTH_REFRESH_THROTTLE_MS)).toBe(true)
    expect(shouldRefresh(last, last + GROWTH_REFRESH_THROTTLE_MS + 1)).toBe(true)
  })

  it('边界：now 早于或等于 last（时钟回拨 / 同一时刻）不放行', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 4 * 10 ** 12 }), fc.integer({ min: 0, max: 10 ** 7 }), (last, back) => {
        const now = last - back // now <= last，差值 <= 0 < 3000
        expect(shouldRefresh(last, now)).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('GROWTH_REFRESH_THROTTLE_MS 常量为 3000', () => {
    expect(GROWTH_REFRESH_THROTTLE_MS).toBe(3000)
  })
})
