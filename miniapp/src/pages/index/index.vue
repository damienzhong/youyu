<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth } from '../../api/transaction'
import {
  formatAmount,
  categoryEmoji,
  dayKeyOf,
  dayLabel,
  timeLabelOf,
  currentMonth,
  monthLabel
} from '../../utils/format'

const auth = useAuthStore()

const month = ref(currentMonth())
const loading = ref(false)
const loaded = ref(false)

const accounts = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const transactions = ref([])

// 本月收入/支出/结余（排除转账），与后端月报口径一致
const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of transactions.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense, balance: income - expense }
})

// 净资产 = 计入总资产的账户当前余额之和
const netWorth = computed(() =>
  accounts.value
    .filter((a) => a.includeInTotal)
    .reduce((s, a) => s + Number(a.currentBalance), 0)
)

async function load() {
  loading.value = true
  try {
    const [accs, cats, txs] = await Promise.all([
      listAccounts(),
      listCategories(),
      listTransactionsByMonth(month.value)
    ])
    accounts.value = accs
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
    categoryMap.value = buildCategoryLabelMap(cats)
    transactions.value = txs
    loaded.value = true
  } catch (e) {
    if (e && e.code !== 'HTTP_401') {
      uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  } finally {
    loading.value = false
  }
}

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  load()
})

// 按天分组（后端已倒序）
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

function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
function goEdit(t) {
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}` })
}
function goBudget() {
  uni.navigateTo({ url: '/pages/budget/budget' })
}
function goReport() {
  uni.switchTab({ url: '/pages/report/report' })
}
function goAccounts() {
  uni.switchTab({ url: '/pages/accounts/accounts' })
}
</script>

<template>
  <view class="home">
    <!-- 概览主卡：品牌绿渐变 -->
    <view class="overview">
      <view class="ov-top">
        <view class="brand"><text class="brand-mk">¥</text><text>有余</text></view>
        <view class="month-chip">{{ monthLabel(month) }}</view>
      </view>
      <text class="ov-label">本月结余</text>
      <text class="ov-balance" :class="{ neg: totals.balance < 0 }">
        ¥{{ formatAmount(totals.balance) }}
      </text>
      <view class="ov-stats">
        <view class="stat">
          <text class="k">收入</text>
          <text class="v">¥{{ formatAmount(totals.income) }}</text>
        </view>
        <view class="stat">
          <text class="k">支出</text>
          <text class="v">¥{{ formatAmount(totals.expense) }}</text>
        </view>
        <view class="stat">
          <text class="k">净资产</text>
          <text class="v" :class="{ neg: netWorth < 0 }">¥{{ formatAmount(netWorth) }}</text>
        </view>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="quick-row">
      <view class="qa" @click="goRecord">
        <text class="qa-ic qa-green">✏️</text><text class="qa-label">记一笔</text>
      </view>
      <view class="qa" @click="goBudget">
        <text class="qa-ic qa-orange">🧮</text><text class="qa-label">预算</text>
      </view>
      <view class="qa" @click="goReport">
        <text class="qa-ic qa-blue">📊</text><text class="qa-label">报表</text>
      </view>
      <view class="qa" @click="goAccounts">
        <text class="qa-ic qa-purple">💎</text><text class="qa-label">账户</text>
      </view>
    </view>

    <!-- 本月流水 -->
    <view class="section-head"><text class="sh-title">本月流水</text></view>

    <view v-if="loaded && !transactions.length" class="empty">
      本月还没有流水，点上方「记一笔」记录第一笔吧。
    </view>

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
  </view>
</template>

<style scoped>
.home {
  padding: 24rpx 24rpx 40rpx;
}

/* 概览主卡 */
.overview {
  position: relative;
  overflow: hidden;
  border-radius: 28rpx;
  padding: 36rpx 36rpx 32rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
}
.ov-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12rpx;
  font-size: 34rpx;
  font-weight: 800;
}
.brand-mk {
  width: 48rpx;
  height: 48rpx;
  border-radius: 14rpx;
  background: rgba(255, 255, 255, 0.22);
  text-align: center;
  line-height: 48rpx;
  font-size: 28rpx;
  font-weight: 800;
}
.month-chip {
  padding: 8rpx 22rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.18);
  font-size: 26rpx;
  font-weight: 700;
}
.ov-label {
  font-size: 26rpx;
  opacity: 0.9;
}
.ov-balance {
  display: block;
  margin-top: 8rpx;
  font-size: 68rpx;
  font-weight: 800;
  line-height: 1.1;
}
.ov-balance.neg {
  color: #fee2e2;
}
.ov-stats {
  display: flex;
  gap: 16rpx;
  margin-top: 28rpx;
}
.ov-stats .stat {
  flex: 1;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.14);
}
.ov-stats .k {
  display: block;
  font-size: 22rpx;
  opacity: 0.85;
}
.ov-stats .v {
  display: block;
  margin-top: 6rpx;
  font-size: 28rpx;
  font-weight: 700;
}
.ov-stats .v.neg {
  color: #fee2e2;
}

/* 快捷入口 */
.quick-row {
  display: flex;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 8rpx;
  margin: 24rpx 0;
}
.qa {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
}
.qa-ic {
  width: 88rpx;
  height: 88rpx;
  border-radius: 26rpx;
  text-align: center;
  line-height: 88rpx;
  font-size: 40rpx;
}
.qa-green { background: #eafaf0; }
.qa-orange { background: #fff3e6; }
.qa-blue { background: #eef4ff; }
.qa-purple { background: #f3ecff; }
.qa-label {
  font-size: 24rpx;
  color: #4b5563;
  font-weight: 600;
}

/* 分区标题 */
.section-head {
  margin: 8rpx 8rpx 16rpx;
}
.sh-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #1f2937;
}
.empty {
  text-align: center;
  color: #6b7280;
  font-size: 28rpx;
  padding: 60rpx 0;
}

/* 按日分组流水 */
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
</style>
