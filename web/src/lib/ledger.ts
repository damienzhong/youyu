/**
 * 记账相关 API 与校验（账户 / 分类 / 交易）。
 *
 * 对接后端（见 design.md）：
 *  - GET  /accounts        列出本人账户（按 sort_order）
 *  - GET  /categories      列出本人分类（按 kind 分组、两级层级）
 *  - POST /transactions    创建支出/收入/转账，事务性更新余额
 *
 * 金额一律以字符串传输（后端 DECIMAL(18,2) / BigDecimal），前端不做浮点运算参与落库，
 * 仅用于范围/小数位校验与展示（需求 4.11 / 6.6）。
 */
import axios from 'axios'
import http, { ApiError, getToken } from '@/lib/http'

/** 账户类型枚举（见 design「Account 模块」）。 */
export type AccountType = 'CASH' | 'BANK_CARD' | 'ALIPAY' | 'WECHAT' | 'CREDIT_CARD'

/** 账户类型中文标签。 */
export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  CASH: '现金',
  BANK_CARD: '银行卡',
  ALIPAY: '支付宝',
  WECHAT: '微信',
  CREDIT_CARD: '信用卡',
}

export interface Account {
  id: number
  name: string
  type: AccountType
  initialBalance?: string
  currentBalance: string
  sortOrder: number
  /** 余额是否计入净资产（后端默认 true；旧数据/构造时可缺省，按 true 处理）。 */
  includeInTotal?: boolean
  /** 是否隐藏账户（记账选账户时不展示，默认 false）。 */
  hidden?: boolean
  /** 账户备注（可空）。 */
  note?: string | null
}

/** 分类类型：支出 / 收入。 */
export type CategoryKind = 'EXPENSE' | 'INCOME'

export interface Category {
  id: number
  kind: CategoryKind
  name: string
  parentId: number | null
}

/** 交易类型。 */
export type TransactionType = 'expense' | 'income' | 'transfer'

export interface ExpenseIncomePayload {
  type: 'expense' | 'income'
  amount: string
  accountId: number
  categoryId: number
  occurredAt: string
  note?: string
}

export interface TransferPayload {
  type: 'transfer'
  amount: string
  sourceAccountId: number
  destinationAccountId: number
  occurredAt: string
  note?: string
}

export type CreateTransactionPayload = ExpenseIncomePayload | TransferPayload

/** 列出本人账户（后端已按 sort_order 排序，前端仍做一次稳定排序兜底）。 */
export async function fetchAccounts(): Promise<Account[]> {
  const data = await http.get<unknown, Account[]>('/accounts')
  const list = Array.isArray(data) ? data : []
  return [...list].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
}

/**
 * 列出本人分类并归一化为扁平数组。
 * 后端可能返回：数组，或按 kind 分组的对象（{ expense: [...], income: [...] } 等）。
 * 这里统一归一化为 Category[]，容错字段命名（parentId / parent_id）。
 */
export async function fetchCategories(): Promise<Category[]> {
  const data = await http.get<unknown, unknown>('/categories')
  return normalizeCategories(data)
}

function normalizeCategories(data: unknown): Category[] {
  const raw: any[] = []

  // 递归展开一个节点及其 children：后端父分类把子分类内嵌在 children 里
  // （CategoryListResponse.Node，需求 5.6），必须一并抓进扁平列表，
  // 否则子分类（如「外卖」）解析不到、流水只能显示父级/占位。
  const collect = (item: any, kindHint?: CategoryKind) => {
    if (!item || typeof item !== 'object') return
    raw.push(kindHint && !('kind' in item) ? { ...item, kind: kindHint } : item)
    const children = (item as { children?: unknown }).children
    if (Array.isArray(children)) {
      for (const child of children) collect(child, kindHint)
    }
  }

  if (Array.isArray(data)) {
    for (const item of data) collect(item)
  } else if (data && typeof data === 'object') {
    // 兼容按 kind 分组：{ expense: [...], income: [...] } 或大写键。
    for (const [key, val] of Object.entries(data as Record<string, unknown>)) {
      if (!Array.isArray(val)) continue
      const kindHint = key.toUpperCase().includes('INCOME') ? 'INCOME' : key.toUpperCase().includes('EXPENSE') ? 'EXPENSE' : undefined
      for (const item of val) collect(item, kindHint)
    }
  }

  return raw
    .filter((c) => c && typeof c === 'object')
    .map((c) => ({
      id: Number(c.id),
      kind: (String(c.kind ?? 'EXPENSE').toUpperCase() as CategoryKind) === 'INCOME' ? 'INCOME' : 'EXPENSE',
      name: String(c.name ?? ''),
      parentId: c.parentId ?? c.parent_id ?? null,
    }))
}

