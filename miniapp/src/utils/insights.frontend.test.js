/**
 * Feature: ai-fun-analysis, Property 16: 前端静默降级（`utils/insights.js` 纯逻辑）。
 *
 * 按 vitest.config.js 约定（node 环境、只跑 src/utils 下不依赖 uni API 的纯逻辑），
 * 本文件覆盖报表页 AI 趣味分析卡片区块从 pages/report/report.vue 抽出的纯逻辑
 * （src/utils/insights.js），对应任务 14.2 / 设计 Property 16：
 *   1. 不请求不展示（需求 1.9、11.4）：未登录（无有效令牌）或「全部账本」聚合视图 →
 *      shouldFetchInsights=false；resolveInsightsState 不调用 fetchInsights、
 *      requested=false、insightsVisible=false、insights=null。
 *   2. 失败/超时静默隐藏（需求 11.1、11.5）：请求失败（reject）或达到 5000ms（含边界）
 *      超时 → insightsVisible=false、insights=null（不抛错、不弹阻断弹窗）。
 *   3. 只产出 AI 自身状态（需求 11.2、11.5）：决策仅返回白名单键
 *      { requested, stale, insights, insightsVisible }，从不返回或改动任何其它报表字段。
 *
 * 真实 uni.* 平台交互与页面渲染按约定归手工验收清单，不在此自动化。
 *
 * Validates: Requirements 11.1
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import fc from 'fast-check'
import {
  AI_INSIGHTS_TIMEOUT_MS,
  shouldFetchInsights,
  resolveInsightsState,
  insightToDisplay
} from './insights'

// resolveInsightsState 只应产出的键集合（AI 自身状态白名单）。
const AI_STATE_KEYS = ['insights', 'insightsVisible', 'requested', 'stale']

// 一份典型的 AI 趣味分析响应包。
function sampleInsights() {
  return {
    month: '2024-03',
    monthStatus: 'final',
    isFallback: false,
    fallbackText: null,
    insights: [
      { type: 'CATEGORY_DELTA', dimension: 'CATEGORY', dimensionId: 1, dimensionName: '餐饮', deltaAmount: -180.0, changeRate: -18.0, direction: 'DOWN', narrativeText: '你的餐饮少花了 180.00 元，降了 18.00%，省钱有一手～' }
    ]
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('常量：AI 趣味分析请求超时 5000ms（需求 11.1）', () => {
  it('AI_INSIGHTS_TIMEOUT_MS 恒为 5000', () => {
    expect(AI_INSIGHTS_TIMEOUT_MS).toBe(5000)
  })
})

describe('shouldFetchInsights：已登录 ∧ 非聚合才请求（需求 1.9、11.4）', () => {
  it('已登录且具体账本 → 请求', () => {
    expect(shouldFetchInsights(true, false)).toBe(true)
  })
  it('未登录 → 不请求（无论是否聚合视图）', () => {
    expect(shouldFetchInsights(false, false)).toBe(false)
    expect(shouldFetchInsights(false, true)).toBe(false)
  })
  it('全部账本聚合视图 → 不请求（无单一账本上下文）', () => {
    expect(shouldFetchInsights(true, true)).toBe(false)
  })
  it('假值登录态一律不请求', () => {
    for (const v of [null, undefined, 0, '']) {
      expect(shouldFetchInsights(v, false)).toBe(false)
    }
  })
})

describe('resolveInsightsState：未登录 / 聚合视图不请求（需求 1.9、11.4）', () => {
  it('未登录 → 不调用 fetchInsights，隐藏区块', async () => {
    const fetchInsights = vi.fn(() => Promise.resolve(sampleInsights()))
    const state = await resolveInsightsState({ isLoggedIn: false, isAll: false, fetchInsights })
    expect(fetchInsights).not.toHaveBeenCalled()
    expect(state.requested).toBe(false)
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })

  it('全部账本聚合视图 → 不调用 fetchInsights，隐藏区块', async () => {
    const fetchInsights = vi.fn(() => Promise.resolve(sampleInsights()))
    const state = await resolveInsightsState({ isLoggedIn: true, isAll: true, fetchInsights })
    expect(fetchInsights).not.toHaveBeenCalled()
    expect(state.requested).toBe(false)
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })
})

describe('resolveInsightsState：成功展示，失败/超时静默隐藏（需求 11.1、11.5）', () => {
  it('成功 → insights=结果、insightsVisible=true', async () => {
    const data = sampleInsights()
    const state = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => Promise.resolve(data)
    })
    expect(state.insightsVisible).toBe(true)
    expect(state.insights).toBe(data)
  })

  it('请求失败（reject）→ 静默隐藏，不抛错', async () => {
    const state = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => Promise.reject({ code: 'HTTP_500', message: 'boom' })
    })
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })

  it('超时（fetch 永不结算，小超时内无响应）→ 静默隐藏', async () => {
    const state = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => new Promise(() => {}), // 永不结算
      timeoutMs: 5
    })
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })

  it('5000ms 超时边界（fake timers）：fetch 永不结算，推进至 AI_INSIGHTS_TIMEOUT_MS → 静默隐藏', async () => {
    vi.useFakeTimers()
    const pending = resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => new Promise(() => {}) // 永不结算，默认超时 = 5000ms
    })
    // 推进到恰好 5000ms（含边界），触发 raceWithTimeout 的超时拒绝。
    await vi.advanceTimersByTimeAsync(AI_INSIGHTS_TIMEOUT_MS)
    const state = await pending
    expect(state.requested).toBe(true)
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })

  it('超时边界（real timers）：响应慢于超时 → 隐藏；快于超时 → 展示', async () => {
    const slow = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => new Promise((res) => setTimeout(() => res(sampleInsights()), 40)),
      timeoutMs: 10
    })
    expect(slow.insightsVisible).toBe(false)

    const fast = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => new Promise((res) => setTimeout(() => res(sampleInsights()), 5)),
      timeoutMs: 50
    })
    expect(fast.insightsVisible).toBe(true)
  })

  it('stale（请求期间切了账本/月份）→ 标记 stale，交调用方跳过应用', async () => {
    const state = await resolveInsightsState({
      isLoggedIn: true,
      isAll: false,
      fetchInsights: () => Promise.resolve(sampleInsights()),
      isStale: () => true
    })
    expect(state.stale).toBe(true)
    expect(state.insightsVisible).toBe(false)
    expect(state.insights).toBe(null)
  })
})

describe('决策只产出 AI 自身状态：从不返回/改动其它报表字段（需求 11.2、11.5）', () => {
  it('返回对象键恒为 AI 状态白名单，不含任何其它报表字段', async () => {
    const cases = [
      { isLoggedIn: false, isAll: false, fetchInsights: () => Promise.resolve(sampleInsights()) },
      { isLoggedIn: true, isAll: true, fetchInsights: () => Promise.resolve(sampleInsights()) },
      { isLoggedIn: true, isAll: false, fetchInsights: () => Promise.resolve(sampleInsights()) },
      { isLoggedIn: true, isAll: false, fetchInsights: () => Promise.reject({ code: 'HTTP_500' }) },
      { isLoggedIn: true, isAll: false, fetchInsights: () => new Promise(() => {}), timeoutMs: 5 }
    ]
    for (const args of cases) {
      const state = await resolveInsightsState(args)
      expect(Object.keys(state).sort()).toEqual([...AI_STATE_KEYS])
    }
  })

  it('AI 请求失败/超时后，其余报表状态（rows/total/trend/members）逐值不变', async () => {
    const other = {
      rows: [{ categoryId: 1, categoryName: '餐饮', amount: '2000.00', percentage: 50, count: 10 }],
      total: '4000.00',
      trend: [{ month: '2024-03', income: '8000.00', expense: '5321.00' }],
      members: [{ userId: 7, displayName: '小明', amount: '1200.00', percentage: 30, count: 4 }]
    }
    const snapshot = JSON.parse(JSON.stringify(other))

    for (const fetchInsights of [
      () => Promise.reject({ code: 'HTTP_500' }), // 失败
      () => new Promise(() => {}) // 超时
    ]) {
      const pageState = {
        ...other,
        insights: sampleInsights(), // 假设先前有旧洞察
        insightsVisible: true
      }
      const state = await resolveInsightsState({
        isLoggedIn: true,
        isAll: false,
        fetchInsights,
        timeoutMs: 5
      })
      // 页面按 loadInsights 逻辑仅更新 AI 字段：
      if (!state.stale) {
        pageState.insights = state.insights
        pageState.insightsVisible = state.insightsVisible
      }
      // AI 卡片被隐藏
      expect(pageState.insightsVisible).toBe(false)
      expect(pageState.insights).toBe(null)
      // 其余报表逐值不变
      expect(pageState.rows).toEqual(snapshot.rows)
      expect(pageState.total).toEqual(snapshot.total)
      expect(pageState.trend).toEqual(snapshot.trend)
      expect(pageState.members).toEqual(snapshot.members)
    }
  })
})

// —— 属性测试：跨大量输入验证不变式（Property 16）——

describe('属性：shouldFetchInsights ⟺ 已登录 ∧ 非聚合（需求 1.9、11.4）', () => {
  it('仅当登录为真且非聚合视图时请求', () => {
    fc.assert(
      fc.property(fc.boolean(), fc.boolean(), (loggedIn, isAll) => {
        expect(shouldFetchInsights(loggedIn, isAll)).toBe(loggedIn && !isAll)
      }),
      { numRuns: 100 }
    )
  })
})

describe('属性：未登录或聚合视图恒不请求不展示（需求 1.9、11.4）', () => {
  it('!isLoggedIn ∨ isAll ⟹ 不调用 fetchInsights 且 requested/insightsVisible=false、insights=null', async () => {
    await fc.assert(
      fc.asyncProperty(fc.boolean(), fc.boolean(), async (loggedIn, isAll) => {
        fc.pre(!loggedIn || isAll)
        const fetchInsights = vi.fn(() => Promise.resolve(sampleInsights()))
        const state = await resolveInsightsState({ isLoggedIn: loggedIn, isAll, fetchInsights })
        expect(fetchInsights).not.toHaveBeenCalled()
        expect(state.requested).toBe(false)
        expect(state.insightsVisible).toBe(false)
        expect(state.insights).toBe(null)
        expect(Object.keys(state).sort()).toEqual([...AI_STATE_KEYS])
      }),
      { numRuns: 50 }
    )
  })
})

describe('属性：任意失败恒静默隐藏（需求 11.1、11.5）', () => {
  it('已登录+具体账本下，reject 任意错误 → insightsVisible=false 且 insights=null，不抛错', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.record({ code: fc.string(), message: fc.string() }),
        async (err) => {
          const state = await resolveInsightsState({
            isLoggedIn: true,
            isAll: false,
            fetchInsights: () => Promise.reject(err),
            timeoutMs: 5
          })
          expect(state.insightsVisible).toBe(false)
          expect(state.insights).toBe(null)
          expect(Object.keys(state).sort()).toEqual([...AI_STATE_KEYS])
        }
      ),
      { numRuns: 50 }
    )
  })
})

// ============================================================================
// Feature: ai-fun-analysis, insightToDisplay 字段隔离
//
// 覆盖任务 14.3 / 设计 Property 14（隐私净化，前端侧）：insightToDisplay 白名单式
// 只抽取展示字段（类型、维度、维度名、关键数值、方向/角色、narrativeText），按方向/
// 角色定图标与色调，优先展示 narrativeText，缺失时降级为「维度名 + 关键数值」兜底串；
// 返回对象的键集合与其中出现的值均绝不包含邮箱/令牌/刷新令牌/其它账本数据/external_id/
// 原始备注/商户原始标识等被禁字段（需求 12.3、12.4）。
//
// Validates: Requirements 12.3, 12.4
// ============================================================================

// insightToDisplay 唯一允许产出的键集合（展示白名单）。
const DISPLAY_KEYS = ['dimension', 'dimensionName', 'icon', 'text', 'tone', 'type']

describe('insightToDisplay：仅产出白名单展示字段（需求 12.3、12.4）', () => {
  it('返回对象的键恒为 { type, dimension, dimensionName, tone, icon, text }', () => {
    const insight = {
      type: 'CATEGORY_DELTA',
      dimension: 'CATEGORY',
      dimensionName: '餐饮',
      deltaAmount: -180.0,
      changeRate: -18.0,
      direction: 'DOWN',
      narrativeText: '你的餐饮少花了 180.00 元'
    }
    const display = insightToDisplay(insight)
    expect(Object.keys(display).sort()).toEqual([...DISPLAY_KEYS])
  })

  it('对空/缺省输入也只产出白名单键（不抛错）', () => {
    for (const bad of [undefined, null, {}]) {
      const display = insightToDisplay(bad)
      expect(Object.keys(display).sort()).toEqual([...DISPLAY_KEYS])
    }
  })
})

describe('insightToDisplay：text 优先 narrativeText，缺失时降级为兜底串（需求 12.3）', () => {
  it('narrativeText 存在 → text === narrativeText（逐字采用叙事文案）', () => {
    const narrativeText = '你的餐饮少花了 180.00 元，降了 18.00%，省钱有一手～'
    const display = insightToDisplay({
      type: 'CATEGORY_DELTA',
      dimensionName: '餐饮',
      deltaAmount: -180.0,
      changeRate: -18.0,
      direction: 'DOWN',
      narrativeText
    })
    expect(display.text).toBe(narrativeText)
  })

  it('narrativeText 缺失（null）→ text 为「维度名 + 关键数值」兜底串（含维度名）', () => {
    const display = insightToDisplay({
      type: 'CATEGORY_DELTA',
      dimensionName: '餐饮',
      deltaAmount: -180.0,
      changeRate: -18.0,
      direction: 'DOWN',
      narrativeText: null
    })
    expect(typeof display.text).toBe('string')
    expect(display.text.length).toBeGreaterThan(0)
    expect(display.text).toContain('餐饮')
    // 兜底串由关键数值拼装，反映方向与金额。
    expect(display.text).toContain('下降')
    expect(display.text).toContain('180.00')
  })

  it('narrativeText 为空串 → 同样降级为兜底串', () => {
    const display = insightToDisplay({
      type: 'FREQUENCY_DELTA',
      dimensionName: '咖啡',
      deltaCount: 3,
      direction: 'UP',
      narrativeText: ''
    })
    expect(display.text).toContain('咖啡')
    expect(display.text).toContain('增加')
  })
})

describe('insightToDisplay：tone/icon 按方向/角色（需求 12.4）', () => {
  it('direction=DOWN 或 role=IMPROVE → tone=calm、icon=📉', () => {
    expect(insightToDisplay({ direction: 'DOWN' }).tone).toBe('calm')
    expect(insightToDisplay({ direction: 'DOWN' }).icon).toBe('📉')
    expect(insightToDisplay({ role: 'IMPROVE' }).tone).toBe('calm')
    expect(insightToDisplay({ role: 'IMPROVE' }).icon).toBe('📉')
  })

  it('direction=UP 或 role=OVERSPEND → tone=reminder、icon=📈', () => {
    expect(insightToDisplay({ direction: 'UP' }).tone).toBe('reminder')
    expect(insightToDisplay({ direction: 'UP' }).icon).toBe('📈')
    expect(insightToDisplay({ role: 'OVERSPEND' }).tone).toBe('reminder')
    expect(insightToDisplay({ role: 'OVERSPEND' }).icon).toBe('📈')
  })
})

describe('insightToDisplay：字段隔离——绝不泄露被禁字段/其值（需求 12.3、12.4）', () => {
  // 被禁字段名 + 哨兵值：邮箱、访问/刷新令牌、其它账本数据、external_id、原始备注、商户原始标识。
  const FORBIDDEN = {
    email: 'victim@example.com',
    token: 'ACCESS_TOKEN_SENTINEL_abc123',
    refreshToken: 'REFRESH_TOKEN_SENTINEL_xyz789',
    externalId: 'EXTERNAL_ID_SENTINEL_0001',
    note: 'ORIGINAL_NOTE_SENTINEL_私密备注',
    otherLedgerId: 'OTHER_LEDGER_SENTINEL_999',
    rawMerchantId: 'RAW_MERCHANT_SENTINEL_M42'
  }

  it('输入即便夹带被禁字段，返回对象既不含其键、也不含其值', () => {
    const insight = {
      type: 'TOP_MOVER',
      dimension: 'CATEGORY',
      dimensionName: '购物',
      deltaAmount: 320.0,
      changeRate: 25.0,
      role: 'OVERSPEND',
      direction: 'UP',
      narrativeText: '购物超得最多，多花了 320.00 元',
      ...FORBIDDEN
    }
    const display = insightToDisplay(insight)

    // 键：仅白名单，不含任何被禁字段名。
    expect(Object.keys(display).sort()).toEqual([...DISPLAY_KEYS])
    for (const key of Object.keys(FORBIDDEN)) {
      expect(display).not.toHaveProperty(key)
    }

    // 值：序列化整个返回对象后，任一哨兵值都不得出现在任何位置（含 text 内）。
    const serialized = JSON.stringify(display)
    for (const sentinel of Object.values(FORBIDDEN)) {
      expect(serialized).not.toContain(sentinel)
    }
  })

  it('narrativeText 缺失走兜底串时，被禁字段/其值同样不泄露', () => {
    const insight = {
      type: 'CATEGORY_DELTA',
      dimension: 'CATEGORY',
      dimensionName: '交通',
      deltaAmount: -60.0,
      changeRate: -12.0,
      direction: 'DOWN',
      narrativeText: null,
      ...FORBIDDEN
    }
    const display = insightToDisplay(insight)
    expect(Object.keys(display).sort()).toEqual([...DISPLAY_KEYS])
    const serialized = JSON.stringify(display)
    for (const sentinel of Object.values(FORBIDDEN)) {
      expect(serialized).not.toContain(sentinel)
    }
  })

  // 属性测试：任意被禁字段值组合下，返回对象恒为白名单键且不含任一哨兵值。
  it('属性：任意被禁字段值 ⟹ 返回对象只含白名单键且不泄露任一值', () => {
    // 用唯一前缀标记注入值，保证其内容不会与展示固定文案（维度名/叙事串/键名）偶然重合，
    // 从而任何出现即为真实泄露。校验展示映射对白名单外字段一律不读取、不透传。
    const inject = () => fc.string().map((s) => `__FORBIDDEN_SENTINEL__${s}`)
    fc.assert(
      fc.property(
        inject(), inject(), inject(), inject(), inject(),
        (email, token, refreshToken, externalId, note) => {
          const display = insightToDisplay({
            type: 'SAVINGS_TOTAL',
            dimension: 'TOTAL',
            dimensionName: '本月结余',
            deltaAmount: 500,
            changeRate: 10,
            role: 'IMPROVE',
            direction: 'DOWN',
            narrativeText: '比上月省下 500.00 元',
            email,
            token,
            refreshToken,
            externalId,
            note
          })
          expect(Object.keys(display).sort()).toEqual([...DISPLAY_KEYS])
          const serialized = JSON.stringify(display)
          // 唯一前缀本身即不得出现，任一注入值也不得出现在返回对象任何位置。
          expect(serialized).not.toContain('__FORBIDDEN_SENTINEL__')
          for (const sentinel of [email, token, refreshToken, externalId, note]) {
            expect(serialized).not.toContain(sentinel)
          }
        }
      ),
      { numRuns: 100 }
    )
  })
})
