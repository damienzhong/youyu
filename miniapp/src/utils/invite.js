import { STORAGE_KEYS } from './config'

/**
 * 邀请码的客户端规则集中处（需求 4.1、4.6、4.7、4.13）。
 *
 * 设计约束：
 * - 本模块只依赖 uni 的同步存储 API（getStorageSync / setStorageSync / removeStorageSync），
 *   不引入页面、请求或 store 依赖，从而能在纯 node 环境下用 vitest 直接测（测试里 mock 全局 uni）。
 * - 存储读写异常一律吞掉并返回 '' / false：邀请码暂存是增长功能，故障绝不允许抛出、
 *   更不允许阻断登录/注册主路径（需求 4.13）。
 */

/** 邀请码字母表（剔除易混 I/O/0/1），长度恰为 8。 */
export const INVITE_CODE_RE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/

/** 待绑定邀请码有效期：7 天，按客户端本地时刻判定（需求 4.6、4.7）。 */
export const PENDING_TTL_MS = 604800000

/** trim + 转大写；null / undefined / 非字符串一律先转字符串。 */
export function normalizeInviteCode(raw) {
  if (raw === null || raw === undefined) return ''
  return String(raw).trim().toUpperCase()
}

/** 规整后长度恰为 8 且每个字符都属于字母表。 */
export function isWellFormedInviteCode(code) {
  return INVITE_CODE_RE.test(normalizeInviteCode(code))
}

/** 邀请链接 = 小程序页面路径（与服务端 InviteService.buildInviteLink 同构）。 */
export function buildInviteLink(code) {
  return `/pages/invitelanding/invitelanding?code=${normalizeInviteCode(code)}`
}

// ---- 本地存储的容错封装：任何异常都不向外抛 ----

function readRaw(key) {
  try {
    const v = uni.getStorageSync(key)
    return v === null || v === undefined ? '' : String(v)
  } catch (e) {
    return ''
  }
}

function writeRaw(key, value) {
  try {
    uni.setStorageSync(key, value)
    return true
  } catch (e) {
    return false
  }
}

function removeRaw(key) {
  try {
    uni.removeStorageSync(key)
    return true
  } catch (e) {
    return false
  }
}

/**
 * 写入待绑定邀请码与写入时刻，覆盖本地已有取值（以最近一次写入为准，需求 4.1）。
 * 邀请码格式非法时不写入、不修改已有暂存并返回 false（需求 4.11）。
 * 写入失败一律返回 false，不抛出（需求 4.13）。
 */
export function savePendingInviteCode(code) {
  const normalized = normalizeInviteCode(code)
  if (!INVITE_CODE_RE.test(normalized)) return false
  // 先写码再写时刻；任一步失败即视为整体失败，由调用方按「暂存不可用」继续走登录主路径。
  if (!writeRaw(STORAGE_KEYS.pendingInviteCode, normalized)) return false
  if (!writeRaw(STORAGE_KEYS.pendingInviteCodeAt, String(Date.now()))) return false
  return true
}

/**
 * 取本次登录/注册可携带的待绑定邀请码：
 *  - 码为空 / 格式非法 → 清除两个键并返回 ''
 *  - 写入时刻缺失 / 不可解析为数字 / 距今已满 7 天 / 早于写入时刻（时钟回拨）→ 清除并返回 ''（需求 4.7）
 *  - 否则返回该码，且**不清除**（登录成功后才由 clearPendingInviteCode 清，需求 4.8、4.12）
 * 读取异常一律返回 ''，不抛出（需求 4.13）。
 */
export function takePendingInviteCode() {
  const code = normalizeInviteCode(readRaw(STORAGE_KEYS.pendingInviteCode))
  if (!INVITE_CODE_RE.test(code)) {
    clearPendingInviteCode()
    return ''
  }

  const rawAt = readRaw(STORAGE_KEYS.pendingInviteCodeAt).trim()
  // 空串走 Number('') === 0 会被误判为「1970 年写入」，故显式挡掉缺失。
  const at = rawAt === '' ? Number.NaN : Number(rawAt)
  if (!Number.isFinite(at)) {
    clearPendingInviteCode()
    return ''
  }

  const elapsed = Date.now() - at
  if (!(elapsed >= 0 && elapsed < PENDING_TTL_MS)) {
    clearPendingInviteCode()
    return ''
  }

  return code
}

