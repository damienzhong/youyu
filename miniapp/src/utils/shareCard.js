/**
 * 分享卡片（share-card）前端纯逻辑。
 *
 * 镜像 utils/insights.js / utils/personalityTags.js 的做法：把分享卡片页的降级决策、
 * 展示映射与品牌 Logo 布局抽成纯函数，供 pages/share/share.vue 复用（单一事实源）。
 * 同一份逻辑既被页面使用、又被单测/属性测试覆盖（对应 Property 16、17）：
 *
 *   1. 降级与不阻断（需求 1.9、11.7、11.8、11.9）：
 *      - 未登录（无有效令牌）→ 不请求、不展示，改由页面引导登录（需求 11.8）。
 *      - 账本相关卡片在「全部账本」聚合视图下 → 不请求、不展示（需求 1.9、11.8）。
 *      - 请求失败或 5000ms（含边界）超时 → 静默隐藏卡片，cardVisible=false、card=null；
 *        不弹阻断性弹窗、绝不触碰其它页面状态（需求 11.9）。
 *      - 成功但卡片不可用（available=false）→ 保留数据包供页面展示「暂不可用」，但
 *        cardVisible=false（隐藏出图/保存/分享入口，需求 11.7）。
 *      - 过期响应（请求期间切换账本/周期）→ 丢弃，不覆盖卡片（需求 11.9）。
 *   2. 展示映射与字段隔离（需求 12.3、12.6）：
 *      - cardToDisplay 白名单式抽取展示字段（头像种子、昵称、标签、一句 AI 文案、
 *        按类型选取的核心数值展示串、品牌名）；优先展示 narrative；绝不引用邮箱/令牌/
 *        其它账本数据等白名单外字段。
 *   3. 品牌 Logo 布局约束（需求 2.5、2.6）：
 *      - computeCardLayout 为品牌 Logo 计算包围盒，使其面积 ≤ 卡片可见区域 logoMaxAreaRatio
 *        （默认 5%），置于卡片一角、不落入视觉中心；返回的布局元素集合不含任何促销/下载
 *        引导/二维码元素。
 */

import { formatAmount } from './format'

/**
 * miniapp 侧分享卡片请求超时：5000ms（含边界）无响应即视为失败（需求 11.9）。
 * 与 pages/share/share.vue 共用同一常量，保证页面与测试口径一致。
 */
export const SHARE_CARD_TIMEOUT_MS = 5000

/**
 * 品牌 Logo 面积占比默认上限：卡片可见区域的 5%（需求 2.5）。
 * 与后端 ShareCardProperties.logoMaxAreaRatio 默认值一致。
 */
export const DEFAULT_LOGO_MAX_AREA_RATIO = 0.05

/**
 * 账本相关卡片集合：这三类按当前账本隔离取数，在「全部账本」聚合视图下无单一账本
 * 上下文，故不请求、不展示（需求 1.8、1.9）。其余三类为账本无关卡片。
 */
export const LEDGER_SCOPED_TYPES = new Set(['MONTHLY_SUMMARY', 'ANNUAL_BILL', 'BUDGET_ACHIEVED'])

/** 是否账本相关卡片（需求 1.8）。 */
export function isLedgerScoped(cardType) {
  return LEDGER_SCOPED_TYPES.has(cardType)
}

/**
 * 是否发起分享卡片请求：已登录（有有效令牌）才请求；账本相关卡片在「全部账本」聚合
 * 视图下不请求、不展示（需求 1.9、11.8）。账本无关卡片不受聚合视图影响。
 */
export function shouldFetchCard(isLoggedIn, isAll, cardType) {
  if (!isLoggedIn) return false
  if (isAll && isLedgerScoped(cardType)) return false
  return true
}

/**
 * Promise.race + 定时器超时包装：promise 与超时竞速，任一先结算即结算；
 * 结算后统一清理定时器，避免测试/运行时残留计时器与未处理拒绝（需求 11.9）。
 * @param {Promise} promise 实际请求
 * @param {number} timeoutMs 超时毫秒
 */
export function raceWithTimeout(promise, timeoutMs = SHARE_CARD_TIMEOUT_MS) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject({ code: 'SHARE_CARD_TIMEOUT' }), timeoutMs)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * 分享卡片加载与静默降级的纯决策核心。返回 { requested, stale, card, cardVisible }：
 *  - 未登录 / 账本相关卡片处于聚合视图：requested=false，card=null、cardVisible=false
 *    （不请求、不展示，需求 1.9、11.8）。
 *  - 请求成功、未过期且卡片可用（available=true）：card=数据包、cardVisible=true
 *    （提供出图/保存/分享入口）。
 *  - 请求成功、未过期但卡片不可用（available=false）：card=数据包、cardVisible=false
 *    （保留数据包供页面展示「暂不可用」，但隐藏出图/保存/分享入口，需求 11.7）。
 *  - 请求失败或超时：card=null、cardVisible=false（静默隐藏，不抛错、不弹阻断弹窗，需求 11.9）。
 *  - 成功但空体（null/undefined）：card=null、cardVisible=false（静默隐藏）。
 *  - 过期（请求期间用户切了账本/周期）：stale=true，交由调用方跳过应用，避免过期数据覆盖新结果。
 *
 * 该函数只产出分享卡片自身状态，从不返回或改动任何其它页面状态，因此本请求的任何失败都不
 * 阻断用户在当前页面其余模块的交互（需求 11.9）。
 *
 * 依赖注入以便测试：fetchCard 为发起请求的函数，isStale 判定是否已过期。
 * @param {object} args
 * @param {boolean} args.isLoggedIn 是否已登录（有有效令牌）
 * @param {boolean} args.isAll 是否「全部账本」聚合视图
 * @param {string} args.cardType 卡片类型键（6 种之一）
 * @param {() => Promise<any>} args.fetchCard 发起分享卡片请求
 * @param {number} [args.timeoutMs] 超时毫秒，缺省 SHARE_CARD_TIMEOUT_MS
 * @param {() => boolean} [args.isStale] 是否已过期（请求期间切换了账本/周期）
 */
