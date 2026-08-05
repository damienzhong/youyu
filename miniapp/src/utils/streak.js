/**
 * 连续记账页的客户端纯逻辑集中处（需求 9.4、9.5、9.6、9.7、9.8、9.9、9.12、9.15、9.16、9.18）。
 *
 * 设计约束（与 utils/growth.js、utils/invite.js 同构）：
 * - 本模块只做算术与状态判定，不引入页面、请求或 store 依赖，因此能在纯 node 环境下用
 *   vitest + fast-check 直接测（页面 .vue 里的逻辑测不到，抽成纯函数才能用属性测试锁住边界）。
 * - 所有函数对畸形入参一律安全降级（返回 30 格全未打卡 / '' / false 或兜底文案），绝不抛出：
 *   连续记账页是次要功能，字段异常不允许把整页搞崩（需求 9.10）。
 * - 打卡格子的自然日边界一律以 `Asia/Shanghai` 固定 UTC+8 偏移换算，不随设备时区变化，
 *   也不用 toLocaleDateString('zh-CN')（其结果随运行环境默认时区漂移，需求 9.6）。
 * - miniapp 内不实现第二套连续段划分：某格是否已打卡只看它是否落在服务端下发的某个区间的
 *   [startDate, endDate] 闭区间内（需求 9.15）；里程碑数值一律取接口下发值，不写死（需求 9.16）。
 */

/** 历史区间分页大小：首屏 20 条，每次触底追加 20 条（需求 9.2、9.9）。 */
export const STREAK_PAGE_SIZE = 20

/** 打卡格子数量：恒为 30，以判定日为末格向前覆盖 30 个连续自然日（需求 9.6）。 */
export const STREAK_CELL_COUNT = 30

/** 下拉刷新的客户端节流窗口：距上次请求发出不足该毫秒数则不再发请求（需求 9.12）。 */
export const STREAK_REFRESH_THROTTLE_MS = 3000

/** 连续记账请求的客户端超时：超过该毫秒数无响应即进入失败态并结束下拉动效（需求 9.10、9.12）。 */
export const STREAK_TIMEOUT_MS = 3000

/**
 * 反挫败感禁词表（需求 9.4）：连续记账页全部可见文案不得含这四个词。
 * 本模块产出的所有文案（restartHint / milestoneText）都不含它们，页面文案也须自查。
 */
export const STREAK_FORBIDDEN_WORDS = ['归零', '清空', '失败', '中断']

/** 一个自然日的毫秒数。 */
const MS_PER_DAY = 86400000

/** Asia/Shanghai 固定偏移 UTC+08:00（不随夏令时变化，与服务端同一口径）。 */
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000

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

/**
 * 把 `YYYY-MM-DD` 日期串解析为 epoch day（自 1970-01-01 起的整数天数），不可解析时返回 null。
 * 借 UTC 午夜避开任何时区换算；round-trip 校验挡掉 `2024-02-30` 这类看似合法实则越界的串。
 */
function dateStrToEpochDay(s) {
  if (typeof s !== 'string') return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim())
  if (!m) return null
  const y = Number(m[1])
  const mo = Number(m[2])
  const d = Number(m[3])
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
  const ms = Date.UTC(y, mo - 1, d)
  if (!Number.isFinite(ms)) return null
  const dt = new Date(ms)
  // 越界日期（如 2 月 30 日）会被 Date.UTC 归一化到别的日期，round-trip 不相等即判非法。
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) return null
  return Math.floor(ms / MS_PER_DAY)
}

/** 把 epoch day 转回 `YYYY-MM-DD`；借 UTC 午夜读取，结果与设备时区无关。 */
function epochDayToDateStr(epochDay) {
  const dt = new Date(epochDay * MS_PER_DAY)
  const y = dt.getUTCFullYear()
  const mo = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const d = String(dt.getUTCDate()).padStart(2, '0')
  return `${y}-${mo}-${d}`
}

/**
 * 近 30 天打卡格子（需求 9.6、9.7、9.15）。
 *
 * - 末格日期 = `nowMs` 按 `Asia/Shanghai` 固定 UTC+8 偏移折算所得的自然日（判定日），
 *   **不随设备时区变化**；30 格自末格向前覆盖 30 个连续自然日，按自然日升序返回、日期两两不同。
 * - 某格已打卡 ⟺ 该格自然日落在某已加载区间项的 [startDate, endDate] 闭区间内；
 *   段边界一律取服务端下发值，miniapp 内不实现第二套段划分。
 * - `segments` 非数组、元素畸形、日期串不可解析时该项被跳过；全部畸形即 30 格全未打卡。
 * - `nowMs` 不可解析时降级用设备当前时刻，**仍恒返回 30 项**、绝不抛出。
 *
 * @param {number} nowMs   设备当前时刻毫秒数
 * @param {Array}  segments 服务端下发的历史区间项数组，每项含 startDate / endDate 日期串
 * @returns {Array<{date: string, checked: boolean}>} 恒 30 项，日期升序、两两不同
 */