/** 登录/注册成功后清除码与写入时刻（需求 4.8）。异常吞掉并返回 false。 */
export function clearPendingInviteCode() {
  const okCode = removeRaw(STORAGE_KEYS.pendingInviteCode)
  const okAt = removeRaw(STORAGE_KEYS.pendingInviteCodeAt)
  return okCode && okAt
}

// ---- 落地页的启动参数解析与降级判定（需求 2.4、2.5、3.3、4.11；Property 16）----
// 刻意放在本模块而非页面里：解析与判定是纯函数，能在 node 下直接测；
// 页面只负责把 onLoad(options) 与登录态喂进来、按返回的 state 渲染。

/** 落地页的三个互斥页面态。 */
export const INVITE_LANDING_STATE = {
  /** 参数合法且未登录：写暂存 + 查邀请人展示信息 */
  INVITER_SHOWN: 'INVITER_SHOWN',
  /** 参数缺失/非法，或查询失败/超时：不含邀请人信息的默认登录引导 */
  DEFAULT: 'DEFAULT',
  /** 已登录：已登录提示 + 回到首页入口 */
  LOGGED_IN: 'LOGGED_IN'
}

/**
 * 单个启动参数取值 → 邀请码：URL 解码 → 去首尾空白 → 转大写（需求 2.4、3.3）。
 * 百分号编码畸形（decodeURIComponent 抛错，如 "%"、"%E0%A4%A"）时降级为按原文解析，
 * 绝不抛出——落地页宁可判为非法走默认引导，也不能白屏（需求 2.5、4.11）。
 */
export function decodeInviteParam(raw) {
  if (raw === null || raw === undefined) return ''
  const s = String(raw)
  let decoded = s
  try {
    decoded = decodeURIComponent(s)
  } catch (e) {
    decoded = s
  }
  return normalizeInviteCode(decoded)
}

/**
 * 落地页 onLoad(options) → 邀请码：`code` 优先，缺失（undefined/null）时取 `scene`
 * （分享卡片传 code、小程序码传 scene）。两者都无 → ''。
 */
export function parseLandingInviteCode(options) {
  if (options === null || options === undefined || typeof options !== 'object') return ''
  const raw = options.code === undefined || options.code === null ? options.scene : options.code
  return decodeInviteParam(raw)
}

/**
 * 由「解析出的邀请码 + 登录态」唯一确定：是否发起邀请人展示信息查询、是否写暂存、初始页面态。
 * - 已登录 → LOGGED_IN，不查不写（需求 4.9）
 * - 未登录 + 合法 → INVITER_SHOWN，写暂存并查询（需求 4.1、4.3）
 * - 未登录 + 非法/缺失 → DEFAULT，不查询、不写入也不修改已有暂存（需求 2.5、4.11）
 */
export function decideInviteLanding(parsedCode, isLoggedIn) {
  const code = normalizeInviteCode(parsedCode)
  const valid = INVITE_CODE_RE.test(code)
  if (isLoggedIn === true) {
    return { code, valid, shouldQuery: false, shouldPersist: false, state: INVITE_LANDING_STATE.LOGGED_IN }
  }
  return {
    code,
    valid,
    shouldQuery: valid,
    shouldPersist: valid,
    state: valid ? INVITE_LANDING_STATE.INVITER_SHOWN : INVITE_LANDING_STATE.DEFAULT
  }
}

/** 解析 + 判定的合成入口，供 `onLoad(options)` 直接调用。 */
export function resolveInviteLanding(options, isLoggedIn) {
  return decideInviteLanding(parseLandingInviteCode(options), isLoggedIn)
}
// ---- 邀请页的展示契约：分享标题 / 列表分页 / 状态文案（需求 2.1、2.2、2.9、7.13；Property 17）----
// 同样刻意从 pages/invite/invite.vue 抽到此处：这几段是纯函数与常量，能在 node 下直接测；
// 页面只负责把接口结果与交互事件喂进来、按返回值渲染或发请求。

