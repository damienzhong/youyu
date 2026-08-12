/**
 * 周期记账展示层工具：把周期规则的频率配置 + 金额 + 收支方向渲染成一句中文摘要，
 * 如「每月 5 日 · 支出 ¥3,000.00」「每周 周一、周三 · 收入 ¥200.00」。
 *
 * 规则响应字段见后端 RecurringRuleResponse：
 *   { type:'expense'|'income', amount, frequency:'DAILY'|'WEEKLY'|'MONTHLY'|'YEARLY',
 *     weeklyDays:[1..7], monthDay, monthEnd, yearMonth, yearDay,
 *     startDate, endCondition:'NEVER'|'UNTIL_DATE'|'COUNT', untilDate, countN, status }
 * weeklyDays 中 1=周一 … 7=周日（与后端 Asia/Shanghai 口径一致）。
 */

import { formatAmount } from './format'

const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 单个星期几（1..7）→ 中文标签；越界回退空串。 */
export function weekdayLabel(day) {
  const n = Number(day)
  return WEEKDAY_LABELS[n - 1] || ''
}

/**
 * 频率部分的中文摘要（不含金额 / 方向）：
 * - DAILY  → 「每天」
 * - WEEKLY → 「每周 周一、周三、周五」（按 1..7 升序去重）
 * - MONTHLY→ 月末标记「每月 月末」，否则「每月 D 日」
 * - YEARLY → 「每年 M月D日」
 * 频率或子字段缺失时回退「周期」，保证不抛异常。
 */
export function frequencyLabel(rule) {
  if (!rule || !rule.frequency) return '周期'
  switch (rule.frequency) {
    case 'DAILY':
      return '每天'
    case 'WEEKLY': {
      const days = Array.isArray(rule.weeklyDays) ? rule.weeklyDays : []
      const labels = [...new Set(days.map(Number))]
        .filter((n) => n >= 1 && n <= 7)
        .sort((a, b) => a - b)
        .map(weekdayLabel)
      return labels.length ? `每周 ${labels.join('、')}` : '每周'
    }
    case 'MONTHLY':
      return rule.monthEnd ? '每月 月末' : `每月 ${rule.monthDay} 日`
    case 'YEARLY':
      return `每年 ${rule.yearMonth}月${rule.yearDay}日`
    default:
      return '周期'
  }
}

/** 收支方向中文标签：expense → 支出，income → 收入。 */
export function directionLabel(type) {
  return type === 'income' ? '收入' : '支出'
}

/**
 * 完整摘要：「频率 · 方向 ¥金额」，如「每月 5 日 · 支出 ¥3,000.00」。
 * 金额沿用 formatAmount（千分位 + 两位小数），前缀 ¥。
 */
export function ruleSummary(rule) {
  if (!rule) return ''
  return `${frequencyLabel(rule)} · ${directionLabel(rule.type)} ¥${formatAmount(rule.amount)}`
}

/**
 * 结束条件中文摘要（副信息，可空）：
 * - NEVER      → 「长期有效」
 * - UNTIL_DATE → 「至 YYYY-MM-DD」
 * - COUNT      → 「共 N 次」
 */
export function endConditionLabel(rule) {
  if (!rule) return ''
  switch (rule.endCondition) {
    case 'UNTIL_DATE':
      return rule.untilDate ? `至 ${String(rule.untilDate).slice(0, 10)}` : ''
    case 'COUNT':
      return rule.countN != null ? `共 ${rule.countN} 次` : ''
    case 'NEVER':
      return '长期有效'
    default:
      return ''
  }
}

/** 规则状态中文标签：ACTIVE → 启用中，PAUSED → 已暂停。 */
export function statusLabel(status) {
  return status === 'PAUSED' ? '已暂停' : '启用中'
}

// ── 新建 / 编辑表单（pages/recurringedit）纯逻辑 ────────────────────
// 页面表单态 → 后端 RecurringRuleRequest 的构造、提交前的本地校验、后端错误码→字段提示映射。
// 全部为不依赖 uni API 的纯函数，便于单测覆盖（见 recurring.editform.test.js）。

