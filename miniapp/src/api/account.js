import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出账户（按 sortOrder、id 升序）。 */
export function listAccounts(ledgerId) {
  return http.get('/accounts', opts(ledgerId))
}

/** 创建账户。 */
export function createAccount(payload, ledgerId) {
  return http.post('/accounts', payload, opts(ledgerId))
}

/** 更新账户（名称/类型/是否计入总资产/授信额度等；余额不变）。 */
export function updateAccount(id, payload, ledgerId) {
  return http.put(`/accounts/${id}`, payload, opts(ledgerId))
}

/** 删除账户（无关联交易才允许）。 */
export function deleteAccount(id, ledgerId) {
  return http.del(`/accounts/${id}`, opts(ledgerId))
}

/** 账户分组（展示顺序即分组顺序），与后端 AccountGroup 对应。 */
export const ACCOUNT_GROUPS = [
  { key: 'FUNDS', label: '资金账户' },
  { key: 'CREDIT', label: '信贷账户' },
  { key: 'PREPAID', label: '充值账户' },
  { key: 'INVESTMENT', label: '投资账户' }
]

/** 账户类型枚举、中文标签、图标与所属分组，顺序即选择器展示顺序。 */
export const ACCOUNT_TYPES = [
  { value: 'CASH', label: '现金', emoji: '💵', group: 'FUNDS' },
  { value: 'BANK_CARD', label: '储蓄卡', emoji: '🏦', group: 'FUNDS' },
  { value: 'WECHAT', label: '微信', emoji: '💬', group: 'FUNDS' },
  { value: 'ALIPAY', label: '支付宝', emoji: '🔵', group: 'FUNDS' },
  { value: 'QQ_WALLET', label: 'QQ钱包', emoji: '🐧', group: 'FUNDS' },
  { value: 'JD_FINANCE', label: '京东金融', emoji: '🛒', group: 'FUNDS' },
  { value: 'HOUSING_FUND', label: '公积金', emoji: '🏘️', group: 'FUNDS' },
  { value: 'MEDICAL_INSURANCE', label: '医保账户', emoji: '🩺', group: 'FUNDS' },
  { value: 'DIGITAL_RMB', label: '数字人民币', emoji: '💴', group: 'FUNDS' },
  { value: 'OTHER_FUNDS', label: '其他', emoji: '💰', group: 'FUNDS' },
  { value: 'CREDIT_CARD', label: '信用卡', emoji: '💳', group: 'CREDIT' },
  { value: 'HUABEI', label: '蚂蚁花呗', emoji: '🐜', group: 'CREDIT' },
  { value: 'JD_BAITIAO', label: '京东白条', emoji: '⬜', group: 'CREDIT' },
  { value: 'OTHER_CREDIT', label: '其他', emoji: '📄', group: 'CREDIT' },
  { value: 'TRANSIT_CARD', label: '公交卡', emoji: '🚌', group: 'PREPAID' },
  { value: 'MEAL_CARD', label: '饭卡', emoji: '🍚', group: 'PREPAID' },
  { value: 'MEMBER_CARD', label: '会员卡', emoji: '🎫', group: 'PREPAID' },
  { value: 'DEPOSIT', label: '押金', emoji: '🔐', group: 'PREPAID' },
  { value: 'OTHER_PREPAID', label: '其他', emoji: '🧧', group: 'PREPAID' },
  { value: 'STOCK', label: '股票', emoji: '📈', group: 'INVESTMENT' },
  { value: 'FUND', label: '基金', emoji: '📊', group: 'INVESTMENT' },
  { value: 'CRYPTO', label: '虚拟货币', emoji: '🪙', group: 'INVESTMENT' },
  { value: 'INVESTMENT', label: '理财', emoji: '💹', group: 'INVESTMENT' },
  { value: 'OTHER_INVESTMENT', label: '其他', emoji: '🗃️', group: 'INVESTMENT' }
]

const TYPE_MAP = Object.fromEntries(ACCOUNT_TYPES.map((t) => [t.value, t]))

export function accountTypeLabel(type) {
  return TYPE_MAP[type]?.label || type
}
export function accountTypeEmoji(type) {
  return TYPE_MAP[type]?.emoji || '💰'
}
export function accountGroupLabel(key) {
  return ACCOUNT_GROUPS.find((g) => g.key === key)?.label || key
}
export function accountGroupOf(type) {
  return TYPE_MAP[type]?.group || 'FUNDS'
}
export function isCreditType(type) {
  return accountGroupOf(type) === 'CREDIT'
}
