import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 创建交易（支出/收入/转账）。 */
export function createTransaction(payload, ledgerId) {
  return http.post('/transactions', payload, opts(ledgerId))
}

/** 列出某自然月交易（month=YYYY-MM，按时间倒序）。 */
export function listTransactionsByMonth(month, ledgerId) {
  return http.get(`/transactions?month=${encodeURIComponent(month)}`, opts(ledgerId))
}

/** 读取单条交易。 */
export function getTransaction(id, ledgerId) {
  return http.get(`/transactions/${id}`, opts(ledgerId))
}

/** 更新交易（整体覆盖：后端先回滚原影响再应用新影响）。 */
export function updateTransaction(id, payload, ledgerId) {
  return http.put(`/transactions/${id}`, payload, opts(ledgerId))
}

/** 删除交易（后端回滚其对账户余额的影响）。 */
export function deleteTransaction(id, ledgerId) {
  return http.del(`/transactions/${id}`, opts(ledgerId))
}
