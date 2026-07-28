import { http } from '../utils/request'

/** 全部账本的账户（跨账本聚合，只读）。 */
export function listAllAccounts() {
  return http.get('/all/accounts')
}

/** 全部账本的分类（跨账本聚合，返回 {expense,income} 树，供解析分类名）。 */
export function listAllCategories() {
  return http.get('/all/categories')
}

/** 全部账本某自然月的交易（跨账本聚合，只读）。 */
export function listAllTransactionsByMonth(month) {
  return http.get(`/all/transactions?month=${encodeURIComponent(month)}`)
}
