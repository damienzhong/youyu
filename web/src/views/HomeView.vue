<script setup lang="ts">
/**
 * 首页（Home）—— 重设计版。
 *
 * 展示（需求 2.3 数据隔离由后端保证；11.1 响应式；11.5 加载失败保留旧数据+重试）：
 *  - 本月概览：结余大数字 + 收入/支出/净资产（品牌绿渐变主卡）。
 *  - 净资产 = 全部账户当前余额之和（GET /accounts）。
 *  - 最近流水：按日期分组，带分类图标 / 父·子分类名 / 账户·时间·备注 / 等宽金额。
 *  - 宽屏（≥768px）额外在右侧展示账户简览 + 净资产合计。
 *  - 悬浮「＋」按钮 → 记一笔（QuickEntry）。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import TransactionEditModal from '@/components/TransactionEditModal.vue'
import {
  fetchAccounts,
  fetchCategories,
  fetchTransactions,
  fetchMonthlyReport,
  fetchBudgetOverview,
  currentMonth,
  monthLabel,
  formatAmount,
  sumBalances,
  accountNameOf,
  categoryNameOf,
  categoryEmoji,
  timeLabelOf,
  dayKeyOf,
  type Account,
  type AccountType,
  type Category,
  type Transaction,
  type MonthlyReport,
  type BudgetOverview,
} from '@/lib/ledger'

const loading = ref(true)
const loadError = ref('')
const loaded = ref(false) // 是否已成功加载过一次（用于「保留上次数据」）

const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])
const recent = ref<Transaction[]>([])
const report = ref<MonthlyReport | null>(null)
const budget = ref<BudgetOverview | null>(null)

// 当前查看的自然月（可选任意年月查看该月概览）。
const month = ref(currentMonth())
const reportLoading = ref(false)
const isCurrentMonth = computed(() => month.value === currentMonth())

const netAssets = computed(() => sumBalances(accounts.value))

const router = useRouter()

// 点击某笔流水 → 打开编辑弹窗（可改/删）。
const editing = ref<Transaction | null>(null)
function openEdit(tx: Transaction) {
  editing.value = tx
}
/** 编辑/删除保存后：刷新当月流水、报表、预算与账户余额。 */
async function onTxSaved() {
  editing.value = null
  try {
    const [page, rep, accs] = await Promise.all([
      fetchTransactions({ month: month.value }),
      fetchMonthlyReport(month.value),
      fetchAccounts(),
    ])
    recent.value = page.items
    report.value = rep
    accounts.value = accs
    loadBudget()
  } catch {
    /* 刷新失败不阻断，保留现有数据 */
  }
}

/** 拉取所选月预算总览（失败不阻塞首页，仅隐藏预算行）。 */
async function loadBudget() {
  try {
    budget.value = await fetchBudgetOverview(month.value)
  } catch {
    budget.value = null
  }
}

// === 月份选择器（任意年月，不可选未来月） ===
const pickerOpen = ref(false)
const pickerYear = ref(Number(currentMonth().split('-')[0]))
const cur = (() => {
  const [y, m] = currentMonth().split('-').map(Number)
  return { y: y ?? 1970, m: m ?? 1 }
})()
const selectedMonthNum = computed(() => Number(month.value.split('-')[1]))
const selectedYearNum = computed(() => Number(month.value.split('-')[0]))

/** 打开选择器，年份定位到当前查看年。 */
function openPicker() {
  pickerYear.value = selectedYearNum.value
  pickerOpen.value = true
}
function stepYear(delta: number) {
  pickerYear.value += delta
}
/** 该 (年, 月) 是否为未来月（不可选）。 */
function isFutureMonth(y: number, m: number): boolean {
  return y > cur.y || (y === cur.y && m > cur.m)
}
/** 选定某月：更新 month 并重新拉取该月概览。 */
async function pickMonth(m: number) {
  if (isFutureMonth(pickerYear.value, m)) return
  month.value = `${pickerYear.value}-${String(m).padStart(2, '0')}`
  pickerOpen.value = false
  reportLoading.value = true
  try {
    const [rep, page] = await Promise.all([
      fetchMonthlyReport(month.value),
      fetchTransactions({ month: month.value }),
    ])
    report.value = rep
    recent.value = page.items
    loadError.value = ''
    loadBudget()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    reportLoading.value = false
  }
}

