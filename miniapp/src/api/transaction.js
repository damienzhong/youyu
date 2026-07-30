import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/**
 * 创建收支交易（expense/income）。转账已改为账户间动作，请用
 * {@link transferBetweenAccounts}（api/account.js，POST /accounts/transfer）。
 */
export function createTransaction(payload, ledgerId) {
  return http.post('/transactions', payload, opts(ledgerId))
}

/** 余额调整：把账户余额校准到 balance，后端用一笔补差流水落地。payload：{ accountId, balance, occurredAt?, note? } */
export function adjustBalance(payload, ledgerId) {
  return http.post('/transactions/adjust', payload, opts(ledgerId))
}

/** 按项目/商家/标签过滤交易并附支出/收入汇总。dim: 'project'|'merchant'|'tag'，id 为对应实体 id。 */
export function filterTransactions(dim, id, ledgerId) {
  const key = dim === 'project' ? 'projectId' : dim === 'merchant' ? 'merchantId' : 'tagId'
  return http.get(`/transactions/filter?${key}=${id}`, opts(ledgerId))
}

/** 关键词搜索流水（跨月，命中备注/分类/商家/标签/金额）。 */
export function searchTransactions(q, ledgerId) {
  return http.get(`/transactions/search?q=${encodeURIComponent(q)}`, opts(ledgerId))
}

/** 批量软删除（移入回收站），返回 { deleted }。 */
export function batchDeleteTransactions(ids, ledgerId) {
  return http.post('/transactions/batch-delete', { ids }, opts(ledgerId))
}

/** 回收站列表（已软删除）。 */
export function listRecycle(ledgerId) {
  return http.get('/transactions/recycle', opts(ledgerId))
}

/** 从回收站恢复一笔。 */
export function restoreTransaction(id, ledgerId) {
  return http.post(`/transactions/${id}/restore`, {}, opts(ledgerId))
}

/** 彻底删除回收站中的一笔。 */
export function purgeTransaction(id, ledgerId) {
  return http.del(`/transactions/${id}/purge`, opts(ledgerId))
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
