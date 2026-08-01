/**
 * 内置线性图标集（统一风格：描边单色、无填充、24×24 viewBox）。
 *
 * 跨端方案：以 SVG data-URI 作为 background-image 渲染（H5 与微信小程序均支持；内联 <svg>
 * 在小程序内不显示，故不用组件内联 SVG）。颜色在生成 data-URI 时烘焙进 stroke，默认灰、选中绿。
 *
 * key 集合与后端 CategoryIcons.guess / 迁移 V24 保持一致，保证前后端与存量数据映射统一。
 */

/** key -> SVG 内部标记（仅 path/rect/circle，统一描边，不含外层 <svg>）。 */
const ICON_PATHS = {
  food: "<path d='M4 3v7a3 3 0 0 0 3 3v8M7 3v6M10 3v6M17 3c-1.5 1.5-2 4-2 6s.5 3 2 3v9'/>",
  transport: "<rect x='5' y='3' width='14' height='14' rx='3'/><path d='M5 12h14M9 20l-1.5 2M15 20l1.5 2'/>",
  shopping: "<path d='M6 8h12l-1 12H7L6 8Z'/><path d='M9 8V6a3 3 0 0 1 6 0v2'/>",
  home: "<path d='M4 11l8-6 8 6'/><path d='M6 10v9h12v-9'/><path d='M10 19v-5h4v5'/>",
  entertainment: "<rect x='3' y='7' width='18' height='10' rx='5'/><path d='M7.5 11v2M6.5 12h2'/><circle cx='15.5' cy='11.5' r='.9'/><circle cx='17.5' cy='13.3' r='.9'/>",
  medical: "<rect x='4' y='7' width='16' height='12' rx='3'/><path d='M9 7V5h6v2M12 11v4M10 13h4'/>",
  education: "<path d='M6 4h11v13H6a2 2 0 0 0-2 2V6a2 2 0 0 1 2-2Z'/><path d='M17 4v13'/>",
  communication: "<rect x='7' y='3' width='10' height='18' rx='3'/><path d='M11 18h2'/>",
  travel: "<path d='M2 12l19-8-8 19-2.6-7.6L2 12Z'/>",
  pet: "<circle cx='7' cy='9' r='2'/><circle cx='12' cy='7' r='2'/><circle cx='17' cy='9' r='2'/><path d='M8 16c0-2.2 1.8-4 4-4s4 1.8 4 4-1.8 3.5-4 3.5-4-1.3-4-3.5Z'/>",
  salary: "<rect x='3' y='6' width='18' height='13' rx='2'/><path d='M3 10h18M16 14h2'/>",
  invest: "<path d='M4 4v16h16'/><path d='M8 15l3-3 2 2 4-5'/>",
  redpacket: "<rect x='5' y='4' width='14' height='16' rx='2'/><path d='M5 4c2.5 3.5 11.5 3.5 14 0'/><circle cx='12' cy='12' r='2'/>",
  refund: "<path d='M9 7L4 12l5 5'/><path d='M4 12h11a5 5 0 0 1 5 5'/>",
  gift: "<rect x='4' y='8' width='16' height='12' rx='2'/><path d='M4 12h16M12 8v12'/><path d='M12 8c-1.5-3-5-3-5-1s3.5 1 5 1Zm0 0c1.5-3 5-3 5-1s-3.5 1-5 1Z'/>",
  heart: "<path d='M12 21s-7-4.5-7-10a4 4 0 0 1 7-2 4 4 0 0 1 7 2c0 5.5-7 10-7 10Z'/>",
  star: "<path d='M12 4l2.2 4.8L19 9.5l-3.6 3.4.9 5.1L12 15.6 7.7 18l.9-5.1L5 9.5l4.8-.7z'/>",
  coffee: "<path d='M5 8h11v4a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4V8Z'/><path d='M16 9h2a2 2 0 0 1 0 4h-2M5 20h11'/>",
  utilities: "<path d='M13 3L5 13h6l-1 8 8-10h-6l1-8Z'/>",
  receipt: "<path d='M6 3h12v18l-3-2-3 2-3-2-3 2V3Z'/><path d='M9 8h6M9 12h6'/>",
  income: "<circle cx='12' cy='12' r='8'/><path d='M9 9l3 3 3-3M12 12v4M9.6 13h4.8'/>",
  more: "<circle cx='6' cy='12' r='1.4'/><circle cx='12' cy='12' r='1.4'/><circle cx='18' cy='12' r='1.4'/>",
  transfer: "<path d='M4 9h13l-3-3M20 15H7l3 3'/>",
  chart: "<path d='M4 21h16'/><path d='M6 21V10M12 21V4M18 21v-7'/>",
  budget: "<rect x='5' y='3' width='14' height='18' rx='2'/><path d='M8 7h8M9 12h.01M12 12h.01M15 12h.01M9 16h.01M12 16h.01M15 16h.01'/>",
  list: "<path d='M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01'/>",
  diamond: "<path d='M6 4h12l3 5-9 11L3 9z'/><path d='M3 9h18M9.5 4L7 9l5 11 5-11-2.5-5'/>",
  user: "<circle cx='12' cy='8' r='4'/><path d='M4 20c1.5-4 5-5.5 8-5.5s6.5 1.5 8 5.5'/>",
  search: "<circle cx='11' cy='11' r='7'/><path d='M20 20l-3.6-3.6'/>",
  members: "<circle cx='9' cy='8' r='3.2'/><path d='M3 19c1-3.2 3.6-4.6 6-4.6s5 1.4 6 4.6'/><path d='M16 5.2a3.2 3.2 0 0 1 0 6M18 14.6c1.8.5 3.2 1.9 3.8 4'/>",
  import: "<path d='M12 3v11M8 10l4 4 4-4'/><path d='M5 20h14'/>",
  export: "<path d='M12 14V3M8 7l4-4 4 4'/><path d='M5 20h14'/>",
  recycle: "<path d='M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13'/>",
  tag: "<path d='M4 4h7l9 9-7 7-9-9V4Z'/><circle cx='8.5' cy='8.5' r='1.4'/>",
  loan: "<path d='M4 11a8 8 0 0 1 14-5l2 2'/><path d='M20 4v4h-4'/><path d='M20 13a8 8 0 0 1-14 5l-2-2'/><path d='M4 20v-4h4'/>",
  book: "<path d='M6 3h11a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3Z'/><path d='M4 5v14M9 8h6'/>"
}

