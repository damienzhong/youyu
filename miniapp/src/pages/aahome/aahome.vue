<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getAaOverview } from '../../api/aa'
import { listMembers, archiveLedger, unarchiveLedger } from '../../api/ledger'
import { canToggleArchive, isUnsettledArchiveError } from '../../utils/aa'
import { listCategories, buildCategoryLabelMap, buildCategoryIconMap, buildCategoryColorMap } from '../../api/category'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { resolveIcon } from '../../utils/icons'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const themeStore = useThemeStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'
const selfId = computed(() => authStore.user?.id ?? null)

const loaded = ref(false)
const overview = ref(null)
const members = ref([])
const categoryLabelMap = ref({})
const categoryIconMap = ref({})
const categoryColorMap = ref({})

// ---------- 成员展示 ----------
function memberName(uid) {
  if (uid != null && uid === selfId.value) return '我'
  const m = members.value.find((x) => x.userId === uid)
  return m ? m.displayName || '成员' : '成员'
}
function memberSeed(uid) {
  const m = members.value.find((x) => x.userId === uid)
  return (m && m.avatarSeed) || memberName(uid).slice(0, 1)
}

// ---------- 三口径 ----------
const calibers = computed(() => overview.value?.calibers || {})
const accountPaid = computed(() => Number(calibers.value.accountPaid) || 0)
const myConsumption = computed(() => Number(calibers.value.myConsumption) || 0)
const receivable = computed(() => Number(calibers.value.receivable) || 0)

// 我的净额（应收正 / 应付负）：从成员净额里取当前用户。
const myNet = computed(() => {
  const nets = overview.value?.memberNets || []
  const mine = nets.find((n) => n.userId === selfId.value)
  return mine ? Number(mine.net) || 0 : 0
})
const netKind = computed(() => (myNet.value > 0.005 ? 'pos' : myNet.value < -0.005 ? 'neg' : 'zero'))
const netLabel = computed(() =>
  netKind.value === 'pos' ? '我的净额（别人欠我）' : netKind.value === 'neg' ? '我的净额（我欠别人）' : '我的净额'
)
const netValueText = computed(() => {
  if (netKind.value === 'zero') return '已结清'
  const prefix = netKind.value === 'pos' ? '应收 ¥' : '应付 ¥'
  return prefix + formatAmount(Math.abs(myNet.value))
})

const allSettled = computed(() => !!overview.value?.allSettled)
const archived = computed(() => !!overview.value?.archived)
// 归档 / 解档入口仅对 AA 账本的创建者（OWNER）开放（需求 8.3、8.5）。
const canArchive = computed(() => canToggleArchive(ledgerStore.current))

// ---------- 流水 ----------
const transactions = computed(() => overview.value?.transactions || [])

function isExpense(t) {
  return t.type === 'aa_expense'
}
function catLabel(t) {
  return categoryLabelMap.value[t.categoryId] || t.note || '支出'
}
function catIconKey(t) {
  return resolveIcon(categoryIconMap.value[t.categoryId], categoryLabelMap.value[t.categoryId], 'expense')
}
function catIconColor(t) {
  return categoryColorMap.value[t.categoryId] || ''
}
function dateOf(t) {
  return String(t.occurredAt || '').slice(5, 10) // MM-DD
}
// 支出副标题：日期 · 付款人 付款；结算：日期 · 由谁转给谁。
function expenseSub(t) {
  const payer = memberName(t.payerUserId)
  const self = t.payerUserId != null && t.payerUserId === selfId.value
  return `${dateOf(t)} · ${self ? '我' : payer} 付款`
}
function settleTitle(t) {
  return `${memberName(t.fromUserId)} 转给 ${memberName(t.toUserId)}`
}
function settleSub(t) {
  const iAmFrom = t.fromUserId != null && t.fromUserId === selfId.value
  const iAmTo = t.toUserId != null && t.toUserId === selfId.value
  const role = iAmTo ? ' · 我收款' : iAmFrom ? ' · 我付款' : ''
  return `${dateOf(t)} · 结算${role}`
}
// 我摊标注：参与人显示金额，非参与人为 null → 显示「未参与」。
function myShareText(t) {
  return t.myShare == null ? '未参与' : `我摊 ¥${formatAmount(t.myShare)}`
}

