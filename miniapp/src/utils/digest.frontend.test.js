/**
 * Feature: smart-monthly-report, 任务 12：前端降级与海报隔离校验（组件级 / mock）。
 *
 * 按 vitest.config.js 约定（node 环境、只跑 src/utils 下不依赖 uni API 的纯逻辑），
 * 本文件覆盖报表页月报区块从 pages/report/report.vue 抽出的纯逻辑（src/utils/digest.js），
 * 对应任务 12：
 *   1. 降级与不阻断（需求 10.1、10.2、10.4）：
 *      - 月报请求失败或 5000ms 超时 → digestVisible=false、digest=null 静默隐藏；
 *      - 其余报表状态（分类行/合计/趋势/成员）取值不受影响。
 *   2. 未登录不请求（需求 10.3）：无有效令牌 → 不发起 monthlyDigest，隐藏区块。
 *   3. 全部账本聚合视图（需求 1.9 / 10.3）：ledgerStore.isAll → 不请求、不展示。
 *   4. 海报字段隔离（需求 8.3）：海报仅绘制当前账本字段（目标月、收入、支出、结余、
 *      分类排行、最大单笔），绝不含邮箱/令牌/其它账本数据——经 mock canvas 捕获绘制文本校验，
 *      并对字段抽取函数断言仅读取白名单键。
 *
 * 真实 uni.createCanvasContext / canvasToTempFilePath / saveImageToPhotosAlbum / 分享等
 * 平台交互按约定归手工验收清单，不在此自动化。
 *
 * Validates: Requirements 8.3, 10.1, 10.2, 10.3, 10.4
 */
import { describe, it, expect, vi } from 'vitest'
import fc from 'fast-check'
import {
  DIGEST_TIMEOUT_MS,
  shouldFetchDigest,
  resolveDigestState,
  selectPosterFields,
  drawDigestPoster,
  digestStatusText,
  shortDate,
  POSTER_TOP_N
} from './digest'

// 一份典型的当前账本月报数据包（九模块）。
function sampleDigest() {
  return {
    month: '2024-03',
    monthStatus: 'final',
    income: '8000.00',
    expense: '5321.00',
    netBalance: '2679.00',
    trend: [{ date: '2024-03-01', income: '0.00', expense: '120.00' }],
    categoryRanking: [
      { categoryId: 1, categoryName: '餐饮', amount: '2000.00', percentage: 37.59, count: 30 },
      { categoryId: 2, categoryName: '交通', amount: '1500.00', percentage: 28.19, count: 12 },
      { categoryId: 3, categoryName: '购物', amount: '1000.00', percentage: 18.79, count: 5 },
      { categoryId: 4, categoryName: '娱乐', amount: '821.00', percentage: 15.43, count: 3 }
    ],
    budget: { hasBudget: true, totalBudget: '6000.00', spent: '5321.00', remaining: '679.00', usedPercent: 88, status: 'WARN', forecast: null },
    largestExpense: { amount: '999.00', categoryName: '购物', date: '2024-03-18', note: '换季外套' },
    mostFrugalWeek: { startDate: '2024-03-08', endDate: '2024-03-14', expense: '210.00' }
  }
}

// 一份「被污染」的月报：混入账本外敏感字段（邮箱/令牌/其它账本数据），
// 用于验证海报绝不泄露这些字段（需求 8.3）。
function pollutedDigest() {
  const d = sampleDigest()
  d.userEmail = 'victim@example.com'
  d.token = 'eyJhbGciOiJIUzI1NiJ9.SECRET_TOKEN.sig'
  d.accessToken = 'Bearer super-secret-token'
  d.otherLedgerName = '前公司报销账本'
  d.otherLedgerId = 987654321
  d.categoryRanking[0].secretNote = 'other-ledger-leak'
  d.largestExpense.email = 'leak@example.com'
  return d
}

const SECRETS = [
  'victim@example.com',
  'eyJhbGciOiJIUzI1NiJ9.SECRET_TOKEN.sig',
  'Bearer super-secret-token',
  '前公司报销账本',
  '987654321',
  'other-ledger-leak',
  'leak@example.com'
]

describe('常量：月报请求超时 5000ms（需求 10.1）', () => {
  it('DIGEST_TIMEOUT_MS 恒为 5000', () => {
    expect(DIGEST_TIMEOUT_MS).toBe(5000)
  })
})

