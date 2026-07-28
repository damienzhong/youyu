<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth, deleteTransaction } from '../../api/transaction'
import {
  formatAmount,
  categoryEmoji,
  dayKeyOf,
  dayLabel,
  timeLabelOf,
  currentMonth
} from '../../utils/format'

const month = ref(currentMonth())
const transactions = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const loading = ref(false)

const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of transactions.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense }
})

async function load() {
  loading.value = true
  try {
    const [accs, cats, txs] = await Promise.all([
      listAccounts(),
      listCategories(),
      listTransactionsByMonth(month.value)
    ])
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
    categoryMap.value = buildCategoryLabelMap(cats)
    transactions.value = txs
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

const grouped = computed(() => {
  const groups = []
  let cur = null
  for (const t of transactions.value) {
    const day = dayKeyOf(t.occurredAt)
    if (!cur || cur.day !== day) {
      cur = { day, label: dayLabel(day), income: 0, expense: 0, items: [] }
      groups.push(cur)
    }
    cur.items.push(t)
    if (t.type === 'income') cur.income += Number(t.amount)
    else if (t.type === 'expense') cur.expense += Number(t.amount)
  }
  return groups
})

function titleOf(t) {
  if (t.type === 'transfer') {
    return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  }
  return categoryMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function subtitleOf(t) {
  const parts = []
  if (t.type !== 'transfer') parts.push(accountMap.value[t.accountId] || '')
  const tm = timeLabelOf(t.occurredAt)
  if (tm) parts.push(tm)
  if (t.note) parts.push(t.note)
  return parts.filter(Boolean).join(' · ')
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
function iconBgClass(t) {
  if (t.type === 'income') return 'inc-bg'
  if (t.type === 'transfer') return 'gray-bg'
  return 'exp-bg'
}
function signedAmount(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}

function goEdit(t) {
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}` })
}
function confirmDelete(t) {
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回滚账户余额，确定删除？',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteTransaction(t.id)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 月度小结条 -->
    <view class="summary">
      <text class="s-month">{{ month }}</text>
      <view class="s-figs">
        <text class="s-inc">收 {{ formatAmount(totals.income) }}</text>
        <text class="s-exp">支 {{ formatAmount(totals.expense) }}</text>
      </view>
    </view>

    <view v-if="!transactions.length && !loading" class="empty">本月暂无记录</view>

    <view v-for="g in grouped" :key="g.day" class="day">
      <view class="day-h">
        <text class="day-date">{{ g.label }}</text>
        <text class="day-sum">
          <text class="inc">收 {{ formatAmount(g.income) }}</text>
          <text class="exp">支 {{ formatAmount(g.expense) }}</text>
        </text>
      </view>
      <view class="tx-list">
        <view
          v-for="t in g.items"
          :key="t.id"
          class="tx-item"
          @click="goEdit(t)"
          @longpress="confirmDelete(t)"
        >
          <text class="ico" :class="iconBgClass(t)">{{ iconOf(t) }}</text>
          <view class="tx-info">
            <text class="tx-title">{{ titleOf(t) }}</text>
            <text class="tx-sub">{{ subtitleOf(t) }}</text>
          </view>
          <text class="tx-amount" :class="t.type">{{ signedAmount(t) }}</text>
        </view>
      </view>
    </view>

    <text v-if="transactions.length" class="hint">点击编辑 · 长按删除</text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 20rpx;
}
.s-month {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2937;
}
.s-figs {
  display: flex;
  gap: 24rpx;
  font-size: 26rpx;
}
.s-inc { color: #16a34a; }
.s-exp { color: #dc2626; }

.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
}
.day {
  margin-bottom: 24rpx;
}
.day-h {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 0 8rpx 12rpx;
  font-size: 24rpx;
  color: #6b7280;
}
.day-date {
  font-weight: 600;
}
.day-sum {
  display: flex;
  gap: 20rpx;
}
.day-sum .inc { color: #16a34a; }
.day-sum .exp { color: #dc2626; }

.tx-list {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
}
.tx-item {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.tx-list .tx-item:first-child {
  border-top: none;
}
.ico {
  width: 76rpx;
  height: 76rpx;
  border-radius: 22rpx;
  text-align: center;
  line-height: 76rpx;
  font-size: 36rpx;
}
.exp-bg { background: #fef2f2; }
.inc-bg { background: #ecfdf5; }
.gray-bg { background: #f1f5f9; }
.tx-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.tx-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2937;
}
.tx-sub {
  font-size: 24rpx;
  color: #6b7280;
}
.tx-amount {
  font-size: 32rpx;
  font-weight: 800;
}
.tx-amount.expense { color: #dc2626; }
.tx-amount.income { color: #16a34a; }
.tx-amount.transfer { color: #6b7280; }
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 24rpx 0;
}
</style>
