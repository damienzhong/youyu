/**
 * 智能月报（smart-monthly-report）前端纯逻辑。
 *
 * 按 vitest.config.js 约定，只有不依赖 uni API 的纯逻辑可自动化测试；页面渲染与
 * uni.* 交互（canvas 出图、保存相册、分享）归手工验收清单。本模块把报表页月报区块的
 * 两类核心判定抽成纯函数，供 pages/report/report.vue 复用（单一事实源），同一份逻辑
 * 既被页面使用、又被单测/属性测试覆盖：
 *
 *   1. 降级与不阻断（需求 10）：
 *      - 未登录（无有效令牌）/「全部账本」聚合视图 → 不请求、不展示（需求 1.9、10.3）。
 *      - 月报请求失败或 5000ms（含边界）超时 → 静默隐藏月报区块，digestVisible=false，
 *        digest=null；绝不触碰其它报表状态（需求 10.1、10.2、10.4）。
 *   2. 海报字段隔离（需求 8.3）：
 *      - selectPosterFields 白名单式抽取当前账本月报字段（目标月、收入、支出、结余、
 *        分类排行 Top、最大单笔），绝不复制邮箱/令牌/其它账本数据。
 *      - drawDigestPoster 只从该白名单绘制，从源头保证海报不泄露账本外数据。
 */

import { formatAmount } from './format'

/**
 * miniapp 侧月报请求超时：5000ms（含边界）无响应即视为失败（需求 10.1）。
 * 与 pages/report/report.vue 共用同一常量，保证页面与测试口径一致。
 */
export const DIGEST_TIMEOUT_MS = 5000

/**
 * 是否发起月报聚合请求：已登录（有有效令牌）且非「全部账本」聚合视图才请求。
 * 未登录或聚合视图一律不请求、不展示（需求 1.9、10.3）。
 */
export function shouldFetchDigest(isLoggedIn, isAll) {
  return !!isLoggedIn && !isAll
}

/** 月状态徽标文案：final → 已完结；其余（partial）→ 进行中（需求 2.5）。 */
export function digestStatusText(s) {
  return s === 'final' ? '已完结' : '进行中'
}

/** 日期 YYYY-MM-DD → M/D 紧凑展示（用于最大单笔 / 最省钱周 / 海报）。 */
export function shortDate(d) {
  const parts = String(d || '').split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : String(d || '')
}

/**
 * Promise.race + 定时器超时包装：promise 与超时竞速，任一先结算即结算；
 * 结算后统一清理定时器，避免测试/运行时残留计时器与未处理拒绝（需求 10.1）。
 * @param {Promise} promise 实际请求
 * @param {number} timeoutMs 超时毫秒
 */
export function raceWithTimeout(promise, timeoutMs = DIGEST_TIMEOUT_MS) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject({ code: 'DIGEST_TIMEOUT' }), timeoutMs)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * 月报加载与静默降级的纯决策核心。返回 { requested, stale, digest, digestVisible }：
 *  - 未登录 / 聚合视图：requested=false，digest=null、digestVisible=false（不请求、不展示）。
 *  - 请求成功且未过期：digest=结果、digestVisible=true。
 *  - 请求失败或超时：digest=null、digestVisible=false（静默隐藏，不抛错、不弹阻断弹窗）。
 *  - 过期（请求期间用户切了账本/月份）：stale=true，交由调用方跳过应用，避免过期数据覆盖新结果。
 *
 * 该函数只产出月报自身状态，从不返回或改动任何其它报表字段（分类占比/趋势/成员等），
 * 因此月报的任何失败都不改变其余报表的展示内容与取值（需求 10.2、10.4）。
 *
 * 依赖注入以便测试：fetchDigest 为发起请求的函数，isStale 判定是否已过期。
 * @param {object} args
 * @param {boolean} args.isLoggedIn 是否已登录（有有效令牌）
 * @param {boolean} args.isAll 是否「全部账本」聚合视图
 * @param {() => Promise<any>} args.fetchDigest 发起月报请求
 * @param {number} [args.timeoutMs] 超时毫秒，缺省 DIGEST_TIMEOUT_MS
 * @param {() => boolean} [args.isStale] 是否已过期（请求期间切换了账本/月份）
 */
export async function resolveDigestState({
  isLoggedIn,
  isAll,
  fetchDigest,
  timeoutMs = DIGEST_TIMEOUT_MS,
  isStale = () => false
}) {
  const hidden = { digest: null, digestVisible: false }
  if (!shouldFetchDigest(isLoggedIn, isAll)) {
    return { requested: false, stale: false, ...hidden }
  }
  try {
    const res = await raceWithTimeout(fetchDigest(), timeoutMs)
    if (isStale()) return { requested: true, stale: true, ...hidden }
    return { requested: true, stale: false, digest: res, digestVisible: true }
  } catch (e) {
    // 失败或超时：静默降级隐藏月报区块（需求 10.1、10.4）。
    if (isStale()) return { requested: true, stale: true, ...hidden }
    return { requested: true, stale: false, ...hidden }
  }
}

/** 海报最多绘制的分类排行条目数（保持卡片紧凑）。 */
export const POSTER_TOP_N = 3

/**
 * 白名单式抽取海报所需的当前账本月报字段（需求 8.2、8.3）。
 * 仅显式拷贝：目标月、月状态、收入、支出、结余、分类排行 Top N（名称+金额）、
 * 最大单笔（名称+金额+日期）。任何其它字段（邮箱、令牌、其它账本数据等）一律不进入结果。
 * @param {object} digest 月报九模块数据包
 */
