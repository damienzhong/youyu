<script setup lang="ts">
/**
 * 首页（Home）。
 *
 * 展示（需求 2.3 数据隔离由后端保证；11.1 响应式；11.5 加载失败保留旧数据+重试）：
 *  - 本月结余 / 收入 / 支出（GET /reports/monthly）。
 *  - 净资产 = 全部账户当前余额之和（GET /accounts）。
 *  - 最近流水（GET /transactions 首页取前若干条）。
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
  formatAmount,
  sumBalances,
  accountNameOf,
  categoryNameOf,
  timeLabelOf,
  type Account,
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
      fetchTransactions({ page: 0, size: 5 }),
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
</script>

<template>
  <section class="home">
    <header class="home-head">
      <h1>有余</h1>
      <span class="text-muted month">{{ month }}</span>
    </header>

    <!-- 加载失败：提示 + 重试（保留上次数据） -->
    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 本月概览 -->
      <div class="card overview">
        <div class="overview-main">
          <div class="ov-label">本月结余</div>
          <div class="ov-balance" :class="{ neg: Number(report?.balance) < 0 }">
            ¥{{ formatAmount(report?.balance ?? '0') }}
          </div>
        </div>
        <div class="overview-sub">
          <div class="sub-item">
            <span class="sub-label">收入</span>
            <span class="sub-val income">¥{{ formatAmount(report?.totalIncome ?? '0') }}</span>
          </div>
          <div class="sub-item">
            <span class="sub-label">支出</span>
            <span class="sub-val expense">¥{{ formatAmount(report?.totalExpense ?? '0') }}</span>
          </div>
          <div class="sub-item">
            <span class="sub-label">净资产</span>
            <span class="sub-val" :class="{ neg: Number(netAssets) < 0 }">¥{{ formatAmount(netAssets) }}</span>
          </div>
        </div>
      </div>

      <!-- 最近流水 -->
      <div class="section-head">
        <h2>最近流水</h2>
        <RouterLink class="more-link" to="/transactions">全部</RouterLink>
      </div>

      <p v-if="recent.length === 0" class="card text-muted empty">
        还没有流水，点右下角「＋」记一笔吧。
      </p>

      <ul v-else class="tx-list card">
        <li v-for="tx in recent" :key="tx.id" class="tx-item">
          <div class="tx-info">
            <div class="tx-title">{{ txTitle(tx) }}</div>
            <div class="tx-sub text-muted">{{ txSubtitle(tx) }}</div>
          </div>
          <div class="tx-amount" :class="tx.type">{{ signedAmount(tx) }}</div>
        </li>
      </ul>
    </template>

    <!-- 悬浮记一笔按钮 -->
    <RouterLink class="fab" to="/quick" aria-label="记一笔">＋</RouterLink>
  </section>
</template>

<style scoped>
.home {
  padding-bottom: 80px;
}
.home-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;
}
.home-head h1 {
  margin: 0;
  font-size: 22px;
  color: var(--color-primary);
}
.month {
  font-size: 14px;
}
.loading {
  padding: 24px 0;
}

.overview {
  margin-bottom: 20px;
}
.overview-main {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 14px;
}
.ov-label {
  font-size: 14px;
  color: var(--color-muted);
  margin-bottom: 6px;
}
.ov-balance {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
  overflow-wrap: anywhere;
}
.ov-balance.neg {
  color: var(--color-danger);
}
.overview-sub {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.sub-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sub-label {
  font-size: 13px;
  color: var(--color-muted);
}
.sub-val {
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.sub-val.income {
  color: var(--color-primary);
}
.sub-val.expense {
  color: var(--color-danger);
}
.sub-val.neg {
  color: var(--color-danger);
}

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
  font-size: 14px;
  color: var(--color-primary);
  font-weight: 600;
}
.empty {
  text-align: center;
}

.tx-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.tx-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}
.tx-item:last-child {
  border-bottom: none;
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
  font-size: 13px;
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.tx-amount {
  font-size: 16px;
  font-weight: 700;
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

.fab {
  position: fixed;
  right: clamp(16px, 5vw, 40px);
  bottom: calc(76px + var(--safe-bottom));
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--color-primary);
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
  background: var(--color-primary-dark);
}
@media (min-width: 768px) {
  .fab {
    bottom: clamp(24px, 5vh, 48px);
  }
}

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
</style>
