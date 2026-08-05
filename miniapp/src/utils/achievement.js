import { STORAGE_KEYS } from './config'

/**
 * 成就页、解锁播报与成就分享的客户端纯逻辑集中处
 * （需求 7.6、7.9、7.11、8.3、8.10、8.12、9.3、9.4、9.5、9.10）。
 *
 * 设计约束（与 utils/growth.js、utils/invite.js 同构）：
 * - 本模块只做算术与状态判定，不引入页面、请求或 store 依赖，因此能在纯 node 环境下用
 *   vitest + fast-check 直接测（`.vue` 里的逻辑测不到，抽成纯函数才能用属性测试锁住边界）。
 *   播报编排是本 spec 里最容易出错的部分（单次至多 3 项、游标只能取已展示项的最大事件 id、
 *   未展示项必须留在待播报集合内），所以整个编排的**决策**都放在这里，
 *   `.vue` 只保留副作用（显示弹层、uni.showToast、发请求、canvas 绘制）。
 * - 全部函数对畸形入参一律安全降级（返回 null / '' / [] / false），**绝不抛出**：
 *   成就是次要功能，字段异常不允许把整页搞崩，也不允许阻断记账等主路径。
 * - 唯一的例外是文件末尾「待高亮成就编码的暂存」三个函数：它们要读写 uni 存储，
 *   因此不是纯函数。之所以仍放本模块，见该节的注释。
 */

/** 成就总数，恒为 16（与服务端清单一致，仅用于展示「已解锁数 / 总数」的兜底）。 */
export const ACHIEVEMENT_TOTAL = 16

/** 待播报查询与游标推进请求的客户端超时（需求 7.3、7.10）。 */
export const PENDING_TIMEOUT_MS = 3000

/** 成就清单请求的客户端超时（需求 9.7）。 */
export const LIST_TIMEOUT_MS = 10000

/** 成就页下拉刷新的客户端节流窗口（需求 9.10）。 */
export const REFRESH_THROTTLE_MS = 3000

/** 单条播报 Toast 的展示时长（需求 7.5）。 */
export const TOAST_DURATION_MS = 1500

/** 相邻两条播报 Toast 的间隔（需求 7.5）。 */
export const TOAST_GAP_MS = 300

/** 解锁弹层的收起动画时长上界（需求 7.8、7.16）。 */
export const MODAL_EXIT_MS = 300

/** 单次播报展示的成就项数上限：1 个解锁弹层 + 至多 2 条 Toast（需求 7.6）。 */
export const MAX_BROADCAST_ITEMS = 3

/** 分享落地后的高亮展示时长（需求 8.11）。 */
export const HIGHLIGHT_MS = 3000

/** 待高亮成就编码的长度上限（需求 8.10、8.12）。 */
export const CODE_MAX_LEN = 64

/** 分享卡片标题长度上限：30 个字符（需求 8.3）。 */
export const SHARE_TITLE_MAX_LEN = 30

/** 成就页路径：既是入口目标也是分享落地页。 */
export const ACHIEVEMENT_PAGE_PATH = '/pages/achievement/achievement'

/** 有限数判定；同时把 null / undefined / '' / 非数字文本挡在外面。 */
function toFiniteOrNull(n) {
  if (n === null || n === undefined || n === '') return null
  const v = Number(n)
  return Number.isFinite(v) ? v : null
}

