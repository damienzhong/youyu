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