/** 创建一笔交易。 */
export function createTransaction(payload: CreateTransactionPayload): Promise<unknown> {
  return http.post<unknown, CreateTransactionPayload>('/transactions', payload)
}

// === 金额校验（需求 6.6：0.01–999,999,999.99，最多两位小数） ===

export const AMOUNT_MIN = 0.01
export const AMOUNT_MAX = 999_999_999.99

/** 至多两位小数、非负的合法金额格式（不含符号、无千分位）。 */
const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/

/**
 * 校验金额字符串，返回错误信息（校验通过返回 null）。
 * - 空：必填
 * - 格式非法或小数位超 2 位
 * - 超出 [0.01, 999,999,999.99] 范围
 */
export function validateAmount(amount: string): string | null {
  const trimmed = amount.trim()
  if (!trimmed || trimmed === '.' ) return '请输入金额'
  if (!AMOUNT_PATTERN.test(trimmed)) return '金额最多两位小数'
  const value = Number(trimmed)
  if (!Number.isFinite(value)) return '金额格式不正确'
  if (value < AMOUNT_MIN) return '金额不能小于 0.01'
  if (value > AMOUNT_MAX) return '金额不能大于 999,999,999.99'
  return null
}

/** 把后端错误码映射为快速记账场景的友好中文提示。 */
export function toEntryErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return '提交失败，请稍后重试'
  switch (err.code) {
    case 'AMOUNT_INVALID':
      return '金额非法：需在 0.01 到 999,999,999.99 之间且最多两位小数'
    case 'FIELD_REQUIRED':
      return '请填写必填项后再提交'
    case 'TRANSFER_SAME_ACCOUNT':
      return '转出与转入账户不能相同'
    case 'NOT_FOUND':
      return '所选账户或分类不存在，请刷新后重试'
    case 'NETWORK_ERROR':
      return '网络异常，提交未成功，请重试'
    default:
      return err.message || '提交失败，请稍后重试'
  }
}

// =====================================================================
// 交易列表 / 单条 / 修改 / 删除、月度报表（任务 10.3：首页与流水列表页）
// =====================================================================

/** 一笔交易（读取形态，见 design「Transaction 模块」）。 */
export interface Transaction {
  id: number
  type: TransactionType
  amount: string
  accountId: number | null
  sourceAccountId: number | null
  destinationAccountId: number | null
  categoryId: number | null
  occurredAt: string
  note: string | null
}

