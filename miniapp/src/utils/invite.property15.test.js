/**
 * Feature: invite-system, Property 15: 待绑定邀请码的时效状态机
 *
 * 对任意（邀请码输入、写入时刻、当前时刻、本地存储行为）组合：合法码写入后覆盖旧值并记录写入时刻；
 * 写入时刻距当前时刻 <604800000ms 时可携带该码，已满 604800000ms 或写入时刻缺失 / 不可解析为时刻时
 * 删除两个存储键且不携带；清除后两个存储键均不存在且此后不再携带；本地存储读 / 写 / 删抛错时
 * 三个入口均不抛出，调用方仍可继续走登录/注册主路径。
 *
 * 不变式：`carried == (code 合法 ∧ 0 ≤ now - at < TTL)`；
 * 携带时两个存储键取值与调用前逐字节相同；不携带时两个存储键被删除；存储抛错时不抛异常。
 *
 * 本任务只覆盖 `utils/invite.js` 的存 / 取 / 清与 7 天判定（登录请求侧的携带与清除时机由
 * stores/auth.js 的任务与手工验收清单覆盖）。
 *
 * Validates: Requirements 4.1, 4.6, 4.7, 4.8, 4.12, 4.13
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import fc from 'fast-check'
import { STORAGE_KEYS } from './config'
import {
  PENDING_TTL_MS,
  savePendingInviteCode,
  takePendingInviteCode,
  clearPendingInviteCode
} from './invite'

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const KEY_CODE = STORAGE_KEYS.pendingInviteCode
const KEY_AT = STORAGE_KEYS.pendingInviteCodeAt

/** 独立于被测实现重算「规整 + 合法」判定（直接照抄验收标准 4.1 的字面定义）。 */
function normalize(raw) {
  if (raw === null || raw === undefined) return ''
  return String(raw).trim().toUpperCase()
}
function isLegal(raw) {
  const n = normalize(raw)
  return n.length === 8 && [...n].every((c) => ALPHABET.includes(c))
}

// ---- mock 的内存存储：可配置 get / set / remove 抛错 ----

let store
let faults

function installUniMock() {
  store = new Map()
  faults = { get: false, set: false, remove: false }
  globalThis.uni = {
    getStorageSync(key) {
      if (faults.get) throw new Error('mock getStorageSync failed')
      return store.has(key) ? store.get(key) : ''
    },
    setStorageSync(key, value) {
      if (faults.set) throw new Error('mock setStorageSync failed')
      store.set(key, value)
    },
    removeStorageSync(key) {
      if (faults.remove) throw new Error('mock removeStorageSync failed')
      store.delete(key)
    }
  }
}

function snapshot() {
  return { code: store.has(KEY_CODE) ? store.get(KEY_CODE) : null, at: store.has(KEY_AT) ? store.get(KEY_AT) : null }
}

// ---- 生成器 ----

const legalCode = fc
  .array(fc.constantFrom(...ALPHABET), { minLength: 8, maxLength: 8 })
  .map((cs) => cs.join(''))

/** 合法码的等价变形（小写 / 首尾空白）—— 规整后仍合法。 */
const legalVariant = fc
  .tuple(legalCode, fc.constantFrom('asis', 'lower', 'pad', 'lowerPad'))
  .map(([code, how]) => {
    if (how === 'lower') return code.toLowerCase()
    if (how === 'pad') return `  ${code}\t`
    if (how === 'lowerPad') return ` ${code.toLowerCase()} `
    return code
  })

/** 畸形串族：长度 ≠8、含字母表外字符（I/O/0/1）、空值、非字符串。 */
const malformedCode = fc.oneof(
  fc.string({ minLength: 0, maxLength: 12 }),
  legalCode.map((c) => c.slice(0, 7)),
  legalCode.map((c) => `${c}A`),
  legalCode.map((c) => `I${c.slice(1)}`),
  legalCode.map((c) => `${c.slice(0, 7)}0`),
  fc.constantFrom('', '   ', null, undefined, 12345678, {})
)

const anyCodeInput = fc.oneof({ weight: 3, arbitrary: legalVariant }, { weight: 2, arbitrary: malformedCode })

const NOW = 1750000000000

/**
 * 写入时刻的取值族，按设计的生成器清单：
 * 时间差 ∈ {0, 1, 604799999, 604800000, 604800001, 负数, NaN, 缺失, "abc"}。
 * 产出直接写进存储的原始串（缺失以 null 表示「键不存在」）。
 */