describe('shouldFetchDigest：已登录 ∧ 非聚合才请求（需求 1.9、10.3）', () => {
  it('已登录且具体账本 → 请求', () => {
    expect(shouldFetchDigest(true, false)).toBe(true)
  })
  it('未登录 → 不请求（无论是否聚合视图）', () => {
    expect(shouldFetchDigest(false, false)).toBe(false)
    expect(shouldFetchDigest(false, true)).toBe(false)
  })
  it('全部账本聚合视图 → 不请求（无单一账本上下文）', () => {
    expect(shouldFetchDigest(true, true)).toBe(false)
  })
  it('假值登录态一律不请求', () => {
    for (const v of [null, undefined, 0, '']) {
      expect(shouldFetchDigest(v, false)).toBe(false)
    }
  })
})

describe('resolveDigestState：未登录 / 聚合视图不请求（需求 10.3、1.9）', () => {
  it('未登录 → 不调用 fetchDigest，隐藏区块', async () => {
    const fetchDigest = vi.fn(() => Promise.resolve(sampleDigest()))
    const state = await resolveDigestState({ isLoggedIn: false, isAll: false, fetchDigest })
    expect(fetchDigest).not.toHaveBeenCalled()
    expect(state.requested).toBe(false)
    expect(state.digestVisible).toBe(false)
    expect(state.digest).toBe(null)
  })

  it('全部账本聚合视图 → 不调用 fetchDigest，隐藏区块', async () => {
    const fetchDigest = vi.fn(() => Promise.resolve(sampleDigest()))
    const state = await resolveDigestState({ isLoggedIn: true, isAll: true, fetchDigest })
    expect(fetchDigest).not.toHaveBeenCalled()
    expect(state.requested).toBe(false)
    expect(state.digestVisible).toBe(false)
    expect(state.digest).toBe(null)
  })
})

describe('resolveDigestState：成功展示，失败/超时静默隐藏（需求 10.1、10.4）', () => {
  it('成功 → digest=结果、digestVisible=true', async () => {
    const data = sampleDigest()
    const state = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => Promise.resolve(data)
    })
    expect(state.digestVisible).toBe(true)
    expect(state.digest).toBe(data)
  })

  it('请求失败（reject）→ 静默隐藏，不抛错', async () => {
    const state = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => Promise.reject({ code: 'HTTP_500', message: 'boom' })
    })
    expect(state.digestVisible).toBe(false)
    expect(state.digest).toBe(null)
  })

  it('超时（fetch 永不结算，5ms 内无响应）→ 静默隐藏', async () => {
    const state = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => new Promise(() => {}), // 永不结算
      timeoutMs: 5
    })
    expect(state.digestVisible).toBe(false)
    expect(state.digest).toBe(null)
  })

  it('超时边界：响应慢于超时 → 隐藏；快于超时 → 展示', async () => {
    const slow = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => new Promise((res) => setTimeout(() => res(sampleDigest()), 40)),
      timeoutMs: 10
    })
    expect(slow.digestVisible).toBe(false)

    const fast = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => new Promise((res) => setTimeout(() => res(sampleDigest()), 5)),
      timeoutMs: 50
    })
    expect(fast.digestVisible).toBe(true)
  })

  it('stale（请求期间切了账本/月份）→ 标记 stale，交调用方跳过应用', async () => {
    const state = await resolveDigestState({
      isLoggedIn: true,
      isAll: false,
      fetchDigest: () => Promise.resolve(sampleDigest()),
      isStale: () => true
    })
    expect(state.stale).toBe(true)
  })
})

