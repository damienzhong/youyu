/**
 * Feature: achievement-system, Property 8: 漏播不可能（未展示项永不被确认）
 *
 * 对任意待播报列表（长度 0–16、成就事件 id 严格递增，含畸形项）与任意提前关闭时机
 * （弹层前关闭 / 弹层后关闭 / 第 1 条 Toast 后关闭 / 全部播完 / 中途进入成就页，
 * 即已展示前缀长度 ∈ [0, 3]）：`ackCursorOf(已展示项)` 恒等于已展示项的最大成就事件 id、
 * 且恒小于任何未展示项的成就事件 id；未展示任何项时恒返回 `null`
 * （此时调用方不得发起游标推进请求）。
 *
 * `planBroadcast` 使单次展示项数恒 ≤ 3（1 个解锁弹层 + 至多 2 条 Toast），
 * 其余项留待后续播报；畸形入参一律降级为 `{ modal: null, toasts: [] }` 且绝不抛出。
 *
 * 本任务只覆盖 `utils/achievement.js` 的两个纯函数（弹层与 Toast 的副作用编排、
 * 记账后 1000ms 内发起请求等时序由手工验收清单覆盖）。
 *
 * Validates: Requirements 7.6, 7.9, 7.11, 7.16
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { planBroadcast, ackCursorOf, MAX_BROADCAST_ITEMS } from './achievement'

/**
 * 独立于被测实现重算「可播报项」判定（照抄模块契约：必须是普通对象且带一个
 * 可解析为非负有限数的 `eventId`）——用作参考实现，避免与被测代码共享同一处错误。
 */
function isUsableRef(item) {
  if (typeof item !== 'object' || item === null || Array.isArray(item)) return false
  const raw = item.eventId
  if (raw === null || raw === undefined || raw === '') return false
  const v = Number(raw)
  return Number.isFinite(v) && v >= 0
}

/** 参考实现：已展示项的最大 eventId，空集为 null。 */
function maxEventIdRef(items) {
  let max = null
  for (const item of items) {
    if (!isUsableRef(item)) continue
    const v = Number(item.eventId)
    if (max === null || v > max) max = v
  }
  return max
}

/** 项的形态族：正常项、等价变形（字符串 id / 带空白 / id 为 0），以及各类畸形项。 */
const SHAPES = [
  'ok',
  'idAsString',
  'idPadded',
  'idMissing',
  'idNull',
  'idText',
  'idNegative',
  'idNaN',
  'notObject',
  'arrayItem'
]

function buildItem(shape, id, code) {
  const base = { code, name: `成就${id}`, description: '描述', category: '起步', unlockedAt: '2025-06-01T12:00:00' }
  switch (shape) {
    case 'ok':
      return { ...base, eventId: id }
    case 'idAsString':
      return { ...base, eventId: String(id) }
    case 'idPadded':
      return { ...base, eventId: `  ${id} ` }
    case 'idMissing':
      return { ...base }
    case 'idNull':
      return { ...base, eventId: null }
    case 'idText':
      return { ...base, eventId: 'abc' }
    case 'idNegative':
      return { ...base, eventId: -id - 1 }
    case 'idNaN':
      return { ...base, eventId: Number.NaN }
    case 'notObject':
      return id % 2 === 0 ? null : `item-${id}`
    case 'arrayItem':
      return [id]
    default:
      return base
  }
}

/**
 * 待播报列表生成器：长度 ∈ [0, 16]，eventId 由严格递增的正增量累加而来（服务端按 id 升序返回），
 * 每个位置的形态独立取自 SHAPES（因此可播报子序列的 id 仍严格递增）。
 */
const anyPendingList = fc
  .array(
    fc.tuple(fc.constantFrom(...SHAPES), fc.integer({ min: 1, max: 50 })),
    { minLength: 0, maxLength: 16 }
  )
  .map((slots) => {
    let id = 0
    return slots.map(([shape, delta], i) => {
      id += delta
      return buildItem(shape, id, `CODE_${i}`)
    })
  })

/** 已展示前缀长度（关闭时机）：0 = 弹层前关闭，1 = 弹层后关闭，2 = 第 1 条 Toast 后，3 = 全部播完。 */
const shownPrefixLen = fc.integer({ min: 0, max: MAX_BROADCAST_ITEMS })

