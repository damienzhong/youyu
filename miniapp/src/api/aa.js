import { http } from '../utils/request'

/**
 * AA 账本接口。账本按请求头 X-Ledger-Id 隔离，故除结算查询显式带 ledgerId 外，
 * 其余读写沿用全局当前账本（由 utils/request 自动附带账本头）。金额一律以字符串承载。
 */

/**
 * 创建 AA 支出。
 * payload：{ amount, categoryId, payerUserId?, payerAccountId?, occurredAt?, note?,
 *   splitMode: 'even'|'custom', participants:[userId], customShares?:[{ userId, amount }] }
 * 付款人为本人时须带 payerAccountId，后端按实付全额从该账户扣款并落分摊行。
 */
export function createAaExpense(payload) {
  return http.post('/aa/expenses', payload)
}

/** 编辑 AA 支出（回滚旧效果后按新参数重建；已涉结算的笔拒改）。payload 同创建。 */
export function updateAaExpense(id, payload) {
  return http.put(`/aa/expenses/${id}`, payload)
}

/** 删除 AA 支出（未涉结算才可删，回滚付款账户与分摊）。 */
export function deleteAaExpense(id) {
  return http.del(`/aa/expenses/${id}`)
}

/** AA 账本概览：账户已支出 / 我的消费 / 待收回三口径 + 成员净额 + 流水。 */
export function getAaOverview(ledgerId) {
  return http.get(`/aa/${ledgerId}/overview`)
}

/** AA 结算页：每人净额 + 最少转账建议（派生）。 */
export function getAaSettlement(ledgerId) {
  return http.get(`/aa/${ledgerId}/settlement`)
}

/**
 * 结清一条涉及本人的建议转账（POST /api/aa/settlements）。账本按当前 X-Ledger-Id 隔离
 * （由 utils/request 依当前账本自动附带账本头，与 createAaExpense 同口径）。
 * payload：{ toUserId | fromUserId, amount, myAccountId }
 * - 提供 fromUserId：当前用户为**收款方**，其所选账户 +金额、应收 −金额。
 * - 提供 toUserId：当前用户为**付款方**，其所选账户 −金额、应付 −金额。
 * 二者恰有其一；amount 为该条建议转账金额（字符串，2 位小数）；myAccountId 为本人所选账户。
 * 返回落库的结算记录。
 */
export function createAaSettlement(payload) {
  return http.post('/aa/settlements', payload)
}

/** createAaSettlement 的别名，保留既有调用点兼容。 */
export const settleAa = createAaSettlement

/** 撤销一条已落库的结算（回滚本人侧账户与债务，作废展示流水）；仅本人结清的记录可撤销。 */
export function revertAaSettlement(id) {
  return http.post(`/aa/settlements/${id}/revert`)
}
