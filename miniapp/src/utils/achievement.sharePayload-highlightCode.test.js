/**
 * Feature: achievement-system, 任务 9.3: `buildAchievementSharePayload` 与
 * `resolveHighlightCode` 的属性测试
 *
 * `buildAchievementSharePayload(achievement)` 的不变式：返回的字段集恰好为 `{ title, path }` 两项；
 * `title` 恒含产品名「有余」、长度恒落在 [1, 30] 个字符的闭区间内、且不含被劈开的半个字符
 * （按 Unicode 码点裁剪，不按 UTF-16 char 裁）；成就展示名称在需求 1 第 1 条约定的
 * 2–10 个码点范围内时 `title` 恒完整包含该名称；
 * `path` 恒为 `/pages/achievement/achievement`，编码非空时带上 `?code=<URL 编码后的编码>`。
 *
 * `resolveHighlightCode(rawCode, achievements)` 的不变式：对缺失 / 空白 / 去空白后长度超过 64 个字符 /
 * 不在成就清单内的取值恒返回 `null`；命中时恒返回去空白后的编码本身（逐字符相等、区分大小写）；
 * 百分号编码畸形时降级为按原文解析且绝不抛出。
 *
 * 交叉不变式：分享 `path` 里的 `code` 参数经 `resolveHighlightCode` 解析后恒等于原成就编码
 * （分享 → 落地高亮的闭环）。
 *
 * 本任务只覆盖 `utils/achievement.js` 的纯函数（canvas 绘制、相册授权与滚动高亮由手工验收清单覆盖）。
 *
 * Validates: Requirements 8.3, 8.10, 8.12
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  buildAchievementSharePayload,
  resolveHighlightCode,
  SHARE_TITLE_MAX_LEN,
  CODE_MAX_LEN,
  ACHIEVEMENT_PAGE_PATH
} from './achievement'

/** 需求 1 第 1 条的 16 个成就编码与展示名称。 */
const CATALOG = [
  ['FIRST_RECORD', '开张'],
  ['STREAK_7', '七日不辍'],
  ['STREAK_30', '卅日成习'],
  ['STREAK_100', '百日不辍'],
  ['STREAK_365', '岁岁有余'],
  ['RECORD_10', '小有账目'],
  ['RECORD_100', '百笔有余'],
  ['RECORD_500', '五百笔在册'],
  ['RECORD_1000', '千笔如一'],
  ['DAYS_100', '百日记账'],
  ['INVITE_1', '同行有余'],
  ['COLLAB_1', '共账之始'],
  ['BUDGET_MET', '预算达标'],
  ['BUDGET_MASTER', '预算达人'],
  ['SAVING_MASTER', '储蓄达人'],
  ['TRAVEL_MASTER', '旅行达人']
]

const CODES = CATALOG.map(([code]) => code)
const ACHIEVEMENTS = CATALOG.map(([code, name]) => ({ code, name, description: `${name}的描述` }))

/** 是否含未配对的代理项（半个字符）——按码点切分后仍落在 D800–DFFF 即为被劈开的半个字符。 */
function hasLoneSurrogate(s) {
  return Array.from(s).some((ch) => {
    const cp = ch.codePointAt(0)
    return cp >= 0xd800 && cp <= 0xdfff
  })
}

/** 展示名称取值族：清单内的中文名、含 emoji / 生僻字的名称（2–10 个码点），以及畸形取值。 */
const anyName = fc.oneof(
  { weight: 6, arbitrary: fc.constantFrom(...CATALOG.map(([, name]) => name)) },
  {
    weight: 3,
    arbitrary: fc
      .array(fc.constantFrom('开', '张', '余', '🏆', '🎉', '𠮷', '㐀'), { minLength: 2, maxLength: 10 })
      .map((cs) => cs.join(''))
  },
  { weight: 1, arbitrary: fc.constantFrom('', '   ', null, undefined, 0, 42, true, {}, []) }
)

/** 成就编码取值族：清单内编码、带首尾空白的等价变形、超长串与畸形取值。 */
const anyCode = fc.oneof(
  { weight: 6, arbitrary: fc.constantFrom(...CODES) },
  { weight: 2, arbitrary: fc.constantFrom(...CODES).map((c) => `  ${c} `) },
  { weight: 1, arbitrary: fc.string({ minLength: 0, maxLength: 80 }) },
  { weight: 1, arbitrary: fc.constantFrom('', '   ', null, undefined, 0, 42, true, {}, [], '中文编码', 'first_record') }
)