describe('降级不影响其余报表：其它状态取值不变（需求 10.2、10.4）', () => {
  // 模拟报表页整体状态：月报状态与其它报表状态并存。
  // resolveDigestState 只产出月报字段，据此更新页面时其余报表字段应逐值不变。
  it('月报失败/超时后，rows/total/trend/members 保持不变', async () => {
    const other = {
      rows: [{ categoryId: 1, categoryName: '餐饮', amount: '2000.00', percentage: 50, count: 10 }],
      total: '4000.00',
      trend: [{ month: '2024-03', income: '8000.00', expense: '5321.00' }],
      members: [{ userId: 7, displayName: '小明', amount: '1200.00', percentage: 30, count: 4 }]
    }
    const snapshot = JSON.parse(JSON.stringify(other))

    for (const fetchDigest of [
      () => Promise.reject({ code: 'HTTP_500' }), // 失败
      () => new Promise(() => {}) // 超时
    ]) {
      const pageState = {
        ...other,
        digest: sampleDigest(), // 假设先前有旧月报
        digestVisible: true
      }
      const state = await resolveDigestState({
        isLoggedIn: true,
        isAll: false,
        fetchDigest,
        timeoutMs: 5
      })
      // 页面按 loadDigest 逻辑仅更新月报字段：
      if (!state.stale) {
        pageState.digest = state.digest
        pageState.digestVisible = state.digestVisible
      }
      // 月报被隐藏
      expect(pageState.digestVisible).toBe(false)
      expect(pageState.digest).toBe(null)
      // 其余报表逐值不变
      expect(pageState.rows).toEqual(snapshot.rows)
      expect(pageState.total).toEqual(snapshot.total)
      expect(pageState.trend).toEqual(snapshot.trend)
      expect(pageState.members).toEqual(snapshot.members)
    }
  })
})

// ── 海报字段隔离（需求 8.3）──────────────────────────────────

describe('selectPosterFields：白名单式抽取，绝不含账本外字段（需求 8.3）', () => {
  it('输出键恒为固定白名单，不含 email/token/其它账本字段', () => {
    const out = selectPosterFields(pollutedDigest())
    expect(Object.keys(out).sort()).toEqual(
      ['categoryRanking', 'expense', 'income', 'largestExpense', 'month', 'monthStatus', 'netBalance'].sort()
    )
    // 序列化整份输出，逐一断言不含任何敏感值
    const json = JSON.stringify(out)
    for (const secret of SECRETS) {
      expect(json).not.toContain(secret)
    }
  })

  it('至少携带目标月、收入、支出、结余（需求 8.2）', () => {
    const out = selectPosterFields(sampleDigest())
    expect(out.month).toBe('2024-03')
    expect(out.income).toBe('8000.00')
    expect(out.expense).toBe('5321.00')
    expect(out.netBalance).toBe('2679.00')
  })

  it('分类排行至多取 Top N，每项仅含名称与金额', () => {
    const out = selectPosterFields(sampleDigest())
    expect(out.categoryRanking.length).toBe(POSTER_TOP_N)
    for (const c of out.categoryRanking) {
      expect(Object.keys(c).sort()).toEqual(['amount', 'categoryName'])
    }
  })

  it('最大单笔仅含金额/名称/日期；缺失时为 null', () => {
    const out = selectPosterFields(sampleDigest())
    expect(Object.keys(out.largestExpense).sort()).toEqual(['amount', 'categoryName', 'date'])
    const noLg = selectPosterFields({ ...sampleDigest(), largestExpense: null })
    expect(noLg.largestExpense).toBe(null)
  })

  it('空/缺省 digest → 不抛错，返回零值白名单', () => {
    for (const bad of [null, undefined, {}]) {
      const out = selectPosterFields(bad)
      expect(out.categoryRanking).toEqual([])
      expect(out.largestExpense).toBe(null)
    }
  })
})

// mock canvas 上下文：记录所有绘制的文本，用于检查泄露。
function makeMockCtx() {
  const texts = []
  const noop = () => {}
  return {
    texts,
    setFillStyle: noop,
    fillRect: noop,
    setFontSize: noop,
    setTextAlign: noop,
    fillText: (t) => texts.push(String(t)),
    createLinearGradient: () => ({ addColorStop: noop })
  }
}

