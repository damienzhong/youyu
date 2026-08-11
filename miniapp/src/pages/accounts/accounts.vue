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
import { fetchCashflow } from '../../api/cashflow'
import { useLedgerStore } from '../../stores/ledger'
import { useThemeStore } from '../../stores/theme'
import { formatAmount, currentMonth, monthLabel } from '../../utils/format'

const ledgerStore = useLedgerStore()
const themeStore = useThemeStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})

// —— 本月现金流（账户维度，独立于账本）——
// 与净资产（存量）互补的钱包流量视图：展示选定自然月的实际流出/流入/净流入 + 今日流出/流入。
// 请求范式照抄提醒页：seq 请求序号 + withTimeout 3000ms 客户端超时守卫，
// 底层请求仍会跑完，靠序号忽略迟到结果；出错或超时只切失败态 + 重试入口，自动重试 0 次（需求 5.8）。
const CASHFLOW_TIMEOUT_MS = 3000
const flowMonth = ref(currentMonth())
const flowState = ref('loading') // loading | ready | error
const flow = ref({ outflow: '0.00', inflow: '0.00', netInflow: '0.00', todayOutflow: '0.00', todayInflow: '0.00' })
let flowSeq = 0
let flowInFlight = false

// 选定自然月是否为历史月：历史月今日不落在其中，今日流出/流入无意义，隐藏今日小行（需求 5.4）。
const isFlowHistory = computed(() => flowMonth.value !== currentMonth())
const flowMonthLabel = computed(() => monthLabel(flowMonth.value))
// 净流入可为负：负值以「净流出」语义 + 红色区分（需求 5.5）。
const flowNetNegative = computed(() => Number(flow.value.netInflow) < 0)
const flowNetLabel = computed(() => (flowNetNegative.value ? '净流出' : '净流入'))
// 金额隐藏与净资产一致：开启隐藏时统一展示 ****（需求 5.6）。
function flowMoney(v) {
  return hideAmounts.value ? '****' : formatAmount(v)
}
// 净流入带符号展示：正数前置 +，负数取绝对值（标签已标「净流出」）；隐藏时同样 ****。
function flowNetMoney() {
  if (hideAmounts.value) return '****'
  const n = Number(flow.value.netInflow)
  return (n < 0 ? '' : '+') + formatAmount(Math.abs(n))
}

/** 客户端单请求超时守卫（需求 5.8）：底层请求仍会跑完，靠 seq 忽略其迟到结果。 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'TIMEOUT', message: '请求超时' }), ms)
    promise.then(
      (v) => { clearTimeout(timer); resolve(v) },
      (e) => { clearTimeout(timer); reject(e) }
    )
  })
}

/**
 * 拉取选定自然月现金流（需求 5.2、5.3）：返回前 loading 占位，返回后渲染真实取值。
 * 出错或 3000ms 超时只切 error 态、展示重试入口，绝不自动重发；不影响净资产/借贷/账户列表（需求 5.8）。
 */
async function loadCashflow() {
  if (flowInFlight) return
  const s = ++flowSeq
  flowInFlight = true
  flowState.value = 'loading'
  try {
    const res = await withTimeout(fetchCashflow(flowMonth.value), CASHFLOW_TIMEOUT_MS)
    if (s !== flowSeq) return
    flow.value = {
      outflow: res?.outflow ?? '0.00',
      inflow: res?.inflow ?? '0.00',
      netInflow: res?.netInflow ?? '0.00',
      todayOutflow: res?.todayOutflow ?? '0.00',
      todayInflow: res?.todayInflow ?? '0.00'
    }
    flowState.value = 'ready'
  } catch (e) {
    if (s !== flowSeq) return
    flowState.value = 'error'
  } finally {
    if (s === flowSeq) flowInFlight = false
  }
}

function retryCashflow() {
  if (flowInFlight) return
  loadCashflow()
}

