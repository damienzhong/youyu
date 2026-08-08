/**
 * Feature: invite-system, Property 17: 邀请页的展示契约与分享降级
 *
 * 对任意邀请码、任意邀请信息响应取值与任意列表结果：邀请链接严格等于
 * `/pages/invitelanding/invitelanding?code={8 字符原文}`（不额外转义）；转发卡片的 `path` 等于邀请链接、
 * 邀请链接为空时降级为不带 `code` 的落地页路径，标题恒含「有余」且长度 ≤30；列表首屏至多 20 条、
 * 每次上拉追加至多 20 条、已加载条数等于总条数后不再发起请求（`requestCount == ceil(min(loaded, total)/20)`，
 * 总条数为 0 时仅首屏一次）且恒有 `loaded <= total`；状态文案与 `REGISTERED`/`INVALID` 一一对应（双射）。
 *
 * 不变式：`link == template(code)`；`sharePath == (link || LANDING_PATH) ∧ title.includes('有余') ∧ title.length ≤ 30`；
 * `requestCount == max(1, ceil(min(loaded, total)/20))` 且 `loaded == total` 且 `loaded <= total`；
 * 状态文案映射在 {REGISTERED, INVALID} 上是双射，且未知状态不会落进这两个文案。
 *
 * 本任务只覆盖 `utils/invite.js` 的纯逻辑（链接模板、分享标题与降级、分页累计与停止条件、状态文案映射）；
 * 剪贴板写入内容与 1500ms 提示、二维码失败时三个操作仍可用、info/列表失败降级的实际渲染由任务 14
 * 的手工验收清单覆盖。
 *
 * Validates: Requirements 2.1, 7.13
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  buildInviteLink,
  buildInviteSharePayload,
  hasMoreInvitees,
  inviteListStateAfterLoad,
  inviteStatusLabel,
  mergeInvitees,
  nextInviteListRequest,
  isValidShareTitle,
  INVITE_LANDING_PATH,
  INVITE_LIST_STATE,
  INVITE_PAGE_SIZE,
  INVITE_SHARE_TITLE,
  INVITE_SHARE_TITLE_MAX_LEN,
  INVITE_STATUS_LABELS
} from './invite'

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

const legalCode = fc
  .array(fc.constantFrom(...ALPHABET), { minLength: 8, maxLength: 8 })
  .map((cs) => cs.join(''))

/** 合法码的等价输入变形：规整后仍是同一个 8 字符原文。 */
const legalCodeInput = fc
  .tuple(legalCode, fc.constantFrom('asis', 'lower', 'pad', 'lowerPad'))
  .map(([code, how]) => {
    if (how === 'lower') return { raw: code.toLowerCase(), code }
    if (how === 'pad') return { raw: `  ${code}\t`, code }
    if (how === 'lowerPad') return { raw: ` ${code.toLowerCase()} `, code }
    return { raw: code, code }
  })

// ---- 列表分页的纯驱动：只用 utils 的判定 + 一个「服务端」页函数 ----

/** 服务端第 page 页：按 register_time 倒序切片，至多 size 条（需求 7.3、7.13）。 */
function serverPage(total, page, size) {
  const from = page * size
  const items = []
  for (let i = from; i < Math.min(total, from + size); i++) items.push({ inviteId: i })
  return { items, total }
}

/**
 * 复刻页面的分页循环：onLoad 发首屏（page 0），随后按 nextInviteListRequest 的判定决定是否再发。
 * `pulls` 为用户上拉次数（可远多于实际页数，用来验证停止后不再发请求）。
 */
function drivePaging(total, pulls) {
  let loaded = []
  let loadedTotal = 0
  let nextPage = 0
  let listState = INVITE_LIST_STATE.LOADING
  let requestCount = 0

  const request = (page) => {
    requestCount++
    const res = serverPage(total, page, INVITE_PAGE_SIZE)
    // 单次请求返回条数不超过分页大小（需求 7.3）
    expect(res.items.length).toBeLessThanOrEqual(INVITE_PAGE_SIZE)
    loadedTotal = Number(res.total) || 0
    loaded = mergeInvitees(loaded, res.items, page)
    nextPage = page + 1
    listState = inviteListStateAfterLoad(loadedTotal)
    return res.items.length
  }

  const firstScreen = request(0)

  const appended = []
  for (let i = 0; i < pulls; i++) {
    const next = nextInviteListRequest({
      listState,
      loadingMore: false,
      loaded: loaded.length,
      total: loadedTotal,
      nextPage
    })
    if (!next.shouldRequest) continue
    appended.push(request(next.page))
  }

  return { loaded, loadedTotal, listState, requestCount, firstScreen, appended, nextPage }
}