describe('drawDigestPoster：mock canvas 捕获绘制文本，仅当前账本字段（需求 8.3）', () => {
  it('绘制文本含目标月/收入/支出/结余，且绝不含邮箱/令牌/其它账本数据', () => {
    const ctx = makeMockCtx()
    drawDigestPoster(ctx, pollutedDigest())
    const drawn = ctx.texts.join('\n')

    // 含当前账本关键字段（需求 8.2）
    expect(drawn).toContain('2024-03')
    expect(drawn).toContain('¥8,000.00') // 收入
    expect(drawn).toContain('¥5,321.00') // 支出
    expect(drawn).toContain('¥2,679.00') // 结余
    expect(drawn).toContain('餐饮') // 分类排行

    // 绝不含任何敏感/账本外数据（需求 8.3）
    for (const secret of SECRETS) {
      expect(drawn).not.toContain(secret)
    }
    // 也不出现 token/email 关键词的实际值片段
    expect(drawn.toLowerCase()).not.toContain('token')
    expect(drawn.toLowerCase()).not.toContain('bearer')
    expect(drawn).not.toContain('@example.com')
  })

  it('空月报也能安全绘制（不抛错、不泄露）', () => {
    const ctx = makeMockCtx()
    const empty = {
      month: '2024-02',
      monthStatus: 'partial',
      income: '0.00',
      expense: '0.00',
      netBalance: '0.00',
      trend: [],
      categoryRanking: [],
      budget: { hasBudget: false },
      largestExpense: null,
      mostFrugalWeek: null,
      token: 'should-not-appear'
    }
    expect(() => drawDigestPoster(ctx, empty)).not.toThrow()
    const drawn = ctx.texts.join('\n')
    expect(drawn).toContain('2024-02')
    expect(drawn).not.toContain('should-not-appear')
  })
})

describe('digestStatusText / shortDate 辅助', () => {
  it('月状态文案：final → 已完结；其余 → 进行中', () => {
    expect(digestStatusText('final')).toBe('已完结')
    expect(digestStatusText('partial')).toBe('进行中')
    expect(digestStatusText(undefined)).toBe('进行中')
  })
  it('shortDate：YYYY-MM-DD → M/D；异常输入回退原值', () => {
    expect(shortDate('2024-03-08')).toBe('3/8')
    expect(shortDate('')).toBe('')
    expect(shortDate(null)).toBe('')
  })
})

// —— 属性测试：跨大量输入验证不变式 ——

describe('属性：shouldFetchDigest ⟺ 已登录 ∧ 非聚合（需求 10.3）', () => {
  it('仅当登录为真且非聚合视图时请求', () => {
    fc.assert(
      fc.property(fc.boolean(), fc.boolean(), (loggedIn, isAll) => {
        expect(shouldFetchDigest(loggedIn, isAll)).toBe(loggedIn && !isAll)
      }),
      { numRuns: 200 }
    )
  })
})

describe('属性：任意失败/超时恒静默隐藏（需求 10.1、10.4）', () => {
  it('已登录+具体账本下，reject 任意错误 → digestVisible=false 且 digest=null，不抛错', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.record({ code: fc.string(), message: fc.string() }),
        async (err) => {
          const state = await resolveDigestState({
            isLoggedIn: true,
            isAll: false,
            fetchDigest: () => Promise.reject(err),
            timeoutMs: 5
          })
          expect(state.digestVisible).toBe(false)
          expect(state.digest).toBe(null)
        }
      ),
      { numRuns: 100 }
    )
  })
})

describe('属性：海报绝不泄露注入的账本外字段（需求 8.3）', () => {
  it('无论 digest 混入何种额外敏感键，selectPosterFields 输出与绘制文本均不含其值', () => {
    fc.assert(
      fc.property(
        fc.dictionary(
          fc.string({ minLength: 1 }).filter(
            (k) =>
              ![
                'month',
                'monthStatus',
                'income',
                'expense',
                'netBalance',
                'categoryRanking',
                'largestExpense'
              ].includes(k)
          ),
          fc.string()
        ),
        (extra) => {
          // 用哨兵前缀标记注入值：该前缀绝不出现在海报固定文案/样例字段中，
          // 因此若海报泄露了任一账本外键的值，其哨兵值必会出现在绘制文本里。
          // （直接用任意字符串会与金额/标签片段偶然重合造成误报，故加哨兵。）
          const tagged = Object.fromEntries(
            Object.entries(extra).map(([k, v]) => [k, '\u0000LEAK\u0000' + v])
          )
          const digest = { ...sampleDigest(), ...tagged }
          const out = selectPosterFields(digest)
          // 输出键固定为白名单
          expect(Object.keys(out).sort()).toEqual(
            ['categoryRanking', 'expense', 'income', 'largestExpense', 'month', 'monthStatus', 'netBalance'].sort()
          )
          // 绘制文本不含任何注入的哨兵值
          const ctx = makeMockCtx()
          drawDigestPoster(ctx, digest)
          const drawn = ctx.texts.join('\n')
          expect(drawn).not.toContain('\u0000LEAK\u0000')
        }
      ),
      { numRuns: 200 }
    )
  })
})
