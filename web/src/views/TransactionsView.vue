<script setup lang="ts">
/**
 * 流水列表页（Transactions）。
 *
 * 需求：
 *  - 2.3：仅展示本人数据（后端强制隔离）。
 *  - 4.6：每条可编辑 / 删除，编辑或删除后余额随之变化（此处删除/编辑后重新拉取列表与展示）。
 *  - 11.1：移动优先响应式；窄屏卡片流、宽屏受宽度约束。
 *  - 11.5：加载失败提示 + 保留上次数据 + 重试。
 *
 * 交易按日期倒序分组（后端已按时间倒序返回，这里按「日」聚合并统计当日收支）。
 * 支持「加载更多」分页累积。
 */
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import TransactionEditModal from '@/components/TransactionEditModal.vue'
import {
  fetchAccounts,
  fetchCategories,
  fetchTransactions,
  deleteTransaction,
  formatAmount,
  accountNameOf,
  categoryNameOf,
  dayKeyOf,
  timeLabelOf,
  toEntryErrorMessage,
  type Account,
  type Category,
  type Transaction,
} from '@/lib/ledger'

const PAGE_SIZE = 20

const loading = ref(true)
const loadError = ref('')
const loaded = ref(false)

const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])
const transactions = ref<Transaction[]>([])

const page = ref(0)
const hasMore = ref(false)
const loadingMore = ref(false)

// 编辑弹窗与删除状态
const editing = ref<Transaction | null>(null)
const deletingId = ref<number | null>(null)
const actionError = ref('')

interface DayGroup {
  day: string
  income: number
  expense: number
  items: Transaction[]
}

/** 按「日」分组并统计当日收入/支出（转账不计入收支）。 */
const groups = computed<DayGroup[]>(() => {
  const map = new Map<string, DayGroup>()
  for (const tx of transactions.value) {
    const day = dayKeyOf(tx.occurredAt)
    let g = map.get(day)
    if (!g) {
      g = { day, income: 0, expense: 0, items: [] }
      map.set(day, g)
    }
    g.items.push(tx)
    const amt = Number(tx.amount)
    if (tx.type === 'income') g.income += amt
    else if (tx.type === 'expense') g.expense += amt
  }
  // 后端按时间倒序，这里保持插入顺序（Map 保序）即为日期倒序。
  return [...map.values()]
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accs, cats, first] = await Promise.all([
      fetchAccounts(),
      fetchCategories(),
      fetchTransactions({ page: 0, size: PAGE_SIZE }),
    ])
    accounts.value = accs
    categories.value = cats
    transactions.value = first.items
    page.value = first.page
    hasMore.value = first.hasMore
    loaded.value = true
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const next = await fetchTransactions({ page: page.value + 1, size: PAGE_SIZE })
    transactions.value = [...transactions.value, ...next.items]
    page.value = next.page
    hasMore.value = next.hasMore
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '加载更多失败，请重试'
  } finally {
    loadingMore.value = false
  }
}

/** 删除后重新拉取，反映余额变化（需求 4.6）。 */
async function onDelete(tx: Transaction) {
  if (!window.confirm('确定删除这笔流水吗？删除后对应账户余额会随之调整。')) return
  actionError.value = ''
  deletingId.value = tx.id
  try {
    await deleteTransaction(tx.id)
    await reloadFromStart()
  } catch (e) {
    actionError.value = toEntryErrorMessage(e)
  } finally {
    deletingId.value = null
  }
}

function onEdit(tx: Transaction) {
  actionError.value = ''
  editing.value = tx
}

async function onSaved() {
  editing.value = null
  await reloadFromStart()
}

