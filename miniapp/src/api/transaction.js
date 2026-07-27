import { http } from '../utils/request'

/**
 * 创建交易（支出/收入/转账）。amount 恒为正，方向由 type 决定。
 * occurredAt 省略时后端取当前时间。
 * @param {{type:string,amount:string,accountId?:number,categoryId?:number,
 *          sourceAccountId?:number,destinationAccountId?:number,note?:string}} payload
 */
export function createTransaction(payload) {
  return http.post('/transactions', payload)
}

/** 列出某自然月交易（month=YYYY-MM，按时间倒序）。 */
export function listTransactionsByMonth(month) {
  return http.get(`/transactions?month=${encodeURIComponent(month)}`)
}

/** 读取单条交易。 */
export function getTransaction(id) {
  return http.get(`/transactions/${id}`)
}

/**
 * 更新交易（整体覆盖：后端先回滚原影响再应用新影响）。
 * 注意：occurredAt 省略时后端会重置为当前时间，编辑时务必带上原始 occurredAt。
 */
export function updateTransaction(id, payload) {
  return http.put(`/transactions/${id}`, payload)
}

/** 删除交易（后端会回滚其对账户余额的影响）。 */
export function deleteTransaction(id) {
  return http.del(`/transactions/${id}`)
}
