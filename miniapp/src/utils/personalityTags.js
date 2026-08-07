/**
 * 趣味人格标签（fun-personality-tags）前端纯逻辑。
 *
 * 镜像 utils/insights.js 的做法：把报表页趣味人格标签卡片区块的核心判定抽成纯函数，
 * 供 pages/report/report.vue 复用（单一事实源）。同一份逻辑既被页面使用、又被
 * 单测/属性测试覆盖：
 *
 *   1. 降级与不阻断（需求 12）：
 *      - 未登录（无有效令牌）/「全部账本」聚合视图 → 不请求、不展示（需求 1.9、11.9、12.4）。
 *      - 请求失败或 5000ms（含边界）超时 → 静默隐藏卡片区块，tagsVisible=false、
 *        tags=null；绝不触碰其它报表状态（需求 12.1、12.2、12.5）。
 *      - 成功但标签体为空（null/undefined）→ 静默隐藏（需求 12.6）。
 *      - 过期响应（请求期间切换账本/月份）→ 丢弃，不覆盖卡片（需求 12.7）。
 *   2. 展示映射与字段隔离（需求 13.3、13.4）：
 *      - tagToDisplay 白名单式抽取一枚标签的展示字段（标题、表情、维度名、关键数值、
 *        narrativeText）；优先展示 narrativeText，缺失时降级为「标题 + 关键数值」兜底串；
 *        绝不引用邮箱/令牌/其它账本数据。
 */

import { formatAmount } from './format'

/**
 * miniapp 侧趣味人格标签请求超时：5000ms（含边界）无响应即视为失败（需求 12.1）。
 * 与 pages/report/report.vue 共用同一常量，保证页面与测试口径一致。
 */
export const PERSONALITY_TAGS_TIMEOUT_MS = 5000

/**
 * 是否发起趣味人格标签聚合请求：已登录（有有效令牌）且非「全部账本」聚合视图才请求。
 * 未登录或聚合视图一律不请求、不展示（需求 1.9、11.9、12.4）。
 */
export function shouldFetchTags(isLoggedIn, isAll) {
  return !!isLoggedIn && !isAll
}

/**
 * Promise.race + 定时器超时包装：promise 与超时竞速，任一先结算即结算；
 * 结算后统一清理定时器，避免测试/运行时残留计时器与未处理拒绝（需求 12.1）。
 * @param {Promise} promise 实际请求
 * @param {number} timeoutMs 超时毫秒
 */
export function raceWithTimeout(promise, timeoutMs = PERSONALITY_TAGS_TIMEOUT_MS) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject({ code: 'PERSONALITY_TAGS_TIMEOUT' }), timeoutMs)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * 趣味人格标签加载与静默降级的纯决策核心。返回 { requested, stale, tags, tagsVisible }：
 *  - 未登录 / 聚合视图：requested=false，tags=null、tagsVisible=false（不请求、不展示）。
 *  - 请求成功且未过期：tags=结果、tagsVisible=true（结果为非空对象时可见）。
 *  - 请求失败或超时：tags=null、tagsVisible=false（静默隐藏，不抛错、不弹阻断弹窗）。
 *  - 成功但空体（null/undefined）：tags=null、tagsVisible=false（静默隐藏，需求 12.6）。
 *  - 过期（请求期间用户切了账本/月份）：stale=true，交由调用方跳过应用，避免过期数据覆盖新结果。
 *
 * 注：兜底响应（isFallback=true 且携带 fallbackText）仍是一个有效的可展示响应对象
 * （卡片展示那条鼓励文案），因此只要结果是非空对象即可见；只有真正的空体/失败/超时才隐藏。
 *
 * 该函数只产出趣味人格标签自身状态，从不返回或改动任何其它报表字段（分类占比/趋势/月报/
 * AI 趣味分析等），因此本请求的任何失败都不改变其余报表的展示内容与取值（需求 12.2、12.5）。
 *
 * 依赖注入以便测试：fetchTags 为发起请求的函数，isStale 判定是否已过期。
 * @param {object} args
 * @param {boolean} args.isLoggedIn 是否已登录（有有效令牌）
 * @param {boolean} args.isAll 是否「全部账本」聚合视图
 * @param {() => Promise<any>} args.fetchTags 发起趣味人格标签请求
 * @param {number} [args.timeoutMs] 超时毫秒，缺省 PERSONALITY_TAGS_TIMEOUT_MS
 * @param {() => boolean} [args.isStale] 是否已过期（请求期间切换了账本/月份）
 */
export async function resolveTagsState({
  isLoggedIn,
  isAll,
  fetchTags,
  timeoutMs = PERSONALITY_TAGS_TIMEOUT_MS,
  isStale = () => false
}) {
  const hidden = { tags: null, tagsVisible: false }
  if (!shouldFetchTags(isLoggedIn, isAll)) {
    return { requested: false, stale: false, ...hidden }
  }
  try {
    const res = await raceWithTimeout(fetchTags(), timeoutMs)
    if (isStale()) return { requested: true, stale: true, ...hidden }
    // 成功：非空对象时展示，缺失（null/undefined）则视为无可展示，静默隐藏。
    return { requested: true, stale: false, tags: res ?? null, tagsVisible: !!res }
  } catch (e) {
    // 失败或超时：静默降级隐藏卡片区块（需求 12.1、12.5）。
    if (isStale()) return { requested: true, stale: true, ...hidden }
    return { requested: true, stale: false, ...hidden }
  }
}