/** 编辑/删除后回到首页数据，重新加载首页大小（余额与列表均刷新）。 */
async function reloadFromStart() {
  const first = await fetchTransactions({ page: 0, size: Math.max(PAGE_SIZE, transactions.value.length) })
  transactions.value = first.items
  page.value = 0
  hasMore.value = first.hasMore
  // 账户余额可能变化，刷新账户用于展示。
  try {
    accounts.value = await fetchAccounts()
  } catch {
    // 账户刷新失败不阻断，列表已更新。
  }
}

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
  if (tx.type === 'transfer') parts.push('转账')
  else parts.push(accountNameOf(accounts.value, tx.accountId))
  const t = timeLabelOf(tx.occurredAt)
  if (t) parts.push(t)
  if (tx.note) parts.push(tx.note)
  return parts.join(' · ')
}

function dayLabel(day: string): string {
  const d = new Date(`${day}T00:00:00`)
  if (Number.isNaN(d.getTime())) return day
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${day} ${weekdays[d.getDay()]}`
}
</script>

<template>
  <section class="tx-page">
    <header class="page-head">
      <h1>流水</h1>
      <RouterLink class="btn btn-sm" to="/quick">记一笔</RouterLink>
    </header>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>
    <div v-if="actionError" class="banner banner-err" role="alert">
      <span>{{ actionError }}</span>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <p v-if="groups.length === 0" class="card text-muted empty">
        还没有流水，去 <RouterLink to="/quick">记一笔</RouterLink> 吧。
      </p>

      <div v-for="g in groups" :key="g.day" class="day-group">
        <div class="day-head">
          <span class="day-date">{{ dayLabel(g.day) }}</span>
          <span class="day-sum">
            <span v-if="g.income > 0" class="income">收 ¥{{ formatAmount(g.income) }}</span>
            <span v-if="g.expense > 0" class="expense">支 ¥{{ formatAmount(g.expense) }}</span>
          </span>
        </div>

        <ul class="tx-list card">
          <li v-for="tx in g.items" :key="tx.id" class="tx-item">
            <div class="tx-info">
              <div class="tx-title">{{ txTitle(tx) }}</div>
              <div class="tx-sub text-muted">{{ txSubtitle(tx) }}</div>
            </div>
            <div class="tx-right">
              <div class="tx-amount" :class="tx.type">{{ signedAmount(tx) }}</div>
              <div class="tx-actions">
                <button class="link-btn" type="button" @click="onEdit(tx)">编辑</button>
                <button
                  class="link-btn danger"
                  type="button"
                  :disabled="deletingId === tx.id"
                  @click="onDelete(tx)"
                >
                  {{ deletingId === tx.id ? '删除中…' : '删除' }}
                </button>
              </div>
            </div>
          </li>
        </ul>
      </div>

      <button v-if="hasMore" class="btn btn-ghost btn-block load-more" type="button" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </button>
    </template>

    <TransactionEditModal
      v-if="editing"
      :transaction="editing"
      :accounts="accounts"
      :categories="categories"
      @close="editing = null"
      @saved="onSaved"
    />
  </section>
</template>

<style scoped>
.tx-page {
  padding-bottom: 40px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
}
.btn-sm {
  min-height: 38px;
  padding: 0 14px;
  font-size: 14px;
}
.loading {
  padding: 24px 0;
}
.empty {
  text-align: center;
}

.day-group {
  margin-bottom: 18px;
}
.day-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px 8px;
}
.day-date {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-muted);
}
.day-sum {
  display: flex;
  gap: 12px;
  font-size: 13px;
}
.day-sum .income {
  color: var(--color-primary);
}
.day-sum .expense {
  color: var(--color-danger);
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
.tx-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  white-space: nowrap;
}
.tx-amount {
  font-size: 16px;
  font-weight: 700;
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
.tx-actions {
  display: flex;
  gap: 10px;
}

.load-more {
  margin-top: 8px;
}
.btn-ghost {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
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
  color: var(--color-primary);
  text-decoration: underline;
  font-weight: 600;
  font-size: 13px;
  padding: 0;
  min-height: 32px;
}
.link-btn.danger {
  color: var(--color-danger);
}
.link-btn:disabled {
  opacity: 0.5;
}
</style>