// 口径说明改为点击 ⓘ 弹出，省去常驻一行说明文字（需求 5.7）。
function showFlowHint() {
  uni.showModal({
    title: '本月现金流',
    content: '账户实际收支：含 AA 实付、不含账户间转账，与「账本」页按账本统计的收支口径不同。',
    showCancel: false,
    confirmText: '知道了'
  })
}

// 左右切月：复用账本页交互范式（上一月/下一月），切月后以新选定月重新请求（需求 5.3）。
function shiftFlowMonth(delta) {
  const [y, m] = flowMonth.value.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  flowMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  loadCashflow()
}

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
  // 现金流独立拉取：与净资产/借贷/账户列表解耦，任何失败/超时都不影响 load()（需求 5.8）。
  loadCashflow()
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

    <!-- 本月现金流：净资产（存量）之下、账户列表之上的钱包流量视图（账户维度，含 AA 实付、不含转账，需求 5.1） -->
    <view class="flow">
      <view class="flow-hd">
        <view class="flow-title">
          <text class="flow-t">本月现金流</text>
          <text class="fh-i" @click="showFlowHint">i</text>
        </view>
        <view class="flow-per">
          <text class="fp-arrow" @click="shiftFlowMonth(-1)">‹</text>
          <text class="fp-label">{{ flowMonthLabel }}</text>
          <text class="fp-arrow" @click="shiftFlowMonth(1)">›</text>
        </view>
      </view>

      <!-- 加载中：占位，不渲染真实取值（需求 5.2） -->
      <view v-if="flowState === 'loading'" class="flow-ph">
        <text class="fph-t">正在加载现金流…</text>
      </view>

      <!-- 出错 / 超时：失败提示 + 重试入口，自动重试 0 次，不影响页面其余内容（需求 5.8） -->
      <view v-else-if="flowState === 'error'" class="flow-err">
        <text class="fe-t">现金流没能加载出来</text>
        <text class="fe-d">网络不太顺畅，稍后再试一次</text>
        <text class="fe-retry" @click="retryCashflow">重试</text>
      </view>

      <!-- 就绪：流出（暖橙）/ 流入（绿）两列 + 净额与今日合并一行（紧凑，需求 5.4、5.5） -->
      <template v-else>
        <view class="flow-io">
          <view class="io-col">
            <text class="io-l"><text class="io-dot out"></text>流出</text>
            <text class="io-v out">{{ flowMoney(flow.outflow) }}</text>
          </view>
          <view class="io-col r">
            <text class="io-l"><text class="io-dot in"></text>流入</text>
            <text class="io-v in">{{ flowMoney(flow.inflow) }}</text>
          </view>
        </view>

        <!-- 净额 + 今日合并为一行：左净额、右今日（历史月隐藏今日） -->
        <view class="flow-sum">
          <text class="fs-net">
            {{ flowNetLabel }}
            <text class="net-v" :class="flowNetNegative ? 'neg' : 'pos'">{{ flowNetMoney() }}</text>
          </text>
          <text v-if="!isFlowHistory" class="fs-today">
            今日 <text class="t-out">{{ flowMoney(flow.todayOutflow) }}</text>
            <text class="t-sep">/</text>
            <text class="t-in">{{ flowMoney(flow.todayInflow) }}</text>
          </text>
        </view>
      </template>
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
/* 暂不带货币符号，留待多币种时统一加。 */
.nw-value.neg { color: #fecaca; }
.nw-foot { display: flex; justify-content: space-between; font-size: 24rpx; opacity: 0.9; }
/* 本月现金流卡：白卡上浮至净资产页头底部（存量 + 流量一屏看全），与原型一致 */
.flow {
  background: #fff;
  border-radius: 22rpx;
  margin: -36rpx 0 20rpx;
  padding: 22rpx 26rpx 20rpx;
  position: relative;
  z-index: 3;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06);
}
.flow-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16rpx; }
.flow-title { display: flex; align-items: center; gap: 10rpx; }
.flow-t { font-size: 28rpx; font-weight: 700; color: #16181c; }
.flow-per { display: flex; align-items: center; gap: 10rpx; }
.fp-arrow {
  font-size: 34rpx;
  color: #9aa2ad;
  padding: 0 12rpx;
  line-height: 1;
}
.fp-arrow:active { color: var(--c-brand, #12a150); }
.fp-label { font-size: 24rpx; color: #5b6470; min-width: 132rpx; text-align: center; }
/* 占位 / 失败态：轻量、居中，不干扰其余区块 */
.flow-ph { padding: 24rpx 0; text-align: center; }
.fph-t { font-size: 24rpx; color: #9aa2ad; }
.flow-err { padding: 18rpx 0 6rpx; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 8rpx; }
.fe-t { font-size: 26rpx; font-weight: 700; color: #25292e; }
.fe-d { font-size: 22rpx; color: #9aa2ad; }
.fe-retry {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--c-brand, #12a150);
  font-weight: 600;
  border: 1rpx solid var(--c-brand, #12a150);
  border-radius: 999rpx;
  padding: 6rpx 32rpx;
}
/* 流出 / 流入：两列外沿对齐（流出左、流入右），语义色 + 等宽数字 */
.flow-io { display: flex; }
.io-col { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10rpx; }
.io-col.r { text-align: right; padding-left: 24rpx; border-left: 1rpx solid #f0f2f4; }
.io-l { font-size: 22rpx; color: #8b939c; }
.io-dot { display: inline-block; width: 12rpx; height: 12rpx; border-radius: 50%; margin-right: 8rpx; vertical-align: middle; }
.io-dot.out { background: #e8663d; }
.io-dot.in { background: #12a150; }
.io-v {
  font-size: 36rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.io-v.out { color: #e8663d; }
.io-v.in { color: #12a150; }
/* 净额 + 今日合并为一行：紧凑，虚线分隔，不再占两行 */
.flow-sum {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx dashed #eceef0;
}
.fs-net { font-size: 24rpx; color: #5b6470; }
.net-v { font-size: 28rpx; font-weight: 700; font-variant-numeric: tabular-nums; margin-left: 6rpx; }
.net-v.pos { color: #12a150; }
.net-v.neg { color: #e5484d; }
/* 今日收支：标签加深、金额用收支语义色，靠颜色提亮而非放大，不与月度大数字抢 */
.fs-today { font-size: 24rpx; color: #5b6470; font-variant-numeric: tabular-nums; }
.fs-today .t-out { color: #e8663d; font-weight: 600; }
.fs-today .t-in { color: #12a150; font-weight: 600; }
.fs-today .t-sep { color: #c7ccd2; margin: 0 4rpx; }
/* 标题旁 ⓘ：点击弹口径说明，省去常驻说明行 */
.fh-i {
  width: 30rpx; height: 30rpx;
  border-radius: 50%;
  border: 1rpx solid #c7ccd2;
  color: #a7adb5;
  font-size: 20rpx;
  line-height: 28rpx;
  text-align: center;
}
/* 借贷卡片：对齐竞品，两张独立卡片并排；每张卡片简洁单行（标签左 + 金额右，小字不抢眼） */
.loan-cards { display: flex; gap: 20rpx; margin-bottom: 20rpx; }
/* 与现金流卡 / 账户列表统一的白卡语言（不再染底色，保持整页协调） */
.loan-card {
  flex: 1;
  background: #fff;
  border-radius: 16rpx;
  padding: 20rpx 22rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12rpx;
  min-width: 0;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.lc-k { font-size: 24rpx; color: #8b939c; }
/* 借贷为次要信息：金额用中性深色、常规字重，不抢现金流/账户的主视觉，暂不带货币符号 */
.lc-v { font-size: 30rpx; font-weight: 500; color: #16181c; font-variant-numeric: tabular-nums; }
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
