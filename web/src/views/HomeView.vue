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
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import {
  fetchAccounts,
  fetchCategories,
  fetchTransactions,
  fetchMonthlyReport,
  currentMonth,
  monthLabel,
  formatAmount,
  sumBalances,
  accountNameOf,
  categoryNameOf,
  timeLabelOf,
  dayKeyOf,
  type Account,
  type AccountType,
  type Category,
  type Transaction,
  type MonthlyReport,
} from '@/lib/ledger'

const loading = ref(true)
const loadError = ref('')
const loaded = ref(false) // 是否已成功加载过一次（用于「保留上次数据」）

const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])
const recent = ref<Transaction[]>([])
const report = ref<MonthlyReport | null>(null)

const month = currentMonth()
const netAssets = computed(() => sumBalances(accounts.value))

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accs, cats, page, rep] = await Promise.all([
      fetchAccounts(),
      fetchCategories(),
      fetchTransactions({ page: 0, size: 8 }),
      fetchMonthlyReport(month),
    ])
    accounts.value = accs
    categories.value = cats
    recent.value = page.items
    report.value = rep
    loaded.value = true
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

/** 分类名 → emoji 图标（按关键字粗匹配，未命中给通用票据图标）。 */
const EMOJI_RULES: Array<[RegExp, string]> = [
  [/餐饮|吃|饭|外卖|美食|聚餐|零食|饮/, '🍜'],
  [/交通|地铁|公交|打车|出行|车|油|停车/, '🚇'],
  [/购物|买|衣|鞋|数码|电器/, '🛍️'],
  [/娱乐|游戏|电影|玩/, '🎮'],
  [/居住|房租|房贷|物业|水电|燃气/, '🏠'],
  [/医疗|药|医院|健康/, '💊'],
  [/教育|学习|书|培训|课/, '📚'],
  [/通讯|话费|网费|流量|手机/, '📱'],
  [/旅行|旅游|酒店|机票/, '✈️'],
  [/宠物/, '🐾'],
  [/工资|薪|收入|奖金|报销/, '💰'],
  [/理财|利息|收益|投资|分红/, '📈'],
  [/红包|礼金/, '🧧'],
]

/** 交易图标：转账固定，收入默认 💰，支出按分类名匹配。 */
function iconOf(tx: Transaction): string {
  if (tx.type === 'transfer') return '🔁'
  const name = categoryNameOf(categories.value, tx.categoryId)
  for (const [re, emoji] of EMOJI_RULES) {
    if (re.test(name)) return emoji
  }
  return tx.type === 'income' ? '💰' : '🧾'
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
    <!-- 顶部问候（宽屏改为标题区） -->
    <header class="home-head">
      <div class="greet">
        <h1>本月概览</h1>
        <p class="text-muted">{{ monthLabel(month) }} · 记好每一笔，日子有余</p>
      </div>
    </header>

    <!-- 加载失败：提示 + 重试（保留上次数据） -->
    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 本月概览主卡 -->
      <div class="overview">
        <div class="ov-label">本月结余</div>
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
          <div class="stat">
            <div class="k">净资产</div>
            <div class="v num">¥{{ formatAmount(netAssets) }}</div>
          </div>
        </div>
      </div>

      <div class="body-grid">
        <!-- 最近流水 -->
        <div class="col-main">
          <div class="section-head">
            <h2>最近流水</h2>
            <RouterLink class="more-link" to="/transactions">全部 →</RouterLink>
          </div>

          <p v-if="recent.length === 0" class="card empty text-muted">
            还没有流水，点右下角「＋」记一笔吧。
          </p>

          <template v-else>
            <div v-for="g in groupedRecent" :key="g.key" class="day">
              <div class="day-h">
                <span>{{ g.label }}</span>
                <span class="day-sum">
                  <span v-if="g.income > 0" class="inc">收 ¥{{ formatAmount(g.income) }}</span>
                  <span v-if="g.expense > 0" class="exp">支 ¥{{ formatAmount(g.expense) }}</span>
                </span>
              </div>
              <ul class="tx-list card">
                <li v-for="tx in g.items" :key="tx.id" class="tx-item">
                  <span class="ico" :class="iconBgClass(tx)">{{ iconOf(tx) }}</span>
                  <div class="tx-info">
                    <div class="tx-title">{{ txTitle(tx) }}</div>
                    <div class="tx-sub text-muted">{{ txSubtitle(tx) }}</div>
                  </div>
                  <div class="tx-amount num" :class="tx.type">{{ signedAmount(tx) }}</div>
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
  </section>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}
.home {
  padding-bottom: 80px;
}
.home-head {
  margin-bottom: 16px;
}
.greet h1 {
  margin: 0;
  font-size: 22px;
}
.greet p {
  margin: 4px 0 0;
  font-size: 13px;
}
.loading {
  padding: 24px 0;
}

/* ===== 概览主卡：品牌绿渐变 ===== */
.overview {
  position: relative;
  overflow: hidden;
  margin-bottom: 22px;
  padding: 22px;
  border-radius: 22px;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 16px 34px rgba(22, 163, 74, 0.28);
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
}
.ov-label {
  position: relative;
  font-size: 13px;
  opacity: 0.9;
}
.ov-balance {
  position: relative;
  margin-top: 4px;
  font-size: 38px;
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
  gap: 10px;
  margin-top: 18px;
}
.ov-stats .stat {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.14);
}
.ov-stats .k {
  font-size: 11px;
  opacity: 0.85;
}
.ov-stats .v {
  margin-top: 3px;
  font-size: 15px;
  font-weight: 700;
  overflow-wrap: anywhere;
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
  justify-content: space-between;
  padding: 0 4px 6px;
  font-size: 12px;
  color: var(--color-muted);
}
.day-sum {
  display: flex;
  gap: 12px;
}
.day-sum .inc {
  color: var(--color-primary);
}
.day-sum .exp {
  color: var(--color-danger);
}

.tx-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
}
.tx-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border-bottom: 1px solid var(--color-border);
}
.tx-item:last-child {
  border-bottom: none;
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
  bottom: calc(76px + var(--safe-bottom));
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
  .greet h1 {
    font-size: 26px;
  }
  .overview {
    margin: 0 0 22px;
  }
  .ov-balance {
    font-size: 44px;
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
