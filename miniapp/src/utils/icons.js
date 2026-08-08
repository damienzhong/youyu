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
  book: "<path d='M6 3h11a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3Z'/><path d='M4 5v14M9 8h6'/>",
  folder: "<path d='M4 7a2 2 0 0 1 2-2h3.5l2 2H18a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z'/>",
  info: "<circle cx='12' cy='12' r='9'/><path d='M12 11v5M12 8h.01'/>",
  cash: "<rect x='3' y='6' width='18' height='12' rx='2'/><circle cx='12' cy='12' r='2.4'/><path d='M6 9h.01M18 15h.01'/>",
  card: "<rect x='3' y='5' width='18' height='14' rx='2'/><path d='M3 9h18M7 15h4'/>",
  chat: "<path d='M5 5h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9l-4 3V7a2 2 0 0 1 0-2Z'/>",
  wallet: "<path d='M3 7a2 2 0 0 1 2-2h11v3'/><rect x='3' y='7' width='18' height='12' rx='2'/><circle cx='17' cy='13' r='1.4'/>",
  lock: "<rect x='5' y='10' width='14' height='10' rx='2'/><path d='M8 10V7a4 4 0 0 1 8 0v3'/>",
  coin: "<ellipse cx='12' cy='7' rx='7' ry='3'/><path d='M5 7v10c0 1.7 3.1 3 7 3s7-1.3 7-3V7'/><path d='M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3'/>",
  yuan: "<circle cx='12' cy='12' r='9'/><path d='M9 8l3 3 3-3M12 11v6M9.5 13.5h5'/>",
  mail: "<rect x='3' y='5' width='18' height='14' rx='2'/><path d='M4 7l8 6 8-6'/>",
  badge: "<circle cx='12' cy='9' r='5'/><path d='M9 13l-2 8 5-3 5 3-2-8'/>",
  warning: "<path d='M12 4l9 16H3z'/><path d='M12 10v4M12 17h.01'/>",
  bell: "<path d='M6 9a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6Z'/><path d='M10 20a2 2 0 0 0 4 0'/>",
  settings: "<circle cx='12' cy='12' r='3.2'/><path d='M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.1 5.1l2.1 2.1M16.8 16.8l2.1 2.1M18.9 5.1L16.8 7.2M7.2 16.8L5.1 18.9'/>",
  // ↓↓↓ 场景分组图标库扩充（来源：design/category-icon-library.html，与既有 key 不重复） ↓↓↓
  breakfast: "<path d='M4 8h13v3a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V8Z'/><path d='M17 9h2a2 2 0 0 1 0 4h-2'/><path d='M4 20h13'/>",
  milktea: "<path d='M8 8h8l-1 12H9L8 8Z'/><path d='M8 8l1-3h6l1 3'/><path d='M10 12v4M14 12v4'/>",
  fruit: "<path d='M12 8c3 0 5 2 5 6s-2 6-5 6-5-2-5-6 2-6 5-6Z'/><path d='M12 8c0-2 1-4 3-4'/><path d='M12 8V5'/>",
  wine: "<path d='M7 3h10l-1 6a4 4 0 0 1-8 0L7 3Z'/><path d='M12 15v5M8 21h8'/>",
  snack: "<path d='M6 9h12l-1 10H7L6 9Z'/><path d='M9 9V6a3 3 0 0 1 6 0v3'/><path d='M9.5 13h5'/>",
  dessert: "<path d='M5 20h14l-2-7H7l-2 7Z'/><path d='M12 13V9M9 9a3 3 0 0 1 6 0'/>",
  hotpot: "<path d='M4 10h16v3a6 6 0 0 1-6 6h-4a6 6 0 0 1-6-6v-3Z'/><path d='M8 6c0 1-1 1-1 2M12 5c0 1-1 1-1 2M16 6c0 1-1 1-1 2'/>",
  veg: "<path d='M12 9c4 0 6 3 6 6s-2 5-6 5-6-2-6-5 2-6 6-6Z'/><path d='M12 9c-1-3 1-5 4-5'/><path d='M12 9c0-2-1-3-3-4'/>",
  bbq: "<path d='M4 6l16 4'/><path d='M7 20l5-7 5 7'/><path d='M9 15h6'/>",
  noodle: "<path d='M5 9h14a7 7 0 0 1-14 0Z'/><path d='M8 9V5M11 9V4M14 9V5'/><path d='M4 20h13'/>",
  subway: "<path d='M6 4h12v11H6z'/><path d='M6 15l-2 4M18 15l2 4M9 19h6'/><path d='M6 9h12'/>",
  taxi: "<path d='M4 11l2-5h12l2 5'/><path d='M4 11h16v6H4z'/><path d='M7 17v2M17 17v2'/><path d='M8 14h.01M16 14h.01'/>",
  fuel: "<path d='M5 20V6a2 2 0 0 1 2-2h5a2 2 0 0 1 2 2v14'/><path d='M5 12h9'/><path d='M14 8l3 3v6a2 2 0 0 0 2-2V9l-3-3'/>",
  parking: "<path d='M5 4h14v16H5z'/><path d='M9 16V8h4a3 3 0 0 1 0 6H9'/>",
  train: "<path d='M7 3h10a2 2 0 0 1 2 2v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2Z'/><path d='M5 20l2-3M19 20l-2-3'/><path d='M5 10h14M9 14h.01M15 14h.01'/>",
  plane: "<path d='M2 12l19-8-8 19-2.6-7.6L2 12Z'/>",
  bike: "<path d='M6 18a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM18 18a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z'/><path d='M6 15l4-7h4l2 4'/><path d='M9 8h4'/>",
  ship: "<path d='M4 14h16l-2 5H6l-2-5Z'/><path d='M12 4v10M7 9h10'/>",
  charge: "<path d='M9 3h6l-1 7h3l-7 11 1-8H8l1-10Z'/>",
  clothes: "<path d='M9 4l3 2 3-2 4 3-2 3-2-1v10H9V9L7 10 5 7l4-3Z'/>",
  shoe: "<path d='M3 15h12l4 2v2H3v-4Z'/><path d='M3 15V9l4-1 2 3 6 1'/>",
  digital: "<path d='M6 3h12v18H6z'/><path d='M9 6h6M10 18h4'/>",
  beauty: "<path d='M9 3h6v5a3 3 0 0 1-6 0V3Z'/><path d='M10 11h4v10h-4z'/>",
  daily: "<path d='M5 8h14l-1 12H6L5 8Z'/><path d='M5 8l2-4h10l2 4'/>",
  homeapp: "<path d='M4 5h16v11H4z'/><path d='M4 20h16M9 16v4M15 16v4'/>",
  bag: "<path d='M6 9h12l-1 11H7L6 9Z'/><path d='M9 9a3 3 0 0 1 6 0'/><path d='M4 9h16'/>",
  baby: "<path d='M12 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z'/><path d='M6 20c0-4 3-8 6-8s6 4 6 8'/><path d='M9 14l-3-2M15 14l3-2'/>",
  supermarket: "<path d='M5 8h14l-1 9H6L5 8Z'/><path d='M5 8L4 4H2'/><path d='M8 8V6a4 4 0 0 1 8 0v2'/><path d='M9 21a1 1 0 1 0 0-2M15 21a1 1 0 1 0 0-2'/>",
  water: "<path d='M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11Z'/>",
  electric: "<path d='M13 2L5 13h6l-1 9 8-11h-6l1-8Z'/>",
  gas: "<path d='M12 3c2 3 4 5 4 8a4 4 0 0 1-8 0c0-1 .5-2 1-3 .5 1 1.5 1 2 0Z'/>",
  property: "<path d='M5 20V9l7-5 7 5v11'/><path d='M9 20v-6h6v6'/>",
  furniture: "<path d='M5 11a3 3 0 0 1 6 0v3H5z'/><path d='M13 11a3 3 0 0 1 6 0v3h-6z'/><path d='M5 14v4M19 14v4'/><path d='M5 14h14'/>",
  repair: "<path d='M14 6a3 3 0 0 1-4 4l-6 6 2 2 6-6a3 3 0 0 1 4-4l-2-2 2-2 2 2-2 2Z'/>",
  wifi: "<path d='M4 9a13 13 0 0 1 16 0'/><path d='M7 12.5a8 8 0 0 1 10 0'/><path d='M10 16a3 3 0 0 1 4 0'/><path d='M12 19h.01'/>",
  clean: "<path d='M14 3l3 3-7 7-3-3 7-7Z'/><path d='M7 10l-3 8 8-3'/><path d='M5 17l2 2'/>",
  plant: "<path d='M12 21v-8'/><path d='M12 13c-3 0-5-2-5-5 3 0 5 2 5 5Z'/><path d='M12 13c3 0 5-2 5-6-3 0-5 2-5 6Z'/>",
  movie: "<path d='M4 5h16v14H4z'/><path d='M4 9h16M8 5v4M14 5v4M8 15h.01M14 15h.01'/>",
  game: "<path d='M7 8h10a4 4 0 0 1 0 8c-2 0-3-2-5-2s-3 2-5 2a4 4 0 0 1 0-8Z'/><path d='M7 12h3M8.5 10.5v3M15.5 11h.01M17 12.5h.01'/>",
  ktv: "<path d='M12 3v10'/><path d='M12 13a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z'/><path d='M12 6c3 0 5 1 5 3'/>",
  sport: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M4 8c4 2 12 2 16 0M4 16c4-2 12-2 16 0M12 3c-3 3-3 15 0 18M12 3c3 3 3 15 0 18'/>",
  music: "<path d='M9 18V6l10-2v12'/><path d='M9 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM19 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z'/>",
  photo: "<path d='M4 7h4l2-2h4l2 2h4v12H4z'/><path d='M12 16a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z'/>",
  show: "<path d='M4 6h16v10H4z'/><path d='M4 16l3 4M20 16l-3 4'/><path d='M9 6v10M15 6v10'/>",
  medicine: "<path d='M7 8a4 4 0 0 1 8 0v8a4 4 0 0 1-8 0V8Z'/><path d='M7 12h8'/>",
  checkup: "<path d='M6 3h9l3 3v15H6z'/><path d='M9 12l2 2 4-4'/>",
  tooth: "<path d='M12 3c2 0 4 1 5 3 1 3-1 4-1 8-.5 4-1 6-2 6s-1-4-2-4-1 4-2 4-1.5-2-2-6c0-4-2-5-1-8 1-2 3-3 5-3Z'/>",
  fitness: "<path d='M4 9v6M20 9v6M6 7v10M18 7v10'/><path d='M6 12h12'/>",
  stationery: "<path d='M14 4l6 6-10 10-6 1 1-6 9-11Z'/><path d='M13 5l6 6'/>",
  tuition: "<path d='M4 7h16v12H4z'/><path d='M4 11h16M8 15h4'/>",
  training: "<path d='M12 4L2 9l10 5 10-5-10-5Z'/><path d='M12 14v5'/>",
  instrument: "<path d='M14 3l7 7-4 1-3 3a4 4 0 1 1-4-4l3-3 1-4Z'/><path d='M8 16a2 2 0 1 0 0-4'/>",
  read: "<path d='M4 5c3-1 6-1 8 1 2-2 5-2 8-1v13c-3-1-6-1-8 1-2-2-5-2-8-1V5Z'/><path d='M12 6v13'/>",
  treat: "<path d='M4 20h16'/><path d='M6 20v-4a6 6 0 0 1 12 0v4'/><path d='M12 6V3M9 4l3 2 3-2'/>",
  ceremony: "<path d='M12 3l2.5 5 5.5.8-4 3.9.9 5.5-4.9-2.6-4.9 2.6.9-5.5-4-3.9 5.5-.8z'/>",
  donate: "<path d='M12 21s-7-4.5-7-10a4 4 0 0 1 7-2 4 4 0 0 1 7 2c0 5.5-7 10-7 10Z'/><path d='M9 12h6'/>",
  family: "<path d='M8 8a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM16 8a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z'/><path d='M4 20c0-3 2-5 4-5s4 2 4 5M12 20c0-3 2-5 4-5s4 2 4 5'/>",
  broadband: "<path d='M4 9a13 13 0 0 1 16 0'/><path d='M7 12.5a8 8 0 0 1 10 0'/><path d='M12 16v3'/>",
  phonebill: "<path d='M6 4h6l1 4-2 2a10 10 0 0 0 5 5l2-2 4 1v4a2 2 0 0 1-2 2C11 20 4 13 4 6a2 2 0 0 1 2-2Z'/>",
  express: "<path d='M4 8h12v9H4z'/><path d='M16 11h3l2 2v4h-5'/><path d='M7 21a2 2 0 1 0 0-4M18 21a2 2 0 1 0 0-4'/>",
  cloud: "<path d='M7 18a4 4 0 0 1 .5-8 5 5 0 0 1 9.5 1 3.5 3.5 0 0 1-1 7H7Z'/>",
  insurance: "<path d='M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6l7-3Z'/><path d='M9 12l2 2 4-4'/>",
  repay: "<path d='M4 11a8 8 0 0 1 14-5l2 2'/><path d='M20 4v4h-4'/><path d='M20 13a8 8 0 0 1-14 5l-2-2'/><path d='M4 20v-4h4'/>",
  interest: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M9 9l3 3 3-3M12 12v4M9.6 13h4.8'/>",
  fee: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M8 12h8M10 9l-2 3 2 3M14 9l2 3-2 3'/>",
  tax: "<path d='M6 3h9l3 3v15H6z'/><path d='M9 9h6M9 13h6M9 17h3'/>",
  bonus: "<path d='M12 2l2.5 5 5.5.8-4 3.9.9 5.5L12 15l-4.9 2.2.9-5.5-4-3.9 5.5-.8z'/>",
  parttime: "<path d='M6 8h12v11H6z'/><path d='M9 8V6a3 3 0 0 1 6 0v2'/><path d='M12 12v3'/>",
  earning: "<path d='M4 18l5-5 4 3 7-8'/><path d='M4 20h16'/>",
  reimburse: "<path d='M6 3h12v18l-3-2-3 2-3-2-3 2V3Z'/><path d='M9 8h6M9 12h6'/>",
  basketball: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M3 12h18M12 3v18M6 6c3 2 3 10 0 12M18 6c-3 2-3 10 0 12'/>",
  soccer: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M12 8l3.5 2.5-1.3 4h-4.4l-1.3-4L12 8Z'/>",
  swim: "<path d='M4 15c2-1.5 3-1.5 5 0s3 1.5 5 0 3-1.5 5 0'/><path d='M4 19c2-1.5 3-1.5 5 0s3 1.5 5 0 3-1.5 5 0'/><path d='M15 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z'/><path d='M8 12l3-2 5 3'/>",
  dumbbell: "<path d='M3 10v4M6 8v8M18 8v8M21 10v4'/><path d='M6 12h12'/>",
  badminton: "<path d='M14 3l7 7-3 1-3.5 3.5-3.5-3.5 3.5-3.5 3-3.5Z'/><path d='M8.5 14.5a3 3 0 1 0 .01 0Z'/>",
  hiking: "<path d='M13 4a1.5 1.5 0 1 0 0-.01Z'/><path d='M11 8l-2 5 3 2 1 6'/><path d='M9 13l-3 7'/><path d='M14 10l3 2 3-1'/>",
  car: "<path d='M5 11l2-5h10l2 5'/><path d='M4 11h16v6H4z'/><path d='M7 17v2M17 17v2M7 14h.01M17 14h.01'/>",
  carwash: "<path d='M5 12l2-4h10l2 4'/><path d='M4 12h16v5H4z'/><path d='M7 17v2M17 17v2M6 4c0 1.5-1 1.5-1 3M12 3c0 1.5-1 1.5-1 3M18 4c0 1.5-1 1.5-1 3'/>",
  maintain: "<path d='M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z'/><path d='M12 2v3M12 19v3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1l2.1-2.1M17 7l2.1-2.1'/>",
  toll: "<path d='M6 21V7l6-3 6 3v14'/><path d='M6 12h12M9 21v-5h6v5'/>",
  tire: "<path d='M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Z'/><path d='M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z'/><path d='M12 3v3M12 18v3M3 12h3M18 12h3'/>",
  carinsure: "<path d='M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6l7-3Z'/><path d='M9 12l2 2 4-4'/>",
  hotel: "<path d='M4 20V6h9a4 4 0 0 1 4 4v10'/><path d='M4 12h13M20 20V10M7 9h3'/>",
  ticket: "<path d='M4 8h16v3a2 2 0 0 0 0 2v3H4v-3a2 2 0 0 0 0-2V8Z'/><path d='M14 8v8'/>",
  luggage: "<path d='M8 8V6a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2'/><path d='M5 8h14v12H5z'/><path d='M9 20v1M15 20v1M12 11v6'/>",
  visa: "<path d='M6 3h9l3 3v15H6z'/><path d='M9 9h6M9 13h4M14.5 16.5l1.5 1.5 3-3.5'/>",
  beach: "<path d='M12 4c4 0 6 3 6 6H6c0-3 2-6 6-6Z'/><path d='M12 4v16M8 20h8'/>",
  map: "<path d='M9 4L4 6v14l5-2 6 2 5-2V4l-5 2-6-2Z'/><path d='M9 4v14M15 6v14'/>",
  haircut: "<path d='M6 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM6 14a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z'/><path d='M8 8l12 8M8 16l12-8'/>",
  laundry: "<path d='M5 3h14v18H5z'/><path d='M12 9a5 5 0 1 0 0 10 5 5 0 0 0 0-10Z'/><path d='M8 6h.01M11 6h.01'/>",
  housekeep: "<path d='M3 21h18'/><path d='M6 21V10l6-5 6 5v11'/><path d='M9 14h6'/>",
  moving: "<path d='M2 8h11v9H2z'/><path d='M13 11h4l3 3v3h-3'/><path d='M7 20a2 2 0 1 0 0-4M18 20a2 2 0 1 0 0-4'/>",
  member: "<path d='M4 8l4 3 4-6 4 6 4-3-1.5 10H5.5L4 8Z'/>",
  locksmith: "<path d='M7 11V8a5 5 0 0 1 10 0v3'/><path d='M6 11h12v9H6z'/><path d='M12 15v2'/>",
  laptop: "<path d='M5 5h14v10H5z'/><path d='M3 19h18l-2-4H5l-2 4Z'/>",
  mobile: "<path d='M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z'/><path d='M11 18h2'/>",
  camera: "<path d='M4 7h4l2-2h4l2 2h4v12H4z'/><path d='M12 16a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z'/>",
  headphone: "<path d='M5 13a7 7 0 0 1 14 0'/><path d='M4 13h3v6H5a1 1 0 0 1-1-1v-5ZM20 13h-3v6h2a1 1 0 0 0 1-1v-5Z'/>",
  printer: "<path d='M7 8V4h10v4'/><path d='M5 8h14v8H5z'/><path d='M8 16h8v4H8z'/><path d='M17 11h.01'/>",
  software: "<path d='M4 5h16v14H4z'/><path d='M4 9h16M7 13l2 2-2 2M12 17h4'/>",
  formula: "<path d='M9 3h6v3l1 2v11a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V8l1-2V3Z'/><path d='M8 12h8'/>",
  diaper: "<path d='M4 6h16v4a8 8 0 0 1-16 0V6Z'/><path d='M4 8c4 2 12 2 16 0'/>",
  toy: "<path d='M9 9a4 4 0 1 1 6 0'/><path d='M12 13v5M9 21h6M10 18h4'/>",
  kidcloth: "<path d='M9 4l3 2 3-2 3 2-1.5 2.5L15 8v9H9V8l-1.5.5L6 6l3-2Z'/>",
  kidedu: "<path d='M12 4L3 8l9 4 9-4-9-4Z'/><path d='M7 11v4c0 1 2.5 2 5 2s5-1 5-2v-4'/>",
  vaccine: "<path d='M14 4l6 6'/><path d='M17 7l-9 9-3 1 1-3 9-9'/><path d='M12 9l3 3'/>",
  cake: "<path d='M5 20h14v-6a3 3 0 0 0-3-3H8a3 3 0 0 0-3 3v6Z'/><path d='M5 15h14'/><path d='M9 11V8M12 11V7M15 11V8'/><path d='M9 5a1 1 0 1 0 2 0M13 5a1 1 0 1 0 2 0'/>",
  lantern: "<path d='M12 3v2'/><path d='M8 5h8'/><path d='M7 8a5 5 0 0 1 10 0v6a5 5 0 0 1-10 0V8Z'/><path d='M12 19v2'/>",
  rings: "<path d='M8 14a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM16 18a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z'/><path d='M8 6l2-2 2 2M16 10l-2-2'/>",
  tree: "<path d='M12 3l5 6h-3l3 4h-3l2 3H8l2-3H7l3-4H7l5-6Z'/><path d='M12 16v4M9 22h6'/>",
  firework: "<path d='M12 12v.01'/><path d='M12 4v3M12 17v3M4 12h3M17 12h3M6 6l2 2M16 16l2 2M18 6l-2 2M8 16l-2 2'/>",
  anniversary: "<path d='M4 6h16v14H4z'/><path d='M4 10h16M8 3v4M16 3v4'/><path d='M12 17s-2.5-1.6-2.5-3.4A1.5 1.5 0 0 1 12 12a1.5 1.5 0 0 1 2.5 1.6C14.5 15.4 12 17 12 17Z'/>",
  makeup: "<path d='M9 3h6v5a3 3 0 0 1-6 0V3Z'/><path d='M10 11h4v10h-4z'/>",
  skincare: "<path d='M9 3h6v3l1 2v11a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V8l1-2V3Z'/><path d='M9 12h6'/>",
  perfume: "<path d='M10 3h4v3h-4z'/><path d='M8 9a4 4 0 0 1 8 0v9a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V9Z'/><path d='M11 13h2'/>",
  nail: "<path d='M9 3h6v10a3 3 0 0 1-6 0V3Z'/><path d='M9 8h6'/>",
  spa: "<path d='M12 4c3 3 3 6 0 9-3-3-3-6 0-9Z'/><path d='M5 13c3 0 5 2 5 5-3 0-5-2-5-5ZM19 13c-3 0-5 2-5 5 3 0 5-2 5-5Z'/>",
  razor: "<path d='M4 4h9v5H4z'/><path d='M8.5 9v4a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2'/><path d='M12 15v5'/>",
  pig: "<path d='M4 12a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6c0 2-1 3-1 4l1 3h-3l-1-2H8l-1 2H4l1-3c0-1-1-2-1-4Z'/><path d='M9 10h.01M20 11l2-1'/>",
  flag: "<path d='M6 3v18'/><path d='M6 4h12l-2 4 2 4H6'/>",
  calendar: "<path d='M4 5h16v15H4z'/><path d='M4 9h16M8 3v4M16 3v4M8 13h.01M12 13h.01M16 13h.01'/>"
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

