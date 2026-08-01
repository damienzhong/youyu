<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  listRepayReminders,
  accountTypeLabel,
  accountTypeIcon,
  isCreditType,
  ACCOUNT_GROUPS
} from '../../api/account'
import { listLoans } from '../../api/loan'
import { useLedgerStore } from '../../stores/ledger'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})

// 借贷汇总（仅具体账本显示；借贷为账本级台账）
const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const reminders = ref([])
const showLoans = computed(() => !ledgerStore.isAll)

// 计入净资产、非隐藏的账户参与统计
const counted = computed(() => accounts.value.filter((a) => a.includeInTotal && !a.hidden))
const netWorth = computed(() => counted.value.reduce((s, a) => s + Number(a.currentBalance), 0))
const totalAssets = computed(() =>
  counted.value.reduce((s, a) => s + Math.max(Number(a.currentBalance), 0), 0)
)
const totalLiab = computed(() =>
  counted.value.reduce((s, a) => s + Math.min(Number(a.currentBalance), 0), 0)
)

// 按分组聚合（仅展示有账户的组），组内保持后端排序
const groups = computed(() => {
  return ACCOUNT_GROUPS.map((g) => {
    const items = accounts.value.filter((a) => (a.group || 'FUNDS') === g.key)
    const subtotal = items.reduce((s, a) => s + Number(a.currentBalance), 0)
    return { ...g, items, subtotal }
  }).filter((g) => g.items.length)
})

function availableOf(a) {
  if (!isCreditType(a.type) || a.creditLimit == null) return null
  return Number(a.creditLimit) + Number(a.currentBalance)
}
function money(v) {
  return hideAmounts.value ? '****' : formatAmount(v)
}
function toggleGroup(key) {
  collapsed.value[key] = !collapsed.value[key]
}

