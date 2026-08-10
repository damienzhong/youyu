/**
 * AA 账本前端计算工具：金额一律以「分」为最小单位处理，避免浮点误差；
 * 与后端 AaMath 口径保持一致（均分余数校正、分摊守恒、本笔影响拆分）。
 */

/** 元（数值/字符串）→ 分（四舍五入的整数）。非法输入回退 0。 */
export function toCents(yuan) {
  const n = typeof yuan === 'number' ? yuan : Number(yuan)
  if (!Number.isFinite(n)) return 0
  return Math.round(n * 100)
}

/** 分 → 元（两位小数字符串），供展示。 */
export function centsToYuan(cents) {
  const n = Number(cents) || 0
  return (n / 100).toFixed(2)
}

/**
 * 均分：把 totalCents 平均分成 n 份，以「分」守恒——base = ⌊total/n⌋，
 * 前 (total − base·n) 份各 +1，保证各份之和恰等于 totalCents（与后端 AaMath.splitEven 一致）。
 * @returns {number[]} 长度 n 的分额数组；n ≤ 0 或 total < 0 返回空数组。
 */
export function splitEvenCents(totalCents, n) {
  if (n <= 0 || totalCents < 0) return []
  const base = Math.floor(totalCents / n)
  const rem = totalCents - base * n // 0 ≤ rem < n
  const out = []
  for (let i = 0; i < n; i++) out.push(base + (i < rem ? 1 : 0))
  return out
}

/**
 * 按参与人顺序做均分，返回 { [userId]: cents } 映射（Σ = totalCents）。
 * @param {number} totalCents 总额（分）
 * @param {Array<number|string>} participantIds 参与人 user_id 列表（保序）
 */
export function evenSharesByUser(totalCents, participantIds) {
  const ids = Array.isArray(participantIds) ? participantIds : []
  const parts = splitEvenCents(totalCents, ids.length)
  const map = {}
  ids.forEach((id, i) => {
    map[id] = parts[i] || 0
  })
  return map
}

/** 自定义分摊求和（分）。传入 { [userId]: cents } 或数值数组。 */
export function sumShares(shares) {
  if (Array.isArray(shares)) {
    return shares.reduce((a, b) => a + (Number(b) || 0), 0)
  }
  return Object.values(shares || {}).reduce((a, b) => a + (Number(b) || 0), 0)
}

/**
 * 校验自定义分摊：各份非负且之和等于总额。
 * @returns {boolean}
 */
export function isValidCustomSplit(totalCents, shares) {
  const values = Array.isArray(shares) ? shares : Object.values(shares || {})
  let sum = 0
  for (const s of values) {
    const c = Number(s)
    if (!Number.isFinite(c) || c < 0) return false
    sum += c
  }
  return sum === totalCents
}

/**
 * 本笔对付款人的影响拆分（付款人为本人时展示）。
 * - 付款账户扣款 = 实付全额（真实现金流出）。
 * - 我的消费 = 付款人自身分摊额（计入支出统计）。
 * - 借出（应收）= 实付额 − 自身分摊额（≥0，形成对他人的应收，不计消费）。
 * @param {number} totalCents 实付总额（分）
 * @param {number} payerShareCents 付款人自身分摊额（分；付款人未参与分摊时为 0）
 * @returns {{ accountDeductCents:number, myConsumptionCents:number, lentCents:number }}
 */
export function payerImpact(totalCents, payerShareCents) {
  const total = Math.max(0, Number(totalCents) || 0)
  const mine = Math.max(0, Number(payerShareCents) || 0)
  const lent = Math.max(0, total - mine)
  return { accountDeductCents: total, myConsumptionCents: mine, lentCents: lent }
}

// ---------- 归档 / 解档生命周期（需求 8.3、8.4、8.5）----------

/** 账本是否已归档（只读）。兼容后端 LedgerResponse.archived 布尔字段。 */
export function isArchived(ledger) {
  return !!(ledger && ledger.archived === true)
}

/**
 * 是否可对该账本执行归档 / 解档：仅 AA 账本、且当前用户为 OWNER。
 * 个人 / 家庭账本不支持归档（后端 AA_ARCHIVE_NOT_SUPPORTED）。
 */
export function canToggleArchive(ledger) {
  if (!ledger) return false
  return ledger.type === 'AA' && ledger.role === 'OWNER'
}

/** 归档 / 解档操作文案：已归档 → 「解档」，否则 → 「归档账本」。 */
export function archiveActionLabel(ledger) {
  return isArchived(ledger) ? '解档' : '归档账本'
}

/**
 * 判断后端错误是否为「未结清、需二次确认后强制归档」（需求 8.4）。
 * 归档未结清的 AA 账本且未带 force 时，后端返回 409 AA_LEDGER_UNSETTLED。
 */
export function isUnsettledArchiveError(err) {
  return !!(err && err.code === 'AA_LEDGER_UNSETTLED')
}
