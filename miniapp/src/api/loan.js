import { http } from '../utils/request'

/** 列出借贷并附待还/待收汇总：{ borrowOutstanding, lendOutstanding, loans:[...] }。 */
export function listLoans() {
  return http.get('/loans')
}

/** 新建借贷。direction: BORROW(借入)/LEND(借出)；amount 正数；counterparty 对方；note 可选。 */
export function createLoan(payload) {
  return http.post('/loans', payload)
}

/** 修改借贷。 */
export function updateLoan(id, payload) {
  return http.put(`/loans/${id}`, payload)
}

/** 切换结清状态（settled 缺省 true）。 */
export function settleLoan(id, settled = true) {
  return http.post(`/loans/${id}/settle?settled=${settled}`)
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