async function load() {
  loading.value = true
  try {
    // 资产始终是「你自己的全部账户」，与当前选哪个账本无关（账本不持有资产）。
    accounts.value = await listAccounts()
    try {
      reminders.value = await listRepayReminders()
    } catch (e) { /* 还款提醒加载失败不阻断资产页 */ }
    if (showLoans.value) {
      try {
        const r = await listLoans()
        borrowOutstanding.value = r.borrowOutstanding
        lendOutstanding.value = r.lendOutstanding
      } catch (e) { /* 借贷加载失败不阻断资产页 */ }
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)

function goLoans() {
  uni.navigateTo({ url: '/pages/loans/loans' })
}
function openAccount(a) {
  uni.navigateTo({ url: `/pages/accountdetail/accountdetail?id=${a.id}` })
}
function openCreate() {
  uni.navigateTo({ url: '/pages/accountedit/accountedit' })
}
</script>

<template>
  <view class="page">
    <!-- 净资产卡 -->
    <view class="networth">
      <view class="nw-top">
        <text class="nw-label">净资产 <text class="eye" @click="hideAmounts = !hideAmounts">{{ hideAmounts ? '🙈' : '👁' }}</text></text>
      </view>
      <text class="nw-value" :class="{ neg: netWorth < 0 }">{{ money(netWorth) }}</text>
      <view class="nw-foot">
        <text>总资产 {{ money(totalAssets) }}</text>
        <text>总负债 {{ money(Math.abs(totalLiab)) }}</text>
      </view>
    </view>

    <!-- 借贷往来（借入待还 / 借出待收） -->
    <view v-if="showLoans" class="loan-row" @click="goLoans">
      <view class="loan-tile">
        <text class="lt-k">借入 / 待还</text>
        <text class="lt-v exp">{{ money(borrowOutstanding) }}</text>
      </view>
      <view class="loan-sep"></view>
      <view class="loan-tile">
        <text class="lt-k">借出 / 待收</text>
        <text class="lt-v inc">{{ money(lendOutstanding) }}</text>
      </view>
      <text class="loan-caret">›</text>
    </view>

    <!-- 信用卡还款提醒 -->
    <view v-if="reminders.length" class="repay">
      <view class="repay-head"><text class="rp-title">信用卡还款</text></view>
      <view v-for="r in reminders" :key="r.accountId" class="repay-row">
        <view class="rp-ic"><AppIcon name="card" :size="34" color="#e5563d" /></view>
        <view class="rp-main">
          <text class="rp-name">{{ r.name }}</text>
          <text class="rp-sub">每月 {{ r.repayDay }} 日还款</text>
        </view>
        <view class="rp-right">
          <text class="rp-days" :class="{ soon: r.daysUntil <= 3 }">{{ r.daysUntil === 0 ? '今天' : r.daysUntil + ' 天后' }}</text>
          <text class="rp-owed">待还 {{ money(r.owed) }}</text>
        </view>
      </view>
    </view>

    <view v-if="!accounts.length && !loading" class="empty">还没有账户，点右下角添加</view>

    <!-- 分组账户 -->
    <view v-for="g in groups" :key="g.key" class="group">
      <view class="group-head" @click="toggleGroup(g.key)">
        <text class="gh-title">{{ g.label }}</text>
        <view class="gh-right">
          <text class="gh-sum" :class="{ neg: g.subtotal < 0 }">{{ money(g.subtotal) }}</text>
          <text class="gh-caret">{{ collapsed[g.key] ? '▾' : '▴' }}</text>
        </view>
      </view>
      <view v-if="!collapsed[g.key]" class="acc-list">
        <view v-for="a in g.items" :key="a.id" class="acc" @click="openAccount(a)">
          <view class="acc-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="42" /></view>
          <view class="acc-main">
            <text class="acc-name">{{ a.name }}<text v-if="!a.includeInTotal" class="acc-flag"> · 不计入</text></text>
            <text v-if="availableOf(a) != null" class="acc-sub">可用 {{ money(availableOf(a)) }}</text>
            <text v-else class="acc-sub">{{ accountTypeLabel(a.type) }}</text>
          </view>
          <text class="acc-bal" :class="{ neg: Number(a.currentBalance) < 0 }">{{ money(a.currentBalance) }}</text>
        </view>
      </view>
    </view>

    <view style="height:210rpx;"></view>
    <view class="fab" @click="openCreate">＋</view>

    <TabBar active="assets" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #eef0f2;
}
/* 净资产卡 */
.networth {
  border-radius: 26rpx;
  padding: 36rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #2b3a34, #1f2a30 70%);
  box-shadow: 0 18rpx 40rpx rgba(31, 42, 48, 0.28);
}
.nw-label { font-size: 24rpx; opacity: 0.85; }
.eye { font-size: 24rpx; margin-left: 8rpx; }
.nw-value {
  display: block;
  font-size: 68rpx;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 8rpx 0 20rpx;
}
.nw-value::before { content: '¥'; font-size: 36rpx; opacity: 0.8; margin-right: 6rpx; }
.nw-value.neg { color: #fecaca; }
.nw-foot { display: flex; justify-content: space-between; font-size: 24rpx; opacity: 0.9; }
.loan-row {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 22rpx;
  padding: 24rpx 28rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.loan-tile { flex: 1; display: flex; flex-direction: column; gap: 8rpx; }
.lt-k { font-size: 24rpx; color: #9aa2ad; }
.lt-v { font-size: 34rpx; font-weight: 800; }
.lt-v::before { content: '¥'; font-size: 22rpx; opacity: 0.7; margin-right: 2rpx; }
.lt-v.exp { color: #e5563d; }
.lt-v.inc { color: #0f8a45; }
.loan-sep { width: 1rpx; height: 56rpx; background: #eceef1; margin: 0 8rpx; }
.loan-caret { color: #c0c4cc; font-size: 34rpx; margin-left: 12rpx; }
.repay {
  background: #fff;
  border-radius: 22rpx;
  padding: 8rpx 28rpx 12rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.repay-head { padding: 20rpx 0 12rpx; }
.rp-title { font-size: 26rpx; font-weight: 800; color: #16181c; }
.repay-row { display: flex; align-items: center; gap: 18rpx; padding: 20rpx 0; border-top: 1rpx solid #f1f3f5; }
.rp-ic {
  width: 64rpx; height: 64rpx; border-radius: 18rpx; background: #fdece8;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.rp-main { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.rp-name { font-size: 28rpx; font-weight: 600; color: #16181c; }
.rp-sub { font-size: 22rpx; color: #9aa2ad; }
.rp-right { display: flex; flex-direction: column; align-items: flex-end; gap: 6rpx; }
.rp-days { font-size: 26rpx; font-weight: 700; color: #5b6470; }
.rp-days.soon { color: #e5563d; }
.rp-owed { font-size: 22rpx; color: #9aa2ad; }
.empty { margin-top: 120rpx; text-align: center; color: #9aa2ad; font-size: 28rpx; }
.group { margin-bottom: 20rpx; }
.group-head { display: flex; justify-content: space-between; align-items: center; padding: 8rpx 12rpx 14rpx; }
.gh-title { font-size: 26rpx; font-weight: 700; color: #5b6470; }
.gh-right { display: flex; align-items: center; gap: 12rpx; }
.gh-sum { font-size: 28rpx; font-weight: 800; color: #16181c; }
.gh-sum.neg { color: #e5484d; }
.gh-caret { font-size: 22rpx; color: #9aa2ad; }
.acc-list {
  background: #fff;
  border-radius: 22rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.acc { display: flex; align-items: center; gap: 20rpx; padding: 26rpx 28rpx; border-top: 1rpx solid #f1f3f5; }
.acc-list .acc:first-child { border-top: none; }
.acc-ic {
  width: 76rpx; height: 76rpx; border-radius: 22rpx; background: #f4f5f7;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.acc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.acc-name { font-size: 30rpx; color: #16181c; font-weight: 600; }
.acc-flag { font-size: 22rpx; color: #9aa2ad; font-weight: 400; }
.acc-sub { font-size: 22rpx; color: #9aa2ad; }
.acc-bal { font-size: 32rpx; font-weight: 800; color: #16181c; }
.acc-bal.neg { color: #e5484d; }
.fab {
  position: fixed;
  right: 40rpx;
  bottom: calc(180rpx + env(safe-area-inset-bottom));
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  font-size: 62rpx;
  line-height: 104rpx;
  text-align: center;
  box-shadow: 0 14rpx 34rpx rgba(18, 161, 80, 0.45);
  z-index: 200;
}
</style>