describe('任务 9.3: 分享载荷与待高亮成就编码', () => {
  it('buildAchievementSharePayload: 字段集恰为 {title, path}，title 含「有余」且长度 ∈ [1, 30]', () => {
    fc.assert(
      fc.property(anyName, anyCode, (name, code) => {
        const payload = buildAchievementSharePayload({ name, code })
        expect(Object.keys(payload).sort()).toEqual(['path', 'title'])
        expect(typeof payload.title).toBe('string')
        expect(payload.title).toContain('有余')
        expect(payload.title.length).toBeGreaterThanOrEqual(1)
        expect(payload.title.length).toBeLessThanOrEqual(SHARE_TITLE_MAX_LEN)
        // 按码点裁剪：绝不把 emoji 或生僻字劈成半个字符
        expect(hasLoneSurrogate(payload.title)).toBe(false)
      }),
      { numRuns: 250 }
    )
  })

  it('buildAchievementSharePayload: 名称在 2–10 个码点内时 title 完整包含该名称', () => {
    const boundedName = fc
      .array(fc.constantFrom('开', '张', '余', '🏆', '🎉', '𠮷', '不', '辍', '习'), { minLength: 2, maxLength: 10 })
      .map((cs) => cs.join(''))
    fc.assert(
      fc.property(boundedName, fc.constantFrom(...CODES), (name, code) => {
        const { title } = buildAchievementSharePayload({ name, code })
        expect(title).toContain(name)
        expect(title).toContain('有余')
        expect(title.length).toBeLessThanOrEqual(SHARE_TITLE_MAX_LEN)
      }),
      { numRuns: 200 }
    )
  })

  it('buildAchievementSharePayload: path 恒为成就页路径，编码非空时带 URL 编码后的 code', () => {
    fc.assert(
      fc.property(anyName, anyCode, (name, code) => {
        const { path } = buildAchievementSharePayload({ name, code })
        const trimmedCode = code === null || code === undefined ? '' : String(code).trim()
        if (trimmedCode === '') {
          expect(path).toBe(ACHIEVEMENT_PAGE_PATH)
        } else {
          expect(path).toBe(`${ACHIEVEMENT_PAGE_PATH}?code=${encodeURIComponent(trimmedCode)}`)
        }
      }),
      { numRuns: 200 }
    )
  })

  it('buildAchievementSharePayload: 畸形入参不抛出，title 仍含「有余」且 path 仍是成就页', () => {
    fc.assert(
      fc.property(fc.constantFrom(null, undefined, 0, '', 'x', 42, true, [], Number.NaN), (bad) => {
        const payload = buildAchievementSharePayload(bad)
        expect(payload.title).toContain('有余')
        expect(payload.title.length).toBeLessThanOrEqual(SHARE_TITLE_MAX_LEN)
        expect(payload.path).toBe(ACHIEVEMENT_PAGE_PATH)
      }),
      { numRuns: 50 }
    )
  })

  it('分享 → 落地闭环：path 里的 code 参数经 resolveHighlightCode 解析后等于原编码', () => {
    fc.assert(
      fc.property(fc.constantFrom(...CATALOG), ([code, name]) => {
        const { path } = buildAchievementSharePayload({ code, name })
        const raw = path.slice(`${ACHIEVEMENT_PAGE_PATH}?code=`.length)
        expect(resolveHighlightCode(raw, ACHIEVEMENTS)).toBe(code)
      }),
      { numRuns: 50 }
    )
  })

  it('resolveHighlightCode: 命中清单时返回去空白后的编码本身，逐字符相等且区分大小写', () => {
    const variant = fc.tuple(fc.constantFrom(...CODES), fc.constantFrom('asis', 'pad', 'encoded', 'padEncoded'))
    fc.assert(
      fc.property(variant, ([code, how]) => {
        let raw = code
        if (how === 'pad') raw = `  ${code}\t`
        if (how === 'encoded') raw = encodeURIComponent(code)
        if (how === 'padEncoded') raw = ` ${encodeURIComponent(code)} `
        expect(resolveHighlightCode(raw, ACHIEVEMENTS)).toBe(code)
        // 大小写不同即不匹配（编码区分大小写）
        expect(resolveHighlightCode(code.toLowerCase(), ACHIEVEMENTS)).toBeNull()
      }),
      { numRuns: 100 }
    )
  })

  it('resolveHighlightCode: 空白 / 超过 64 个字符 / 不在清单内的取值恒返回 null', () => {
    const tooLong = fc
      .integer({ min: CODE_MAX_LEN + 1, max: CODE_MAX_LEN + 40 })
      .map((n) => 'A'.repeat(n))
    const blank = fc.constantFrom('', '   ', '\t', '\n', null, undefined)
    /** 去空白后非空、长度合规，且无论按原文还是按 URL 解码都不在清单内的串。 */
    const notInCatalog = fc.string({ minLength: 1, maxLength: 30 }).filter((s) => {
      let decoded = s
      try {
        decoded = decodeURIComponent(s)
      } catch (e) {
        decoded = s
      }
      const t = decoded.trim()
      return t.length > 0 && t.length <= CODE_MAX_LEN && !CODES.includes(t) && !CODES.includes(s.trim())
    })
    fc.assert(
      fc.property(fc.oneof(tooLong, blank, notInCatalog), (raw) => {
        expect(resolveHighlightCode(raw, ACHIEVEMENTS)).toBeNull()
      }),
      { numRuns: 200 }
    )
  })

  it('resolveHighlightCode: 恰好 64 个字符不因长度被拒（只因不在清单内返回 null）', () => {
    const code64 = 'A'.repeat(CODE_MAX_LEN)
    expect(resolveHighlightCode(code64, ACHIEVEMENTS)).toBeNull()
    expect(resolveHighlightCode(code64, [{ code: code64 }])).toBe(code64)
    expect(resolveHighlightCode('A'.repeat(CODE_MAX_LEN + 1), [{ code: 'A'.repeat(CODE_MAX_LEN + 1) }])).toBeNull()
  })

  it('resolveHighlightCode: 百分号编码畸形与畸形清单入参均不抛出且返回 null', () => {
    const malformedPercent = fc.constantFrom('%', '%E0%A4%A', '%%', '%zz', 'A%', '%C0%80')
    fc.assert(
      fc.property(malformedPercent, (raw) => {
        expect(resolveHighlightCode(raw, ACHIEVEMENTS)).toBeNull()
      }),
      { numRuns: 50 }
    )
    fc.assert(
      fc.property(fc.constantFrom(...CODES), fc.constantFrom(null, undefined, 0, '', 'x', 42, true, {}), (code, badList) => {
        expect(resolveHighlightCode(code, badList)).toBeNull()
      }),
      { numRuns: 50 }
    )
    // 清单内含畸形项时仍能命中合法项
    expect(resolveHighlightCode('BUDGET_MET', [null, 1, 'x', {}, { code: 'BUDGET_MET' }])).toBe('BUDGET_MET')
  })
})
