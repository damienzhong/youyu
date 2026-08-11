import { http } from '../utils/request'

/**
 * 资产现金流与具体账本无关：账户维度的跨账本只读聚合，带 noLedger: true，不发送 X-Ledger-Id 头（需求 5.9、2.8）。
 * 归属只认登录令牌用户，后端忽略任何指定目标身份的参数/头。
 */

/**
 * 拉取选定自然月的账户维度现金流：对应后端 GET /api/all/cashflow?month=YYYY-MM。
 * @param {string} month 形如 "2026-08" 的自然月
 * @returns {Promise<{month:string, outflow:string, inflow:string, netInflow:string,
 *   todayOutflow:string, todayInflow:string}>}
 *   各金额均为两位小数字符串；netInflow 可为负；历史月今日两值为 "0.00"。
 */
export function fetchCashflow(month) {
  return http.get(`/all/cashflow?month=${encodeURIComponent(month)}`, { noLedger: true })
}
