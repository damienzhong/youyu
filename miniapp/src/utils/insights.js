/**
 * AI 趣味分析（ai-fun-analysis）前端纯逻辑。
 *
 * 镜像 utils/digest.js 的做法：把报表页 AI 趣味分析卡片区块的核心判定抽成纯函数，
 * 供 pages/report/report.vue 复用（单一事实源）。同一份逻辑既被页面使用、又被
 * 单测/属性测试覆盖：
 *
 *   1. 降级与不阻断（需求 11）：
 *      - 未登录（无有效令牌）/「全部账本」聚合视图 → 不请求、不展示（需求 1.9、11.4）。
 *      - 请求失败或 5000ms（含边界）超时 → 静默隐藏卡片区块，insightsVisible=false、
 *        insights=null；绝不触碰其它报表状态（需求 11.1、11.2、11.5）。
 *   2. 展示映射与字段隔离（需求 12.3、12.4）：
 *      - insightToDisplay 白名单式抽取一条洞察的展示字段（类型、维度名、关键数值、
 *        方向/角色、narrativeText），按方向/角色定图标与色调；优先展示 narrativeText，
 *        缺失时降级为「维度名 + 关键数值」兜底串；绝不引用邮箱/令牌/其它账本数据。
 */

import { formatAmount } from './format'

/**
 * miniapp 侧 AI 趣味分析请求超时：5000ms（含边界）无响应即视为失败（需求 11.1）。
 * 与 pages/report/report.vue 共用同一常量，保证页面与测试口径一致。
 */
export const AI_INSIGHTS_TIMEOUT_MS = 5000

/**
 * 是否发起 AI 趣味分析聚合请求：已登录（有有效令牌）且非「全部账本」聚合视图才请求。
 * 未登录或聚合视图一律不请求、不展示（需求 1.9、11.4）。
 */
export function shouldFetchInsights(isLoggedIn, isAll) {
  return !!isLoggedIn && !isAll
}

/**
 * Promise.race + 定时器超时包装：promise 与超时竞速，任一先结算即结算；
 * 结算后统一清理定时器，避免测试/运行时残留计时器与未处理拒绝（需求 11.1）。
 * @param {Promise} promise 实际请求
 * @param {number} timeoutMs 超时毫秒
 */
export function raceWithTimeout(promise, timeoutMs = AI_INSIGHTS_TIMEOUT_MS) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject({ code: 'AI_INSIGHTS_TIMEOUT' }), timeoutMs)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * AI 趣味分析加载与静默降级的纯决策核心。返回 { requested, stale, insights, insightsVisible }：
 *  - 未登录 / 聚合视图：requested=false，insights=null、insightsVisible=false（不请求、不展示）。
 *  - 请求成功且未过期：insights=结果、insightsVisible=true（结果非空时可见）。
 *  - 请求失败或超时：insights=null、insightsVisible=false（静默隐藏，不抛错、不弹阻断弹窗）。
 *  - 过期（请求期间用户切了账本/月份）：stale=true，交由调用方跳过应用，避免过期数据覆盖新结果。
 *
 * 该函数只产出 AI 趣味分析自身状态，从不返回或改动任何其它报表字段（分类占比/趋势/月报等），
 * 因此本请求的任何失败都不改变其余报表的展示内容与取值（需求 11.2、11.5）。
 *
 * 依赖注入以便测试：fetchInsights 为发起请求的函数，isStale 判定是否已过期。
 * @param {object} args
 * @param {boolean} args.isLoggedIn 是否已登录（有有效令牌）
 * @param {boolean} args.isAll 是否「全部账本」聚合视图
 * @param {() => Promise<any>} args.fetchInsights 发起 AI 趣味分析请求
 * @param {number} [args.timeoutMs] 超时毫秒，缺省 AI_INSIGHTS_TIMEOUT_MS
 * @param {() => boolean} [args.isStale] 是否已过期（请求期间切换了账本/月份）
 */
export async function resolveInsightsState({
  isLoggedIn,
  isAll,
  fetchInsights,
  timeoutMs = AI_INSIGHTS_TIMEOUT_MS,
  isStale = () => false
}) {
  const hidden = { insights: null, insightsVisible: false }
  if (!shouldFetchInsights(isLoggedIn, isAll)) {
    return { requested: false, stale: false, ...hidden }
  }
  try {
    const res = await raceWithTimeout(fetchInsights(), timeoutMs)
    if (isStale()) return { requested: true, stale: true, ...hidden }
    // 成功：结果存在时展示，缺失（null/undefined）则视为无可展示，静默隐藏。
    return { requested: true, stale: false, insights: res ?? null, insightsVisible: !!res }
  } catch (e) {
    // 失败或超时：静默降级隐藏卡片区块（需求 11.1、11.5）。
    if (isStale()) return { requested: true, stale: true, ...hidden }
    return { requested: true, stale: false, ...hidden }
  }
}

