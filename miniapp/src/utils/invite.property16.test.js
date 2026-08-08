/**
 * Feature: invite-system, Property 16: 落地页的邀请码解析与降级
 *
 * 对任意 `code` / `scene` 启动参数取值（URL 编码变形、首尾空白、小写、长度 ≠8、含字母表外字符、
 * 百分号编码畸形、缺失）与任意登录态：解析结果等于「URL 解码 → 去首尾空白 → 转大写」后的取值；
 * 合法且未登录时才发起邀请人展示信息查询并写入待绑定邀请码；参数非法或已登录时不发起查询、
 * 不写入也不修改已有暂存（本地存储快照逐字节相等）。
 *
 * 不变式：`parsed == upper(trim(decode(raw)))` 且解析恒不抛出；
 * `shouldQuery == shouldPersist == (合法 ∧ 未登录)`；
 * `state ∈ {INVITER_SHOWN, DEFAULT, LOGGED_IN}` 且由（合法性、登录态）唯一确定；
 * 不写入分支下 `snapshot(before) == snapshot(after)`。
 *
 * 本任务只覆盖 `utils/invite.js` 的纯解析与判定函数；页面态渲染、查询失败/5s 超时后的降级展示
 * 与两个登录入口由任务 14 的手工验收清单覆盖。
 *
 * Validates: Requirements 2.4, 2.5, 4.11
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import fc from 'fast-check'
import { STORAGE_KEYS } from './config'
import {
  INVITE_LANDING_STATE,
  decodeInviteParam,
  parseLandingInviteCode,
  decideInviteLanding,
  resolveInviteLanding,
  savePendingInviteCode
} from './invite'

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const KEY_CODE = STORAGE_KEYS.pendingInviteCode
const KEY_AT = STORAGE_KEYS.pendingInviteCodeAt

/** 独立于被测实现重算判定（照抄验收标准 2.5 / 4.11 的字面定义）。 */
function isLegal(code) {
  return typeof code === 'string' && code.length === 8 && [...code].every((c) => ALPHABET.includes(c))
}

/** 参考实现：URL 解码（畸形则降级为原文）→ trim → 大写。 */
function expectedParse(raw) {
  if (raw === null || raw === undefined) return ''
  const s = String(raw)
  let decoded = s
  try {
    decoded = decodeURIComponent(s)
  } catch (e) {
    decoded = s
  }
  return decoded.trim().toUpperCase()
}

// ---- mock 的内存存储（本属性只关心快照是否被改动）----

let store

function installUniMock() {
  store = new Map()
  globalThis.uni = {
    getStorageSync(key) {
      return store.has(key) ? store.get(key) : ''
    },
    setStorageSync(key, value) {
      store.set(key, value)
    },
    removeStorageSync(key) {
      store.delete(key)
    }
  }
}

function snapshot() {
  return {
    code: store.has(KEY_CODE) ? store.get(KEY_CODE) : null,
    at: store.has(KEY_AT) ? store.get(KEY_AT) : null
  }
}

// ---- 生成器 ----

const legalCode = fc
  .array(fc.constantFrom(...ALPHABET), { minLength: 8, maxLength: 8 })
  .map((cs) => cs.join(''))