/** 金额（数值）→「1,234.56 元」；无定义（null/undefined）返回空串。 */
function amountText(value) {
  return value == null ? '' : `${formatAmount(value)} 元`
}

/** 占比/比率（数值）→「12.34%」；无定义（null/undefined）返回空串。 */
function pctText(value) {
  return value == null ? '' : `${formatAmount(value)}%`
}

/** 笔数（整数）→「8 笔」；无定义（null/undefined）返回空串。 */
function countText(value) {
  return value == null ? '' : `${value} 笔`
}

/**
 * narrativeText 缺失时的兜底串：仅由「标题 + 该标签类型的关键数值」拼装，
 * 绝不引用白名单外字段。各标签键按其在场的关键数值降级为一句可读中文（需求 8.6）。
 * @param {object} d 已抽取的白名单展示字段
 */
function buildFallbackText(d) {
  const title = d.title || ''
  switch (d.tagKey) {
    case 'SAVINGS_MASTER': {
      // 携带节省额 + 节省率（+ 目标月/上月总支出）。
      const parts = [amountText(d.savings) && `省下 ${amountText(d.savings)}`, pctText(d.saveRate) && `（${pctText(d.saveRate)}）`]
      return `${title} ${parts.filter(Boolean).join('')}`.trim()
    }
    case 'FINANCE_STAR': {
      // 携带总收入 + 结余率。
      const parts = [amountText(d.income) && `收入 ${amountText(d.income)}`, pctText(d.saveRate) && `，结余率 ${pctText(d.saveRate)}`]
      return `${title} ${parts.filter(Boolean).join('')}`.trim()
    }
    case 'BUDGET_MASTER': {
      // 携带本月预算 + 已用 + 使用率。
      const parts = [amountText(d.used) && `已用 ${amountText(d.used)}`, amountText(d.budget) && ` / 预算 ${amountText(d.budget)}`, pctText(d.usedRate) && `（${pctText(d.usedRate)}）`]
      return `${title} ${parts.filter(Boolean).join('')}`.trim()
    }
    case 'TAKEOUT_EXPLORER':
    case 'COFFEE_COLLECTOR':
    case 'TRAVEL_ENTHUSIAST':
    case 'SHOPPING_LIFER': {
      // 行为类：携带匹配笔数 + 金额 + 占比（+ 维度名）。
      const name = d.dimensionName ? `${d.dimensionName} ` : ''
      const parts = [
        countText(d.matchCount),
        amountText(d.matchAmount) && ` 共 ${amountText(d.matchAmount)}`,
        pctText(d.matchPercent) && `（占比 ${pctText(d.matchPercent)}）`
      ]
      return `${title} ${name}${parts.filter(Boolean).join('')}`.trim()
    }
    case 'LATE_NIGHT_KING': {
      // 携带夜宵时段 + 夜宵笔数。
      const win = d.lateNightWindow ? `${d.lateNightWindow} ` : ''
      return `${title} ${win}${countText(d.lateNightCount)}`.trim()
    }
    default:
      return title
  }
}

/**
 * 把一枚标签映射为展示项（需求 12、13.3、13.4）。白名单式只抽取展示所需字段：
 * 标签键、标题、表情、维度名、关键数值（金额/占比/笔数）、夜宵时段、阈值、narrativeText。
 * 绝不引用邮箱/令牌/其它账本数据等白名单外字段。
 *
 * 返回 { tagKey, title, emoji, dimensionName, text }：
 *  - text 优先取 narrativeText；缺失（null/空串）时降级为「标题 + 关键数值」兜底串。
 * @param {object} tag 一枚 PersonalityTag 机器字段 + 标签文案
 */
export function tagToDisplay(tag) {
  const t = tag || {}
  // 白名单抽取：只保留展示相关字段，绝不引用白名单外字段。
  const d = {
    tagKey: t.tagKey ?? '',
    title: t.title ?? '',
    emoji: t.emoji ?? '',
    dimensionName: t.dimensionName ?? '',
    currentValue: t.currentValue ?? null,
    previousValue: t.previousValue ?? null,
    income: t.income ?? null,
    savings: t.savings ?? null,
    saveRate: t.saveRate ?? null,
    budget: t.budget ?? null,
    used: t.used ?? null,
    usedRate: t.usedRate ?? null,
    matchCount: t.matchCount ?? null,
    matchAmount: t.matchAmount ?? null,
    matchPercent: t.matchPercent ?? null,
    lateNightCount: t.lateNightCount ?? null,
    lateNightWindow: t.lateNightWindow ?? null,
    threshold: t.threshold ?? null,
    narrativeText: t.narrativeText ?? null
  }
  const text = d.narrativeText != null && d.narrativeText !== '' ? d.narrativeText : buildFallbackText(d)
  return {
    tagKey: d.tagKey,
    title: d.title,
    emoji: d.emoji,
    dimensionName: d.dimensionName,
    text
  }
}
