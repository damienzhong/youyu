import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出本人全部账户（管理视图，余额可见；按 sortOrder、id 升序）。 */
export function listAccounts(ledgerId) {
  return http.get('/accounts', opts(ledgerId))
}

/** 列出当前账本可选账户（记账用）：本人纳入的 + 他人暴露的；余额不可见时字段为 null 且 canSeeBalance=false。 */
export function listSelectableAccounts(ledgerId) {
  return http.get('/accounts/selectable', opts(ledgerId))
}

/** 本人全部账户的账本参与关联（批量）：[{ accountId, ledgerId, visibleToOthers, showBalance }]。账本名/类型由前端按 ledgerId 解析。 */
export function listAccountLedgerLinks() {
  return http.get('/accounts/ledger-links')
}

/** 信用卡还款提醒：已开启提醒的信用卡，下一个还款日/剩余天数/待还金额，按剩余天数升序。 */
export function listRepayReminders() {
  return http.get('/accounts/repay-reminders')
}

/** 快速记账默认账户：上一笔在此账本记账用的账户（仍可选则用之），否则可选集第一个；无则返回空。 */
export function getDefaultAccount(ledgerId) {
  return http.get('/accounts/default', opts(ledgerId))
}

/** 账户明细：owner 见全部流水（跨账本 + 转账）；协作成员仅见该账户在当前账本内的流水。 */
export function listAccountTransactions(id, ledgerId) {
  return http.get(`/accounts/${id}/transactions`, opts(ledgerId))
}

/** 读取账户在当前账本的可见性状态（owner 视角）：{ ledgerId, participates, visibleToOthers, showBalance }。 */
export function getAccountVisibility(id, ledgerId) {
  return http.get(`/accounts/${id}/visibility`, opts(ledgerId))
}

/** 创建账户（默认纳入当前账本，便于立即记账）。 */
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

/**
 * 设置账户在某账本的可见性（纳入/更新）。
 * payload：{ ledgerId?, visibleToOthers?, showBalance? }；ledgerId 缺省用当前账本。
 */
export function setAccountVisibility(id, payload, ledgerId) {
  return http.put(`/accounts/${id}/visibility`, payload, opts(ledgerId))
}

/** 取消账户在某账本的参与（未来不可选，历史保留）。返回 { hasHistory }。 */
export function detachAccountFromLedger(id, targetLedgerId, ledgerId) {
  return http.del(`/accounts/${id}/ledgers/${targetLedgerId}`, opts(ledgerId))
}

/**
 * 账户间转账（脱离账本，源/目标须为本人账户）。
 * payload：{ sourceAccountId, destinationAccountId, amount, occurredAt?, note? }
 */
export function transferBetweenAccounts(payload) {
  return http.post('/accounts/transfer', payload)
}

/**
 * 编辑已有转账（就地更新，不新增流水）。
 * payload：{ sourceAccountId, destinationAccountId, amount, occurredAt?, note? }
 */
export function updateTransfer(id, payload) {
  return http.put(`/accounts/transfer/${id}`, payload)
}

