/**
 * 支付宝 / 微信 个人账单 CSV 解析（浏览器端）。
 *
 * 设计要点：
 *  - 编码：微信为 UTF-8(BOM)、支付宝为 GBK；先按 UTF-8 解码判源，判不出再按 GBK 解码。
 *  - 列定位：扫描出同时含「交易时间」「收/支」的行作为列头，之后<b>按列名</b>映射列下标（抗列序/格式微调）。
 *  - 收/支：收入→income、支出→expense；「不计收支」「/」等中性行跳过（转账/提现/余额宝等）。
 *  - 金额去除 ¥/￥/千分位；时间 `YYYY-MM-DD HH:mm:ss` → ISO。
 *  - externalId：`来源:订单号`，供后端去重。
 *  - 分类：用用户已有分类名对账单文本做子串匹配，未命中留空由后端用默认分类兜底。
 */
import type { Category, CategoryKind } from '@/lib/ledger'

export type BillSource = 'alipay' | 'wechat'

export interface ParsedEntry {
  type: 'expense' | 'income'
  amount: string
  occurredAt: string
  note: string
  externalId: string | null
  categoryId: number | null
  /** 预览用：匹配到的分类展示名（未命中为空）。 */
  categoryName: string
}

export interface ParsedBill {
  source: BillSource
  entries: ParsedEntry[]
  neutralCount: number // 跳过的中性行数
  invalidCount: number // 跳过的无法解析行数
  from: string | null
  to: string | null
  expenseCount: number
  incomeCount: number
  expenseTotal: string
  incomeTotal: string
}

/** 按来源关键字判定账单类型；判不出返回 null。 */
export function detectSource(text: string): BillSource | null {
  if (text.includes('微信支付账单') || text.includes('微信昵称')) return 'wechat'
  if (text.includes('支付宝') || text.includes('交易记录明细') || text.includes('账务明细')) return 'alipay'
  return null
}

/** 解析一行 CSV，支持双引号包裹与内部逗号/转义引号。 */
export function parseCsvLine(line: string): string[] {
  const out: string[] = []
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

/** 从 File 读取并解析；自动判来源与编码。 */
export async function readBillFile(file: File, categories: Category[]): Promise<ParsedBill> {
  const buf = await file.arrayBuffer()
  let text = safeDecode(buf, 'utf-8')
  let source = detectSource(text)
  if (source === null) {
    const gbk = safeDecode(buf, 'gbk')
    const s2 = detectSource(gbk)
    if (s2) {
      text = gbk
      source = s2
    }
  }
  if (source === null) {
    throw new Error('无法识别账单来源，请确认是支付宝或微信导出的 CSV 文件')
  }
  return parseBillText(text, source, categories)
}

function safeDecode(buf: ArrayBuffer, encoding: string): string {
  try {
    return new TextDecoder(encoding).decode(buf)
  } catch {
    return ''
  }
}

/** 列名匹配辅助。 */
function findCol(headers: string[], ...keys: string[]): number {
  for (let i = 0; i < headers.length; i++) {
    const h = headers[i] ?? ''
    if (keys.some((k) => h.includes(k))) return i
  }
  return -1
}

/** 解析已解码文本为归一化账单。 */
export function parseBillText(text: string, source: BillSource, categories: Category[]): ParsedBill {
  const lines = text.split(/\r?\n/)

  // 定位列头行：含「交易时间」且含「收/支」。
  let headerIdx = -1
  let headers: string[] = []
  for (let i = 0; i < lines.length; i++) {
    const cells = parseCsvLine(lines[i] ?? '')
    const joined = cells.join('')
    if ((joined.includes('交易时间') || joined.includes('交易创建时间')) && joined.includes('收/支')) {
      headerIdx = i
      headers = cells
      break
    }
  }
  if (headerIdx < 0) {
    throw new Error('未找到账单明细表头，文件可能不完整')
  }

  const cTime = findCol(headers, '交易时间', '交易创建时间', '交易时间')
  const cInout = findCol(headers, '收/支')
  const cAmount = findCol(headers, '金额')
  const cParty = findCol(headers, '交易对方', '对方')
  const cProduct = findCol(headers, '商品说明', '商品名称', '商品')
  const cCatText = findCol(headers, '交易分类', '类型')
  const cOrder = findCol(headers, '交易订单号', '交易单号', '交易号')
  const cNote = findCol(headers, '备注')

  const entries: ParsedEntry[] = []
  let neutralCount = 0
  let invalidCount = 0
  let expenseTotalCents = 0
  let incomeTotalCents = 0
  let minDate: string | null = null
  let maxDate: string | null = null

  for (let i = headerIdx + 1; i < lines.length; i++) {
    const raw = lines[i] ?? ''
    if (!raw.trim()) continue
    const cells = parseCsvLine(raw)
    if (cells.length < headers.length - 2) continue // 摘要/尾部行

    const inout = (cells[cInout] ?? '').trim()
    let type: 'expense' | 'income'
    if (inout === '收入') type = 'income'
    else if (inout === '支出') type = 'expense'
    else {
      // 不计收支 / "/" / 其他 → 中性，跳过
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
    const noteParts = [party, product].filter(Boolean)
    const note = noteParts.join(' · ').slice(0, 200)

    const order = cOrder >= 0 ? (cells[cOrder] ?? '').trim() : ''
    const externalId = order ? `${source}:${order}` : null

    const catText = [cCatText >= 0 ? cells[cCatText] : '', party, product].filter(Boolean).join(' ')
    const kind: CategoryKind = type === 'income' ? 'INCOME' : 'EXPENSE'
    const matched = matchCategory(catText, kind, categories)

    entries.push({
      type,
      amount,
      occurredAt,
      note,
      externalId,
      categoryId: matched?.id ?? null,
      categoryName: matched?.name ?? '',
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
    incomeTotal: (incomeTotalCents / 100).toFixed(2),
  }
}

/** 去除 ¥/￥/千分位/空白后解析金额；非法或 ≤0 返回 null。 */
function normalizeAmount(raw: string): string | null {
  const s = raw.replace(/[¥￥,\s]/g, '').trim()
  if (!s) return null
  const n = Number(s)
  if (!Number.isFinite(n) || n <= 0) return null
  return n.toFixed(2)
}

/** `YYYY-MM-DD HH:mm:ss` / `YYYY/MM/DD HH:mm` → ISO 本地时间字符串；解析失败返回空。 */
function normalizeTime(raw: string): string {
  const s = raw.trim().replace(/\//g, '-')
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?/.exec(s)
  if (!m) return ''
  const [, y, mo, d, hh = '0', mm = '0', ss = '0'] = m
  const p = (v: string) => String(Number(v)).padStart(2, '0')
  return `${y}-${p(mo!)}-${p(d!)}T${p(hh)}:${p(mm)}:${p(ss)}`
}

/**
 * 关键字匹配分类：在该 kind 的用户分类里，找名称是账单文本子串的分类（子分类优先，更细）。
 * 未命中返回 null（由后端用默认分类兜底）。
 */
export function matchCategory(
  text: string,
  kind: CategoryKind,
  categories: Category[],
): Category | null {
  const cands = categories.filter((c) => c.kind === kind && c.name)
  // 子分类（有 parentId）优先，其次名称更长者优先，匹配更精确。
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