describe('Property 17: 邀请页的展示契约与分享降级', () => {
  it('link == /pages/invitelanding/invitelanding?code={8 字符原文}，不额外转义（需求 2.1）', () => {
    fc.assert(
      fc.property(legalCodeInput, ({ raw, code }) => {
        const link = buildInviteLink(raw)

        expect(link).toBe(`${INVITE_LANDING_PATH}?code=${code}`)
        // code 为原文：既没有百分号编码，也没有首尾空白
        expect(link.slice(`${INVITE_LANDING_PATH}?code=`.length)).toBe(code)
        expect(link).not.toContain('%')
        expect(link).toBe(link.trim())
        expect(encodeURIComponent(code)).toBe(code)
      }),
      { numRuns: 150 }
    )
  })

  it('sharePath == 邀请链接（为空则降级为无 code 的落地页），标题恒含「有余」且长度 ≤30（需求 2.2、2.9）', () => {
    const emptyish = fc.constantFrom('', '   ', null, undefined)
    fc.assert(
      fc.property(
        fc.oneof(
          legalCode.map((c) => ({ link: buildInviteLink(c), empty: false })),
          emptyish.map((v) => ({ link: v, empty: true }))
        ),
        ({ link, empty }) => {
          const payload = buildInviteSharePayload(link)

          expect(payload.title).toBe(INVITE_SHARE_TITLE)
          expect(payload.title).toContain('有余')
          expect(payload.title.length).toBeLessThanOrEqual(INVITE_SHARE_TITLE_MAX_LEN)
          expect(isValidShareTitle(payload.title)).toBe(true)

          if (empty) {
            expect(payload.path).toBe(INVITE_LANDING_PATH)
            expect(payload.path).not.toContain('code=')
            expect(payload.degraded).toBe(true)
          } else {
            expect(payload.path).toBe(link)
            expect(payload.degraded).toBe(false)
          }
        }
      ),
      { numRuns: 100 }
    )
  })

  it('requestCount == max(1, ceil(min(loaded,total)/20))，首屏 ≤20、每次追加 ≤20、loaded == total ≤ total（需求 7.13）', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 200 }), fc.integer({ min: 0, max: 15 }), (total, pulls) => {
        const r = drivePaging(total, pulls)

        // 首屏至多 20 条，每次上拉追加至多 20 条
        expect(r.firstScreen).toBeLessThanOrEqual(INVITE_PAGE_SIZE)
        r.appended.forEach((n) => expect(n).toBeLessThanOrEqual(INVITE_PAGE_SIZE))

        const loaded = r.loaded.length
        // 累计条数恒不超过总条数
        expect(loaded).toBeLessThanOrEqual(total)
        // 上拉次数足够时取完全部；不够时恰好等于已发请求页数 × 20 的截断
        const pagesFetched = r.requestCount
        expect(loaded).toBe(Math.min(total, pagesFetched * INVITE_PAGE_SIZE))
        // 停止条件：首屏必发一次；此后每 20 条一次请求
        const expectedCount = Math.max(
          1,
          Math.min(Math.ceil(Math.min(loaded, total) / INVITE_PAGE_SIZE), 1 + pulls)
        )
        expect(pagesFetched).toBe(expectedCount)
        // 记录无重复、顺序即服务端序（累计而非覆盖）
        expect(r.loaded.map((x) => x.inviteId)).toEqual([...Array(loaded).keys()])
        expect(r.listState).toBe(
          total === 0 ? INVITE_LIST_STATE.EMPTY : INVITE_LIST_STATE.LOADED
        )
      }),
      { numRuns: 200 }
    )
  })

  it('已加载条数等于总条数后，任意多次上拉都不再发起请求（需求 7.13）', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 200 }), fc.integer({ min: 0, max: 15 }), (total, extraPulls) => {
        // 先用足量上拉把全部取完
        const pullsToDrain = Math.ceil(200 / INVITE_PAGE_SIZE) + 1
        const r = drivePaging(total, pullsToDrain)
        expect(r.loaded.length).toBe(total)
        expect(hasMoreInvitees(r.loaded.length, total)).toBe(false)

        const before = r.requestCount
        // 取完后再上拉 extraPulls 次：判定恒为「不发请求」
        for (let i = 0; i < extraPulls; i++) {
          const next = nextInviteListRequest({
            listState: r.listState,
            loadingMore: false,
            loaded: r.loaded.length,
            total,
            nextPage: r.nextPage
          })
          expect(next.shouldRequest).toBe(false)
          expect(next.page).toBeNull()
        }
        expect(r.requestCount).toBe(before)
      }),
      { numRuns: 100 }
    )
  })

  it('列表失败 / 追加中 / 首屏未成功时不发起下一页请求（需求 7.12、7.13）', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...Object.values(INVITE_LIST_STATE)),
        fc.boolean(),
        fc.integer({ min: 0, max: 200 }),
        fc.integer({ min: 0, max: 200 }),
        fc.integer({ min: 0, max: 10 }),
        (listState, loadingMore, loaded, total, nextPage) => {
          const next = nextInviteListRequest({ listState, loadingMore, loaded, total, nextPage })
          const expected =
            listState === INVITE_LIST_STATE.LOADED && !loadingMore && loaded < total

          expect(next.shouldRequest).toBe(expected)
          expect(next.page).toBe(expected ? nextPage : null)
        }
      ),
      { numRuns: 150 }
    )
  })

  it('状态文案与 REGISTERED / INVALID 一一对应（双射），未知状态不落进这两个文案（需求 7.13）', () => {
    const known = Object.keys(INVITE_STATUS_LABELS)
    const labels = Object.values(INVITE_STATUS_LABELS)
    // 两个状态各有互不相同的文案 → 单射；文案集合恰为两项 → 满射
    expect(labels.length).toBe(2)
    expect(new Set(labels).size).toBe(2)

    fc.assert(
      fc.property(fc.constantFrom(...known), (status) => {
        const label = inviteStatusLabel(status)
        expect(label).toBe(INVITE_STATUS_LABELS[status])
        // 逆映射唯一：由文案能反推回原状态
        expect(known.filter((s) => inviteStatusLabel(s) === label)).toEqual([status])
      }),
      { numRuns: 25 }
    )

    fc.assert(
      fc.property(
        fc.oneof(
          fc.string({ minLength: 0, maxLength: 20 }).filter((s) => !known.includes(s)),
          fc.constantFrom(null, undefined, '', 'registered', 'invalid', 'DELETED', 0, {})
        ),
        (unknown) => {
          const label = inviteStatusLabel(unknown)
          expect(labels).not.toContain(label)
        }
      ),
      { numRuns: 150 }
    )
  })

  it('首屏覆盖、后续页追加；接口 items 缺失或畸形时不丢已加载记录（需求 7.12）', () => {
    fc.assert(
      fc.property(
        fc.array(fc.record({ inviteId: fc.integer() }), { maxLength: 20 }),
        fc.oneof(
          fc.array(fc.record({ inviteId: fc.integer() }), { maxLength: 20 }),
          fc.constantFrom(null, undefined, 'x', 0, {})
        ),
        fc.integer({ min: 0, max: 5 }),
        (prev, incoming, page) => {
          const merged = mergeInvitees(prev, incoming, page)
          const add = Array.isArray(incoming) ? incoming : []

          if (page === 0) expect(merged).toEqual(add)
          else expect(merged).toEqual(prev.concat(add))
          // 非首屏时已加载记录一行不动
          if (page !== 0) expect(merged.slice(0, prev.length)).toEqual(prev)
        }
      ),
      { numRuns: 100 }
    )
  })
})
