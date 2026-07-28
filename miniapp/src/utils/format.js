/**
 * 展示层工具：金额格式化、分类 emoji、时间/日期标签。
 * 与 web 端 lib/ledger.ts 的展示逻辑保持一致，保证两端观感统一。
 */

/** 金额：千分位 + 两位小数，保留负号。手写分组，跨小程序/H5 都稳。 */
export function formatAmount(value) {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return '0.00'
  const neg = n < 0
  const fixed = Math.abs(n).toFixed(2)
  const [int, dec] = fixed.split('.')
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + grouped + '.' + dec
}

/** 分类名 → emoji（关键字启发式，与 web 一致）；未命中按种类兜底。 */
const CATEGORY_EMOJI_RULES = [
  [/餐饮|吃|饭|外卖|美食|聚餐|零食|饮|咖啡|奶茶/, '🍜'],
  [/交通|地铁|公交|打车|出行|车|油|加油|停车|高铁/, '🚇'],
  [/购物|买|衣|鞋|服饰|数码|电器|日用/, '🛍️'],
  [/娱乐|游戏|电影|玩|唱|运动|健身/, '🎮'],
  [/居住|房租|房贷|物业|水电|燃气|家居/, '🏠'],
  [/医疗|药|医院|健康|体检/, '💊'],
  [/教育|学习|书|培训|课|学费/, '📚'],
  [/通讯|话费|网费|流量|手机|宽带/, '📱'],
  [/旅行|旅游|酒店|机票|景点/, '✈️'],
  [/宠物/, '🐾'],
  [/工资|薪|奖金|报销|劳务/, '💰'],
  [/理财|利息|收益|投资|分红|基金|股票/, '📈'],
  [/红包|礼金|转赠/, '🧧'],
  [/退款|返现/, '💸']
]

export function categoryEmoji(name, kind) {
  const s = String(name ?? '')
  for (const [re, emoji] of CATEGORY_EMOJI_RULES) {
    if (re.test(s)) return emoji
  }
  return kind === 'income' ? '💰' : '🧾'
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** occurredAt → 日键 YYYY-MM-DD。 */
export function dayKeyOf(occurredAt) {
  return String(occurredAt || '').slice(0, 10)
}

/** YYYY-MM-DD → M月D日 周X。 */
export function dayLabel(key) {
  const [y, m, d] = String(key).split('-').map(Number)
  const wd = new Date(y || 1970, (m || 1) - 1, d || 1).getDay()
  return `${m}月${d}日 ${WEEKDAYS[wd] ?? ''}`
}

/** occurredAt → HH:mm。 */
export function timeLabelOf(occurredAt) {
  return String(occurredAt || '').slice(11, 16)
}

/** 当前自然月 YYYY-MM。 */
export function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 月份标签 YYYY-MM → YYYY年M月。 */
export function monthLabel(month) {
  const [y, m] = String(month).split('-')
  return `${y}年${Number(m)}月`
}