/** 收支类型选项（分段控件）。 */
export const TYPE_OPTIONS = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' }
]

/** 频率选项（分段控件）。 */
export const FREQUENCY_OPTIONS = [
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
  { value: 'YEARLY', label: '每年' }
]

/** 结束条件选项（分段控件）。 */
export const END_CONDITION_OPTIONS = [
  { value: 'NEVER', label: '永不' },
  { value: 'UNTIL_DATE', label: '到某日' },
  { value: 'COUNT', label: '共 N 次' }
]

/**
 * 入账方式选项（分段控件，recurring-auto-post 需求 7.1、7.2）：
 * 默认「待确认」，与后端默认 CONFIRM 一致。
 */
export const POST_MODE_OPTIONS = [
  { value: 'CONFIRM', label: '待确认' },
  { value: 'AUTO', label: '自动入账' }
]

/** 入账方式说明文案：仅「自动入账」需给出（到期自动记账并通知；目标失效会转为待确认）。 */
export const POST_MODE_HINTS = {
  AUTO: '到期自动记账并通知你；若分类 / 账户已删除会自动转为待确认。',
  CONFIRM: '到期生成一条待确认记账，需你手动确认后才入账。'
}

/** 星期几选项（1=周一 … 7=周日），供每周多选。 */
export const WEEKDAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7].map((n) => ({ value: n, label: weekdayLabel(n) }))

const AMOUNT_MIN = 0.01
const AMOUNT_MAX = 999999999.99
const NOTE_MAX = 200
const COUNT_MIN = 1
const COUNT_MAX = 9999

/**
 * 解析金额文本为数值（元）：要求非空、仅数字与至多 2 位小数、在 0.01–999999999.99（含端点）。
 * 不合法返回 null。
 */
export function parseRuleAmount(text) {
  const s = String(text == null ? '' : text).trim()
  if (!s || !/^\d+(\.\d{1,2})?$/.test(s)) return null
  const n = Number(s)
  if (!Number.isFinite(n) || n < AMOUNT_MIN || n > AMOUNT_MAX) return null
  return n
}

/** 星期几集合规范化：转数字、去重、过滤 1–7 以外、升序。 */
export function normalizeWeeklyDays(days) {
  return [...new Set((Array.isArray(days) ? days : []).map(Number))]
    .filter((n) => Number.isInteger(n) && n >= 1 && n <= 7)
    .sort((a, b) => a - b)
}

/**
 * 提交前本地校验（对齐需求 1.2–1.8、2.10）：返回 { ok:true } 或 { ok:false, field, message }。
 * field 取值：type / amount / category / account / note / frequency / endCondition。
 * 校验通过不代表后端一定接受（分类 / 账户归属仍由后端判定），仅拦住明显不合法的输入。
 */
export function validateRuleForm(form) {
  const f = form || {}
  if (f.type !== 'expense' && f.type !== 'income') {
    return { ok: false, field: 'type', message: '请选择支出或收入' }
  }
  if (parseRuleAmount(f.amountText) == null) {
    return { ok: false, field: 'amount', message: '请输入 0.01–999999999.99 之间、至多两位小数的金额' }
  }
  if (f.categoryId == null) {
    return { ok: false, field: 'category', message: '请选择分类' }
  }
  if (f.accountId == null) {
    return { ok: false, field: 'account', message: '请选择账户' }
  }
  if (f.note && String(f.note).length > NOTE_MAX) {
    return { ok: false, field: 'note', message: `备注不能超过 ${NOTE_MAX} 个字符` }
  }
  const freqErr = validateFrequency(f)
  if (freqErr) return freqErr
  const endErr = validateEndCondition(f)
  if (endErr) return endErr
  return { ok: true }
}