/** 合法码的等价变形：整体百分号编码、逐字符编码、小写、首尾空白（含编码后的空白）。 */
const legalVariant = fc
  .tuple(legalCode, fc.constantFrom('asis', 'lower', 'pad', 'encodedPad', 'fullEncoded', 'lowerEncoded'))
  .map(([code, how]) => {
    if (how === 'lower') return { raw: code.toLowerCase(), code }
    if (how === 'pad') return { raw: `  ${code}\t`, code }
    if (how === 'encodedPad') return { raw: `%20${code}%20`, code }
    if (how === 'fullEncoded') {
      return { raw: [...code].map((c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`).join(''), code }
    }
    if (how === 'lowerEncoded') return { raw: encodeURIComponent(` ${code.toLowerCase()} `), code }
    return { raw: code, code }
  })

/** 畸形取值族：长度 ≠8、字母表外字符（I/O/0/1）、空值、百分号编码畸形、任意串。 */
const malformedRaw = fc.oneof(
  fc.string({ minLength: 0, maxLength: 200 }),
  legalCode.map((c) => c.slice(0, 7)),
  legalCode.map((c) => `${c}A`),
  legalCode.map((c) => `I${c.slice(1)}`),
  legalCode.map((c) => `${c.slice(0, 7)}0`),
  legalCode.map((c) => `%${c}`), // decodeURIComponent 抛错 → 降级按原文解析
  fc.constantFrom('', '   ', '%', '%E0%A4%A', '%zz', '%%%%%%%%', null, undefined, 12345678, {})
)

const anyRaw = fc.oneof(
  { weight: 3, arbitrary: legalVariant.map((v) => v.raw) },
  { weight: 2, arbitrary: malformedRaw }
)

/** 参数位置：只有 code / 只有 scene / 两者都有（code 优先）/ 都无 / options 本身缺失。 */
const optionsShape = fc.constantFrom('code', 'scene', 'both', 'none', 'nullOptions')

function buildOptions(shape, raw, otherRaw) {
  if (shape === 'nullOptions') return null
  if (shape === 'none') return {}
  if (shape === 'code') return { code: raw }
  if (shape === 'scene') return { scene: raw }
  return { code: raw, scene: otherRaw }
}

/** `code` 优先、缺失（undefined/null）时回落 `scene` —— 与 `options.code ?? options.scene` 同义。 */
function expectedRawOf(shape, raw, otherRaw) {
  if (shape === 'nullOptions' || shape === 'none') return undefined
  if (shape === 'code') return raw
  if (shape === 'scene') return raw
  return raw === undefined || raw === null ? otherRaw : raw
}

const existingPending = fc.constantFrom('none', 'valid', 'expired')

describe('Property 16: 落地页的邀请码解析与降级', () => {
  beforeEach(() => {
    installUniMock()
  })

  afterEach(() => {
    delete globalThis.uni
  })

  it('parsed == upper(trim(decode(raw)))，且对任意取值恒不抛出（含百分号编码畸形）', () => {
    fc.assert(
      fc.property(optionsShape, anyRaw, anyRaw, (shape, raw, otherRaw) => {
        const options = buildOptions(shape, raw, otherRaw)
        const expected = expectedParse(expectedRawOf(shape, raw, otherRaw))

        const parsed = parseLandingInviteCode(options)

        expect(parsed).toBe(expected)
        // 解析结果恒为已 trim 的大写串
        expect(parsed).toBe(parsed.trim().toUpperCase())
      }),
      { numRuns: 200 }
    )
  })

  it('合法码的编码 / 空白 / 大小写变形均解析回该 8 字符原文', () => {
    fc.assert(
      fc.property(legalVariant, fc.constantFrom('code', 'scene'), ({ raw, code }, key) => {
        const parsed = parseLandingInviteCode({ [key]: raw })
        expect(parsed).toBe(code)
        expect(decodeInviteParam(raw)).toBe(code)
      }),
      { numRuns: 150 }
    )
  })

  it('shouldQuery == shouldPersist == (合法 ∧ 未登录)，state 由（合法性, 登录态）唯一确定', () => {
    fc.assert(
      fc.property(optionsShape, anyRaw, anyRaw, fc.boolean(), (shape, raw, otherRaw, loggedIn) => {
        const options = buildOptions(shape, raw, otherRaw)
        const parsed = expectedParse(expectedRawOf(shape, raw, otherRaw))
        const legal = isLegal(parsed)

        const d = resolveInviteLanding(options, loggedIn)

        expect(d.code).toBe(parsed)
        expect(d.valid).toBe(legal)
        expect(d.shouldQuery).toBe(legal && !loggedIn)
        expect(d.shouldPersist).toBe(legal && !loggedIn)
        expect(d.shouldQuery).toBe(d.shouldPersist)
        const expectedState = loggedIn
          ? INVITE_LANDING_STATE.LOGGED_IN
          : legal
            ? INVITE_LANDING_STATE.INVITER_SHOWN
            : INVITE_LANDING_STATE.DEFAULT
        expect(d.state).toBe(expectedState)
        expect(Object.values(INVITE_LANDING_STATE)).toContain(d.state)
        // 同一输入的判定可重复（纯函数）
        expect(decideInviteLanding(parsed, loggedIn)).toEqual(d)
      }),
      { numRuns: 200 }
    )
  })

  it('非法参数或已登录时：不查询且本地暂存快照逐字节不变（需求 2.5、4.11）', () => {
    fc.assert(
      fc.property(
        optionsShape,
        anyRaw,
        anyRaw,
        fc.boolean(),
        existingPending,
        legalCode,
        (shape, raw, otherRaw, loggedIn, pending, seedCode) => {
          installUniMock()
          if (pending === 'valid') {
            store.set(KEY_CODE, seedCode)
            store.set(KEY_AT, String(Date.now()))
          } else if (pending === 'expired') {
            store.set(KEY_CODE, seedCode)
            store.set(KEY_AT, '1')
          }
          const before = snapshot()

          const d = resolveInviteLanding(buildOptions(shape, raw, otherRaw), loggedIn)
          // 页面按判定行事：仅在 shouldPersist 为真时写暂存
          if (d.shouldPersist) savePendingInviteCode(d.code)

          if (d.shouldPersist) {
            expect(snapshot().code).toBe(d.code)
          } else {
            expect(d.shouldQuery).toBe(false)
            expect(snapshot()).toEqual(before)
          }
        }
      ),
      { numRuns: 150 }
    )
  })
})
