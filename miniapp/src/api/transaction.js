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

/** 删除交易（后端会回滚其对账户余额的影响）。 */
export function deleteTransaction(id) {
  return http.del(`/transactions/${id}`)
}