/**
 * 场景分组（有序，供图标选择器分组展示）。label 为分组名，keys 为该组图标 key（保持设计顺序）。
 * 来源：design/category-icon-library.html 的 ICONS 分组表；所有 key 均存在于 ICON_PATHS。
 */
export const ICON_GROUPS = [
  { label: '餐饮美食', keys: ['food', 'breakfast', 'coffee', 'milktea', 'fruit', 'wine', 'snack', 'dessert', 'hotpot', 'veg', 'bbq', 'noodle'] },
  { label: '交通出行', keys: ['transport', 'subway', 'taxi', 'fuel', 'parking', 'train', 'plane', 'bike', 'ship', 'charge'] },
  { label: '购物消费', keys: ['shopping', 'clothes', 'shoe', 'digital', 'beauty', 'daily', 'homeapp', 'gift', 'bag', 'baby', 'supermarket'] },
  { label: '居家生活', keys: ['home', 'water', 'electric', 'gas', 'property', 'furniture', 'repair', 'wifi', 'clean', 'plant'] },
  { label: '休闲娱乐', keys: ['entertainment', 'movie', 'game', 'ktv', 'travel', 'sport', 'book', 'music', 'photo', 'pet', 'show'] },
  { label: '医疗健康', keys: ['medical', 'medicine', 'checkup', 'heart', 'tooth', 'fitness'] },
  { label: '教育学习', keys: ['education', 'stationery', 'tuition', 'training', 'instrument', 'read'] },
  { label: '人情往来', keys: ['redpacket', 'treat', 'ceremony', 'donate', 'family'] },
  { label: '通讯数码', keys: ['communication', 'broadband', 'phonebill', 'mail', 'express', 'cloud'] },
  { label: '金融理财', keys: ['invest', 'insurance', 'repay', 'interest', 'fee', 'tax'] },
  { label: '收入来源', keys: ['salary', 'bonus', 'parttime', 'refund', 'earning', 'reimburse'] },
  { label: '运动户外', keys: ['basketball', 'soccer', 'swim', 'dumbbell', 'badminton', 'hiking'] },
  { label: '汽车养车', keys: ['car', 'carwash', 'maintain', 'toll', 'tire', 'carinsure'] },
  { label: '旅行度假', keys: ['hotel', 'ticket', 'luggage', 'visa', 'beach', 'map'] },
  { label: '生活服务', keys: ['haircut', 'laundry', 'housekeep', 'moving', 'member', 'locksmith'] },
  { label: '数码办公', keys: ['laptop', 'mobile', 'camera', 'headphone', 'printer', 'software'] },
  { label: '母婴亲子', keys: ['formula', 'diaper', 'toy', 'kidcloth', 'kidedu', 'vaccine'] },
  { label: '节日纪念', keys: ['cake', 'lantern', 'rings', 'tree', 'firework', 'anniversary'] },
  { label: '美容个护', keys: ['makeup', 'skincare', 'perfume', 'nail', 'spa', 'razor'] },
  { label: '其他常用', keys: ['receipt', 'transfer', 'cash', 'card', 'wallet', 'coin', 'pig', 'star', 'flag', 'more', 'calendar', 'lock'] }
]

/** 全部内置图标 key 集合（后端白名单来源，须与后端 CategoryIcons.KEYS 一致）。 */
export const ICON_KEY_SET = new Set(Object.keys(ICON_PATHS))

/** 分类图标默认背景色（品牌绿）；icon_color 为空/非法时回退。 */
export const DEFAULT_ICON_COLOR = '#12a150'

/** 分类图标背景色调色板（来源：category-icon-library.html 的 COLORS，≥8 色）。 */
export const ICON_COLORS = [
  '#12a150', '#2eb8a6', '#3aa0d0', '#5b8def', '#8b78e0',
  '#e0609a', '#e5563d', '#f0a13b', '#e8b93b', '#8a94a6'
]

/** 是否为合法的 hex 颜色（#RRGGBB）。 */
export function isHexColor(s) {
  return typeof s === 'string' && /^#[0-9a-fA-F]{6}$/.test(s)
}

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