describe('Property 8: 漏播不可能（未展示项永不被确认）', () => {
  it('shown 为空 ⇒ null；否则 ack == max(shown.eventId) 且 ack < min(unshown.eventId)', () => {
    fc.assert(
      fc.property(anyPendingList, shownPrefixLen, (items, k) => {
        const plan = planBroadcast(items)
        const planned = plan.modal === null ? [] : [plan.modal, ...plan.toasts]
        const shown = planned.slice(0, k)

        // 未展示项 = 全部可播报项减去已展示项（含本次计划外、留待后续播报的项）
        const usable = items.filter(isUsableRef)
        const unshown = usable.filter((it) => !shown.includes(it))

        const ack = ackCursorOf(shown)

        if (shown.length === 0) {
          expect(ack).toBeNull()
        } else {
          expect(ack).toBe(maxEventIdRef(shown))
          for (const item of unshown) {
            expect(ack).toBeLessThan(Number(item.eventId))
          }
        }
      }),
      { numRuns: 250 }
    )
  })

  it('单次播报展示项数恒 ≤3：toasts ≤2，modal + toasts ≤ MAX_BROADCAST_ITEMS', () => {
    fc.assert(
      fc.property(anyPendingList, (items) => {
        const plan = planBroadcast(items)
        expect(Array.isArray(plan.toasts)).toBe(true)
        expect(plan.toasts.length).toBeLessThanOrEqual(MAX_BROADCAST_ITEMS - 1)
        const shownCount = (plan.modal === null ? 0 : 1) + plan.toasts.length
        expect(shownCount).toBeLessThanOrEqual(MAX_BROADCAST_ITEMS)
        // 多于 3 项时剩余项留待后续播报（不展示、也不计入本次计划）
        const usable = items.filter(isUsableRef)
        expect(shownCount).toBe(Math.min(usable.length, MAX_BROADCAST_ITEMS))
      }),
      { numRuns: 150 }
    )
  })

  it('计划保序：第 1 个可播报项走弹层、第 2–3 项走 Toast，且均为原列表中的项', () => {
    fc.assert(
      fc.property(anyPendingList, (items) => {
        const plan = planBroadcast(items)
        const usable = items.filter(isUsableRef)
        if (usable.length === 0) {
          expect(plan).toEqual({ modal: null, toasts: [] })
          return
        }
        expect(plan.modal).toBe(usable[0])
        expect(plan.toasts).toEqual(usable.slice(1, MAX_BROADCAST_ITEMS))
        // eventId 升序：弹层项恒是本次展示项里 id 最小的那一项（先解锁的先播报）
        const planned = [plan.modal, ...plan.toasts]
        for (let i = 1; i < planned.length; i += 1) {
          expect(Number(planned[i].eventId)).toBeGreaterThan(Number(planned[i - 1].eventId))
        }
      }),
      { numRuns: 150 }
    )
  })

  it('畸形入参安全降级：planBroadcast 返回空计划、ackCursorOf 返回 null，均不抛出', () => {
    const garbage = fc.constantFrom(null, undefined, 0, 1, '', 'x', true, false, {}, { items: [] }, Number.NaN)
    fc.assert(
      fc.property(garbage, (bad) => {
        expect(planBroadcast(bad)).toEqual({ modal: null, toasts: [] })
        expect(ackCursorOf(bad)).toBeNull()
      }),
      { numRuns: 50 }
    )
  })

  it('全部项畸形时不发起游标推进：modal 为 null 且 ackCursorOf 恒返回 null', () => {
    const malformedOnly = fc
      .array(fc.constantFrom('idMissing', 'idNull', 'idText', 'idNegative', 'idNaN', 'notObject', 'arrayItem'), {
        minLength: 1,
        maxLength: 16
      })
      .map((shapes) => shapes.map((shape, i) => buildItem(shape, i + 1, `CODE_${i}`)))
    fc.assert(
      fc.property(malformedOnly, (items) => {
        expect(planBroadcast(items)).toEqual({ modal: null, toasts: [] })
        expect(ackCursorOf(items)).toBeNull()
      }),
      { numRuns: 100 }
    )
  })

  it('eventId 为 0 的首项可被确认（游标合法取值含 0）', () => {
    const plan = planBroadcast([{ eventId: 0, name: '开张' }, { eventId: 5, name: '七日不辍' }])
    expect(plan.modal.eventId).toBe(0)
    expect(ackCursorOf([plan.modal])).toBe(0)
    expect(ackCursorOf([plan.modal, ...plan.toasts])).toBe(5)
  })
})
