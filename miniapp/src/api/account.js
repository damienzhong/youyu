import { http } from '../utils/request'

/** 列出本人账户（按 sortOrder、id 升序）。 */
export function listAccounts() {
  return http.get('/accounts')
}

/**
 * 创建账户。
 * @param {{name:string,type:string,initialBalance:string|number,note?:string}} payload
 */
export function createAccount(payload) {
  return http.post('/accounts', payload)
}

/**
 * 更新账户（名称/类型/是否计入总资产等；余额不变）。
 * 注意：includeInTotal/hidden/note 会被整体覆盖，编辑时应带上现值以免被重置。
 */
export function updateAccount(id, payload) {
  return http.put(`/accounts/${id}`, payload)
}

/** 删除账户（无关联交易才允许）。 */
export function deleteAccount(id) {
  return http.del(`/accounts/${id}`)
}

/** 账户类型枚举与中文标签，顺序即展示顺序。 */
export const ACCOUNT_TYPES = [
  { value: 'CASH', label: '现金' },
  { value: 'BANK_CARD', label: '储蓄卡' },
  { value: 'ALIPAY', label: '支付宝' },
  { value: 'WECHAT', label: '微信' },
  { value: 'CREDIT_CARD', label: '信用卡' }
]

export function accountTypeLabel(type) {
  return ACCOUNT_TYPES.find((t) => t.value === type)?.label || type
}