function validateFrequency(f) {
  switch (f.frequency) {
    case 'DAILY':
      return null
    case 'WEEKLY':
      if (normalizeWeeklyDays(f.weeklyDays).length === 0) {
        return { ok: false, field: 'frequency', message: '请至少选择一个星期几' }
      }
      return null
    case 'MONTHLY':
      if (f.monthEnd) return null
      if (!Number.isInteger(Number(f.monthDay)) || Number(f.monthDay) < 1 || Number(f.monthDay) > 31) {
        return { ok: false, field: 'frequency', message: '请选择每月的日期（1–31 或月末）' }
      }
      return null
    case 'YEARLY': {
      const m = Number(f.yearMonth)
      const d = Number(f.yearDay)
      if (!Number.isInteger(m) || m < 1 || m > 12 || !Number.isInteger(d) || d < 1 || d > 31) {
        return { ok: false, field: 'frequency', message: '请选择每年的月份与日期' }
      }
      return null
    }
    default:
      return { ok: false, field: 'frequency', message: '请选择频率' }
  }
}

function validateEndCondition(f) {
  switch (f.endCondition) {
    case 'NEVER':
      return null
    case 'UNTIL_DATE':
      if (!f.untilDate) {
        return { ok: false, field: 'endCondition', message: '请选择结束日期' }
      }
      if (f.startDate && String(f.untilDate) < String(f.startDate)) {
        return { ok: false, field: 'endCondition', message: '结束日期不能早于开始日期' }
      }
      return null
    case 'COUNT': {
      const n = Number(f.countN)
      if (!Number.isInteger(n) || n < COUNT_MIN || n > COUNT_MAX) {
        return { ok: false, field: 'endCondition', message: `次数需为 ${COUNT_MIN}–${COUNT_MAX} 之间的整数` }
      }
      return null
    }
    default:
      return { ok: false, field: 'endCondition', message: '请选择结束条件' }
  }
}

/**
 * 表单态 → 后端 RecurringRuleRequest：按所选频率 / 结束条件只带对应子字段。
 * 金额规范化为 2 位小数字符串；备注去空白（空则不带）；startDate 为空则不带（后端默认取创建当日）。
 * 调用前应先 validateRuleForm 通过。
 */
export function buildRulePayload(form) {
  const f = form || {}
  const amount = parseRuleAmount(f.amountText)
  const payload = {
    type: f.type,
    amount: amount != null ? amount.toFixed(2) : String(f.amountText || ''),
    categoryId: f.categoryId,
    accountId: f.accountId,
    frequency: f.frequency,
    endCondition: f.endCondition,
    // 入账方式：默认 CONFIRM（待确认），与后端默认一致（recurring-auto-post 需求 1.2、7.2）。
    postMode: f.postMode === 'AUTO' ? 'AUTO' : 'CONFIRM'
  }
  const note = f.note != null ? String(f.note).trim() : ''
  if (note) payload.note = note

  switch (f.frequency) {
    case 'WEEKLY':
      payload.weeklyDays = normalizeWeeklyDays(f.weeklyDays)
      break
    case 'MONTHLY':
      if (f.monthEnd) {
        payload.monthEnd = true
      } else {
        payload.monthEnd = false
        payload.monthDay = Number(f.monthDay)
      }
      break
    case 'YEARLY':
      payload.yearMonth = Number(f.yearMonth)
      payload.yearDay = Number(f.yearDay)
      break
    default:
      break
  }

  switch (f.endCondition) {
    case 'UNTIL_DATE':
      payload.untilDate = f.untilDate
      break
    case 'COUNT':
      payload.countN = Number(f.countN)
      break
    default:
      break
  }

  if (f.startDate) payload.startDate = f.startDate
  return payload
}

/**
 * 后端错误码 → { field, message }，用于把校验错误落到对应字段提示 / toast。
 * 复用既有 AMOUNT_INVALID / NOTE_TOO_LONG，并映射周期记账专有码。
 * 未识别的错误回退其 message（无则通用提示），field 为 null（走 toast）。
 */