// === 首页快捷入口（资产/统计接现有页；预算/导入功能待建，先占位提示） ===
interface QuickAction {
  key: string
  label: string
  icon: string
  tint: string
  to?: string // 已有页面直接跳转
  soon?: boolean // 功能未上线，点了提示「敬请期待」
}
const quickActions: QuickAction[] = [
  { key: 'assets', label: '资产', icon: '💎', tint: 'qa-green', to: '/accounts' },
  { key: 'stats', label: '统计', icon: '📊', tint: 'qa-blue', to: '/reports' },
  { key: 'budget', label: '预算', icon: '🧮', tint: 'qa-orange', to: '/budget' },
  { key: 'import', label: '导入', icon: '📥', tint: 'qa-purple', soon: true },
]
const comingSoon = ref('')
let comingSoonTimer: ReturnType<typeof setTimeout> | undefined
function onQuickAction(a: QuickAction) {
  if (a.to) {
    router.push(a.to)
    return
  }
  // 占位：轻提示「敬请期待」，功能后续迭代补齐。
  comingSoon.value = `「${a.label}」功能即将上线，敬请期待～`
  if (comingSoonTimer) clearTimeout(comingSoonTimer)
  comingSoonTimer = setTimeout(() => {
    comingSoon.value = ''
  }, 2000)
}