async function load() {
  const lid = ledgerStore.currentLedgerId
  if (!lid || lid === 'all') return
  try {
    const [ov, mem, cats] = await Promise.all([
      getAaOverview(lid),
      listMembers(lid),
      listCategories()
    ])
    overview.value = ov
    members.value = mem || []
    categoryLabelMap.value = buildCategoryLabelMap(cats)
    categoryIconMap.value = buildCategoryIconMap(cats)
    categoryColorMap.value = buildCategoryColorMap(cats)
    loaded.value = true
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

onShow(async () => {
  uni.hideTabBar({ animation: false, fail() {} })
  if (!authStore.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  try {
    await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  // 若当前账本已非 AA（如切回个人/家庭账本），回到通用账本首页。
  if (ledgerStore.isAll || ledgerStore.current?.type !== 'AA') {
    uni.redirectTo({ url: '/pages/index/index' })
    return
  }
  load()
})

function openLedgerSwitch() {
  // 账本切换沿用通用账本页的切换器。
  uni.redirectTo({ url: '/pages/index/index' })
}
function goRecord() {
  if (archived.value) {
    uni.showToast({ title: '账本已归档，只读', icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/aarecord/aarecord' })
}
function goMembers() {
  const lid = ledgerStore.currentLedgerId
  uni.navigateTo({ url: `/pages/aamembers/aamembers?id=${lid}` })
}
function goSettle() {
  // 结算页（任务 7.5，pages/aasettle）；带 ?id= 定位账本，未就绪时兜底提示。
  const lid = ledgerStore.currentLedgerId
  uni.navigateTo({
    url: `/pages/aasettle/aasettle?id=${lid}`,
    fail() {
      uni.showToast({ title: '结算页开发中', icon: 'none' })
    }
  })
}

// ---------- 归档 / 解档（需求 8.3、8.4、8.5）----------
// 归档后账本只读、移入「已归档」分组，历史与导出保留、可随时解档。
// 仅创建者可操作；点「更多」弹出操作菜单。
function openMore() {
  if (!canArchive.value) return
  if (archived.value) {
    uni.showModal({
      title: '解档账本',
      content: '解档后账本恢复可编辑，可继续记账、编辑与结算。',
      confirmText: '解档',
      success: (r) => {
        if (r.confirm) doUnarchive()
      }
    })
    return
  }
  uni.showModal({
    title: '归档账本',
    content: '归档后账本转为只读（不可记账 / 编辑 / 结清），移入「已归档」分组，历史与导出保留，可随时解档。',
    confirmText: '归档',
    confirmColor: '#12a150',
    success: (r) => {
      if (r.confirm) doArchive(false)
    }
  })
}

// 归档：未结清时后端返回 AA_LEDGER_UNSETTLED，二次确认后带 force=true 重试（需求 8.4）。
async function doArchive(force) {
  const lid = ledgerStore.currentLedgerId
  if (!lid || lid === 'all') return
  try {
    await archiveLedger(lid, force)
    uni.showToast({ title: '已归档', icon: 'success' })
    await refreshAfterArchive()
  } catch (e) {
    if (isUnsettledArchiveError(e)) {
      uni.showModal({
        title: '仍有未结清金额',
        content: '该账本仍有成员未结清（应收 / 应付非 0）。仍要归档吗？归档后将只读，可随时解档后再结清。',
        confirmText: '仍要归档',
        confirmColor: '#e5544b',
        success: (r) => {
          if (r.confirm) doArchive(true)
        }
      })
      return
    }
    uni.showToast({ title: e.message || '归档失败', icon: 'none' })
  }
}

async function doUnarchive() {
  const lid = ledgerStore.currentLedgerId
  if (!lid || lid === 'all') return
  try {
    await unarchiveLedger(lid)
    uni.showToast({ title: '已解档', icon: 'success' })
    await refreshAfterArchive()
  } catch (e) {
    uni.showToast({ title: e.message || '解档失败', icon: 'none' })
  }
}

// 归档 / 解档后刷新账本 store 与概览，使各页只读标志（archived）与首页横幅同步更新。
async function refreshAfterArchive() {
  try {
    await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  await load()
}
</script>

<template>
  <view class="aah" :style="themeStore.current.vars">
    <!-- Hero -->
    <view class="top">
      <view class="statusbar" :style="{ height: statusBarHeight }"></view>
      <view class="hnav">
        <view class="hnav-left" @click="openLedgerSwitch">
          <text class="hl-name">{{ ledgerStore.currentName }}</text>
          <text class="hl-tag">AA</text>
          <text class="hl-caret">▾</text>
        </view>
        <view class="hnav-right">
          <text v-if="archived" class="hl-arch">已归档 · 只读</text>
          <text class="hl-members" @click="goMembers">成员</text>
          <text v-if="canArchive" class="hl-more" @click="openMore">⋯</text>
        </view>
      </view>

      <view class="hero">
        <text class="lab">{{ netLabel }}</text>
        <text class="big tabnum" :class="netKind">{{ netValueText }}</text>
        <view class="grid3">
          <view class="g">
            <text class="k">账户已支出</text>
            <text class="v tabnum">¥{{ formatAmount(accountPaid) }}</text>
          </view>
          <view class="g">
            <text class="k">我的消费</text>
            <text class="v tabnum">¥{{ formatAmount(myConsumption) }}</text>
          </view>
          <view class="g">
            <text class="k">待收回</text>
            <text class="v tabnum">¥{{ formatAmount(receivable) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 资产口径说明 -->
    <view class="pad">
      <view class="note">
        资产口径：净资产只减少「我的消费 ¥{{ formatAmount(myConsumption) }}」；账户已支出中多付的部分是<text class="lend">应收（资产）</text>，收款后转成账户余额。
      </view>
    </view>

    <!-- 结清状态条 -->
    <view v-if="loaded" class="pad">
      <view class="statusbar-card" :class="{ done: allSettled }">
        <text class="sc-ic">{{ allSettled ? '✓' : '•' }}</text>
        <text class="sc-t">{{ allSettled ? '已全部结清' : '进行中 · 有待结算' }}</text>
        <text class="sc-go" @click="goSettle">去结算 ›</text>
      </view>
    </view>

    <!-- 流水 -->
    <view class="content">
      <view class="flow-h">
        <text class="fh-t">流水</text>
        <text class="fh-sub">共 {{ transactions.length }} 笔</text>
      </view>

      <view v-if="loaded && !transactions.length" class="empty">
        <text class="e-em">🤝</text>
        <text class="e-t">还没有记账</text>
        <text class="e-s">点下方「记一笔」记录第一笔共同开销</text>
      </view>

      <view v-else class="tx-list">
        <view v-for="t in transactions" :key="t.type + '-' + t.id" class="tx">
          <template v-if="isExpense(t)">
            <CategoryIcon :icon="catIconKey(t)" :color="catIconColor(t)" :size="40" />
            <view class="tx-info">
              <text class="tx-title">{{ catLabel(t) }}</text>
              <text class="tx-sub">{{ expenseSub(t) }}</text>
            </view>
            <view class="tx-amt">
              <text class="a tabnum">¥{{ formatAmount(t.amount) }}</text>
              <text class="mine" :class="{ none: t.myShare == null }">{{ myShareText(t) }}</text>
            </view>
          </template>
          <template v-else>
            <view class="tx-ic settle">🔁</view>
            <view class="tx-info">
              <text class="tx-title">{{ settleTitle(t) }}</text>
              <text class="tx-sub">{{ settleSub(t) }}</text>
            </view>
            <view class="tx-amt">
              <text class="a tabnum settle-a">¥{{ formatAmount(t.amount) }}</text>
              <text class="mine">结算</text>
            </view>
          </template>
        </view>
      </view>
      <view style="height:220rpx;"></view>
    </view>

    <!-- 底部操作 -->
    <view class="fab-wrap">
      <view class="fab ghost" @click="goSettle">去结算</view>
      <view class="fab primary" :class="{ disabled: archived }" @click="goRecord">＋ 记一笔</view>
    </view>

    <TabBar active="ledger" />
  </view>
</template>

<style scoped>
.aah { min-height: 100vh; background: var(--c-page-bg, #f5f6f8); }

/* Hero */
.top {
  background: linear-gradient(150deg, #12a150, #0b7d3c);
  padding-bottom: 20rpx;
  position: relative;
  overflow: hidden;
}
.top::after {
  content: '';
  position: absolute;
  right: -60rpx;
  top: -40rpx;
  width: 320rpx;
  height: 320rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.statusbar { width: 100%; }
.hnav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 84rpx;
  padding: 0 28rpx;
  color: #fff;
  position: relative;
  z-index: 2;
}
.hnav-left { display: flex; align-items: center; gap: 10rpx; }
.hl-name { font-size: 32rpx; font-weight: 800; }
.hl-tag {
  font-size: 18rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.hl-caret { font-size: 20rpx; opacity: 0.9; }
.hnav-right { display: flex; align-items: center; gap: 16rpx; }
.hl-arch { font-size: 20rpx; opacity: 0.9; }
.hl-members {
  font-size: 24rpx;
  color: #fff;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 6rpx 20rpx;
  font-weight: 600;
}
.hl-more {
  font-size: 34rpx;
  color: #fff;
  line-height: 1;
  padding: 2rpx 6rpx;
  font-weight: 700;
}

.hero { padding: 6rpx 32rpx 8rpx; color: #fff; position: relative; z-index: 2; }
.hero .lab { font-size: 24rpx; opacity: 0.9; }
.hero .big {
  display: block;
  font-size: 56rpx;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 6rpx 0 20rpx;
  line-height: 1.1;
}
.hero .big.zero { font-size: 44rpx; }
.grid3 { display: flex; gap: 14rpx; }
.grid3 .g {
  flex: 1;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 16rpx;
  padding: 16rpx 16rpx;
}
.grid3 .k { font-size: 20rpx; opacity: 0.9; display: block; }
.grid3 .v { font-size: 30rpx; font-weight: 800; margin-top: 6rpx; display: block; }
.tabnum { font-variant-numeric: tabular-nums; }

.pad { padding: 20rpx 24rpx 0; }
.note {
  background: #fff8e6;
  border: 1rpx solid #f3e2b3;
  border-radius: 16rpx;
  padding: 18rpx 22rpx;
  font-size: 23rpx;
  line-height: 1.6;
  color: #7a5b16;
}
.note .lend { color: #3a7bd5; font-weight: 700; }

.statusbar-card {
  display: flex;
  align-items: center;
  gap: 14rpx;
  background: #fff;
  border-radius: 16rpx;
  padding: 22rpx 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.sc-ic {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  background: #fdecec;
  color: #e5533d;
  font-size: 24rpx;
  text-align: center;
  line-height: 40rpx;
  flex: 0 0 auto;
}
.statusbar-card.done .sc-ic { background: #e6f6ec; color: #12a150; }
.sc-t { flex: 1; font-size: 28rpx; font-weight: 600; color: #1f2329; }
.sc-go { font-size: 24rpx; color: #12a150; font-weight: 700; }

.content { padding: 8rpx 24rpx 0; }
.flow-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 4rpx 8rpx;
}
.fh-t { font-size: 26rpx; font-weight: 700; color: #1f2329; }
.fh-sub { font-size: 22rpx; color: #9aa2ad; }

.empty {
  background: #fff;
  border-radius: 18rpx;
  padding: 60rpx 30rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}
.e-em { font-size: 72rpx; }
.e-t { font-size: 30rpx; font-weight: 700; color: #1f2329; }
.e-s { font-size: 24rpx; color: #9aa2ad; }

.tx-list { background: #fff; border-radius: 18rpx; padding: 4rpx 24rpx; box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05); }
.tx {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 0;
  border-top: 1rpx solid #f0f2f5;
}
.tx:first-child { border-top: none; }
.tx-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  flex: 0 0 auto;
}
.tx-ic.settle { background: #eef3fb; }
.tx-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.tx-title { font-size: 29rpx; font-weight: 500; color: #1f2329; }
.tx-sub { font-size: 22rpx; color: #9aa2ad; }
.tx-amt { text-align: right; display: flex; flex-direction: column; gap: 6rpx; }
.tx-amt .a { font-size: 30rpx; font-weight: 700; color: #1f2329; }
.tx-amt .a.settle-a { color: #3a7bd5; }
.tx-amt .mine { font-size: 21rpx; color: #9aa2ad; }
.tx-amt .mine.none { color: #c8ccd2; }

.fab-wrap {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(120rpx + env(safe-area-inset-bottom));
  display: flex;
  gap: 20rpx;
  padding: 0 32rpx;
  z-index: 400;
}
.fab {
  flex: 1;
  text-align: center;
  border-radius: 44rpx;
  padding: 24rpx 0;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 10rpx 26rpx rgba(20, 24, 28, 0.16);
}
.fab.ghost { background: #fff; color: #12a150; }
.fab.primary { background: #12a150; color: #fff; }
.fab.primary.disabled { opacity: 0.5; }
</style>
