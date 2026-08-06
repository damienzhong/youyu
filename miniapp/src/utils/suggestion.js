/**
 * 记账推荐（record-suggestion）前端纯逻辑。
 *
 * 按 vitest.config.js 约定，只有不依赖 uni API 的纯逻辑可自动化测试；页面渲染与
 * uni.* 交互归手工验收清单。本模块把首页推荐卡与记账页预填的判定/构造逻辑抽成纯函数，
 * 供 index.vue / record.vue 复用，同一份逻辑既被页面使用、又被单测覆盖（单一事实源）。
 *
 * 边界（均已在需求裁定）：
 * - 纯只读派生：点候选仅跳转记账页预填，绝不调用任何写接口、不创建交易（需求 4.2）。
 * - 聚合视图/未登录不请求（需求 5.3、7.4）。
 * - <2 条不展示卡；失败/超时静默降级、重试 0 次（需求 1.1、7.1、7.2、7.5）。
 * - 分类/账户已删 → 预填留空由用户重选（需求 4.5）；金额缺失/非正 → 留空（需求 4.6）。
 */

/** miniapp 侧推荐请求超时：3000ms（含边界）无响应即失败，且不自动重试（需求 7.2）。 */
export const SUGGEST_TIMEOUT_MS = 3000
/** 展示门槛：少于 2 条不展示推荐卡（需求 1.1、7.1）。 */
export const MIN_SUGGESTIONS = 2
/** 至多展示 3 条（需求 1.4、3.4）。 */
export const MAX_SUGGESTIONS = 3

/**
 * 是否发起推荐请求：已登录且非「全部账本」聚合视图才请求。
 * 未登录或聚合视图一律不请求（需求 5.3、7.4）。
 */
export function shouldFetchSuggestions(isLoggedIn, isAll) {
  return !!isLoggedIn && !isAll
}

/**
 * 从后端返回的候选列表挑出可展示的项：
 * - 非数组一律视为空；
 * - < 2 条返回空（不展示卡、不占位，需求 1.6、7.1）；
 * - >= 2 条取前 3（需求 1.4、3.4）。
 */
export function pickVisibleSuggestions(list) {
  const arr = Array.isArray(list) ? list : []
  return arr.length >= MIN_SUGGESTIONS ? arr.slice(0, MAX_SUGGESTIONS) : []
}

/**
 * 构造记账页预填查询串（不含前导 '?'）。仅带 type/amount/categoryId/accountId/note，
 * 全部经 encodeURIComponent 编码；amount 为 null 时省略（需求 4.6 由记账页留空处理）。
 * 该函数只拼参数，绝不产生任何写请求（需求 4.1、4.2）。
 */
export function buildPrefillQuery(s) {
  const parts = [`type=${encodeURIComponent(s.type)}`]
  if (s.amount != null) parts.push(`amount=${encodeURIComponent(s.amount)}`)
  if (s.categoryId != null) parts.push(`categoryId=${encodeURIComponent(s.categoryId)}`)
  if (s.accountId != null) parts.push(`accountId=${encodeURIComponent(s.accountId)}`)
  if (s.note) parts.push(`note=${encodeURIComponent(s.note)}`)
  return parts.join('&')
}

/** 记账页完整跳转 url（供 uni.navigateTo 使用）。 */
export function buildRecordUrl(s) {
  return '/pages/record/record?' + buildPrefillQuery(s)
}

/**
 * 解析预填金额：存在且为正数 → 返回其字符串形式；缺失/空/非数字/非正 → 返回 null（留空，需求 4.6）。
 */
export function resolvePrefillAmount(amount) {
  const amt = amount != null && amount !== '' ? Number(amount) : NaN
  return !Number.isNaN(amt) && amt > 0 ? String(amt) : null
}

/**
 * 解析预填备注：URL 编码传入，解码后返回；解码异常回退原值；空/缺失返回 null。
 */
export function resolvePrefillNote(note) {
  if (!note) return null
  try {
    return decodeURIComponent(note)
  } catch (e) {
    return note
  }
}

/**
 * 判断某分类 id 是否存在于分类树（含子分类）。用于预填时决定是否设置分类：
 * 不存在（已删）→ 由调用方留空让用户重选（需求 4.5）。纯函数，不依赖组件状态。
 */
export function categoryTreeHasId(tree, id) {
  if (id == null || !tree) return false
  const groups = [tree.expense || [], tree.income || []]
  for (const list of groups) {
    for (const p of list) {
      if (p && p.id === id) return true
      if (p && Array.isArray(p.children) && p.children.some((c) => c && c.id === id)) return true
    }
  }
  return false
}

/**
 * 判断某账户 id 是否在可选账户集内。不在（已删/不可见）→ 由调用方留空回退默认账户（需求 4.5）。
 */
export function accountsHasId(accounts, id) {
  if (id == null || !Array.isArray(accounts)) return false
  return accounts.some((a) => a && a.id === id)
}