const rawAtSpec = fc.oneof(
  fc
    .constantFrom(0, 1, PENDING_TTL_MS - 1, PENDING_TTL_MS, PENDING_TTL_MS + 1)
    .map((elapsed) => String(NOW - elapsed)),
  fc.integer({ min: 1, max: 10 ** 12 }).map((d) => String(NOW + d)), // 时钟回拨 → 负时间差
  fc.constantFrom('NaN', 'abc', '', '   ', 'Infinity', '12ab', null)
)

const faultSpec = fc.constantFrom('none', 'get', 'set', 'remove')

describe('Property 15: 待绑定邀请码的时效状态机', () => {
  beforeEach(() => {
    installUniMock()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
    delete globalThis.uni
  })

  it('carried == (code 合法 ∧ 0 ≤ now - at < TTL)，携带时保留两键、不携带时删除两键', () => {
    fc.assert(
      fc.property(anyCodeInput, rawAtSpec, (codeInput, rawAt) => {
        installUniMock()
        const storedCode = codeInput === null || codeInput === undefined ? '' : String(codeInput)
        store.set(KEY_CODE, storedCode)
        if (rawAt !== null) store.set(KEY_AT, rawAt)

        const trimmedAt = rawAt === null ? '' : String(rawAt).trim()
        const at = trimmedAt === '' ? Number.NaN : Number(trimmedAt)
        const elapsed = NOW - at
        const expectCarried =
          isLegal(codeInput) && Number.isFinite(at) && elapsed >= 0 && elapsed < PENDING_TTL_MS

        const got = takePendingInviteCode()

        expect(got).toBe(expectCarried ? normalize(codeInput) : '')
        const after = snapshot()
        if (expectCarried) {
          // 取不清除：登录成功后才由 clearPendingInviteCode 清（4.8、4.12）
          expect(after.code).toBe(storedCode)
          expect(after.at).toBe(rawAt === null ? null : rawAt)
        } else {
          expect(after.code).toBeNull()
          expect(after.at).toBeNull()
        }
      }),
      { numRuns: 300 }
    )
  })

  it('合法码写入覆盖旧值并记录写入时刻；非法码不写入且不修改已有暂存', () => {
    fc.assert(
      fc.property(legalVariant, anyCodeInput, fc.integer({ min: 0, max: 10 ** 6 }), (first, second, gapMs) => {
        installUniMock()
        vi.setSystemTime(NOW)

        expect(savePendingInviteCode(first)).toBe(true)
        expect(snapshot()).toEqual({ code: normalize(first), at: String(NOW) })

        vi.setSystemTime(NOW + gapMs)
        const before = snapshot()
        const ok = savePendingInviteCode(second)

        expect(ok).toBe(isLegal(second))
        if (isLegal(second)) {
          // 以最近一次写入为准（4.1）
          expect(snapshot()).toEqual({ code: normalize(second), at: String(NOW + gapMs) })
        } else {
          expect(snapshot()).toEqual(before)
        }
      }),
      { numRuns: 200 }
    )
  })

  it('清除后两键均不存在且此后不再携带；写入→取→取 幂等', () => {
    fc.assert(
      fc.property(legalVariant, fc.integer({ min: 0, max: PENDING_TTL_MS - 1 }), (codeInput, elapsed) => {
        installUniMock()
        vi.setSystemTime(NOW)
        expect(savePendingInviteCode(codeInput)).toBe(true)

        vi.setSystemTime(NOW + elapsed)
        expect(takePendingInviteCode()).toBe(normalize(codeInput))
        expect(takePendingInviteCode()).toBe(normalize(codeInput))

        expect(clearPendingInviteCode()).toBe(true)
        expect(snapshot()).toEqual({ code: null, at: null })
        expect(takePendingInviteCode()).toBe('')
      }),
      { numRuns: 200 }
    )
  })

  it('存储 get / set / remove 抛错时三个入口均不抛出，返回 "" / false（不阻断登录主路径）', () => {
    fc.assert(
      fc.property(anyCodeInput, faultSpec, (codeInput, fault) => {
        installUniMock()
        vi.setSystemTime(NOW)
        if (fault !== 'none') faults[fault] = true

        let threw = false
        let saved
        let taken
        let cleared
        try {
          saved = savePendingInviteCode(codeInput)
          taken = takePendingInviteCode()
          cleared = clearPendingInviteCode()
        } catch (e) {
          threw = true
        }

        expect(threw).toBe(false)
        if (fault === 'set') expect(saved).toBe(false)
        if (fault === 'get') expect(taken).toBe('')
        if (fault === 'remove') expect(cleared).toBe(false)
        if (fault === 'none' || fault === 'get') expect(saved).toBe(isLegal(codeInput))
      }),
      { numRuns: 200 }
    )
  })
})