export function checkinCells(nowMs, segments) {
  const now = toFiniteOrNull(nowMs)
  const baseMs = now === null ? Date.now() : now
  // 判定日的 epoch day：把时刻平移到 UTC+8 后取整天（等价于 Asia/Shanghai 的自然日）。
  const lastEpochDay = Math.floor((baseMs + SHANGHAI_OFFSET_MS) / MS_PER_DAY)

  const ranges = []
  if (Array.isArray(segments)) {
    for (const seg of segments) {
      if (!seg || typeof seg !== 'object') continue
      const start = dateStrToEpochDay(seg.startDate)
      const end = dateStrToEpochDay(seg.endDate)
      if (start === null || end === null) continue
      // 服务端保证 start <= end，此处仍取 min/max 兜底畸形边界，避免闭区间判定恒为假。
      ranges.push([Math.min(start, end), Math.max(start, end)])
    }
  }

  const cells = []
  for (let i = STREAK_CELL_COUNT - 1; i >= 0; i--) {
    const epochDay = lastEpochDay - i
    let checked = false
    for (let r = 0; r < ranges.length; r++) {
      if (epochDay >= ranges[r][0] && epochDay <= ranges[r][1]) {
        checked = true
        break
      }
    }
    cells.push({ date: epochDayToDateStr(epochDay), checked })
  }
  return cells
}

/**
 * 断链后的重新开始引导文案（需求 9.4）。
 * `broken` 为真且 `lastStreakDays` 非空时返回「上次连续 N 天，今天重新开始」，否则返回 ''。
 * 文案刻意不含 STREAK_FORBIDDEN_WORDS 中任何一个词（反挫败感靠文案而非改数字）。
 */
export function restartHint(overview) {
  const o = overview || {}
  if (o.broken !== true) return ''
  const last = toFiniteOrNull(o.lastStreakDays)
  if (last === null) return ''
  return `上次连续 ${last} 天，今天重新开始`
}

/**
 * 里程碑进度文案（需求 9.8、9.16）。
 * - `nextMilestone` 为空 → 「已达成全部里程碑」；
 * - `nextMilestone` 非空且 `daysToNextMilestone >= 1` → 「距 N 天里程碑还差 M 天」；
 * - `daysToNextMilestone` 小于 1（含 0 / 负数 / 不可解析）→ 按空处理，返回 '' 不展示数值。
 * 里程碑数值一律取接口下发值，绝不写死 7 / 30 / 100 / 365。
 */
export function milestoneText(overview) {
  const o = overview || {}
  const next = toFiniteOrNull(o.nextMilestone)
  if (next === null) return '已达成全部里程碑'
  const days = toFiniteOrNull(o.daysToNextMilestone)
  if (days === null || days < 1) return ''
  return `距 ${next} 天里程碑还差 ${days} 天`
}

/**
 * 首次记账用户判定（需求 9.5）：累计记账天数与段总数都恰为 0。
 * 刻意不看「记账日历为空」——日历不是接口字段；两者在服务端等价。
 */
export function isFirstTimeUser(overview) {
  const o = overview || {}
  return o.totalRecordDays === 0 && o.segmentCount === 0
}

/** 是否还有下一页：已加载条数 < 总条数（需求 9.9、9.18 的停止条件）。 */
export function hasMoreSegments(loadedCount, total) {
  return toCount(loadedCount) < toCount(total)
}

/**
 * 下拉刷新节流判定（需求 9.12），语义与 utils/growth.js 的同名函数一致：
 * 距上次请求发出已满 3000ms 才放行。
 * - lastRequestAtMs 缺省 / 不可解析（尚未请求过）→ 放行（返回 true）。
 * - nowMs 不可解析 → 安全降级为不刷新（返回 false），不用一个 NaN 差值误判、也不抛出。
 */
export function shouldRefresh(lastRequestAtMs, nowMs) {
  const last = toFiniteOrNull(lastRequestAtMs)
  if (last === null) return true
  const current = toFiniteOrNull(nowMs)
  if (current === null) return false
  return current - last >= STREAK_REFRESH_THROTTLE_MS
}