/** 方向/角色 → 色调：下降/改善为暖绿中性（calm），上升/超支为提醒橙（reminder）。 */
function toneOf(direction, role) {
  if (role === 'IMPROVE' || direction === 'DOWN') return 'calm'
  if (role === 'OVERSPEND' || direction === 'UP') return 'reminder'
  return 'calm'
}

/** 色调 → 图标：calm（下降/改善）→ 📉；reminder（上升/超支）→ 📈。 */
function iconOf(tone) {
  return tone === 'reminder' ? '📈' : '📉'
}

/** 变化率 → 「12.34%」；无定义（null/undefined）返回空串。 */
function ratePct(rate) {
  return rate == null ? '' : `${formatAmount(rate)}%`
}

/**
 * narrativeText 缺失时的兜底串：仅由「维度名 + 关键数值」拼装，绝不引用白名单外字段。
 * 各类洞察按其在场的关键数值降级为一句可读中文。
 * @param {object} d 已抽取的白名单展示字段
 */
function buildFallbackText(d) {
  const name = d.dimensionName || ''
  const rate = ratePct(d.changeRate)
  switch (d.type) {
    case 'CATEGORY_DELTA': {
      const verb = d.direction === 'UP' ? '上升' : '下降'
      const amt = d.deltaAmount == null ? '' : ` ${formatAmount(Math.abs(d.deltaAmount))} 元`
      return `${name} ${verb}${amt}${rate ? `（${rate}）` : ''}`.trim()
    }
    case 'SAVINGS_TOTAL': {
      const verb = d.role === 'OVERSPEND' ? '多花' : '省下'
      const amt = d.deltaAmount == null ? '' : ` ${formatAmount(Math.abs(d.deltaAmount))} 元`
      return `比上月${verb}${amt}${rate ? `（${rate}）` : ''}`.trim()
    }
    case 'FREQUENCY_DELTA': {
      const verb = d.direction === 'UP' ? '增加' : '减少'
      const cnt = d.deltaCount == null ? '' : ` ${Math.abs(d.deltaCount)} 次`
      return `${name} 次数${verb}${cnt}${rate ? `（${rate}）` : ''}`.trim()
    }
    case 'TREND_STREAK': {
      const verb = d.direction === 'UP' ? '上升' : '下降'
      const months = d.streakMonths == null ? '' : ` ${d.streakMonths} 个月`
      return `${name} 连续${months}${verb}`.trim()
    }
    case 'TOP_MOVER': {
      const verb = d.role === 'OVERSPEND' ? '多花' : '少花'
      const amt = d.deltaAmount == null ? '' : ` ${formatAmount(Math.abs(d.deltaAmount))} 元`
      const lead = d.role === 'OVERSPEND' ? '超得最多' : '省得最多'
      return `${name} ${lead}，${verb}${amt}${rate ? `（${rate}）` : ''}`.trim()
    }
    default:
      return name
  }
}

/**
 * 把一条洞察映射为展示项（需求 12.3、12.4）。白名单式只抽取展示所需字段：
 * 类型、维度、维度名、关键数值（金额/笔数/变化率/连续月数）、方向/角色、narrativeText。
 * 绝不引用邮箱/令牌/其它账本数据等白名单外字段。
 *
 * 返回 { type, dimension, dimensionName, tone, icon, text }：
 *  - tone/icon 按方向（DOWN/UP）或角色（IMPROVE/OVERSPEND）确定。
 *  - text 优先取 narrativeText；缺失（null）时降级为「维度名 + 关键数值」兜底串。
 * @param {object} insight 一条 AiInsight 机器字段 + 叙事文案
 */
export function insightToDisplay(insight) {
  const i = insight || {}
  // 白名单抽取：只保留展示相关字段。
  const d = {
    type: i.type ?? '',
    dimension: i.dimension ?? null,
    dimensionName: i.dimensionName ?? '',
    currentValue: i.currentValue ?? null,
    previousValue: i.previousValue ?? null,
    currentCount: i.currentCount ?? null,
    previousCount: i.previousCount ?? null,
    deltaAmount: i.deltaAmount ?? null,
    deltaCount: i.deltaCount ?? null,
    changeRate: i.changeRate ?? null,
    streakMonths: i.streakMonths ?? null,
    direction: i.direction ?? null,
    role: i.role ?? null,
    narrativeText: i.narrativeText ?? null
  }
  const tone = toneOf(d.direction, d.role)
  const text = d.narrativeText != null && d.narrativeText !== '' ? d.narrativeText : buildFallbackText(d)
  return {
    type: d.type,
    dimension: d.dimension,
    dimensionName: d.dimensionName,
    tone,
    icon: iconOf(tone),
    text
  }
}
