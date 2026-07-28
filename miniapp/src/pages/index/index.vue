<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useLedgerStore } from '../../stores/ledger'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth } from '../../api/transaction'
import { listAllAccounts, listAllTransactionsByMonth } from '../../api/aggregate'
import { budgetOverview } from '../../api/budget'
import { createLedger } from '../../api/ledger'
import {
  formatAmount,
  categoryEmoji,
  dayKeyOf,
  dayLabel,
  timeLabelOf,
  currentMonth
} from '../../utils/format'

const auth = useAuthStore()
const ledgerStore = useLedgerStore()

const month = ref(currentMonth())
const loaded = ref(false)
const accounts = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const transactions = ref([])
const remainingBudget = ref(null)

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const yearLabel = computed(() => month.value.split('-')[0] + '年')
const monthLabelShort = computed(() => Number(month.value.split('-')[1]) + '月')

const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of transactions.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense, net: income - expense }
})

function signedNet(n) {
  return (n >= 0 ? '+' : '-') + formatAmount(Math.abs(n))
}

async function load() {
  try {
    const cats = await listCategories()
    categoryMap.value = buildCategoryLabelMap(cats)
    if (ledgerStore.isAll) {
      // 全部账本：跨账本聚合只读视图
      const [accs, txs] = await Promise.all([
        listAllAccounts(),
        listAllTransactionsByMonth(month.value)
      ])
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
      transactions.value = txs
      remainingBudget.value = null
    } else {
      const [accs, txs] = await Promise.all([
        listAccounts(),
        listTransactionsByMonth(month.value)
      ])
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
      transactions.value = txs
      loadBudget()
    }
    loaded.value = true
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

function onMonthChange(e) {
  month.value = e.detail.value
  load()
}

async function loadBudget() {
  try {
    const ov = await budgetOverview(month.value)
    remainingBudget.value = ov.hasBudget ? ov.remaining : null
  } catch (e) {
    remainingBudget.value = null
  }
}

onShow(async () => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  try {
    await ledgerStore.load()
  } catch (e) {
    /* 账本加载失败不阻断 */
  }
  load()
})

// ---------- 账本切换底部弹层 ----------
const LEDGER_EMOJI = ['🧾', '🐾', '🏠', '💼', '✈️', '🎁', '📚', '🍼']
function ledgerEmoji(i) {
  return LEDGER_EMOJI[i % LEDGER_EMOJI.length]
}
const showLedgerSheet = ref(false)
function pickLedger(id) {
  showLedgerSheet.value = false
  if (id !== ledgerStore.currentLedgerId) {
    ledgerStore.setCurrent(id)
    uni.reLaunch({ url: '/pages/index/index' })
  }
}
function addLedger() {
  showLedgerSheet.value = false
  uni.showModal({
    title: '新建账本',
    editable: true,
    placeholderText: '账本名称',
    success: async (r) => {
      if (!r.confirm || !r.content?.trim()) return
      try {
        const l = await createLedger(r.content.trim())
        ledgerStore.setCurrent(l.id)
        uni.reLaunch({ url: '/pages/index/index' })
      } catch (e) {
        uni.showToast({ title: e.message || '创建失败', icon: 'none' })
      }
    }
  })
}

// ---------- 按日分组流水 ----------
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
function subOf(t) {
  if (t.type === 'transfer') return t.note || '转账'
  return accountMap.value[t.accountId] || ''
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
function iconBg(t) {
  if (t.type === 'income') return '#ecfdf5'
  if (t.type === 'transfer') return '#f1f5f9'
  return '#fef2f2'
}
function signed(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}

function goRecord() {
  if (ledgerStore.isAll) {
    uni.showToast({ title: '「全部」下请先选择具体账本', icon: 'none' })
    showLedgerSheet.value = true
    return
  }
  uni.navigateTo({ url: '/pages/record/record' })
}
function goEdit(t) {
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}` })
}
function goAccounts() {
  uni.switchTab({ url: '/pages/accounts/accounts' })
}
function goReport() {
  uni.switchTab({ url: '/pages/report/report' })
}
function goBudget() {
  uni.navigateTo({ url: '/pages/budget/budget' })
}
function goImport() {
  uni.navigateTo({ url: '/pages/billimport/billimport' })
}
</script>

<template>
  <view class="home">
    <!-- 绿色顶部区（自定义导航 + 概览） -->
    <view class="top">
      <view class="statusbar" :style="{ height: statusBarHeight }"></view>
      <view class="nav">
        <text class="nav-menu" @click="showLedgerSheet = true">☰</text>
        <view class="nav-title" @click="showLedgerSheet = true">
          <text>{{ ledgerStore.currentName }}</text>
          <text class="nav-caret">▾</text>
        </view>
        <view class="nav-right"></view>
      </view>

      <view class="summary">
        <picker mode="date" fields="month" :value="month" @change="onMonthChange">
          <view class="sum-month">
            <text class="sm-year">{{ yearLabel }}</text>
            <text class="sm-month">{{ monthLabelShort }} ▾</text>
          </view>
        </picker>
        <view class="sum-figs">
          <view class="fig">
            <text class="fig-k">支出</text>
            <text class="fig-v">{{ formatAmount(totals.expense) }}</text>
          </view>
          <view class="fig">
            <text class="fig-k">收入</text>
            <text class="fig-v">{{ formatAmount(totals.income) }}</text>
          </view>
          <view class="fig">
            <text class="fig-k">净收支</text>
            <text class="fig-v">{{ signedNet(totals.net) }}</text>
          </view>
          <view class="fig">
            <text class="fig-k">剩余预算</text>
            <text class="fig-v">{{ remainingBudget != null ? formatAmount(remainingBudget) : '未设' }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 快捷入口（白卡，上浮覆盖绿色边界） -->
    <view class="quick">
      <view class="qa" @click="goAccounts">
        <text class="qa-ic" style="background:#eafaf0">💎</text><text class="qa-l">资产</text>
      </view>
      <view class="qa" @click="goReport">
        <text class="qa-ic" style="background:#eef4ff">📊</text><text class="qa-l">统计</text>
      </view>
      <view class="qa" @click="goBudget">
        <text class="qa-ic" style="background:#fff3e6">🧮</text><text class="qa-l">预算</text>
      </view>
      <view class="qa" @click="goImport">
        <text class="qa-ic" style="background:#f3ecff">📥</text><text class="qa-l">导入</text>
      </view>
    </view>

    <!-- 按日分组流水 -->
    <view class="content">
      <view v-if="loaded && !transactions.length" class="empty">
        本月还没有流水，点右下角「＋」记一笔
      </view>

      <view v-for="g in grouped" :key="g.day" class="day">
        <view class="day-h">
          <text class="day-date">{{ g.label }}</text>
          <text class="day-sum">收 {{ formatAmount(g.income) }}　支 {{ formatAmount(g.expense) }}</text>
        </view>
        <view class="tx-list">
          <view v-for="t in g.items" :key="t.id" class="tx" @click="goEdit(t)">
            <text class="tx-ic" :style="{ background: iconBg(t) }">{{ iconOf(t) }}</text>
            <text class="tx-title">{{ titleOf(t) }}</text>
            <view class="tx-right">
              <text class="tx-amt" :class="t.type">{{ signed(t) }}</text>
              <text class="tx-sub">{{ subOf(t) }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 记一笔悬浮按钮 -->
    <view class="fab" @click="goRecord">＋</view>

    <!-- 账本选择底部弹层 -->
    <view v-if="showLedgerSheet" class="mask" @click="showLedgerSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-cancel" @click="showLedgerSheet = false">取消</text>
          <text class="sheet-title">选择账本</text>
          <text class="sheet-spacer"></text>
        </view>
        <scroll-view scroll-y class="sheet-list">
          <view class="sheet-item" @click="pickLedger('all')">
            <text class="li-ic">🗂️</text>
            <text class="li-name">全部账本</text>
            <text class="li-radio" :class="{ on: ledgerStore.currentLedgerId === 'all' }"></text>
          </view>
          <view
            v-for="(l, i) in ledgerStore.ledgers"
            :key="l.id"
            class="sheet-item"
            @click="pickLedger(l.id)"
          >
            <text class="li-ic">{{ ledgerEmoji(i) }}</text>
            <text class="li-name">{{ l.name }}</text>
            <text class="li-radio" :class="{ on: l.id === ledgerStore.currentLedgerId }"></text>
          </view>
        </scroll-view>
        <view class="sheet-add" @click="addLedger">＋ 添加</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: #f2f4f5;
}

/* 绿色顶部 */
.top {
  background: linear-gradient(160deg, #22b06b, #16a34a 70%);
  padding-bottom: 72rpx;
}
.nav {
  display: flex;
  align-items: center;
  height: 88rpx;
  padding: 0 24rpx;
  color: #fff;
}
.nav-menu {
  font-size: 40rpx;
  width: 60rpx;
}
.nav-title {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  font-size: 34rpx;
  font-weight: 700;
}
.nav-caret {
  font-size: 22rpx;
  opacity: 0.9;
}
.nav-right {
  width: 60rpx;
}
.summary {
  display: flex;
  align-items: center;
  padding: 8rpx 36rpx 0;
  color: #fff;
}
.sum-month {
  display: flex;
  flex-direction: column;
  margin-right: 20rpx;
}
.sm-year {
  font-size: 22rpx;
  opacity: 0.9;
}
.sm-month {
  font-size: 28rpx;
  font-weight: 700;
}
.sum-figs {
  flex: 1;
  display: flex;
  gap: 12rpx;
}
.fig {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.fig-k {
  font-size: 20rpx;
  opacity: 0.85;
  white-space: nowrap;
}
.fig-v {
  font-size: 27rpx;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 快捷入口白卡上浮 */
.quick {
  display: flex;
  margin: -52rpx 24rpx 0;
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 8rpx;
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.05);
}
.qa {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.qa-ic {
  width: 84rpx;
  height: 84rpx;
  border-radius: 50%;
  text-align: center;
  line-height: 84rpx;
  font-size: 40rpx;
}
.qa-l {
  font-size: 24rpx;
  color: #4b5563;
}

/* 流水 */
.content {
  padding: 24rpx;
}
.empty {
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
  padding: 80rpx 0;
}
.day {
  margin-bottom: 20rpx;
}
.day-h {
  display: flex;
  justify-content: space-between;
  padding: 0 8rpx 12rpx;
  font-size: 24rpx;
  color: #9ca3af;
}
.tx-list {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
}
.tx {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 28rpx;
  border-top: 1rpx solid #f2f4f5;
}
.tx-list .tx:first-child {
  border-top: none;
}
.tx-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  text-align: center;
  line-height: 72rpx;
  font-size: 34rpx;
}
.tx-title {
  flex: 1;
  font-size: 30rpx;
  color: #1f2937;
}
.tx-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
}
.tx-amt {
  font-size: 30rpx;
  font-weight: 700;
}
.tx-amt.expense { color: #e64340; }
.tx-amt.income { color: #16a34a; }
.tx-amt.transfer { color: #6b7280; }
.tx-sub {
  font-size: 22rpx;
  color: #9ca3af;
}

/* FAB */
.fab {
  position: fixed;
  right: 44rpx;
  bottom: 60rpx;
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #1eb257, #128a3f);
  color: #fff;
  font-size: 60rpx;
  line-height: 100rpx;
  text-align: center;
  box-shadow: 0 12rpx 30rpx rgba(22, 163, 74, 0.4);
}

/* 账本弹层 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  padding: 28rpx 32rpx;
  border-bottom: 1rpx solid #f2f4f5;
}
.sheet-cancel {
  font-size: 28rpx;
  color: #9ca3af;
  width: 80rpx;
}
.sheet-title {
  flex: 1;
  text-align: center;
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2937;
}
.sheet-spacer {
  width: 80rpx;
}
.sheet-list {
  max-height: 560rpx;
}
.sheet-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 28rpx 36rpx;
}
.li-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  background: #f5f6f7;
  text-align: center;
  line-height: 64rpx;
  font-size: 34rpx;
}
.li-name {
  flex: 1;
  font-size: 30rpx;
  color: #1f2937;
}
.li-radio {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 2rpx solid #d1d5db;
  box-sizing: border-box;
}
.li-radio.on {
  border-color: #16a34a;
  background:
    radial-gradient(circle at center, #16a34a 0, #16a34a 10rpx, #fff 11rpx, #fff 100%);
}
.sheet-add {
  text-align: center;
  padding: 32rpx;
  font-size: 30rpx;
  color: #16a34a;
  font-weight: 600;
  border-top: 1rpx solid #f2f4f5;
}
</style>