export async function resolveCardState({
  isLoggedIn,
  isAll,
  cardType,
  fetchCard,
  timeoutMs = SHARE_CARD_TIMEOUT_MS,
  isStale = () => false
}) {
  const hidden = { card: null, cardVisible: false }
  if (!shouldFetchCard(isLoggedIn, isAll, cardType)) {
    return { requested: false, stale: false, ...hidden }
  }
  try {
    const res = await raceWithTimeout(fetchCard(), timeoutMs)
    if (isStale()) return { requested: true, stale: true, ...hidden }
    // 成功但空体：视为无可展示，静默隐藏。
    if (!res) return { requested: true, stale: false, ...hidden }
    // 卡片可用才提供出图/保存/分享入口；不可用时保留数据包供展示「暂不可用」，入口隐藏。
    return { requested: true, stale: false, card: res, cardVisible: !!res.available }
  } catch (e) {
    // 失败或超时：静默降级隐藏卡片（需求 11.9）。
    if (isStale()) return { requested: true, stale: true, ...hidden }
    return { requested: true, stale: false, ...hidden }
  }
}

/** 金额（数值）→「1,234.56 元」；无定义（null/undefined）返回空串。 */
function amountText(value) {
  return value == null ? '' : `${formatAmount(value)} 元`
}

/** 占比/百分比（数值）→「12.34%」；无定义（null/undefined）返回空串。 */
function pctText(value) {
  return value == null ? '' : `${formatAmount(value)}%`
}

/** 天数（整数）→「100 天」；无定义（null/undefined）返回空串。 */
function daysText(value) {
  return value == null ? '' : `${value} 天`
}

/**
 * 按卡片类型从核心数据抽取一组核心数值展示串（需求 2.1、2.5）。仅取白名单核心字段，
 * 缺失（null/undefined）的字段自动省略，绝不引用邮箱/令牌/其它账本数据。
 * @param {string} cardType 卡片类型键
 * @param {object} core 核心数据（不可用时为 null）
 * @returns {string[]} 核心数值展示串数组（可空）
 */
function buildCoreLines(cardType, core) {
  const c = core || {}
  const lines = []
  const push = (label, text) => {
    if (text) lines.push(`${label} ${text}`.trim())
  }
  switch (cardType) {
    case 'STREAK_MILESTONE':
      push('连续记账里程碑', daysText(c.milestone))
      push('当前连续', daysText(c.currentStreakDays))
      push('历史最长', daysText(c.maxStreakDays))
      break
    case 'MONTHLY_SUMMARY':
      push('本月收入', amountText(c.income))
      push('本月支出', amountText(c.expense))
      push('结余', amountText(c.balance))
      if (c.topCategoryName) {
        push(`支出最高 ${c.topCategoryName}`, pctText(c.topCategoryPercent))
      }
      break
    case 'ANNUAL_BILL':
      push('年度收入', amountText(c.annualIncome))
      push('年度支出', amountText(c.annualExpense))
      push('年度结余', amountText(c.annualBalance))
      if (c.topExpenseMonth) push('支出最高月', c.topExpenseMonth)
      if (c.topCategoryName) push('支出最高分类', c.topCategoryName)
      break
    case 'ACHIEVEMENT_BADGE':
      if (c.badgeName) push('徽章', c.badgeName)
      if (c.badgeDescription) lines.push(c.badgeDescription)
      if (c.unlockedDate) push('解锁于', c.unlockedDate)
      break
    case 'BUDGET_ACHIEVED':
      push('本月预算', amountText(c.totalBudget))
      push('已用', amountText(c.usedAmount))
      push('剩余', amountText(c.remaining))
      push('使用率', pctText(c.usedPercent))
      break
    case 'LEVEL_UP':
      if (c.level != null) push('当前等级', `Lv.${c.level}`)
      if (c.exp != null) push('成长值', `${c.exp}`)
      if (c.expInCurrentLevel != null) push('本级已获得', `${c.expInCurrentLevel}`)
      break
    default:
      break
  }
  return lines
}