export function mapRuleError(err) {
  const code = err && err.code
  const message = (err && err.message) || ''
  switch (code) {
    case 'RECURRING_RULE_INVALID':
      return { field: err.field || null, message: message || '记账信息有误，请检查后重试' }
    case 'AMOUNT_INVALID':
      return { field: 'amount', message: message || '金额需在 0.01–999999999.99 之间且至多两位小数' }
    case 'NOTE_TOO_LONG':
      return { field: 'note', message: message || '备注不能超过 200 个字符' }
    case 'RECURRING_FREQUENCY_INVALID':
      return { field: 'frequency', message: message || '频率配置有误，请重新选择' }
    case 'RECURRING_END_CONDITION_INVALID':
      return { field: 'endCondition', message: message || '结束条件有误，请重新选择' }
    case 'RECURRING_POST_MODE_INVALID':
      return { field: 'postMode', message: message || '入账方式有误，请重新选择' }
    default:
      return { field: null, message: message || '保存失败，请稍后重试' }
  }
}

/**
 * 规则详情（RecurringRuleResponse）→ 编辑态表单初值。用于「编辑」进入时回填。
 * monthEnd 展开为 monthMode（DAY/END）便于分段控件；缺失子字段给出合理缺省。
 */
export function ruleToForm(rule) {
  const r = rule || {}
  return {
    type: r.type === 'income' ? 'income' : 'expense',
    amountText: r.amount != null ? String(r.amount) : '',
    categoryId: r.categoryId != null ? r.categoryId : null,
    accountId: r.accountId != null ? r.accountId : null,
    note: r.note || '',
    frequency: r.frequency || 'MONTHLY',
    weeklyDays: normalizeWeeklyDays(r.weeklyDays),
    monthEnd: !!r.monthEnd,
    monthDay: r.monthDay != null ? r.monthDay : 1,
    yearMonth: r.yearMonth != null ? r.yearMonth : 1,
    yearDay: r.yearDay != null ? r.yearDay : 1,
    startDate: r.startDate ? String(r.startDate).slice(0, 10) : '',
    endCondition: r.endCondition || 'NEVER',
    untilDate: r.untilDate ? String(r.untilDate).slice(0, 10) : '',
    countN: r.countN != null ? r.countN : '',
    postMode: r.postMode === 'AUTO' ? 'AUTO' : 'CONFIRM'
  }
}

// ── 待确认项列表（pages/recurringpending）纯逻辑 ───────────────────
// 分组 / 排序、批量结果摘要、错误码→中文提示、修改后确认的 overrides 构造。
// 全部为不依赖 uni API 的纯函数，便于单测覆盖（见 recurring.pending.test.js）。

/**
 * 待确认项错误码 → 中文提示（确认 / 跳过单条与批量逐条失败共用）。
 * 覆盖任务约定的 5 个码；未识别回退通用提示。
 */
const PENDING_ITEM_ERROR_LABELS = {
  RECURRING_ITEM_ALREADY_PROCESSED: '该项已处理（已确认或已跳过）',
  RECURRING_ITEM_TARGET_MISSING: '分类或账户已不存在，请修改后再确认',
  NOT_FOUND: '待确认项不存在或无权访问',
  AMOUNT_INVALID: '金额需在 0.01–999999999.99 之间且至多两位小数',
  NOTE_TOO_LONG: '备注不能超过 200 个字符'
}

/** 待确认项错误码 → 中文提示；未识别回退传入的兜底或通用文案。 */
export function pendingItemErrorLabel(code, fallback) {
  return PENDING_ITEM_ERROR_LABELS[code] || fallback || '操作失败，请稍后重试'
}

/**
 * 把待确认项列表按期次到期日升序分组：返回 [{ date, items }]，组内保持传入顺序
 * （后端已按「到期日升序 → 规则创建时间升序 → 项 id 升序」返回，需求 5.2），组间按日期升序。
 * date 取 occurrenceDate 的 YYYY-MM-DD 前缀；缺失到期日归入空串组置末。
 */
export function groupPendingItemsByDate(items) {
  const list = Array.isArray(items) ? items : []
  const groups = new Map()
  for (const it of list) {
    const date = it && it.occurrenceDate ? String(it.occurrenceDate).slice(0, 10) : ''
    if (!groups.has(date)) groups.set(date, [])
    groups.get(date).push(it)
  }
  return [...groups.entries()]
    .sort((a, b) => {
      if (a[0] === b[0]) return 0
      if (!a[0]) return 1
      if (!b[0]) return -1
      return a[0] < b[0] ? -1 : 1
    })
    .map(([date, groupItems]) => ({ date, items: groupItems }))
}

