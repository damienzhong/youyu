import { http } from '../utils/request'

/**
 * 分类占比报表。
 * @param {string} from YYYY-MM-DD（含）
 * @param {string} to   YYYY-MM-DD（含）
 * @param {'expense'|'income'} kind 统计类别，缺省 expense
 * 返回 { from, to, totalExpense, categories:[{categoryId,categoryName,amount,percentage,count}] }
 */
export function categoryReport(from, to, kind = 'expense') {
  return http.get(`/reports/category?from=${from}&to=${to}&kind=${kind}`)
}

/**
 * 成员消费占比报表（协作账本）。
 * 返回 { from, to, totalExpense, members:[{userId,displayName,amount,percentage,count}] }
 */
export function memberReport(from, to, kind = 'expense') {
  return http.get(`/reports/members?from=${from}&to=${to}&kind=${kind}`)
}

/**
 * 维度占比报表（按项目/商家/标签）。
 * @param {'project'|'merchant'|'tag'} dim 维度
 * 返回 { from, to, dimension, total, items:[{id,name,amount,percentage,count}] }
 */
export function dimensionReport(from, to, dim, kind = 'expense') {
  return http.get(`/reports/dimension?from=${from}&to=${to}&dim=${dim}&kind=${kind}`)
}

/**
 * 月度趋势报表。fromMonth/toMonth 为 YYYY-MM（区间 ≤24 个月）。
 * 返回 { months:[{month, income, expense}] }
 */
export function trendReport(fromMonth, toMonth) {
  return http.get(`/reports/trend?fromMonth=${fromMonth}&toMonth=${toMonth}`)
}

/**
 * 智能月报聚合。month 为 YYYY-MM（缺省时由后端取 Asia/Shanghai 当前自然月）。
 * 纯只读派生，一次返回九模块数据包。沿用 utils/request.js 网络层：
 * 自动带 Authorization 与 X-Ledger-Id；401 清 token 跳登录；
 * LEDGER_NOT_ACCESSIBLE 自动清本地账本并重试一次。
 *
 * 返回九模块数据包：
 * {
 *   month,            // 目标月标识 YYYY-MM
 *   monthStatus,      // 月状态：'partial'（进行中）/ 'final'（已完结）
 *   income,           // 本月收入（排除转账，2 位小数），与 /reports/monthly 同值
 *   expense,          // 本月支出（排除转账，2 位小数），与 /reports/monthly 同值
 *   netBalance,       // 结余 = income - expense（可为负）
 *   trend,            // 消费趋势：按自然日升序、稠密（范围内每日一项）[{date, income, expense}]；空月为 []
 *   categoryRanking,  // 分类排行：金额降序 [{categoryId, categoryName, amount, percentage, count}]；空月为 []
 *   budget,           // 预算情况 {hasBudget, totalBudget, spent, remaining, usedPercent, status, forecast}
 *   largestExpense,   // 最大单笔消费 {amount, categoryName, date, note}；无支出为 null
 *   mostFrugalWeek    // 最省钱的一周 {startDate, endDate, expense}；无完整周分段为 null
 * }
 * @param {string} month 目标月 YYYY-MM
 */
export function monthlyDigest(month) {
  return http.get(`/reports/monthly-digest?month=${month}`)
}

/** 给定 YYYY-MM，返回该自然月的起止日期 { from, to }（YYYY-MM-DD）。 */
export function monthRange(month) {
  const [y, m] = month.split('-').map(Number)
  const lastDay = new Date(y, m, 0).getDate()
  const mm = String(m).padStart(2, '0')
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(lastDay).padStart(2, '0')}` }
}

/** 相对某个 YYYY-MM 偏移若干月，返回新的 YYYY-MM。 */
export function shiftMonth(month, delta) {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
