import { http } from '../utils/request'

/** 列出借贷并附待还/待收汇总：{ borrowOutstanding, lendOutstanding, loans:[...] }。 */
export function listLoans() {
  return http.get('/loans')
}

/** 新建借贷。direction: BORROW(借入)/LEND(借出)；amount 正数；counterparty 对方；note 可选。 */
export function createLoan(payload) {
  return http.post('/loans', payload)
}

/** 借贷详情（含收款/还款明细）：{ loan, repayments:[...] }。 */
export function getLoan(id) {
  return http.get(`/loans/${id}`)
}

/** 某账户的借贷流水投影（借出/借入本金 + 收款/还款），供账户流水合并展示。 */
export function listAccountLoanEntries(accountId) {
  return http.get(`/loans/account-entries?accountId=${accountId}`)
}

/** 修改借贷。 */
export function updateLoan(id, payload) {
  return http.put(`/loans/${id}`, payload)
}

/** 新增一笔收款(借出)/还款(借入)。payload：{ amount, accountId?, occurredAt?, note? }。 */
export function addRepayment(id, payload) {
  return http.post(`/loans/${id}/repayments`, payload)
}

/** 删除一笔收款/还款。 */
export function deleteRepayment(id, repaymentId) {
  return http.del(`/loans/${id}/repayments/${repaymentId}`)
}

/** 删除借贷。 */
export function deleteLoan(id) {
  return http.del(`/loans/${id}`)
}

export const LOAN_DIRECTIONS = [
  { value: 'BORROW', label: '借入' },
  { value: 'LEND', label: '借出' }
]

export function loanDirLabel(d) {
  return d === 'BORROW' ? '借入' : '借出'
}
