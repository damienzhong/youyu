/**
 * 截图记账的纯函数部分（平台无关，不调接口）：合并多张截图的识别结果、跨图去重、同批退款抵消、
 * 决定每笔默认记不记、分组。页面只负责取图 / 调接口 / 渲染。
 */

/** 与后端 CategoryClassifier.merchantKey 同口径：去括号门店信息、去空白、小写。 */
export function merchantKey(s) {
  return String(s || '').replace(/[(（【[][^)）】\]]*[)）】\]]/g, '').replace(/\s+/g, '').toLowerCase()
}

/** 退款行的商户名常带「-退款」「退款-」：去掉后再和原支出比对。 */
function refundBase(s) {
  return merchantKey(s).replace(/[-—_·]?退款[-—_·]?/g, '')
}

function minuteOf(t) {
  return t ? String(t).slice(0, 16) : ''
}

/**
 * 合并多张截图的识别结果。
 * @param {Array} shots 后端 recognize 返回的 shots（可来自多次请求，按截图顺序）
 * @returns {{ rows: Array, meta: { images, recognized, overlap, offsetPairs, transfers, duplicates } }}
 *
 * 每个 row：{ key, shot, direction, type:'expense'|'income'|null, amount, occurredAt, dateOnly,
 *   amountUncertain, merchant, item, note, categoryId, categorySource, duplicate,
 *   state:'record'|'skip', reason, flags:{ amount, category, time } }
 * reason：'' | 'duplicate'（账本里已有）| 'offset'（退款与原支出抵消）| 'transfer'（不算消费）
 */
export function mergeShots(shots) {
  const rows = []
  const seen = new Map()
  let overlap = 0
  ;(shots || []).forEach((s, si) => {
    if (!s || s.status !== 'OK') return
    for (const e of s.entries || []) {
      // 跨截图去重：两张滚动截图的重叠行（同方向、同金额、同一分钟、同商户）
      const sig = [e.direction, e.amount, minuteOf(e.occurredAt), merchantKey(e.merchant)].join('|')
      if (e.occurredAt && seen.has(sig) && seen.get(sig) !== si) {
        overlap++
        continue
      }
      seen.set(sig, si)
      rows.push(toRow(e, si, rows.length))
    }
  })

  // 同批退款抵消：退款找同商户、时间不晚于退款、金额不小于退款的原支出
  let offsetPairs = 0
  for (const r of rows) {
    if (r.direction !== 'REFUND') continue
    const base = refundBase(r.merchant)
    const orig = rows.find((x) =>
      x.direction === 'EXPENSE' && x.reason === '' &&
      base && (merchantKey(x.merchant) === base || merchantKey(x.merchant).startsWith(base) || base.startsWith(merchantKey(x.merchant))) &&
      Number(x.amount) >= Number(r.amount) &&
      (!x.occurredAt || !r.occurredAt || x.occurredAt <= r.occurredAt))
    if (!orig) continue
    offsetPairs++
    r.state = 'skip'
    r.reason = 'offset'
    r.offsetWith = orig.key
    const left = Math.round((Number(orig.amount) - Number(r.amount)) * 100)
    if (left <= 0) {
      orig.state = 'skip'
      orig.reason = 'offset'
      orig.offsetWith = r.key
    } else {
      // 部分退款：原支出按差额记
      orig.originalAmount = orig.amount
      orig.amount = (left / 100).toFixed(2)
      orig.partialRefund = r.amount
    }
  }

  const meta = {
    images: (shots || []).length,
    recognized: rows.length + overlap,
    overlap,
    offsetPairs,
    transfers: rows.filter((r) => r.reason === 'transfer').length,
    duplicates: rows.filter((r) => r.reason === 'duplicate').length
  }
  return { rows, meta }
}

function toRow(e, shot, idx) {
  const isIncome = e.direction === 'INCOME' || e.direction === 'REFUND'
  const row = {
    key: `${shot}-${idx}`,
    shot,
    direction: e.direction,
    type: e.direction === 'TRANSFER' ? null : isIncome ? 'income' : 'expense',
    amount: Number(e.amount).toFixed(2),
    occurredAt: e.occurredAt || null,
    dateOnly: !!e.dateOnly,
    amountUncertain: !!e.amountUncertain,
    merchant: e.merchant || '',
    item: e.item || '',
    note: e.note || e.merchant || '',
    categoryId: e.categoryId ?? null,
    categorySource: e.categorySource || 'NONE',
    duplicate: e.duplicate || null,
    state: 'record',
    reason: ''
  }
  if (e.direction === 'TRANSFER') {
    row.state = 'skip'
    row.reason = 'transfer'
  } else if (e.direction === 'REFUND' && row.categoryId == null) {
    // 找不到原支出、也没有「退款」类收入分类：默认不记，让用户决定
    row.state = 'skip'
  } else if (row.duplicate) {
    row.state = 'skip'
    row.reason = 'duplicate'
  }
  return row
}

/** 这一笔需要用户看一眼的原因（用于置顶标黄）。 */
export function rowFlags(r) {
  return {
    amount: r.amountUncertain,
    category: r.categoryId == null || r.categorySource === 'AI',
    time: !r.occurredAt || r.dateOnly
  }
}

/** 分组：需要看一眼 / 已分好类 / 疑似重复 / 不计入（抵消、转账、未决退款）。 */
export function groupRows(rows) {
  const attention = []
  const ready = []
  const duplicate = []
  const excluded = []
  for (const r of rows) {
    if (r.reason === 'duplicate') duplicate.push(r)
    else if (r.reason === 'offset' || r.reason === 'transfer' || (r.state === 'skip' && r.direction === 'REFUND')) excluded.push(r)
    else {
      const f = rowFlags(r)
      ;(f.amount || f.category || f.time ? attention : ready).push(r)
    }
  }
  return { attention, ready, duplicate, excluded }
}

/** 选中的合计：支出为负、收入为正，返回 { count, expense, income }（字符串两位小数）。 */
export function selectedTotals(rows) {
  let count = 0
  let exp = 0
  let inc = 0
  for (const r of rows) {
    if (r.state !== 'record' || !r.type) continue
    count++
    const c = Math.round(Number(r.amount) * 100)
    if (r.type === 'income') inc += c
    else exp += c
  }
  return { count, expense: (exp / 100).toFixed(2), income: (inc / 100).toFixed(2) }
}

/** 32 位小写十六进制批次号（不依赖 crypto，小程序端可用）。 */
export function newBatchId() {
  let s = ''
  for (let i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16)
  return s
}

/** 当前时间的本地 ISO（无时区，到秒），作为看不出时间时的兜底。 */
export function nowLocalIso(d = new Date()) {
  const p = (v) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 展示用时间：今天 10:32 / 昨天 09:12 / 10/21 12:05；仅日期时不带时分。 */
export function displayTime(t, dateOnly, now = new Date()) {
  if (!t) return '时间未识别'
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(t)
  if (!m) return t
  const p = (v) => String(v).padStart(2, '0')
  const today = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
  const y = new Date(now.getTime() - 86400000)
  const yest = `${y.getFullYear()}-${p(y.getMonth() + 1)}-${p(y.getDate())}`
  const day = `${m[1]}-${m[2]}-${m[3]}`
  const hm = dateOnly ? '' : ` ${m[4]}:${m[5]}`
  if (day === today) return `今天${hm}`
  if (day === yest) return `昨天${hm}`
  return `${m[2]}/${m[3]}${hm}`
}