export function selectPosterFields(digest) {
  const d = digest || {}
  const ranking = Array.isArray(d.categoryRanking) ? d.categoryRanking : []
  const lg = d.largestExpense
  return {
    month: d.month ?? '',
    monthStatus: d.monthStatus ?? '',
    income: d.income ?? 0,
    expense: d.expense ?? 0,
    netBalance: d.netBalance ?? 0,
    categoryRanking: ranking.slice(0, POSTER_TOP_N).map((c) => ({
      categoryName: (c && c.categoryName) || '未分类',
      amount: c && c.amount != null ? c.amount : 0
    })),
    largestExpense: lg
      ? {
          categoryName: lg.categoryName || '未分类',
          amount: lg.amount != null ? lg.amount : 0,
          date: lg.date || ''
        }
      : null
  }
}

/** 金额 → 「¥1,234.56」海报显示串（复用 formatAmount，页面与海报口径一致）。 */
export function posterMoney(v) {
  return '¥' + formatAmount(v)
}

/**
 * 在给定 canvas 上下文上绘制月报卡片（需求 8.2、8.3）。
 * 关键约束：先经 selectPosterFields 抽取白名单字段，再仅从该白名单绘制——
 * 从源头保证海报只含当前账本月报字段，绝不含邮箱/令牌/其它账本数据。
 *
 * ctx 需支持旧版 uni canvas 绘图 API：setFillStyle/fillRect/setFontSize/
 * setTextAlign/fillText/createLinearGradient(+addColorStop)。
 * @param {object} ctx canvas 上下文
 * @param {object} digest 月报数据包（可能含账本外字段，将被白名单过滤）
 * @param {object} [opts]
 * @param {number} [opts.width] 画布逻辑宽度，缺省 600
 * @param {number} [opts.height] 画布逻辑高度，缺省 800
 * @param {(v:any)=>string} [opts.money] 金额格式化，缺省 posterMoney
 * @param {(s:string)=>string} [opts.statusText] 月状态文案，缺省 digestStatusText
 */
export function drawDigestPoster(ctx, digest, opts = {}) {
  const { width = 600, height = 800, money = posterMoney, statusText = digestStatusText } = opts
  const d = selectPosterFields(digest)
  const W = width

  // 背景
  ctx.setFillStyle('#f4f5f7')
  ctx.fillRect(0, 0, W, height)

  // 顶部渐变头图
  const grad = ctx.createLinearGradient(0, 0, W, 220)
  grad.addColorStop(0, '#22c55e')
  grad.addColorStop(0.55, '#12a150')
  grad.addColorStop(1, '#0b6b34')
  ctx.setFillStyle(grad)
  ctx.fillRect(0, 0, W, 220)

  // 标题
  ctx.setFillStyle('#ffffff')
  ctx.setFontSize(38)
  ctx.setTextAlign('left')
  ctx.fillText('智能月报', 40, 78)

  // 目标月 + 月状态
  ctx.setFontSize(26)
  ctx.fillText(String(d.month || ''), 40, 128)
  ctx.setFontSize(22)
  ctx.setTextAlign('right')
  ctx.fillText(statusText(d.monthStatus), W - 40, 128)
  ctx.setTextAlign('left')

  // 收入 / 支出 / 结余 三张卡
  const cardY = 260
  const cardH = 150
  const gap = 24
  const cardW = (W - 40 * 2 - gap * 2) / 3
  const stats = [
    { label: '收入', value: d.income, color: '#12a150' },
    { label: '支出', value: d.expense, color: '#f0553d' },
    { label: '结余', value: d.netBalance, color: Number(d.netBalance) < 0 ? '#f0553d' : '#1677ff' }
  ]
  stats.forEach((s, i) => {
    const x = 40 + i * (cardW + gap)
    ctx.setFillStyle('#ffffff')
    ctx.fillRect(x, cardY, cardW, cardH)
    ctx.setFillStyle('#9aa2ad')
    ctx.setFontSize(22)
    ctx.setTextAlign('center')
    ctx.fillText(s.label, x + cardW / 2, cardY + 46)
    ctx.setFillStyle(s.color)
    ctx.setFontSize(30)
    ctx.fillText(money(s.value), x + cardW / 2, cardY + 100)
  })
  ctx.setTextAlign('left')

  let y = cardY + cardH + 60

  // 分类排行 Top（来自白名单 categoryRanking）
  const top = d.categoryRanking
  if (top.length) {
    ctx.setFillStyle('#1f2937')
    ctx.setFontSize(26)
    ctx.fillText('分类排行', 40, y)
    y += 40
    top.forEach((c, i) => {
      ctx.setFillStyle('#4b5563')
      ctx.setFontSize(24)
      ctx.setTextAlign('left')
      ctx.fillText(`${i + 1}. ${c.categoryName}`, 40, y)
      ctx.setFillStyle('#1f2937')
      ctx.setTextAlign('right')
      ctx.fillText(money(c.amount), W - 40, y)
      ctx.setTextAlign('left')
      y += 44
    })
    y += 20
  }

  // 最大单笔（来自白名单 largestExpense）
  const lg = d.largestExpense
  if (lg) {
    ctx.setFillStyle('#1f2937')
    ctx.setFontSize(26)
    ctx.fillText('最大单笔', 40, y)
    y += 40
    ctx.setFillStyle('#4b5563')
    ctx.setFontSize(24)
    ctx.fillText(`${lg.categoryName} · ${shortDate(lg.date)}`, 40, y)
    ctx.setFillStyle('#f0553d')
    ctx.setTextAlign('right')
    ctx.fillText(money(lg.amount), W - 40, y)
    ctx.setTextAlign('left')
    y += 44
  }

  // 页脚
  ctx.setFillStyle('#9aa2ad')
  ctx.setFontSize(22)
  ctx.setTextAlign('center')
  ctx.fillText('有余 · 记账小结', W / 2, height - 40)
  ctx.setTextAlign('left')
}
