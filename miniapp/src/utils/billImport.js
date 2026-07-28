/**
 * 支付宝 / 微信个人账单 CSV 解析（平台无关：只处理已解码文本）。
 * 移植自 web 端 lib/billImport.ts。文件读取与编码（微信 UTF-8 / 支付宝 GBK）在页面按端处理。
 */

/** 按来源关键字判定账单类型；判不出返回 null。 */
export function detectSource(text) {
  if (text.includes('微信支付账单') || text.includes('微信昵称')) return 'wechat'
  if (text.includes('支付宝') || text.includes('交易记录明细') || text.includes('账务明细')) return 'alipay'
  return null
}

/** 解析一行 CSV，支持双引号包裹与内部逗号/转义引号。 */
export function parseCsvLine(line) {
  const out = []
  let cur = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (inQuotes) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        cur += ch
      }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      out.push(cur)
      cur = ''
    } else {
      cur += ch
    }
  }
  out.push(cur)
  return out.map((s) => s.trim())
}

function findCol(headers, ...keys) {
  for (let i = 0; i < headers.length; i++) {
    const h = headers[i] ?? ''
    if (keys.some((k) => h.includes(k))) return i
  }
  return -1
}

function normalizeAmount(raw) {
  const s = String(raw).replace(/[¥￥,\s]/g, '').trim()
  if (!s) return null
  const n = Number(s)
  if (!Number.isFinite(n) || n <= 0) return null
  return n.toFixed(2)
}

function normalizeTime(raw) {
  const s = String(raw).trim().replace(/\//g, '-')
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?/.exec(s)
  if (!m) return ''
  const [, y, mo, d, hh = '0', mm = '0', ss = '0'] = m
  const p = (v) => String(Number(v)).padStart(2, '0')
  return `${y}-${p(mo)}-${p(d)}T${p(hh)}:${p(mm)}:${p(ss)}`
}

/**
 * 关键字匹配分类：在该 kind 的分类里找名称是账单文本子串的（子分类优先，名称更长优先）。
 * categories: [{ id, name, kind:'EXPENSE'|'INCOME', parentId }]
 */
export function matchCategory(text, kind, categories) {
  const cands = categories.filter((c) => c.kind === kind && c.name)
  const ordered = [...cands].sort((a, b) => {
    const pa = a.parentId != null ? 1 : 0
    const pb = b.parentId != null ? 1 : 0
    if (pa !== pb) return pb - pa
    return b.name.length - a.name.length
  })
  for (const c of ordered) {
    if (text.includes(c.name)) return c
  }
  return null
}

/** 解析已解码文本为归一化账单。categories 为扁平分类列表。 */
export function parseBillText(text, source, categories) {
  const lines = text.split(/\r?\n/)

  let headerIdx = -1
  let headers = []
  for (let i = 0; i < lines.length; i++) {
    const cells = parseCsvLine(lines[i] ?? '')
    const joined = cells.join('')
    if ((joined.includes('交易时间') || joined.includes('交易创建时间')) && joined.includes('收/支')) {
      headerIdx = i
      headers = cells
      break
    }
  }
  if (headerIdx < 0) throw new Error('未找到账单明细表头，文件可能不完整')

  const cTime = findCol(headers, '交易时间', '交易创建时间')
  const cInout = findCol(headers, '收/支')
  const cAmount = findCol(headers, '金额')
  const cParty = findCol(headers, '交易对方', '对方')
  const cProduct = findCol(headers, '商品说明', '商品名称', '商品')
  const cCatText = findCol(headers, '交易分类', '类型')
  const cOrder = findCol(headers, '交易订单号', '交易单号', '交易号')

  const entries = []
  let neutralCount = 0
  let invalidCount = 0
  let expenseTotalCents = 0
  let incomeTotalCents = 0
  let minDate = null
  let maxDate = null

  for (let i = headerIdx + 1; i < lines.length; i++) {
    const raw = lines[i] ?? ''
    if (!raw.trim()) continue
    const cells = parseCsvLine(raw)
    if (cells.length < headers.length - 2) continue

    const inout = (cells[cInout] ?? '').trim()
    let type
    if (inout === '收入') type = 'income'
    else if (inout === '支出') type = 'expense'
    else {
      neutralCount++
      continue
    }

    const amount = normalizeAmount(cells[cAmount] ?? '')
    if (amount == null) {
      invalidCount++
      continue
    }
    const occurredAt = normalizeTime(cells[cTime] ?? '')
    if (!occurredAt) {
      invalidCount++
      continue
    }
    const dateOnly = occurredAt.slice(0, 10)
    if (!minDate || dateOnly < minDate) minDate = dateOnly
    if (!maxDate || dateOnly > maxDate) maxDate = dateOnly

    const party = (cells[cParty] ?? '').trim()
    const product = (cells[cProduct] ?? '').trim()
    const note = [party, product].filter(Boolean).join(' · ').slice(0, 200)

    const order = cOrder >= 0 ? (cells[cOrder] ?? '').trim() : ''
    const externalId = order ? `${source}:${order}` : null

    const catText = [cCatText >= 0 ? cells[cCatText] : '', party, product].filter(Boolean).join(' ')
    const kind = type === 'income' ? 'INCOME' : 'EXPENSE'
    const matched = matchCategory(catText, kind, categories)

    entries.push({
      type,
      amount,
      occurredAt,
      note,
      externalId,
      categoryId: matched ? matched.id : null,
      categoryName: matched ? matched.name : ''
    })

    const cents = Math.round(Number(amount) * 100)
    if (type === 'expense') expenseTotalCents += cents
    else incomeTotalCents += cents
  }

  return {
    source,
    entries,
    neutralCount,
    invalidCount,
    from: minDate,
    to: maxDate,
    expenseCount: entries.filter((e) => e.type === 'expense').length,
    incomeCount: entries.filter((e) => e.type === 'income').length,
    expenseTotal: (expenseTotalCents / 100).toFixed(2),
    incomeTotal: (incomeTotalCents / 100).toFixed(2)
  }
}