/**
 * 批量结果摘要文案：如「成功 3 · 失败 1」；全部成功则「全部 3 条已处理」，全部失败则「3 条均未成功」。
 * 入参为后端批量响应 { successCount, failureCount }。
 */
export function batchResultSummary(result) {
  const s = Number(result && result.successCount) || 0
  const f = Number(result && result.failureCount) || 0
  if (f === 0) return `全部 ${s} 条已处理`
  if (s === 0) return `${f} 条均未成功`
  return `成功 ${s} · 失败 ${f}`
}

// ── 首页「待确认」入口角标（pages/index）纯逻辑 ─────────────────────
// 由 fetchRecurringPendingItems() 返回的当前账本 PENDING 列表推算角标数字与展示文案。
// 纯函数、不依赖 uni API，任何非数组 / 脏数据都安全降级为 0（隐藏角标），便于单测覆盖。

/**
 * 待确认项列表 → 当前账本 PENDING 期数：仅计入非空且带 id 的条目，
 * 与列表页 `res.filter((it) => it && it.id != null)` 口径一致；非数组返回 0。
 */
export function pendingCountOf(items) {
  if (!Array.isArray(items)) return 0
  return items.filter((it) => it && it.id != null).length
}

/**
 * 角标展示文案：0 返回空串（调用方据此隐藏角标），超过 99 显示 '99+'，其余为数字串。
 */
export function pendingBadgeText(count) {
  const n = Number(count)
  if (!Number.isFinite(n) || n <= 0) return ''
  return n > 99 ? '99+' : String(Math.floor(n))
}

/**
 * 后端批量响应的 failed 列表 → 带中文原因的明细：[{ itemId, errorCode, message }]。
 * 供列表页把逐条失败原因映射为可读提示（需求 5.6）。
 */
export function describeBatchFailures(result) {
  const failed = (result && Array.isArray(result.failed)) ? result.failed : []
  return failed.map((f) => ({
    itemId: f && f.itemId,
    errorCode: f && f.errorCode,
    message: pendingItemErrorLabel(f && f.errorCode)
  }))
}

/**
 * 修改后确认的 overrides 构造：对比编辑态表单与待确认项快照，仅收敛「被改动」的字段，
 * 供 confirmRecurringPendingItem(id, overrides) 使用（需求 4.3；类型不可改，不参与 overrides）。
 * - amount：文本合法（parseRuleAmount）且与快照金额（数值口径）不同 → 2 位小数字符串
 * - categoryId / accountId：与快照不同 → 带上
 * - note：去空白后与快照（空串归一）不同 → 带上（空串表示清空备注）
 * - occurredAt：所选日期与期次到期日不同 → `${date}T00:00:00`
 * 返回可能为空对象 {}（等价于直接确认）。
 */
export function buildConfirmOverrides(form, item) {
  const f = form || {}
  const snap = item || {}
  const overrides = {}

  const amt = parseRuleAmount(f.amountText)
  if (amt != null) {
    const snapAmt = snap.amount != null ? Number(snap.amount) : null
    if (snapAmt == null || Math.abs(amt - snapAmt) >= 0.005) {
      overrides.amount = amt.toFixed(2)
    }
  }

  if (f.categoryId != null && f.categoryId !== snap.categoryId) {
    overrides.categoryId = f.categoryId
  }
  if (f.accountId != null && f.accountId !== snap.accountId) {
    overrides.accountId = f.accountId
  }

  const note = f.note != null ? String(f.note).trim() : ''
  const snapNote = snap.note != null ? String(snap.note).trim() : ''
  if (note !== snapNote) {
    overrides.note = note
  }

  const date = f.occurredDate ? String(f.occurredDate).slice(0, 10) : ''
  const snapDate = snap.occurrenceDate ? String(snap.occurrenceDate).slice(0, 10) : ''
  if (date && date !== snapDate) {
    overrides.occurredAt = `${date}T00:00:00`
  }

  return overrides
}