onMounted(load)
onUnmounted(() => {
  if (comingSoonTimer) clearTimeout(comingSoonTimer)
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accs, cats, page, rep] = await Promise.all([
      fetchAccounts(),
      fetchCategories(),
      fetchTransactions({ month: month.value }),
      fetchMonthlyReport(month.value),
    ])
    accounts.value = accs
    categories.value = cats
    recent.value = page.items
    report.value = rep
    loaded.value = true
    loadBudget()
  } catch (e) {
    // 失败：保留上次已加载数据，仅提示 + 重试（需求 11.5）。
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

// === 按日期分组 ===

interface DayGroup {
  key: string
  label: string
  income: number
  expense: number
  items: Transaction[]
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** `YYYY-MM-DD` → `M月D日 周X`。 */
function dayLabel(key: string): string {
  const [y, m, d] = key.split('-').map(Number)
  const wd = new Date(y || 1970, (m || 1) - 1, d || 1).getDay()
  return `${m}月${d}日 ${WEEKDAYS[wd] ?? ''}`
}

/** 把最近流水按「日」分组（保持后端返回的倒序）。 */
const groupedRecent = computed<DayGroup[]>(() => {
  const map = new Map<string, DayGroup>()
  const order: string[] = []
  for (const tx of recent.value) {
    const key = dayKeyOf(tx.occurredAt)
    let g = map.get(key)
    if (!g) {
      g = { key, label: dayLabel(key), income: 0, expense: 0, items: [] }
      map.set(key, g)
      order.push(key)
    }
    g.items.push(tx)
    const n = Number(tx.amount)
    if (tx.type === 'income') g.income += n
    else if (tx.type === 'expense') g.expense += n
  }
  return order.map((k) => map.get(k)!)
})

// === 展示辅助 ===

/** 交易金额展示带符号：支出为负、收入为正、转账中性。 */
function signedAmount(tx: Transaction): string {
  if (tx.type === 'expense') return `-${formatAmount(tx.amount)}`
  if (tx.type === 'income') return `+${formatAmount(tx.amount)}`
  return formatAmount(tx.amount)
}

function txTitle(tx: Transaction): string {
  if (tx.type === 'transfer') {
    return `${accountNameOf(accounts.value, tx.sourceAccountId)} → ${accountNameOf(accounts.value, tx.destinationAccountId)}`
  }
  return categoryNameOf(categories.value, tx.categoryId) || (tx.type === 'income' ? '收入' : '支出')
}

function txSubtitle(tx: Transaction): string {
  const parts: string[] = []
  if (tx.type !== 'transfer') parts.push(accountNameOf(accounts.value, tx.accountId))
  const t = timeLabelOf(tx.occurredAt)
  if (t) parts.push(t)
  if (tx.note) parts.push(tx.note)
  return parts.join(' · ')
}

/** 交易图标：转账固定，收入/支出按分类名匹配（共用 ledger.categoryEmoji）。 */
function iconOf(tx: Transaction): string {
  if (tx.type === 'transfer') return '🔁'
  const name = categoryNameOf(categories.value, tx.categoryId)
  return categoryEmoji(name, tx.type === 'income' ? 'INCOME' : 'EXPENSE')
}

/** 图标底色：支出红调、收入绿调、转账灰调。 */
function iconBgClass(tx: Transaction): string {
  if (tx.type === 'income') return 'inc-bg'
  if (tx.type === 'transfer') return 'gray-bg'
  return 'exp-bg'
}

/** 账户圆点颜色（按类型）。 */
const ACCOUNT_DOT: Record<AccountType, string> = {
  CASH: '#16a34a',
  BANK_CARD: '#0ea5e9',
  ALIPAY: '#1677ff',
  WECHAT: '#07c160',
  CREDIT_CARD: '#f59e0b',
}
function accountDot(type: AccountType): string {
  return ACCOUNT_DOT[type] ?? '#94a3b8'
}
</script>

<template>
  <section class="home">
    <!-- 加载失败：提示 + 重试（保留上次数据） -->
    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 概览主卡：品牌露出 + 月份选择 + 收支（截图分享自带品牌与数据） -->
      <div class="overview">
        <div class="ov-top">
          <div class="brand"><span class="brand-mk">¥</span>有余</div>
          <button type="button" class="month-chip" :class="{ dim: reportLoading }" @click="openPicker">
            {{ monthLabel(month) }} <span class="car">▾</span>
          </button>
        </div>
        <div class="ov-label">{{ isCurrentMonth ? '本月结余' : '当月结余' }}</div>
        <div class="ov-balance num" :class="{ neg: Number(report?.balance) < 0 }">
          ¥{{ formatAmount(report?.balance ?? '0') }}
        </div>
        <div class="ov-stats">
          <div class="stat">
            <div class="k">收入</div>
            <div class="v num">¥{{ formatAmount(report?.totalIncome ?? '0') }}</div>
          </div>
          <div class="stat">
            <div class="k">支出</div>
            <div class="v num">¥{{ formatAmount(report?.totalExpense ?? '0') }}</div>
          </div>
        </div>

        <!-- 本月剩余预算（点进预算页；未设预算引导设置） -->
        <button v-if="budget" type="button" class="ov-budget" @click="router.push('/budget')">
          <template v-if="budget.hasBudget">
            <span class="obk">预算剩余</span>
            <span class="obv num" :class="{ neg: Number(budget.remaining) < 0 }">
              ¥{{ formatAmount(budget.remaining ?? '0') }}
            </span>
            <span class="obp" :class="(budget.status ?? 'OK').toLowerCase()">已用 {{ budget.usedPercent }}%</span>
          </template>
          <template v-else>
            <span class="obk">还没设本月预算</span>
            <span class="obset">去设置 →</span>
          </template>
        </button>
      </div>

      <!-- 快捷入口行 -->
      <div class="quick-row card">
        <button v-for="a in quickActions" :key="a.key" type="button" class="qa" @click="onQuickAction(a)">
          <span class="qa-ic" :class="a.tint">{{ a.icon }}</span>
          <span class="qa-label">{{ a.label }}</span>
        </button>
      </div>

      <!-- 占位功能轻提示 -->
      <p v-if="comingSoon" class="coming-soon" role="status">{{ comingSoon }}</p>

      <div class="body-grid">
        <!-- 最近流水 -->
        <div class="col-main">
          <div class="section-head">
            <h2>{{ isCurrentMonth ? '本月流水' : '当月流水' }}</h2>
          </div>

          <p v-if="recent.length === 0" class="card empty text-muted">
            {{ isCurrentMonth ? '本月还没有流水，点右下角「＋」记一笔吧。' : '这个月没有流水。' }}
          </p>

          <template v-else>
            <div v-for="g in groupedRecent" :key="g.key" class="day">
              <div class="day-h">
                <span class="day-date">{{ g.label }}</span>
                <span class="day-sum">
                  <span class="inc">收 {{ formatAmount(g.income) }}</span>
                  <span class="exp">支 {{ formatAmount(g.expense) }}</span>
                  <span class="net" :class="g.income - g.expense >= 0 ? 'pos' : 'neg'">
                    净 {{ g.income - g.expense >= 0 ? '+' : '-' }}{{ formatAmount(Math.abs(g.income - g.expense)) }}
                  </span>
                </span>
              </div>
              <ul class="tx-list card">
                <li v-for="tx in g.items" :key="tx.id">
                  <button type="button" class="tx-item" @click="openEdit(tx)">
                    <span class="ico" :class="iconBgClass(tx)">{{ iconOf(tx) }}</span>
                    <div class="tx-info">
                      <div class="tx-title">{{ txTitle(tx) }}</div>
                      <div class="tx-sub text-muted">{{ txSubtitle(tx) }}</div>
                    </div>
                    <div class="tx-amount num" :class="tx.type">{{ signedAmount(tx) }}</div>
                  </button>
                </li>
              </ul>
            </div>
          </template>
        </div>

        <!-- 宽屏右侧：账户简览 -->
        <aside class="col-side">
          <div class="card side-card">
            <div class="side-head">
              <h3>我的账户</h3>
              <RouterLink class="more-link" to="/accounts">管理</RouterLink>
            </div>
            <p v-if="accounts.length === 0" class="text-muted empty-side">还没有账户</p>
            <template v-else>
              <div v-for="a in accounts" :key="a.id" class="acc-row">
                <span class="acc-name">
                  <span class="dot" :style="{ background: accountDot(a.type) }"></span>{{ a.name }}
                </span>
                <span class="acc-bal num" :class="{ neg: Number(a.currentBalance) < 0 }">
                  ¥{{ formatAmount(a.currentBalance) }}
                </span>
              </div>
              <div class="net-row">
                <span>净资产</span>
                <span class="num" :class="{ neg: Number(netAssets) < 0 }">¥{{ formatAmount(netAssets) }}</span>
              </div>
            </template>
          </div>
        </aside>
      </div>
    </template>

    <!-- 悬浮记一笔按钮 -->
    <RouterLink class="fab" to="/quick" aria-label="记一笔">＋</RouterLink>

    <!-- 点击流水 → 编辑/删除弹窗 -->
    <TransactionEditModal
      v-if="editing"
      :transaction="editing"
      :accounts="accounts"
      :categories="categories"
      @close="editing = null"
      @saved="onTxSaved"
    />

    <!-- 月份选择底部面板：任意年月（不可选未来月） -->
    <div v-if="pickerOpen" class="picker-mask" @click.self="pickerOpen = false">
      <div class="picker" role="dialog" aria-label="选择月份">
        <div class="picker-head">
          <button type="button" class="pk-cancel" @click="pickerOpen = false">取消</button>
          <span class="pk-title">选择月份</span>
          <span class="pk-spacer"></span>
        </div>
        <div class="year-row">
          <button type="button" class="y-arrow" aria-label="上一年" @click="stepYear(-1)">‹</button>
          <span class="y-val num">{{ pickerYear }}</span>
          <button
            type="button"
            class="y-arrow"
            aria-label="下一年"
            :disabled="pickerYear >= cur.y"
            @click="stepYear(1)"
          >
            ›
          </button>
        </div>
        <div class="months">
          <button
            v-for="m in 12"
            :key="m"
            type="button"
            class="mo"
            :class="{
              active: pickerYear === selectedYearNum && m === selectedMonthNum,
              future: isFutureMonth(pickerYear, m),
            }"
            :disabled="isFutureMonth(pickerYear, m)"
            @click="pickMonth(m)"
          >
            {{ m }}月
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}
.home {
  padding-bottom: 80px;
}
.loading {
  padding: 24px 0;
}