/** 非负整数化：非数字 / NaN / Infinity / 负数一律折成 0（接口字段可能缺失或畸形）。 */
function toCount(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

/** 普通对象判定：数组与 null 都不算（接口项应当是对象）。 */
function isPlainObject(v) {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** 字符串化 + 去首尾空白；null / undefined 一律得到 ''。 */
function trimmed(v) {
  if (v === null || v === undefined) return ''
  return String(v).trim()
}

/**
 * 可播报项判定：必须是对象且带一个可解析为非负整数的 eventId。
 * eventId 是游标推进的唯一依据，没有它的项一旦被「展示」就无法确认，
 * 因此这里直接把它排除在播报计划之外。
 */
function isBroadcastItem(item) {
  if (!isPlainObject(item)) return false
  const id = toFiniteOrNull(item.eventId)
  return id !== null && id >= 0
}

/**
 * 播报计划（需求 7.4、7.5、7.6）：第 1 项走解锁弹层，第 2–3 项走 Toast，其余留待后续播报。
 *
 * - 入参非数组、空数组、或全部项畸形 → `{ modal: null, toasts: [] }`（静默降级，不抛出）。
 * - `toasts` 长度恒 ≤ 2，`modal` 与 `toasts` 合计恒 ≤ MAX_BROADCAST_ITEMS（3 项）。
 * - 返回的是原始项引用，页面直接拿去渲染名称 / 描述 / 解锁日期。
 *
 * @returns {{modal: object|null, toasts: object[]}}
 */
export function planBroadcast(items) {
  if (!Array.isArray(items)) return { modal: null, toasts: [] }
  const usable = items.filter(isBroadcastItem)
  if (usable.length === 0) return { modal: null, toasts: [] }
  return { modal: usable[0], toasts: usable.slice(1, MAX_BROADCAST_ITEMS) }
}

/**
 * 已展示项的最大成就事件 id（需求 7.9、7.11）；未展示任何项返回 null，
 * 此时调用方**不得**发起游标推进请求。
 *
 * 签名刻意只接受「本次已展示」的子集，绝不接受整个待播报列表——
 * 这是需求 7.11「未播报的成就必须留在待播报集合内」的唯一防线：
 * 只要调用方传的是已展示子集，游标就永远推不到未展示项上，漏播在结构上不可能发生。
 */
export function ackCursorOf(shownItems) {
  if (!Array.isArray(shownItems)) return null
  let max = null
  for (const item of shownItems) {
    if (!isPlainObject(item)) continue
    const id = toFiniteOrNull(item.eventId)
    if (id === null || id < 0) continue
    if (max === null || id > max) max = id
  }
  return max
}

/**
 * 按 `category`（服务端下发的分类中文展示名）分组（需求 9.3）。
 *
 * 保持两个顺序不变：组的顺序取分类在响应中的首现顺序（服务端清单已保证
 * 「起步 / 坚持 / 积累 / 协作 / 主题」），组内项的顺序取响应中的相对顺序。
 * 非数组入参、非对象项、`category` 去空白后为空的项一律跳过（降级为少渲染，不抛出）。
 *
 * @returns {{category: string, items: object[]}[]}
 */
export function groupByCategory(achievements) {
  if (!Array.isArray(achievements)) return []
  const groups = []
  const indexByCategory = new Map()
  for (const a of achievements) {
    if (!isPlainObject(a)) continue
    const category = trimmed(a.category)
    if (!category) continue
    if (!indexByCategory.has(category)) {
      indexByCategory.set(category, groups.length)
      groups.push({ category, items: [] })
    }
    groups[indexByCategory.get(category)].items.push(a)
  }
  return groups
}

/**
 * 未解锁成就的进度文案 `${current} / ${target}`（需求 9.5）；
 * 已解锁返回 ''（改由页面展示解锁日期，需求 9.4）。
 *
 * 当前值钳制在 [0, target]（需求 9.5「当前值不大于门槛数值」）：服务端已保证这一点，
 * 前端仍钳一次以防字段异常渲染出「17 / 16」这种自相矛盾的进度。
 * target 缺失 / 不可解析为正数时返回 ''，不渲染「0 / undefined」。
 */
export function achievementProgressText(a) {
  if (!isPlainObject(a)) return ''
  if (a.unlocked === true) return ''
  const target = toFiniteOrNull(a.target)
  if (target === null || target <= 0) return ''
  const targetInt = Math.floor(target)
  const current = Math.min(toCount(a.current), targetInt)
  return `${current} / ${targetInt}`
}

/**
 * 解锁日期文案（需求 9.4）：LocalDateTime 字符串（如 `2025-06-01T12:00:00`）→ `YYYY-MM-DD`，
 * 即解锁时刻所属自然日的年月日三项。
 *
 * - 未解锁项（`unlocked === false`）恒返回 ''（需求 9.5「不为其展示解锁日期」）。
 *   待播报项没有 `unlocked` 字段，本身即已解锁，因此只挡显式的 false。
 * - 空值 / 非字符串 / 前 10 位不是 `YYYY-MM-DD` 形状的畸形取值一律返回 ''。
 */
export function unlockedDateLabel(a) {
  if (!isPlainObject(a)) return ''
  if (a.unlocked === false) return ''
  const s = trimmed(a.unlockedAt)
  if (!s) return ''
  const date = s.slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : ''
}

/**
 * 分享卡片载荷（需求 8.3）：`{ title, path }` 恰好两项。
 *
 * - `path` = `/pages/achievement/achievement?code=<成就编码经 URL 编码>`；
 *   编码缺失 / 空白时降级为不带 `code` 的成就页路径（落地展示无高亮的默认页，需求 8.12）。
 * - `title` 含产品名「有余」与成就展示名称，长度恒落在 [1, 30]（需求 8.3）：
 *   名称缺失时退成「新成就」；名称过长时按 Unicode 码点从尾部裁剪，
 *   **不按 UTF-16 char 裁**（否则会把 emoji 或生僻字劈成半个字符）。
 */
export function buildAchievementSharePayload(achievement) {
  const a = isPlainObject(achievement) ? achievement : {}
  const code = trimmed(a.code)
  const path = code ? `${ACHIEVEMENT_PAGE_PATH}?code=${encodeURIComponent(code)}` : ACHIEVEMENT_PAGE_PATH

  const prefix = '我在「有余」解锁了'
  const chars = Array.from(trimmed(a.name) || '新成就')
  while (chars.length > 0 && (prefix + chars.join('')).length > SHARE_TITLE_MAX_LEN) {
    chars.pop()
  }
  // 名称被裁到空也仍有前缀，标题长度恒 ≥1 且含「有余」。
  return { title: prefix + chars.join(''), path }
}

/**
 * 启动参数 `code` → 待高亮成就编码（需求 8.10、8.12）。
 *
 * URL 解码 → 裁剪首尾空白 → 长度 > CODE_MAX_LEN(64) 一律 null → 不在清单内一律 null。
 * 百分号编码畸形（decodeURIComponent 抛错，如 `%`、`%E0%A4%A`）时降级为按原文解析，
 * 绝不抛出——宁可判为不匹配走默认页，也不能白屏（沿用 utils/invite.js 的 decodeInviteParam）。
 * 编码比对逐字符相等（区分大小写），与服务端清单的编码取值一致。
 */
export function resolveHighlightCode(rawCode, achievements) {
  if (rawCode === null || rawCode === undefined) return null
  const raw = String(rawCode)
  let decoded = raw
  try {
    decoded = decodeURIComponent(raw)
  } catch (e) {
    decoded = raw
  }
  const code = decoded.trim()
  if (!code) return null
  if (code.length > CODE_MAX_LEN) return null
  if (!Array.isArray(achievements)) return null
  const hit = achievements.some((a) => isPlainObject(a) && trimmed(a.code) === code)
  return hit ? code : null
}

/**
 * 下拉刷新节流判定（需求 9.10）：距上一次成就清单请求发出已满 3000ms 才放行。
 * 语义与 utils/growth.js 的同名函数逐条一致：
 * - lastRequestAt 缺省 / 不可解析（尚未请求过）→ 放行（返回 true）。
 * - now 不可解析 → 安全降级为不刷新（返回 false），不用一个 NaN 差值误判、也不抛出。
 */
export function shouldRefresh(lastRequestAt, now) {
  const last = toFiniteOrNull(lastRequestAt)
  if (last === null) return true
  const current = toFiniteOrNull(now)
  if (current === null) return false
  return current - last >= REFRESH_THROTTLE_MS
}

// ---- 待高亮成就编码的暂存（需求 8.13、8.14）----
// 本节是全模块唯一触碰 uni 存储的三个函数（沿用 utils/invite.js 的 savePendingInviteCode
// 既有模式），放这里而不是放页面里，是因为写入方是成就页、读取方是登录页，
// 两处必须共用同一个键与同一套降级规则，散在两个 .vue 里必然漂移。
// 与邀请码刻意不同的两点，都写在下面各自的注释里：不做 7 天有效期、不做格式白名单。

/**
 * 暂存待高亮成就编码；写入失败（存储不可用）返回 false 且**不抛出**——
 * 暂存只影响登录后要不要高亮某一项，绝不允许阻断登录主路径（对齐需求 8.13）。
 *
 * 只做长度上限（CODE_MAX_LEN）这一道闸：编码是否真实存在由 resolveHighlightCode
 * 在拿到清单响应后逐字符比对，前置的格式白名单会与服务端清单形成第二份规则。
 */
export function savePendingAchievementCode(rawCode) {
  const code = trimmed(rawCode)
  if (!code || code.length > CODE_MAX_LEN) return false
  try {
    uni.setStorageSync(STORAGE_KEYS.pendingAchievementCode, code)
    return true
  } catch (e) {
    return false
  }
}

/**
 * 取出并**立即清除**暂存的待高亮成就编码；无暂存 / 读取异常一律返回 ''，不抛出。
 *
 * 一次性消费（与邀请码的「取不清除、登录成功后再清」相反）：高亮是纯展示效果，
 * 用过即弃最简单，也就不需要邀请码那套 7 天有效期——一个过期的编码最坏也只是
 * 让用户下次进成就页时多看到一次高亮，不产生任何数据后果。
 */
export function takePendingAchievementCode() {
  let code = ''
  try {
    code = trimmed(uni.getStorageSync(STORAGE_KEYS.pendingAchievementCode))
  } catch (e) {
    code = ''
  }
  clearPendingAchievementCode()
  return code && code.length <= CODE_MAX_LEN ? code : ''
}

/** 清除暂存的待高亮成就编码；异常吞掉并返回 false。 */
export function clearPendingAchievementCode() {
  try {
    uni.removeStorageSync(STORAGE_KEYS.pendingAchievementCode)
    return true
  } catch (e) {
    return false
  }
}