/** 不带 `code` 的落地页路径：邀请链接为空时的分享降级目标（需求 2.9）。 */
export const INVITE_LANDING_PATH = '/pages/invitelanding/invitelanding'

/** 分享卡片标题上限：30 个字符（需求 2.2）。 */
export const INVITE_SHARE_TITLE_MAX_LEN = 30

/** 分享卡片标题：含产品名「有余」且长度 15 ≤ 30（需求 2.2）。 */
export const INVITE_SHARE_TITLE = '我在用「有余」记账，一起来试试'

/** 标题是否满足验收标准 2.2：含「有余」且长度 ≤ 30。 */
export function isValidShareTitle(title) {
  const s = title === null || title === undefined ? '' : String(title)
  return s.includes('有余') && s.length > 0 && s.length <= INVITE_SHARE_TITLE_MAX_LEN
}

/**
 * 转发卡片载荷：邀请链接非空时 `path` 等于该链接；为空（邀请信息未就绪）时降级为
 * 不带 `code` 的落地页路径，并置 `degraded` 供页面提示「邀请码尚未就绪」（需求 2.2、2.9）。
 * 标题恒为 INVITE_SHARE_TITLE，不随降级变化。
 */
export function buildInviteSharePayload(inviteLink) {
  const link = inviteLink === null || inviteLink === undefined ? '' : String(inviteLink).trim()
  return { title: INVITE_SHARE_TITLE, path: link || INVITE_LANDING_PATH, degraded: !link }
}

/** 邀请记录状态文案：与 `REGISTERED` / `INVALID` 一一对应（需求 7.13）。 */
export const INVITE_STATUS_LABELS = { REGISTERED: '已注册', INVALID: '已注销' }

/** 两个已知状态映射为各自文案；未知取值原样透出（不编造文案，也不落进上面两个文案里）。 */
export function inviteStatusLabel(status) {
  if (status === 'REGISTERED') return INVITE_STATUS_LABELS.REGISTERED
  if (status === 'INVALID') return INVITE_STATUS_LABELS.INVALID
  return String(status || '')
}

/** 列表分页大小：首屏至多 20 条，每次上拉追加至多 20 条（需求 7.13）。 */
export const INVITE_PAGE_SIZE = 20

/** 列表区的四个互斥状态。 */
export const INVITE_LIST_STATE = {
  LOADING: 'loading',
  LOADED: 'loaded',
  EMPTY: 'empty',
  ERROR: 'error'
}

/** 非负整数化：非数字 / NaN / 负数一律折成 0（接口字段可能缺失或畸形）。 */
function toCount(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

/** 列表成功返回后的状态：总条数为 0 → 空状态，否则已加载（需求 7.14）。 */
export function inviteListStateAfterLoad(total) {
  return toCount(total) === 0 ? INVITE_LIST_STATE.EMPTY : INVITE_LIST_STATE.LOADED
}

/** 首屏覆盖、后续页追加；入参非数组时按空页处理（不抛出、不丢已加载记录）。 */
export function mergeInvitees(prev, incoming, page) {
  const list = Array.isArray(incoming) ? incoming : []
  if (page === 0) return list.slice()
  return (Array.isArray(prev) ? prev : []).concat(list)
}

/** 已加载条数小于总条数才还有下一页（需求 7.13 的停止条件）。 */
export function hasMoreInvitees(loaded, total) {
  return toCount(loaded) < toCount(total)
}

/**
 * 上拉触底时的分页决策（需求 7.13）：
 * 仅在「列表已加载成功 ∧ 没有在追加中 ∧ 已加载条数 < 总条数」时发起下一页请求，
 * 否则一律不发（已加载条数等于总条数后停止发起后续列表请求）。
 */
export function nextInviteListRequest(view) {
  const v = view || {}
  const blocked =
    v.listState !== INVITE_LIST_STATE.LOADED ||
    v.loadingMore === true ||
    !hasMoreInvitees(v.loaded, v.total)
  if (blocked) return { shouldRequest: false, page: null }
  return { shouldRequest: true, page: v.nextPage }
}