/* 概览卡顶部：品牌 + 月份 chip */
.ov-top {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 800;
}
.brand-mk {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 800;
}
.month-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
.month-chip.dim {
  opacity: 0.6;
}
.month-chip .car {
  font-size: 11px;
  opacity: 0.9;
}

/* ===== 概览主卡：品牌绿渐变（略收紧，给快捷入口和流水让位） ===== */
.overview {
  position: relative;
  overflow: hidden;
  margin-bottom: 14px;
  padding: 18px 18px 16px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 14px 30px rgba(22, 163, 74, 0.26);
}
.overview::after {
  content: '';
  position: absolute;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  top: -80px;
  right: -40px;
  /* 装饰圆不拦截点击，否则会盖住右上角月份 chip */
  pointer-events: none;
}
.ov-label {
  position: relative;
  font-size: 13px;
  opacity: 0.9;
}
.ov-balance {
  position: relative;
  margin-top: 4px;
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.1;
  overflow-wrap: anywhere;
}
.ov-balance.neg {
  color: #fee2e2;
}
.ov-stats {
  position: relative;
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.ov-stats .stat {
  flex: 1;
  min-width: 0;
  padding: 9px 10px;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.14);
}
.ov-stats .k {
  font-size: 11px;
  opacity: 0.85;
}
.ov-stats .v {
  margin-top: 3px;
  font-weight: 700;
  /* 随屏宽自适应字号，长金额也保持单行不换行 */
  font-size: clamp(11px, 3.4vw, 15px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 本月剩余预算行（绿卡内，半透明白底，可点进预算页） */
.ov-budget {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 12px;
  border: none;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  cursor: pointer;
  text-align: left;
}
.ov-budget .obk {
  font-size: 12px;
  opacity: 0.9;
}
.ov-budget .obv {
  font-size: 16px;
  font-weight: 800;
}
.ov-budget .obv.neg {
  color: #fee2e2;
}
.ov-budget .obp {
  margin-left: auto;
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.2);
}
.ov-budget .obp.warn {
  background: #fff4e5;
  color: #b45309;
}
.ov-budget .obp.over {
  background: #fef2f2;
  color: var(--color-danger);
}
.ov-budget .obset {
  margin-left: auto;
  font-size: 13px;
  font-weight: 700;
}

/* ===== 快捷入口行 ===== */
.quick-row {
  display: flex;
  padding: 14px 4px;
  margin-bottom: 20px;
}
.qa {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  cursor: pointer;
}
.qa-ic {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 21px;
}
.qa-green {
  background: #eafaf0;
}
.qa-blue {
  background: #eef4ff;
}
.qa-orange {
  background: #fff3e6;
}
.qa-purple {
  background: #f3ecff;
}
.qa-label {
  font-size: 12px;
  color: #4b5563;
  font-weight: 600;
}
.coming-soon {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: var(--radius);
  background: #ecfdf5;
  color: var(--color-primary-dark);
  font-size: 13px;
  text-align: center;
}

/* ===== 分区标题 ===== */
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.section-head h2 {
  margin: 0;
  font-size: 16px;
}
.more-link {
  font-size: 13px;
  color: var(--color-primary);
  font-weight: 600;
}
.empty {
  text-align: center;
}

/* ===== 流水（按日分组） ===== */
.day {
  margin-bottom: 14px;
}
.day-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px 6px;
  font-size: 12px;
  color: var(--color-muted);
}
.day-date {
  flex: 0 0 auto;
  font-weight: 600;
}
.day-sum {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
  font-variant-numeric: tabular-nums;
}
.day-sum .inc {
  color: var(--color-primary);
}
.day-sum .exp {
  color: var(--color-danger);
}
.day-sum .net {
  font-weight: 700;
}
.day-sum .net.pos {
  color: var(--color-primary-dark);
}
.day-sum .net.neg {
  color: var(--color-danger);
}