/**
 * 把卡片数据包映射为展示项（需求 2.1、12.3、12.6）。白名单式只抽取展示所需字段：
 * 卡片类型、可用性、头像种子、昵称、标签、一句 AI 文案、按类型选取的核心数值展示串、品牌名。
 * 绝不引用邮箱/令牌/其它账本数据等白名单外字段。
 *
 * 返回 { cardType, available, avatarSeed, nickname, label, narrative, coreLines, brandName }：
 *  - narrative 优先展示（主视觉之一）；coreLines 为按类型抽取的核心数值展示串（主视觉）。
 * @param {object} card 一份 ShareCardResponse 数据包
 */
export function cardToDisplay(card) {
  const c = card || {}
  const cardType = c.cardType ?? ''
  const available = !!c.available
  return {
    cardType,
    available,
    avatarSeed: c.avatarSeed ?? '',
    nickname: c.nickname ?? '',
    label: c.label ?? null,
    narrative: c.narrative ?? null,
    coreLines: available ? buildCoreLines(cardType, c.core) : [],
    brandName: c.brandName ?? ''
  }
}

/**
 * 分享卡片布局元素白名单（需求 2.1、2.6）：恰好六类内容元素，绝不含任何促销文案、
 * 下载引导或二维码/小程序码元素。
 */
export const CARD_LAYOUT_ELEMENTS = Object.freeze([
  'avatar',
  'nickname',
  'label',
  'narrative',
  'core',
  'logo'
])

/**
 * 为品牌 Logo 计算包围盒与卡片布局（需求 2.5、2.6）。
 *
 * 约束：
 *  - Logo 包围盒面积 ÷ (canvasWidth × canvasHeight) ≤ logoMaxAreaRatio（默认 0.05）；
 *  - Logo 置于卡片右下角，不落入卡片视觉中心区域（中心 50% 矩形，即 [0.25,0.75]×[0.25,0.75]），
 *    核心数据、昵称与一句 AI 文案占据主视觉；
 *  - 返回的 elements 集合为固定六元素白名单，不含任何促销/下载引导/二维码元素。
 *
 * logoMaxAreaRatio 非法（NaN/负值/>1）时钳制到 [0, 1]，缺省 0.05。
 *
 * @param {number} canvasWidth 画布宽度（px）
 * @param {number} canvasHeight 画布高度（px）
 * @param {number} [logoMaxAreaRatio] Logo 面积占比上限，默认 0.05
 * @returns {object} { canvas, visualCenter, logo, elements }
 */
export function computeCardLayout(canvasWidth, canvasHeight, logoMaxAreaRatio = DEFAULT_LOGO_MAX_AREA_RATIO) {
  const W = Number.isFinite(canvasWidth) && canvasWidth > 0 ? canvasWidth : 0
  const H = Number.isFinite(canvasHeight) && canvasHeight > 0 ? canvasHeight : 0
  // 钳制面积占比到 [0, 1]，非法回退默认 0.05。
  let ratio = Number(logoMaxAreaRatio)
  if (!Number.isFinite(ratio)) ratio = DEFAULT_LOGO_MAX_AREA_RATIO
  ratio = Math.min(Math.max(ratio, 0), 1)

  const canvasArea = W * H
  // 视觉中心区域：画布中心 50%（[0.25,0.75]×[0.25,0.75]），主视觉留给核心数据/昵称/文案。
  const visualCenter = {
    x: W * 0.25,
    y: H * 0.25,
    width: W * 0.5,
    height: H * 0.5
  }

  // 面积预算：留 5% 安全裕度确保严格 ≤ 上限（避免浮点误差触边）。
  const areaBudget = canvasArea * ratio * 0.95
  // 目标宽高比（文字 Logo 略宽于高）。
  const aspect = 2.2
  let logoH = areaBudget > 0 ? Math.sqrt(areaBudget / aspect) : 0
  let logoW = logoH * aspect

  // 钳制到右下角安全带内（角区 0.25×0.25，再留 10% 余量），确保不落入视觉中心且不越界。
  const cornerW = W * 0.25 * 0.9
  const cornerH = H * 0.25 * 0.9
  if (logoW > cornerW && logoW > 0) {
    const s = cornerW / logoW
    logoW *= s
    logoH *= s
  }
  if (logoH > cornerH && logoH > 0) {
    const s = cornerH / logoH
    logoW *= s
    logoH *= s
  }

  // 置于右下角，留极小内边距；padding 取 min(W,H) 的 2%，保证 x/y 均落在角区外侧、不入中心。
  const padding = Math.min(W, H) * 0.02
  const logoX = Math.max(W - padding - logoW, 0)
  const logoY = Math.max(H - padding - logoH, 0)
  const logoArea = logoW * logoH

  return {
    canvas: { width: W, height: H },
    visualCenter,
    logo: {
      x: logoX,
      y: logoY,
      width: logoW,
      height: logoH,
      area: logoArea,
      areaRatio: canvasArea > 0 ? logoArea / canvasArea : 0
    },
    // 固定六元素白名单，绝不含促销/下载引导/二维码元素（需求 2.6）。
    elements: [...CARD_LAYOUT_ELEMENTS]
  }
}