/** 转交账户给另一用户。 */
export function transferAccountOwnership(id, newOwnerUserId) {
  return http.post(`/accounts/${id}/transfer-ownership`, { newOwnerUserId })
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

/**
 * 常见发卡银行：以品牌色 + 简称徽标作为“图标”（避免引入受版权保护的行标图片）。
 * issuingBank 以中文行名（label）持久化；显示时用 bankOf(label) 取徽标样式。
 */
export const BANKS = [
  // 国有大型 + 邮储
  { label: '工商银行', short: '工', color: '#c7000b' },
  { label: '农业银行', short: '农', color: '#00954c' },
  { label: '中国银行', short: '中', color: '#b01c2e' },
  { label: '建设银行', short: '建', color: '#005baa' },
  { label: '交通银行', short: '交', color: '#004a9f' },
  { label: '邮储银行', short: '邮', color: '#00713c' },
  // 全国性股份制
  { label: '招商银行', short: '招', color: '#c7000b' },
  { label: '民生银行', short: '民', color: '#0a8a3c' },
  { label: '中信银行', short: '信', color: '#c8102e' },
  { label: '光大银行', short: '光', color: '#6f2c91' },
  { label: '浦发银行', short: '浦', color: '#003a70' },
  { label: '兴业银行', short: '兴', color: '#1a4f9c' },
  { label: '平安银行', short: '平', color: '#e60012' },
  { label: '广发银行', short: '广', color: '#e60012' },
  { label: '华夏银行', short: '华', color: '#c8102e' },
  { label: '浙商银行', short: '浙', color: '#b12028' },
  { label: '渤海银行', short: '渤', color: '#1b4a9c' },
  { label: '恒丰银行', short: '恒', color: '#c1272d' },
  // 主要城市 / 农商行
  { label: '北京银行', short: '京', color: '#c8102e' },
  { label: '上海银行', short: '沪', color: '#005ba1' },
  { label: '江苏银行', short: '苏', color: '#007a4d' },
  { label: '南京银行', short: '南', color: '#b01c2e' },
  { label: '宁波银行', short: '甬', color: '#d40f2b' },
  { label: '杭州银行', short: '杭', color: '#c8102e' },
  { label: '徽商银行', short: '徽', color: '#c1272d' },
  { label: '天津银行', short: '津', color: '#005baa' },
  { label: '成都银行', short: '成', color: '#b01c2e' },
  { label: '广州银行', short: '穗', color: '#c8102e' },
  { label: '青岛银行', short: '青', color: '#0a6cb5' },
  { label: '长沙银行', short: '长', color: '#c8102e' },
  { label: '哈尔滨银行', short: '哈', color: '#c1272d' },
  { label: '农村信用社', short: '社', color: '#00954c' },
  { label: '其他银行', short: '银', color: '#8a94a6' }
]
const BANK_MAP = Object.fromEntries(BANKS.map((b) => [b.label, b]))

/**
 * 银行 → 稳定 slug（品牌 Logo 资源包文件名 static/brand/banks/<slug>.svg）。
 * 未列出的行没有内置 Logo，AccountBadge 会回退「品牌色圆徽 + 简称」。
 */
const BANK_SLUG = {
  工商银行: 'icbc', 农业银行: 'abc', 中国银行: 'boc', 建设银行: 'ccb', 交通银行: 'bocom', 邮储银行: 'psbc',
  招商银行: 'cmb', 民生银行: 'cmbc', 中信银行: 'citic', 光大银行: 'ceb', 浦发银行: 'spdb', 兴业银行: 'cib',
  平安银行: 'pab', 广发银行: 'cgb', 华夏银行: 'hxb', 渤海银行: 'cbhb',
  北京银行: 'bob', 上海银行: 'bosc', 宁波银行: 'nbcb', 江苏银行: 'jsb'
}

export function bankOf(label) {
  const b = BANK_MAP[label]
  return b ? { ...b, slug: BANK_SLUG[label] || null } : null
}

const TYPE_MAP = Object.fromEntries(ACCOUNT_TYPES.map((t) => [t.value, t]))

export function accountTypeLabel(type) {
  return TYPE_MAP[type]?.label || type
}
export function accountTypeEmoji(type) {
  return TYPE_MAP[type]?.emoji || '💰'
}

/** 账户类型 → 统一线性图标 key（与 utils/icons.js 图标集一致）。 */
const TYPE_ICON = {
  CASH: 'cash', BANK_CARD: 'card', WECHAT: 'chat', ALIPAY: 'wallet', QQ_WALLET: 'wallet',
  JD_FINANCE: 'shopping', HOUSING_FUND: 'home', MEDICAL_INSURANCE: 'medical',
  DIGITAL_RMB: 'yuan', OTHER_FUNDS: 'wallet',
  CREDIT_CARD: 'card', HUABEI: 'card', JD_BAITIAO: 'card', OTHER_CREDIT: 'card',
  TRANSIT_CARD: 'transport', MEAL_CARD: 'food', MEMBER_CARD: 'star', DEPOSIT: 'lock', OTHER_PREPAID: 'card',
  STOCK: 'candles', FUND: 'invest', CRYPTO: 'coin', INVESTMENT: 'moneybag', OTHER_INVESTMENT: 'coin'
}

/** 账户类型对应的线性图标 key；未知类型回退 wallet。 */
export function accountTypeIcon(type) {
  return TYPE_ICON[type] || 'wallet'
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

/**
 * 账户显示名：由属性自动拼装，不再手填。
 * - 有发卡银行（储蓄卡/信用卡）：`${发卡行}${类型}` + 有卡号则追加 `（后四位）`，如「民生银行储蓄卡（0010）」。
 * - 其它类型：直接用类型名（现金/微信/支付宝…）。
 */
export function accountDisplayName(a) {
  if (!a) return ''
  const label = accountTypeLabel(a.type)
  if (a.issuingBank) {
    const last4 = a.cardNo ? String(a.cardNo).slice(-4) : ''
    return last4 ? `${a.issuingBank}${label}（${last4}）` : `${a.issuingBank}${label}`
  }
  // 非银行卡账户：优先展示自定义名称（如「华泰证券」），便于区分同类型的多个账户；缺省回退类型名。
  return a.name && String(a.name).trim() ? String(a.name).trim() : label
}

/**
 * 保存时用于持久化的账户名。
 * - 银行卡/信用卡：由发卡行+类型+卡号后四位拼装（忽略自定义名）。
 * - 其它类型：优先用自定义名称，为空则回退类型名。
 */
export function composeAccountName({ type, issuingBank, cardNo, name }) {
  if (issuingBank) {
    return accountDisplayName({ type, issuingBank, cardNo })
  }
  const custom = name != null ? String(name).trim() : ''
  return custom || accountTypeLabel(type)
}