.tx-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
}
.tx-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border: none;
  border-top: 1px solid var(--color-border);
  background: none;
  text-align: left;
  cursor: pointer;
}
.tx-list li:first-child .tx-item {
  border-top: none;
}
.tx-item:active {
  background: var(--color-bg);
}
.ico {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.exp-bg {
  background: #fef2f2;
}
.inc-bg {
  background: #ecfdf5;
}
.gray-bg {
  background: #f1f5f9;
}
.tx-info {
  min-width: 0;
  flex: 1;
}
.tx-title {
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.tx-sub {
  font-size: 12px;
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.tx-amount {
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}
.tx-amount.expense {
  color: var(--color-danger);
}
.tx-amount.income {
  color: var(--color-primary);
}
.tx-amount.transfer {
  color: var(--color-muted);
}

/* ===== 账户简览（宽屏侧栏） ===== */
.col-side {
  display: none;
}
.side-card {
  padding: 18px;
}
.side-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.side-head h3 {
  margin: 0;
  font-size: 15px;
}
.empty-side {
  font-size: 14px;
}
.acc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--color-border);
  font-size: 14px;
}
.acc-row:last-of-type {
  border-bottom: none;
}
.acc-name {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.dot {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  border-radius: 50%;
}
.acc-bal {
  font-weight: 700;
  white-space: nowrap;
}
.acc-bal.neg {
  color: var(--color-danger);
}
.net-row {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 2px solid var(--color-border);
  font-weight: 800;
}
.net-row .neg {
  color: var(--color-danger);
}

/* ===== 悬浮记一笔 ===== */
.fab {
  position: fixed;
  right: clamp(16px, 5vw, 40px);
  bottom: calc(24px + var(--safe-bottom));
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #1eb257, #128a3f);
  color: #fff;
  font-size: 30px;
  font-weight: 300;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 20px rgba(22, 163, 74, 0.4);
  z-index: 20;
}
.fab:active {
  filter: brightness(0.95);
}

/* ===== 月份选择底部面板 ===== */
.picker-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 60;
}
.picker {
  width: 100%;
  max-width: 480px;
  background: var(--color-surface);
  border-radius: 18px 18px 0 0;
  padding: 14px 16px calc(20px + var(--safe-bottom));
}
.picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.pk-cancel {
  border: none;
  background: none;
  color: var(--color-muted);
  font-size: 14px;
  cursor: pointer;
}
.pk-title {
  font-size: 16px;
  font-weight: 800;
}
.pk-spacer {
  width: 28px;
}
.year-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  margin-bottom: 16px;
}
.y-val {
  font-size: 20px;
  font-weight: 800;
  min-width: 72px;
  text-align: center;
}
.y-arrow {
  width: 34px;
  height: 34px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
  font-size: 16px;
  cursor: pointer;
}
.y-arrow:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.months {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.mo {
  height: 46px;
  border: 1px solid var(--color-border);
  border-radius: 11px;
  background: var(--color-surface);
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  cursor: pointer;
}
.mo.active {
  background: var(--color-primary);
  color: #fff;
  border-color: var(--color-primary);
}
.mo.future,
.mo:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
@media (min-width: 768px) {
  .picker-mask {
    align-items: center;
  }
  .picker {
    max-width: 420px;
    border-radius: 16px;
  }
}

/* ===== 加载失败横幅 ===== */
.banner {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
.link-btn {
  border: none;
  background: none;
  color: inherit;
  text-decoration: underline;
  font-weight: 600;
}

/* ===== 宽屏：两栏 + 隐藏悬浮按钮（改用侧栏/顶部入口场景） ===== */
@media (min-width: 768px) {
  .m-label {
    font-size: 28px;
  }
  .overview {
    margin: 0 0 16px;
  }
  .ov-balance {
    font-size: 40px;
  }
  .quick-row {
    margin-bottom: 22px;
  }
  .body-grid {
    display: grid;
    grid-template-columns: 1fr 320px;
    gap: 22px;
    align-items: start;
  }
  .col-side {
    display: block;
  }
  .fab {
    bottom: clamp(24px, 5vh, 48px);
  }
}
</style>
