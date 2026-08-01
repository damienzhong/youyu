<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  listRepayReminders,
  accountTypeIcon,
  accountDisplayName,
  isCreditType,
  ACCOUNT_GROUPS
} from '../../api/account'
import { listLoans } from '../../api/loan'
import { useLedgerStore } from '../../stores/ledger'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})

// 借贷汇总（仅具体账本显示；借贷为账本级台账）
const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const reminders = ref([])
const showLoans = computed(() => !ledgerStore.isAll)

// 是否计入资产统计：仅由「余额计入总资产」决定。
// 「隐藏账户」只影响记账时的选账户弹窗（见账户编辑页说明），不改变资产/净资产口径。
function countsToTotal(a) {
  return a.includeInTotal
}
const counted = computed(() => accounts.value.filter(countsToTotal))
const netWorth = computed(() => counted.value.reduce((s, a) => s + Number(a.currentBalance), 0))
const totalAssets = computed(() =>
  counted.value.reduce((s, a) => s + Math.max(Number(a.currentBalance), 0), 0)
)
const totalLiab = computed(() =>
  counted.value.reduce((s, a) => s + Math.min(Number(a.currentBalance), 0), 0)
)

// 按分组聚合（仅展示有账户的组），组内保持后端排序。
// 小计与净资产同口径：只累加「计入总资产」的账户，保证各组小计之和等于净资产（不计入账户仍单独展示，但不计入小计）。
const groups = computed(() => {
  return ACCOUNT_GROUPS.map((g) => {
    const items = accounts.value.filter((a) => (a.group || 'FUNDS') === g.key)
    const subtotal = items.reduce((s, a) => s + (countsToTotal(a) ? Number(a.currentBalance) : 0), 0)
    return { ...g, items, subtotal }
  }).filter((g) => g.items.length)
})

function availableOf(a) {
  if (!isCreditType(a.type) || a.creditLimit == null) return null
  return Number(a.creditLimit) + Number(a.currentBalance)
}

// 还款提醒 → 账户行小标签：进入「提前提醒窗口」（剩余天数 ≤ 提前天数）才显示。
const reminderMap = computed(() => {
  const m = {}
  for (const r of reminders.value) m[r.accountId] = r
  return m
})
function repayTag(a) {
  const r = reminderMap.value[a.id]
  if (!r || r.daysUntil > (r.remindDays ?? 3)) return ''
  return r.daysUntil === 0 ? '今天还款' : `${r.daysUntil}天后还款`
}
function repaySoon(a) {
  const r = reminderMap.value[a.id]
  return !!r && r.daysUntil <= 3
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
// 添加账户：先弹类型选择，选完打开全屏编辑弹窗（不再跳转页面）。
const typeSheet = ref(false)
const editVisible = ref(false)
const editCreateType = ref(null)
function goBack() {
  // 有上一页则返回；若本页处于栈底（冷启动/刷新直接进入、栈被重置等），回退到首页 tab，避免“点返回没反应”。
  const pages = getCurrentPages()
  if (pages && pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.switchTab({ url: '/pages/index/index' })
  }
}
function openCreate() {
  typeSheet.value = true
}
function onPickType(t) {
  typeSheet.value = false
  editCreateType.value = t.value
  editVisible.value = true
}
function onAccountSaved() {
  load()
}
</script>

<template>
  <view class="page">
    <!-- 页面标题（自定义导航，二级页含返回）-->
    <view class="topbar" :style="{ paddingTop: statusBarHeight }">
      <text class="topbar-back" @click="goBack">‹</text>
      <text class="topbar-title">资产管理</text>
      <text class="topbar-back placeholder"></text>
    </view>

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
            <view class="acc-titlerow">
              <text class="acc-name">{{ accountDisplayName(a) }}</text>
              <text v-if="repayTag(a)" class="acc-tag" :class="{ soon: repaySoon(a) }">{{ repayTag(a) }}</text>
              <text v-if="!a.includeInTotal" class="acc-flag">不计入</text>
            </view>
            <text v-if="availableOf(a) != null" class="acc-sub">可用 {{ money(availableOf(a)) }}</text>
          </view>
          <text class="acc-bal" :class="{ neg: Number(a.currentBalance) < 0 }">{{ money(a.currentBalance) }}</text>
        </view>
      </view>
    </view>

    <!-- 添加账户（列表底部，替代悬浮按钮，避免与底部「记一笔」重复） -->
    <view class="add-account" @click="openCreate">
      <text class="aa-plus">＋</text>
      <text class="aa-t">添加账户</text>
    </view>

    <view style="height:calc(40rpx + env(safe-area-inset-bottom));"></view>

    <!-- 账户类型选择（本页弹出，选完再打开编辑弹窗） -->
    <AccountTypeSheet v-model:visible="typeSheet" @pick="onPickType" />

    <!-- 新建账户全屏弹窗（资产页已改自定义导航，弹窗需让出状态栏高度）-->
    <AccountEditSheet
      v-model:visible="editVisible"
      :create-type="editCreateType"
      @saved="onAccountSaved"
    />

  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 0 24rpx 24rpx;
  background: #eef0f2;
}
/* 自定义页面标题（二级页导航，含返回）*/
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 8rpx;
  margin: 0 -24rpx 8rpx;
}
.topbar-back {
  width: 72rpx;
  text-align: center;
  font-size: 48rpx;
  line-height: 1;
  color: #16181c;
}
.topbar-back.placeholder { color: transparent; }
.topbar-title {
  flex: 1;
  text-align: center;
  font-size: 34rpx;
  font-weight: 800;
  color: #16181c;
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
.acc-titlerow { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.acc-name { font-size: 30rpx; color: #16181c; font-weight: 600; }
.acc-tag {
  font-size: 20rpx; font-weight: 700; padding: 2rpx 12rpx; border-radius: 999rpx;
  background: #eef1f5; color: #5b6470;
}
.acc-tag.soon { background: #fdece8; color: #e5563d; }
.acc-flag { font-size: 20rpx; color: #9aa2ad; font-weight: 400; background: #f0f2f5; border-radius: 999rpx; padding: 2rpx 12rpx; }
.acc-sub { font-size: 22rpx; color: #9aa2ad; }
.acc-bal { font-size: 32rpx; font-weight: 800; color: #16181c; }
.acc-bal.neg { color: #e5484d; }
.add-account {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  margin: 8rpx 0 4rpx;
  padding: 28rpx 0;
  background: #fff;
  border-radius: 22rpx;
  color: #12a150;
  font-weight: 700;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.aa-plus { font-size: 34rpx; line-height: 1; }
.aa-t { font-size: 30rpx; }
</style>
