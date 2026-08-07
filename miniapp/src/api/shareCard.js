import { http } from '../utils/request'

/**
 * 分享卡片数据与账本语义分层（需求 1.7、10.2）：
 * 账本无关卡片（STREAK_MILESTONE / ACHIEVEMENT_BADGE / LEVEL_UP）带 noLedger: true，
 * 不发送 X-Ledger-Id 头（对齐 api/streak.js 的写法）；账本相关卡片
 * （MONTHLY_SUMMARY / ANNUAL_BILL / BUDGET_ACHIEVED）默认带 X-Ledger-Id。
 * 全部分享卡片请求收敛到本模块，不要在页面里另起 http 调用。
 */

/** 账本无关卡片集合：不发送 X-Ledger-Id 头（需求 1.7）。 */
const LEDGER_INDEPENDENT = new Set(['STREAK_MILESTONE', 'ACHIEVEMENT_BADGE', 'LEVEL_UP'])

/** 各类型可选周期/标识参数白名单：month=YYYY-MM / year=YYYY / code / milestone（需求 10.2）。 */
const CARD_PARAM_KEYS = ['month', 'year', 'code', 'milestone']

/**
 * 分享卡片数据包，对应后端 GET /api/share-cards。纯只读派生，一次返回该卡片的展示数据包
 * 或不可用标识。沿用 utils/request.js 网络层：自动带 Authorization；401 清 token 跳登录；
 * LEDGER_NOT_ACCESSIBLE 自动清本地账本并重试一次（仅账本相关卡片）。
 *
 * 账本无关卡片带 noLedger: true 不发送 X-Ledger-Id；账本相关卡片默认带 X-Ledger-Id。
 * 请求只拼接与该类型相关且有值的可选参数（month/year/code/milestone），忽略其余。
 *
 * 返回卡片数据包：
 * {
 *   cardType,           // 卡片类型键（6 种之一，区分大小写）
 *   available,          // 卡片是否可用
 *   unavailableReason,  // 不可用原因（可用时为 null）
 *   nickname,           // 昵称（去空白为空取「有余用户」）
 *   avatarSeed,         // 文字头像种子（昵称首字符）
 *   label,              // 标签（无可用来源时为 null）
 *   narrative,          // 一句 AI 文案（可用时非空、1..60 字符；不可用时为 null）
 *   brandName,          // 品牌名（默认「有余」）
 *   core                // 卡片核心数据；不可用时为 null
 * }
 *
 * @param {'STREAK_MILESTONE'|'MONTHLY_SUMMARY'|'ANNUAL_BILL'|'ACHIEVEMENT_BADGE'|'BUDGET_ACHIEVED'|'LEVEL_UP'} type 卡片类型（必填，区分大小写）
 * @param {{ month?: string, year?: string, code?: string, milestone?: string|number }} [params] 该类型可选周期/标识参数：
 *   MONTHLY_SUMMARY / BUDGET_ACHIEVED 可选 month（YYYY-MM，缺省当前自然月）；
 *   ANNUAL_BILL 可选 year（YYYY，缺省当前自然年）；
 *   ACHIEVEMENT_BADGE 可选 code（成就编码，缺省取最近解锁）；
 *   STREAK_MILESTONE 可选 milestone（里程碑天数，缺省取已达成最高里程碑）。
 * @returns {Promise<object>} 分享卡片数据包
 */
export function shareCard(type, params = {}) {
  const query = [`type=${encodeURIComponent(type)}`]
  for (const key of CARD_PARAM_KEYS) {
    const value = params[key]
    if (value != null && value !== '') {
      query.push(`${key}=${encodeURIComponent(value)}`)
    }
  }
  const qs = query.join('&')
  const opts = LEDGER_INDEPENDENT.has(type) ? { noLedger: true } : {}
  return http.get(`/share-cards?${qs}`, opts)
}