/** 默认图标色（未选中）。 */
export const ICON_DEFAULT_COLOR = '#5b6470'
/** 选中态图标色（品牌绿）。 */
export const ICON_ACTIVE_COLOR = '#12a150'

/** 图标选择器展示顺序（供新建/编辑分类挑选）。 */
export const ICON_KEYS = [
  'food', 'transport', 'shopping', 'home', 'entertainment', 'medical',
  'education', 'communication', 'travel', 'pet', 'coffee', 'utilities',
  'salary', 'invest', 'redpacket', 'refund', 'gift', 'heart', 'star',
  'receipt', 'income', 'more'
]

/** 取图标内部标记；未知 key 回退到 receipt。 */
function pathOf(key) {
  return ICON_PATHS[key] || ICON_PATHS.receipt
}

/** 是否为已知图标 key。 */
export function hasIcon(key) {
  return !!ICON_PATHS[key]
}

/**
 * 生成图标的 background-image 值：`url("data:image/svg+xml,...")`，颜色烘焙进 stroke。
 * @param {string} key 图标 key
 * @param {string} color 描边颜色
 */
export function iconDataUri(key, color = ICON_DEFAULT_COLOR) {
  const svg =
    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='" +
    color +
    "' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>" +
    pathOf(key) +
    '</svg>'
  return 'url("data:image/svg+xml,' + encodeURIComponent(svg) + '")'
}

/** 名称关键字 -> 图标 key（与后端 CategoryIcons.guess 一致），用于缺省/兜底。 */
const GUESS_RULES = [
  [/餐饮|吃|饭|外卖|美食|聚餐|零食|饮|咖啡|奶茶/, 'food'],
  [/交通|地铁|公交|打车|出行|车|油|加油|停车|高铁/, 'transport'],
  [/购物|买|衣|鞋|服饰|数码|电器|日用/, 'shopping'],
  [/娱乐|游戏|电影|玩|唱|运动|健身/, 'entertainment'],
  [/居住|房租|房贷|物业|水电|燃气|家居/, 'home'],
  [/医疗|药|医院|健康|体检/, 'medical'],
  [/教育|学习|书|培训|课|学费/, 'education'],
  [/通讯|话费|网费|流量|手机|宽带/, 'communication'],
  [/旅行|旅游|酒店|机票|景点/, 'travel'],
  [/宠物/, 'pet'],
  [/工资|薪|奖金|报销|劳务|兼职/, 'salary'],
  [/理财|利息|收益|投资|分红|基金|股票/, 'invest'],
  [/红包|礼金|转赠|人情/, 'redpacket'],
  [/退款|返现/, 'refund']
]

/** 按名称推断图标 key；未命中按种类兜底（收入 income / 支出 receipt）。 */
export function guessIcon(name, kind) {
  const s = String(name ?? '')
  for (const [re, key] of GUESS_RULES) {
    if (re.test(s)) return key
  }
  return kind === 'income' ? 'income' : 'receipt'
}

/** 归一化：优先用已知的 icon key，否则按名称推断。供渲染分类图标统一入口。 */
export function resolveIcon(icon, name, kind) {
  if (icon && ICON_PATHS[icon]) return icon
  return guessIcon(name, kind)
}
