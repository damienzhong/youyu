import { accountTypeIcon, bankOf } from '../api/account'

/**
 * 账户品牌图标解析（安静风格）：统一浅底圆角块。
 * - 品牌（第三方 App / 银行）：品牌色的字徽或真实 Logo —— 只有品牌本身带颜色。
 * - 通用类型（现金/储蓄卡/公积金/股票…）：中性灰线图标，不抢色。
 * - 真实 Logo（资源包）加载失败时回退到品牌色字徽，永不空图标。
 *
 * 资源包：static/brand/apps/<slug>.svg、static/brand/banks/<slug>.svg。
 */

const BASE = '/static/brand'

/** 第三方 App 品牌：slug=资源包文件名，color=品牌色，short=字徽兜底。 */
export const APP_BRAND = {
  WECHAT: { slug: 'wechat', color: '#07C160', short: '微' },
  ALIPAY: { slug: 'alipay', color: '#1677FF', short: '支' },
  QQ_WALLET: { slug: 'qq', color: '#1296DB', short: 'Q' },
  JD_FINANCE: { slug: 'jd', color: '#E1251B', short: '京' },
  HUABEI: { slug: 'huabei', color: '#1677FF', short: '花' },
  JD_BAITIAO: { slug: 'baitiao', color: '#E1251B', short: '白' },
  DIGITAL_RMB: { slug: 'ecny', color: '#C1272D', short: '¥' },
  CRYPTO: { slug: 'bitcoin', color: '#F7931A', short: '฿' }
}

/** 通用类型语义色（线图标着色，与品牌色字徽风格统一，浅底细线不显花）。 */
const TYPE_COLOR = {
  CASH: '#12a150', BANK_CARD: '#3a7bd5', HOUSING_FUND: '#c79a3b',
  MEDICAL_INSURANCE: '#e5563d', OTHER_FUNDS: '#7c8698',
  CREDIT_CARD: '#5b8def', OTHER_CREDIT: '#7c8698',
  TRANSIT_CARD: '#2eb8a6', MEAL_CARD: '#f0a13b', MEMBER_CARD: '#e0609a',
  DEPOSIT: '#6b7280', OTHER_PREPAID: '#b98a4a',
  STOCK: '#e5563d', FUND: '#5b8def', INVESTMENT: '#12a150', OTHER_INVESTMENT: '#7c8698'
}
const DEFAULT_COLOR = '#7c8698'

const BANK_AWARE = new Set(['BANK_CARD', 'CREDIT_CARD'])

/**
 * 解析账户徽标描述。
 * @param {{type?:string, issuingBank?:string}} account
 * @returns {{logo?:string, char?:string, color?:string, iconKey?:string}}
 *   char/color → 品牌色字徽；iconKey → 中性灰线图标；有 logo 则优先尝试真实 Logo。
 */
export function accountBrand(account) {
  const type = account && account.type
  // 银行卡 / 信用卡：选定发卡行 → 品牌色字徽（有 slug 则试真实行标）
  if (BANK_AWARE.has(type)) {
    const bank = account && account.issuingBank ? bankOf(account.issuingBank) : null
    if (bank) {
      return { logo: bank.slug ? `${BASE}/banks/${bank.slug}.svg` : '', char: bank.short, color: bank.color }
    }
    // 未选发卡行：语义色卡片线图标
    return { iconKey: accountTypeIcon(type), color: TYPE_COLOR[type] || DEFAULT_COLOR }
  }
  // 第三方 App 品牌：品牌色字徽 / 真实 Logo
  const app = APP_BRAND[type]
  if (app) {
    return { logo: `${BASE}/apps/${app.slug}.svg`, char: app.short, color: app.color }
  }
  // 其余通用类型：语义色线图标
  return { iconKey: accountTypeIcon(type), color: TYPE_COLOR[type] || DEFAULT_COLOR }
}