/** 交易分页结果（对后端 Page 或纯数组两种返回做归一化）。 */
export interface TransactionPage {
  items: Transaction[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

export interface FetchTransactionsParams {
  /** 页码，从 0 开始（与 Spring Data 一致）。 */
  page?: number
  size?: number
  /** 指定自然月 `YYYY-MM`：返回该月全部交易（首页「当月流水」），忽略分页。 */
  month?: string
}

/** 把后端原始交易对象归一化为 Transaction（容错 camelCase / snake_case）。 */
function normalizeTransaction(raw: any): Transaction {
  const type = String(raw?.type ?? 'expense').toLowerCase() as TransactionType
  return {
    id: Number(raw?.id),
    type: type === 'income' ? 'income' : type === 'transfer' ? 'transfer' : 'expense',
    amount: String(raw?.amount ?? '0'),
    accountId: raw?.accountId ?? raw?.account_id ?? null,
    sourceAccountId: raw?.sourceAccountId ?? raw?.source_account_id ?? null,
    destinationAccountId: raw?.destinationAccountId ?? raw?.destination_account_id ?? null,
    categoryId: raw?.categoryId ?? raw?.category_id ?? null,
    occurredAt: String(raw?.occurredAt ?? raw?.occurred_at ?? ''),
    note: raw?.note ?? null,
  }
}

/**
 * 列出本人交易（后端按时间倒序）。兼容两种返回：
 *  - Spring Data Page：{ content: [...], totalElements, number, size, ... }
 *  - 纯数组：[...]（此时无分页元信息，视为单页）。
 */
export async function fetchTransactions(params: FetchTransactionsParams = {}): Promise<TransactionPage> {
  const page = params.page ?? 0
  const size = params.size ?? 20
  // 指定 month：返回该自然月全部交易（后端返回数组，走下方数组分支）。
  const query = params.month ? { month: params.month } : { page, size }
  const data = await http.get<unknown, any>('/transactions', { params: query })

  if (Array.isArray(data)) {
    const items = data.map(normalizeTransaction)
    return { items, page: 0, size: items.length, total: items.length, hasMore: false }
  }

  const content: any[] = Array.isArray(data?.content) ? data.content : Array.isArray(data?.items) ? data.items : []
  const items = content.map(normalizeTransaction)
  const total = Number(data?.totalElements ?? data?.total ?? items.length)
  const curPage = Number(data?.number ?? data?.page ?? page)
  const curSize = Number(data?.size ?? size)
  const totalPages = data?.totalPages != null ? Number(data.totalPages) : Math.ceil(total / (curSize || 1))
  const hasMore = data?.last != null ? !data.last : curPage + 1 < totalPages
  return { items, page: curPage, size: curSize, total, hasMore }
}

/** 单条读取（校验归属由后端保证）。 */
export async function fetchTransaction(id: number): Promise<Transaction> {
  const data = await http.get<unknown, any>(`/transactions/${id}`)
  return normalizeTransaction(data)
}

/** 修改一笔交易（后端回滚原影响并应用新影响，需求 4.6）。 */
export function updateTransaction(id: number, payload: CreateTransactionPayload): Promise<unknown> {
  return http.put<unknown, CreateTransactionPayload>(`/transactions/${id}`, payload)
}

/** 删除一笔交易（后端回滚原影响，需求 4.6）。 */
export function deleteTransaction(id: number): Promise<unknown> {
  return http.delete<unknown, unknown>(`/transactions/${id}`)
}

/** 本月报表（收入/支出/结余，见 design「Report 模块」）。 */
export interface MonthlyReport {
  month: string
  totalIncome: string
  totalExpense: string
  balance: string
}

/** 拉取指定自然月报表；month 形如 `YYYY-MM`（按 UTC+8）。 */
export async function fetchMonthlyReport(month: string): Promise<MonthlyReport> {
  const data = await http.get<unknown, any>('/reports/monthly', { params: { month } })
  const totalIncome = String(data?.totalIncome ?? data?.total_income ?? '0')
  const totalExpense = String(data?.totalExpense ?? data?.total_expense ?? '0')
  const balance = String(data?.balance ?? (Number(totalIncome) - Number(totalExpense)).toFixed(2))
  return { month, totalIncome, totalExpense, balance }
}

/** 分类占比报表中的单个分类项（金额 + 占该范围总支出的百分比）。 */
export interface CategoryReportItem {
  categoryId: number
  amount: string
  /** 占总支出百分比（保留两位小数，各项之和约为 100，见需求 7.3）。 */
  percentage: number
}

/** 分类占比报表（见 design「Report 模块」，GET /reports/category）。 */
export interface CategoryReport {
  totalExpense: string
  categories: CategoryReportItem[]
}

/**
 * 拉取分类占比报表；from / to 为闭区间日期（`YYYY-MM-DD`，按 UTC+8）。
 * 后端返回 { totalExpense, categories:[{ categoryId, amount, percentage }] }（排除转账）。
 * 前端按金额降序排序，便于图例展示。
 */
export async function fetchCategoryReport(from: string, to: string): Promise<CategoryReport> {
  const data = await http.get<unknown, any>('/reports/category', { params: { from, to } })
  const totalExpense = String(data?.totalExpense ?? data?.total_expense ?? '0')
  const rawList: any[] = Array.isArray(data?.categories) ? data.categories : []
  const categories = rawList
    .map((c) => ({
      categoryId: Number(c?.categoryId ?? c?.category_id),
      amount: String(c?.amount ?? '0'),
      percentage: Number(c?.percentage ?? 0),
    }))
    .sort((a, b) => Number(b.amount) - Number(a.amount))
  return { totalExpense, categories }
}

/** 月度趋势中的单月收支（无数据月各项为 0，见需求 7.4）。 */
export interface TrendMonth {
  month: string
  income: string
  expense: string
}

/** 月度趋势报表（见 design「Report 模块」，GET /reports/trend）。 */
export interface TrendReport {
  months: TrendMonth[]
}

/**
 * 拉取月度趋势报表；fromMonth / toMonth 形如 `YYYY-MM`（闭区间，按 UTC+8）。
 * 区间跨度上限 24 个月（超出后端返回 REPORT_RANGE_INVALID）。
 * 后端返回 { months:[{ month, income, expense }] }（排除转账）。
 */
export async function fetchTrendReport(fromMonth: string, toMonth: string): Promise<TrendReport> {
  const data = await http.get<unknown, any>('/reports/trend', { params: { fromMonth, toMonth } })
  const rawList: any[] = Array.isArray(data?.months) ? data.months : []
  const months = rawList.map((m) => ({
    month: String(m?.month ?? ''),
    income: String(m?.income ?? '0'),
    expense: String(m?.expense ?? '0'),
  }))
  return { months }
}

// === 展示辅助 ===

/** 当前自然月 `YYYY-MM`（按 Asia/Shanghai / UTC+8，与后端月度边界一致）。 */
export function currentMonth(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date())
  const y = parts.find((p) => p.type === 'year')?.value ?? '1970'
  const m = parts.find((p) => p.type === 'month')?.value ?? '01'
  return `${y}-${m}`
}

/**
 * 月份平移：在 `YYYY-MM` 基础上增减 delta 个自然月，返回新的 `YYYY-MM`。
 * 纯算术实现，不受本地时区影响。
 */
export function shiftMonth(month: string, delta: number): string {
  const parts = month.split('-')
  const y = Number(parts[0])
  const m = Number(parts[1])
  if (!Number.isFinite(y) || !Number.isFinite(m)) return month
  // 转成从 0 计的月序，平移后再还原。
  const total = y * 12 + (m - 1) + delta
  const ny = Math.floor(total / 12)
  const nm = total - ny * 12 + 1
  return `${String(ny).padStart(4, '0')}-${String(nm).padStart(2, '0')}`
}

/**
 * 某自然月的闭区间日期边界，用于分类占比报表（GET /reports/category）。
 * 返回 { from: `YYYY-MM-01`, to: `YYYY-MM-<该月最后一天>` }。
 */
export function monthRange(month: string): { from: string; to: string } {
  const parts = month.split('-')
  const y = Number(parts[0])
  const m = Number(parts[1])
  // Date.UTC(y, m, 0) 取「第 m 月（1 基）」的最后一天（m 作 0 基下月的第 0 天）。
  const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate()
  const mm = String(m).padStart(2, '0')
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(lastDay).padStart(2, '0')}` }
}

/** 月份展示标签：`YYYY-MM` → `YYYY年M月`。 */
export function monthLabel(month: string): string {
  const [y, m] = month.split('-')
  return `${y}年${Number(m)}月`
}

/** 月份短标签：`YYYY-MM` → `M月`（趋势图横轴用）。 */
export function shortMonthLabel(month: string): string {
  const m = month.split('-')[1]
  return `${Number(m)}月`
}

/** 金额展示：千分位 + 两位小数（保留负号，如信用卡欠款/净资产为负）。 */
export function formatAmount(value: string | number): string {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return '0.00'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/**
 * 净资产 = 计入净资产（includeInTotal）的账户当前余额之和（以分为单位求和避免浮点误差）。
 * 关闭「计入总资产」的账户不参与合计。
 */
export function sumBalances(accounts: Account[]): string {
  const cents = accounts
    .filter((a) => a.includeInTotal !== false)
    .reduce((acc, a) => acc + Math.round(Number(a.currentBalance) * 100), 0)
  return (cents / 100).toFixed(2)
}

/** 总资产 = 计入净资产账户中，余额为正的部分之和。 */
export function sumAssets(accounts: Account[]): string {
  const cents = accounts
    .filter((a) => a.includeInTotal !== false)
    .reduce((acc, a) => acc + Math.max(0, Math.round(Number(a.currentBalance) * 100)), 0)
  return (cents / 100).toFixed(2)
}

/** 总负债 = 计入净资产账户中，余额为负部分的绝对值之和。 */
export function sumLiabilities(accounts: Account[]): string {
  const cents = accounts
    .filter((a) => a.includeInTotal !== false)
    .reduce((acc, a) => acc + Math.max(0, -Math.round(Number(a.currentBalance) * 100)), 0)
  return (cents / 100).toFixed(2)
}

/** 账户名称解析（用于流水展示）。 */
export function accountNameOf(accounts: Account[], id: number | null | undefined): string {
  if (id == null) return '—'
  return accounts.find((a) => a.id === id)?.name ?? '未知账户'
}

/** 分类名称解析（父 > 子 展示；找不到返回占位）。 */
export function categoryNameOf(categories: Category[], id: number | null | undefined): string {
  if (id == null) return ''
  const c = categories.find((x) => x.id === id)
  if (!c) return ''
  if (c.parentId != null) {
    const parent = categories.find((p) => p.id === c.parentId)
    return parent ? `${parent.name} · ${c.name}` : c.name
  }
  return c.name
}

/** 把交易 occurredAt 归一到本地「日」键（YYYY-MM-DD），用于按日期分组。 */
export function dayKeyOf(occurredAt: string): string {
  const d = new Date(occurredAt)
  if (Number.isNaN(d.getTime())) return occurredAt.slice(0, 10)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 分类名 → emoji 图标（按关键字粗匹配，未命中按种类给通用图标）。
 * 首页流水与记一笔的分类网格共用，保证同一分类图标一致。
 * 由于分类为用户自建、无图标字段，这里用名称关键字启发式映射（需求 5.x）。
 */
const CATEGORY_EMOJI_RULES: Array<[RegExp, string]> = [
  [/餐饮|吃|饭|外卖|美食|聚餐|零食|饮|咖啡|奶茶/, '🍜'],
  [/交通|地铁|公交|打车|出行|车|油|加油|停车|高铁/, '🚇'],
  [/购物|买|衣|鞋|服饰|数码|电器|日用/, '🛍️'],
  [/娱乐|游戏|电影|玩|唱|运动|健身/, '🎮'],
  [/居住|房租|房贷|物业|水电|燃气|家居/, '🏠'],
  [/医疗|药|医院|健康|体检/, '💊'],
  [/教育|学习|书|培训|课|学费/, '📚'],
  [/通讯|话费|网费|流量|手机|宽带/, '📱'],
  [/旅行|旅游|酒店|机票|景点/, '✈️'],
  [/宠物/, '🐾'],
  [/工资|薪|奖金|报销|劳务/, '💰'],
  [/理财|利息|收益|投资|分红|基金|股票/, '📈'],
  [/红包|礼金|转赠/, '🧧'],
  [/退款|返现/, '💸'],
]

/** 取分类图标 emoji。name 可传「父 · 子」组合以提高命中率；kind 决定兜底图标。 */
export function categoryEmoji(name: string | null | undefined, kind?: CategoryKind): string {
  const s = String(name ?? '')
  for (const [re, emoji] of CATEGORY_EMOJI_RULES) {
    if (re.test(s)) return emoji
  }
  return kind === 'INCOME' ? '💰' : '🧾'
}

/** 展示用时间（HH:mm）。 */
export function timeLabelOf(occurredAt: string): string {
  const d = new Date(occurredAt)
  if (Number.isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// =====================================================================
// 账户管理 CRUD（任务 10.5：账户列表/新增/编辑/删除）
// 对接后端（见 design「Account 模块」）：
//   POST   /accounts        创建账户 {name, type, initialBalance, sortOrder?}
//   PUT    /accounts/{id}   修改名称/类型（后端保留 current_balance，需求 3.6）
//   DELETE /accounts/{id}   删除（有交易则拒绝 ACCOUNT_IN_USE，需求 3.7）
// =====================================================================

/** 账户类型选项（用于下拉/选择器），顺序即展示顺序。 */
export const ACCOUNT_TYPE_OPTIONS: AccountType[] = ['CASH', 'BANK_CARD', 'ALIPAY', 'WECHAT', 'CREDIT_CARD']

/** 初始余额范围（需求 3.1）：DECIMAL(18,2)，最多两位小数，允许负值（信用卡欠款，需求 3.4）。 */
export const BALANCE_MIN = -9_999_999_999_999_999.99
export const BALANCE_MAX = 9_999_999_999_999_999.99

/** 账户名称去空白后 1–50 字符（需求 3.1）；返回错误信息，合法返回 null。 */
export function validateAccountName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return '请输入账户名称'
  if (trimmed.length > 50) return '账户名称不能超过 50 个字符'
  return null
}

/** 允许带负号、至多两位小数的金额格式（初始余额用）。 */
const SIGNED_AMOUNT_PATTERN = /^-?\d+(\.\d{1,2})?$/

/** 校验初始余额字符串（需求 3.1：范围内、最多两位小数；允许负值）；合法返回 null。 */
export function validateInitialBalance(balance: string): string | null {
  const trimmed = balance.trim()
  if (!trimmed || trimmed === '-' || trimmed === '.') return '请输入初始余额'
  if (!SIGNED_AMOUNT_PATTERN.test(trimmed)) return '初始余额最多两位小数'
  const value = Number(trimmed)
  if (!Number.isFinite(value)) return '初始余额格式不正确'
  if (value < BALANCE_MIN || value > BALANCE_MAX) return '初始余额超出允许范围'
  return null
}

export interface CreateAccountPayload {
  name: string
  type: AccountType
  initialBalance: string
  sortOrder?: number
  includeInTotal?: boolean
  hidden?: boolean
  note?: string | null
}

export interface UpdateAccountPayload {
  name: string
  type: AccountType
  includeInTotal?: boolean
  hidden?: boolean
  note?: string | null
}

/** 创建账户（需求 3.1–3.4）。 */
export function createAccount(payload: CreateAccountPayload): Promise<Account> {
  return http.post<unknown, Account>('/accounts', payload)
}

/** 修改账户名称/类型（后端保留余额，需求 3.6）。 */
export function updateAccount(id: number, payload: UpdateAccountPayload): Promise<Account> {
  return http.put<unknown, Account>(`/accounts/${id}`, payload)
}

/** 删除账户（有交易则被后端拒绝，需求 3.7/3.8）。 */
export function deleteAccount(id: number): Promise<unknown> {
  return http.delete<unknown, unknown>(`/accounts/${id}`)
}

/** 把后端错误码映射为账户管理场景的友好中文提示。 */
export function toAccountErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return '操作失败，请稍后重试'
  switch (err.code) {
    case 'ACCOUNT_FIELD_INVALID':
      return err.message || '账户信息不合法：请检查名称、类型与初始余额'
    case 'ACCOUNT_IN_USE':
      return '该账户已有交易记录，不能删除'
    case 'NOT_FOUND':
      return '账户不存在，请刷新后重试'
    case 'FIELD_REQUIRED':
      return '请填写必填项后再提交'
    case 'NETWORK_ERROR':
      return '网络异常，操作未成功，请重试'
    default:
      return err.message || '操作失败，请稍后重试'
  }
}

// =====================================================================
// 分类管理 CRUD（任务 10.x：两级分类、支出/收入独立）
// 对接后端（见 design「Category 模块」）：
//   POST   /categories        创建父/子分类 {kind, name, parentId?}
//   PUT    /categories/{id}   重命名 {name}
//   DELETE /categories/{id}   删除（被引用/含子分类则拒绝）
// =====================================================================

/** 分类名称去空白后 1–50 字符（需求 5.1/5.7）；合法返回 null。 */
export function validateCategoryName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return '请输入分类名称'
  if (trimmed.length > 50) return '分类名称不能超过 50 个字符'
  return null
}

export interface CreateCategoryPayload {
  kind: CategoryKind
  name: string
  parentId?: number | null
}

/** 创建分类（父分类不传 parentId；子分类传父分类 id，需求 5.1/5.2）。 */
export function createCategory(payload: CreateCategoryPayload): Promise<Category> {
  return http.post<unknown, Category>('/categories', payload)
}

/** 重命名分类（保留关联，需求 5.4）。 */
export function updateCategory(id: number, name: string): Promise<Category> {
  return http.put<unknown, Category>(`/categories/${id}`, { name })
}

/** 删除分类（被引用/含子分类则被后端拒绝，需求 5.5/5.9）。 */
export function deleteCategory(id: number): Promise<unknown> {
  return http.delete<unknown, unknown>(`/categories/${id}`)
}

/** 把后端错误码映射为分类管理场景的友好中文提示。 */
export function toCategoryErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return '操作失败，请稍后重试'
  switch (err.code) {
    case 'CATEGORY_DEPTH_EXCEEDED':
      return '分类最多支持两级，不能在子分类下再建分类'
    case 'CATEGORY_NAME_INVALID':
      return '分类名称不合法：去空白后需为 1–50 个字符'
    case 'CATEGORY_NAME_DUPLICATE':
      return '同级下已存在同名分类'
    case 'CATEGORY_IN_USE':
      return '该分类仍被交易引用或含子分类，不能删除'
    case 'NOT_FOUND':
      return '分类不存在，请刷新后重试'
    case 'NETWORK_ERROR':
      return '网络异常，操作未成功，请重试'
    default:
      return err.message || '操作失败，请稍后重试'
  }
}

// =====================================================================
// 数据导出（任务 10.5：CSV / JSON 导出入口，需求 8.1/8.2）
//
// 导出接口需要 Authorization Bearer 头，且返回文件下载（Content-Disposition
// attachment）。因此不能用普通 <a href> 直链，而是用带鉴权的请求以 Blob 拉取，
// 再在客户端触发下载（object URL + download 属性）。
// 这里直接用 axios（而非 http 实例）发起请求，以便读取响应头中的文件名；
// http 实例的响应拦截器会解包为 data，拿不到 headers。
// =====================================================================

export type ExportFormat = 'csv' | 'json'

export interface ExportResult {
  blob: Blob
  filename: string
}

/** 默认导出文件名（当响应未提供 Content-Disposition 时兜底）。 */
function defaultExportName(format: ExportFormat): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const y = parts.find((p) => p.type === 'year')?.value ?? '1970'
  const m = parts.find((p) => p.type === 'month')?.value ?? '01'
  const d = parts.find((p) => p.type === 'day')?.value ?? '01'
  return `youyu-export-${y}${m}${d}.${format}`
}

/** 从 Content-Disposition 解析文件名（兼容 filename* 与普通 filename）。 */
function parseFilename(disposition: string | undefined): string | null {
  if (!disposition) return null
  // 优先 RFC 5987 的 filename*=UTF-8''xxx
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(disposition)
  if (star && star[1]) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
    } catch {
      /* 解码失败则回退到普通 filename */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  if (plain && plain[1]) return plain[1].trim()
  return null
}

/** 把 export 接口的错误响应（Blob）转换为 ApiError，尽量还原后端错误体。 */
async function toExportError(err: unknown): Promise<ApiError> {
  if (axios.isAxiosError(err)) {
    const status = err.response?.status
    if (status === 401) {
      setToken401Redirect()
      return new ApiError('UNAUTHENTICATED', '登录已失效，请重新登录', undefined, 401)
    }
    const data = err.response?.data
    if (data instanceof Blob) {
      try {
        const text = await data.text()
        const body = JSON.parse(text)
        if (body && typeof body === 'object' && 'code' in body) {
          return new ApiError(body.code, body.message ?? '导出失败', body.field, status)
        }
      } catch {
        /* 非 JSON 错误体，走兜底 */
      }
    }
    return new ApiError('EXPORT_FAILED', '导出失败，请稍后重试', undefined, status)
  }
  return new ApiError('NETWORK_ERROR', '网络异常，导出未成功，请重试')
}

/** 401 时清令牌并跳登录（与 http 拦截器保持一致行为）。 */
function setToken401Redirect(): void {
  if (typeof window === 'undefined') return
  localStorage.removeItem('youyu_token')
  const path = window.location.pathname
  if (!path.startsWith('/login') && !path.startsWith('/register')) {
    window.location.href = '/login'
  }
}

/**
 * 以指定格式导出全部数据，返回 Blob 与文件名（需求 8.1/8.2）。
 * 带 Authorization 头、responseType='blob'；导出可能较大，超时放宽到 60s。
 */
export async function exportData(format: ExportFormat): Promise<ExportResult> {
  const token = getToken()
  try {
    const res = await axios.get<Blob>('/api/export', {
      params: { format },
      responseType: 'blob',
      timeout: 60_000,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
    const disposition = (res.headers?.['content-disposition'] ?? res.headers?.['Content-Disposition']) as
      | string
      | undefined
    const filename = parseFilename(disposition) ?? defaultExportName(format)
    return { blob: res.data, filename }
  } catch (e) {
    throw await toExportError(e)
  }
}

/** 触发浏览器下载一个 Blob（创建临时 object URL + download 属性）。 */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 稍后释放，确保下载已开始。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

// =====================================================================
// 预算（月度总预算 + 分类预算 + 预算健康）
// 对接后端（见 BudgetController）：
//   GET    /budgets?month=YYYY-MM                     预算总览
//   PUT    /budgets?month=YYYY-MM        {amount}     设置月度总预算
//   POST   /budgets/categories?month=    {categoryId, amount}  设置分类预算
//   DELETE /budgets/categories/{id}?month=YYYY-MM     删除分类预算
//   POST   /budgets/copy-previous?month=YYYY-MM       沿用上月预算
// 金额一律字符串传输（后端 DECIMAL/BigDecimal）。
// =====================================================================

export type BudgetStatus = 'OK' | 'WARN' | 'OVER'

/** 预算健康（前瞻）：剩余天数、日均可用、预计月底结余。 */
export interface BudgetHealth {
  daysLeft: number
  dailyAvailable: string
  projectedBalance: string
  projectedOver: boolean
}

/** 单个分类预算明细。 */
export interface CategoryBudgetItem {
  categoryId: number
  name: string
  budget: string
  spent: string
  remaining: string
  usedPercent: number
  txCount: number
  status: BudgetStatus
}

/** 预算总览。 */
export interface BudgetOverview {
  month: string
  hasBudget: boolean
  totalBudget: string | null
  spent: string
  remaining: string | null
  usedPercent: number
  status: BudgetStatus | null
  currentMonth: boolean
  health: BudgetHealth | null
  allocated: string
  unallocated: string | null
  categories: CategoryBudgetItem[]
}

/** 把后端预算总览归一化（金额转字符串，容错缺省）。 */
function normalizeBudgetOverview(data: any, month: string): BudgetOverview {
  const str = (v: unknown): string => (v == null ? '0' : String(v))
  const strOrNull = (v: unknown): string | null => (v == null ? null : String(v))
  const rawCats: any[] = Array.isArray(data?.categories) ? data.categories : []
  const health = data?.health
    ? {
        daysLeft: Number(data.health.daysLeft ?? 0),
        dailyAvailable: str(data.health.dailyAvailable),
        projectedBalance: str(data.health.projectedBalance),
        projectedOver: Boolean(data.health.projectedOver),
      }
    : null
  return {
    month: String(data?.month ?? month),
    hasBudget: Boolean(data?.hasBudget),
    totalBudget: strOrNull(data?.totalBudget),
    spent: str(data?.spent),
    remaining: strOrNull(data?.remaining),
    usedPercent: Number(data?.usedPercent ?? 0),
    status: (data?.status ?? null) as BudgetStatus | null,
    currentMonth: Boolean(data?.currentMonth),
    health,
    allocated: str(data?.allocated),
    unallocated: strOrNull(data?.unallocated),
    categories: rawCats.map((c) => ({
      categoryId: Number(c?.categoryId),
      name: String(c?.name ?? ''),
      budget: str(c?.budget),
      spent: str(c?.spent),
      remaining: str(c?.remaining),
      usedPercent: Number(c?.usedPercent ?? 0),
      txCount: Number(c?.txCount ?? 0),
      status: (c?.status ?? 'OK') as BudgetStatus,
    })),
  }
}

/** 拉取某自然月预算总览；month 形如 `YYYY-MM`（缺省取当前月）。 */
export async function fetchBudgetOverview(month?: string): Promise<BudgetOverview> {
  const params = month ? { month } : undefined
  const data = await http.get<unknown, any>('/budgets', { params })
  return normalizeBudgetOverview(data, month ?? '')
}

/** 设置/更新月度总预算，返回最新总览。 */
export async function setTotalBudget(month: string, amount: string): Promise<BudgetOverview> {
  const data = await http.put<unknown, any>('/budgets', { amount }, { params: { month } })
  return normalizeBudgetOverview(data, month)
}

/** 设置/更新某分类预算，返回最新总览。 */
export async function setCategoryBudget(
  month: string,
  categoryId: number,
  amount: string,
): Promise<BudgetOverview> {
  const data = await http.post<unknown, any>(
    '/budgets/categories',
    { categoryId, amount },
    { params: { month } },
  )
  return normalizeBudgetOverview(data, month)
}

/** 删除某分类预算，返回最新总览。 */
export async function deleteCategoryBudget(month: string, categoryId: number): Promise<BudgetOverview> {
  const data = await http.delete<unknown, any>(`/budgets/categories/${categoryId}`, { params: { month } })
  return normalizeBudgetOverview(data, month)
}

/** 沿用上月预算（总预算 + 分类预算复制到本月），返回最新总览。 */
export async function copyPreviousBudget(month: string): Promise<BudgetOverview> {
  const data = await http.post<unknown, any>('/budgets/copy-previous', null, { params: { month } })
  return normalizeBudgetOverview(data, month)
}

/** 把预算相关后端错误码映射为友好中文提示。 */
export function toBudgetErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return '操作失败，请稍后重试'
  switch (err.code) {
    case 'BUDGET_AMOUNT_INVALID':
      return '预算金额需在 0.01 到 9,999,999,999,999,999.99 之间且最多两位小数'
    case 'BUDGET_CATEGORY_INVALID':
      return '只能给支出分类设置预算'
    case 'BUDGET_MONTH_INVALID':
      return '月份格式不正确'
    case 'NOT_FOUND':
      return '分类不存在，请刷新后重试'
    case 'NETWORK_ERROR':
      return '网络异常，操作未成功，请重试'
    default:
      return err.message || '操作失败，请稍后重试'
  }
}
