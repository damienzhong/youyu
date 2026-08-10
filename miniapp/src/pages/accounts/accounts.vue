<script setup>
import { ref, computed } from 'vue'
import { onShow, onLoad, onUnload } from '@dcloudio/uni-app'
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
import { useThemeStore } from '../../stores/theme'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const themeStore = useThemeStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})

// 借贷汇总（仅具体账本显示；借贷为账本级台账）
const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const loans = ref([])
const reminders = ref([])
// 借贷为用户级（与账户一致，独立于账本），资产页始终展示，不受当前账本/「全部」影响。
const showLoans = computed(() => true)

// 未结待收/待还中「计入净资产」的部分：账户余额已反映借贷现金流出/入，
// 这里把待收作为资产、待还作为负债补回，保证净资产不因借贷重复计算。
const receivables = computed(() =>
  loans.value
    .filter((l) => l.direction === 'LEND' && !l.settled && l.includeInTotal !== false)
    .reduce((s, l) => s + Number(l.amount), 0)
)
const payables = computed(() =>
  loans.value
    .filter((l) => l.direction === 'BORROW' && !l.settled && l.includeInTotal !== false)
    .reduce((s, l) => s + Number(l.amount), 0)
)

// 是否计入资产统计：仅由「余额计入总资产」决定。
// 「隐藏账户」只影响记账时的选账户弹窗（见账户编辑页说明），不改变资产/净资产口径。
function countsToTotal(a) {
  return a.includeInTotal
}
const counted = computed(() => accounts.value.filter(countsToTotal))
const netWorth = computed(() =>
  counted.value.reduce((s, a) => s + Number(a.currentBalance), 0) + receivables.value - payables.value
)
const totalAssets = computed(() =>
  counted.value.reduce((s, a) => s + Math.max(Number(a.currentBalance), 0), 0) + receivables.value
)
const totalLiab = computed(() =>
  counted.value.reduce((s, a) => s + Math.min(Number(a.currentBalance), 0), 0) - payables.value
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
        loans.value = r.loans || []
      } catch (e) { /* 借贷加载失败不阻断资产页 */ }
    } else {
      loans.value = []
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(() => {
  // 资产已升为一级 tab：隐藏原生 tabBar，仅显示自定义 <TabBar>（与首页/报表/我的一致）。
  uni.hideTabBar({ animation: false, fail() {} })
  load()
})

// 中间凸起键在资产页触发「添加账户」（TabBar.onCenter 广播），打开账户类型选择。
function onFabAddAccount() {
  openCreate()
}
onLoad(() => {
  uni.$on('assets:addAccount', onFabAddAccount)
})
onUnload(() => {
  uni.$off('assets:addAccount', onFabAddAccount)
})

function goLoans(dir) {
  uni.navigateTo({ url: `/pages/loans/loans?direction=${dir}` })
}
function openAccount(a) {
  uni.navigateTo({ url: `/pages/accountdetail/accountdetail?id=${a.id}` })
}
// 添加账户：先弹类型选择，选完打开全屏编辑弹窗（不再跳转页面）。
const typeSheet = ref(false)
const editVisible = ref(false)
const editCreateType = ref(null)
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
  <view class="page" :style="themeStore.current.vars">
    <!-- 沉浸式页头：净资产（渐变延伸到状态栏，与首页/账本/我的一致；净资产本身即标题，无需冗余页名） -->
    <view class="hero" :style="{ paddingTop: `calc(${statusBarHeight} + 24rpx)` }">
      <view class="nw-top">
        <text class="nw-label">净资产 <text class="eye" @click="hideAmounts = !hideAmounts">{{ hideAmounts ? '🙈' : '👁' }}</text></text>
      </view>
      <text class="nw-value" :class="{ neg: netWorth < 0 }">{{ money(netWorth) }}</text>
      <view class="nw-foot">
        <text>总资产 {{ money(totalAssets) }}</text>
        <text>总负债 {{ money(Math.abs(totalLiab)) }}</text>
      </view>
    </view>

    <!-- 借贷往来：对齐竞品，两张独立卡片并排；每张卡片简洁单行（标签左 + 金额右） -->
    <view v-if="showLoans" class="loan-cards">
      <view class="loan-card" @click="goLoans('BORROW')">
        <text class="lc-k">借入/待还</text>
        <text class="lc-v">{{ money(borrowOutstanding) }}</text>
      </view>
      <view class="loan-card" @click="goLoans('LEND')">
        <text class="lc-k">借出/待收</text>
        <text class="lc-v">{{ money(lendOutstanding) }}</text>
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
          <AccountBadge :account="a" :size="64" />
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

    <!-- 底部留白：让出自定义 TabBar 高度 + 安全区 -->
    <view style="height:calc(160rpx + env(safe-area-inset-bottom));"></view>

    <!-- 账户类型选择（本页弹出，选完再打开编辑弹窗） -->
    <AccountTypeSheet v-model:visible="typeSheet" @pick="onPickType" />

    <!-- 新建账户全屏弹窗（资产页已改自定义导航，弹窗需让出状态栏高度）-->
    <AccountEditSheet
      v-model:visible="editVisible"
      :create-type="editCreateType"
      @saved="onAccountSaved"
    />

    <TabBar active="assets" />

  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 0 24rpx 24rpx;
  background: var(--c-page-bg, #eef0f2);
}
/* 沉浸式页头：净资产（全宽、渐变延伸到状态栏，与首页/账本/我的一致） */
.hero {
  margin: 0 -24rpx 24rpx;
  padding: 0 30rpx 44rpx;
  color: #fff;
  background: var(--c-hero, linear-gradient(150deg, #1fbf63, #0f8a45 78%));
  position: relative;
  overflow: hidden;
}
.hero::after {
  content: '';
  position: absolute;
  right: -60rpx; top: -50rpx;
  width: 300rpx; height: 300rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.hero > view, .hero > text { position: relative; z-index: 2; }
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
/* 借贷卡片：对齐竞品，两张独立卡片并排；每张卡片简洁单行（标签左 + 金额右，小字不抢眼） */
.loan-cards { display: flex; gap: 20rpx; margin-bottom: 24rpx; }
.loan-card {
  flex: 1;
  background: #fff;
  border-radius: 18rpx;
  padding: 24rpx 24rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12rpx;
  min-width: 0;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.lc-k { font-size: 24rpx; color: #9aa2ad; }
/* 金额统一用中性深色、常规字重，不再区分红/绿，也不加粗（对齐竞品） */
.lc-v { font-size: 30rpx; font-weight: 400; color: #16181c; }
.lc-v::before { content: '¥'; font-size: 20rpx; opacity: 0.7; margin-right: 2rpx; }
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
.acc { display: flex; align-items: center; gap: 20rpx; padding: 22rpx 26rpx; border-top: 1rpx solid #f1f3f5; }
.acc-list .acc:first-child { border-top: none; }
.acc-ic {
  width: 66rpx; height: 66rpx; border-radius: 20rpx; background: #f4f5f7;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.acc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.acc-titlerow { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.acc-name { font-size: 29rpx; color: #16181c; font-weight: 500; }
.acc-tag {
  font-size: 20rpx; font-weight: 700; padding: 2rpx 12rpx; border-radius: 999rpx;
  background: #eef1f5; color: #5b6470;
}
.acc-tag.soon { background: #fdece8; color: #e5563d; }
.acc-flag { font-size: 20rpx; color: #9aa2ad; font-weight: 400; background: #f0f2f5; border-radius: 999rpx; padding: 2rpx 12rpx; }
.acc-sub { font-size: 22rpx; color: #9aa2ad; }
.acc-bal { font-size: 30rpx; font-weight: 600; color: #16181c; font-variant-numeric: tabular-nums; }
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
  color: var(--c-brand, #12a150);
  font-weight: 700;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.aa-plus { font-size: 34rpx; line-height: 1; }
.aa-t { font-size: 30rpx; }
</style>
