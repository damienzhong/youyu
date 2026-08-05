/**
 * 提醒设置页的纯函数与常量（对齐 utils/streak.js 的既有写法）。
 * 只做与 UI 框架无关的取值判定，便于单测（任务 11.3）直接覆盖，页面里不再重复内联校验逻辑。
 */

/** 单请求客户端超时：3000ms 内无响应即判失败，不自动重试（需求 10.9）。 */
export const REMINDER_TIMEOUT_MS = 3000

/** 提醒条数上限（后端需求 1.6 为 10 条；前端仅用于「新增」入口的禁用提示，真正拦截以后端为准）。 */
export const REMINDER_MAX = 10

/** 剩余订阅次数累积上限（后端需求 5.3）。 */
export const QUOTA_MAX = 50

/** 频率三选一：value 与后端枚举一一对应（区分大小写），label 为中文展示（需求 10.3）。 */
export const FREQUENCY_OPTIONS = [
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKDAY', label: '工作日' },
  { value: 'WEEKEND', label: '周末' }
]

/** 频率枚举值 → 中文标签；未知值回退原值，避免渲染空白。 */
export function frequencyLabel(freq) {
  const hit = FREQUENCY_OPTIONS.find((o) => o.value === freq)
  return hit ? hit.label : String(freq ?? '')
}

/** 频率是否为合法三选一之一（区分大小写）。 */
export function isValidFrequency(freq) {
  return FREQUENCY_OPTIONS.some((o) => o.value === freq)
}

/**
 * 本地时间校验（需求 10.3）：须为零填充两位小时 + 两位分钟的 HH:mm，
 * 且小时 0–23、分钟 0–59。picker mode="time" 正常只吐 HH:mm，这里仍做一次防御式校验。
 */
export function isValidTime(hhmm) {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(String(hhmm ?? ''))
}

/**
 * 提交前整体本地校验（需求 10.3、10.4）：频率已从三项中选定且时间在合法范围内。
 * 返回 { ok, field }，field 指示首个不合法项，供页面就地提示（不合法则不发请求）。
 */
export function validateReminderForm({ frequency, remindTime }) {
  if (!isValidFrequency(frequency)) return { ok: false, field: 'frequency' }
  if (!isValidTime(remindTime)) return { ok: false, field: 'remindTime' }
  return { ok: true, field: null }
}

/** 剩余订阅次数兜底：非数字 / NaN / 负数折成 0，超过上限夹到上限（需求 10.7）。 */
export function normalizeQuota(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.min(Math.floor(v), QUOTA_MAX)
}
