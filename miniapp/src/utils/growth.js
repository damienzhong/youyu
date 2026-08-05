/**
 * 成长页与经验明细页的客户端纯逻辑集中处（需求 13.5、13.6、13.7、13.10、13.16、13.17）。
 *
 * 设计约束（与 utils/invite.js 同构）：
 * - 本模块只做算术与状态判定，不引入页面、请求或 store 依赖，因此能在纯 node 环境下用
 *   vitest + fast-check 直接测（页面 .vue 里的逻辑测不到，抽成纯函数才能用属性测试锁住边界）。
 * - 所有函数对畸形入参一律安全降级（返回 0 / '' / false 或兜底文案），绝不抛出：
 *   成长页是次要功能，字段异常不允许把整页搞崩（需求 13.8）。
 */

/** 经验明细分页大小：首屏至多 20 条，每次上拉追加至多 20 条（需求 13.10）。 */
export const GROWTH_PAGE_SIZE = 20

/** 下拉刷新的客户端节流窗口：距上次请求发出不足该毫秒数则不再发请求（需求 13.16、13.17）。 */
export const GROWTH_REFRESH_THROTTLE_MS = 3000

/** 成长请求的客户端超时：超过该毫秒数无响应即进入失败态并结束下拉动效（需求 13.8、13.16）。 */
export const GROWTH_TIMEOUT_MS = 10000

/** 未知事件类型的兜底文案：永不向用户展示原始枚举字符串（需求 13.10）。 */
export const GROWTH_EVENT_FALLBACK_LABEL = '成长记录'

/** 有限数判定；同时把 null / undefined / '' / 非数字文本挡在外面。 */
function toFiniteOrNull(n) {
  if (n === null || n === undefined || n === '') return null
  const v = Number(n)
  return Number.isFinite(v) ? v : null
}

/** 非负整数化：非数字 / NaN / Infinity / 负数一律折成 0（接口字段可能缺失或畸形）。 */
function toCount(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

/**
 * 升级进度比例，恒落在 [0, 1] 的闭区间内（需求 13.5、13.6）。
 *
 * 三条边界（对应设计文档）：
 * ① 未满级时比例 = expInCurrentLevel / (nextLevelExp − currentLevelExp)。服务端保证分子 ≥0、
 *    分母 ≥1，前端仍 clamp 到 [0, 1] 以防服务端字段异常。
 * ② 满级时 maxLevelReached 为真、nextLevelExp 为 null，分母不成立：**直接返回 1，不做除法**
 *    （否则得到 NaN，渲染成进度条宽度 NaN% 会在真机上表现为整条进度条消失）。
 * ③ 分母 <= 0 或任一取值不可解析为有限数时返回 0，绝不返回 NaN / Infinity / 负数。
 */
export function levelProgress(overview) {
  const o = overview || {}

  // 满级：nextLevelExp 为空值，分母不成立，直接取满格（需求 13.6）。
  if (o.maxLevelReached === true && (o.nextLevelExp === null || o.nextLevelExp === undefined)) {
    return 1
  }

  const numerator = toFiniteOrNull(o.expInCurrentLevel)
  const next = toFiniteOrNull(o.nextLevelExp)
  const base = toFiniteOrNull(o.currentLevelExp)
  // 任一字段缺失或不可解析为有限数 → 0（需求 13.6 ③）。
  if (numerator === null || next === null || base === null) return 0

  const denominator = next - base
  // 分母 <= 0（含 0 与负数，避免除零得到 Infinity / NaN）→ 0。
  if (!(denominator > 0)) return 0

  const ratio = numerator / denominator
  if (!Number.isFinite(ratio)) return 0
  if (ratio < 0) return 0
  if (ratio > 1) return 1
  return ratio
}

/** 取 eventKey 冒号后半段（日期或月份）；无冒号或空值时返回 ''，绝不另发请求。 */
function afterColon(eventKey) {
  const s = eventKey === null || eventKey === undefined ? '' : String(eventKey)
  const idx = s.indexOf(':')
  return idx >= 0 ? s.slice(idx + 1).trim() : ''
}

/**
 * 事件类型 → 中文文案的映射（需求 13.10；achievement-system 需求 12.5、12.11）。
 * 七个已知类型各有互不相同的中文文案；DAILY_RECORD 带日期、BUDGET_MET 与 SAVING_MONTH 带月份，
 * 日期与月份均从 eventKey 冒号后半段取（不再另发请求）。
 * 未知类型 / 空串 / null / 畸形取值一律返回「成长记录」兜底，不显示原始枚举字符串
 * （SAVING_MONTH 与 BADGE 的原始枚举取值与事件键原文都不出现在文案里）。
 */
export function growthEventLabel(eventType, eventKey) {
  switch (eventType) {
    case 'FIRST_RECORD':
      return '首笔记账'
    case 'DAILY_RECORD': {
      const date = afterColon(eventKey)
      return date ? `每日记账 ${date}` : '每日记账'
    }
    case 'STREAK':
      return '连续记账里程碑'
    case 'BUDGET_MET': {
      const month = afterColon(eventKey)
      return month ? `预算达成 ${month}` : '预算达成'
    }
    case 'FIRST_INVITE':
      return '首次邀请好友'
    case 'SAVING_MONTH': {
      // 月份取 event_key（`SAVING_MONTH:YYYY-MM`）冒号后半段，不另发请求；无月份时退回不带月份的文案。
      const month = afterColon(eventKey)
      return month ? `储蓄达成 ${month}` : '储蓄达成'
    }
    case 'BADGE':
      return '点亮徽章'
    default:
      return GROWTH_EVENT_FALLBACK_LABEL
  }
}

/**
 * 徽章进度文案（需求 13.7）：
 * 已点亮返回 ''（不显示进度文案，改由页面展示解锁时刻）；
 * 未点亮返回 `${current} / ${target}`。
 */
export function badgeProgressText(badge) {
  const b = badge || {}
  if (b.unlocked === true) return ''
  return `${b.current} / ${b.target}`
}

/** 是否还有下一页：已加载条数 < 总条数（需求 13.10 的停止条件）。 */
export function hasMoreGrowthEvents(loaded, total) {
  return toCount(loaded) < toCount(total)
}

/**
 * 下拉刷新节流判定（需求 13.16、13.17）：距上次成长概览请求发出已满 3000ms 才放行。
 * - lastRequestAt 缺省 / 不可解析（尚未请求过）→ 放行（返回 true）。
 * - now 不可解析 → 安全降级为不刷新（返回 false），不用一个 NaN 差值误判、也不抛出。
 */
export function shouldRefresh(lastRequestAt, now) {
  const last = toFiniteOrNull(lastRequestAt)
  if (last === null) return true
  const current = toFiniteOrNull(now)
  if (current === null) return false
  return current - last >= GROWTH_REFRESH_THROTTLE_MS
}
