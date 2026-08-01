<script setup>
import { ref, computed } from 'vue'
import { onShow, onLoad } from '@dcloudio/uni-app'
import { listAccounts, accountTypeIcon, accountDisplayName, listAccountTransactions } from '../../api/account'
import { listCategories, buildCategoryLabelMap, buildCategoryIconMap } from '../../api/category'
import { resolveIcon } from '../../utils/icons'
import { formatAmount, dayKeyOf, dayLabel, timeLabelOf } from '../../utils/format'

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const accId = ref(null)
const acc = ref(null)
const txs = ref([])
const catMap = ref({})
const catIconMap = ref({})
const loading = ref(false)
const expanded = ref({}) // 月份展开状态

onLoad((q) => {
  accId.value = q && q.id ? Number(q.id) : null
})
onShow(load)

async function load() {
  if (accId.value == null) return
  loading.value = true
  try {
    const [all, list, cats] = await Promise.all([
      listAccounts(),
      listAccountTransactions(accId.value),
      listCategories()
    ])
    acc.value = all.find((a) => a.id === accId.value) || null
    txs.value = list || []
    catMap.value = buildCategoryLabelMap(cats)
    catIconMap.value = buildCategoryIconMap(cats)
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 该账户视角下，一笔流水对余额的方向增量（流入为正、流出为负）。
function signedAmount(t) {
  const amt = Number(t.amount)
  if (t.type === 'transfer') {
    if (t.sourceAccountId === accId.value) return -amt
    if (t.destinationAccountId === accId.value) return amt
    return 0
  }
  return t.type === 'income' ? amt : -amt
}
const inflow = computed(() =>
  txs.value.reduce((s, t) => s + Math.max(signedAmount(t), 0), 0)
)
const outflow = computed(() =>
  txs.value.reduce((s, t) => s + Math.max(-signedAmount(t), 0), 0)
)

// 按月分组（倒序），每月含流入/流出/结余与按日流水。
const months = computed(() => {
  const map = new Map()
  for (const t of txs.value) {
    const mk = String(t.occurredAt || '').slice(0, 7)
    if (!mk) continue
    let m = map.get(mk)
    if (!m) { m = { key: mk, inflow: 0, outflow: 0, list: [] }; map.set(mk, m) }
    const s = signedAmount(t)
    if (s >= 0) m.inflow += s
    else m.outflow += -s
    m.list.push(t)
  }
  const arr = [...map.values()].sort((a, b) => (a.key < b.key ? 1 : -1))
  for (const m of arr) {
    m.net = m.inflow - m.outflow
    const [y, mo] = m.key.split('-')
    m.year = y
    m.mon = mo
    m.list.sort((a, b) => (a.occurredAt < b.occurredAt ? 1 : -1))
  }
  return arr
})
function toggleMonth(k) { expanded.value[k] = !expanded.value[k] }

function titleOf(t) {
  if (t.type === 'transfer') {
    return t.sourceAccountId === accId.value ? '转出' : '转入'
  }
  return catMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function iconOf(t) {
  if (t.type === 'transfer') return 'transfer'
  return resolveIcon(catIconMap.value[t.categoryId], catMap.value[t.categoryId], t.type)
}
function subOf(t) {
  const parts = []
  const d = dayLabel(dayKeyOf(t.occurredAt))
  if (d) parts.push(d)
  const tm = timeLabelOf(t.occurredAt)
  if (tm) parts.push(tm)
  if (t.note) parts.push(t.note)
  return parts.join(' · ')
}

// 顶部操作
function goEdit() {
  uni.navigateTo({ url: `/pages/accountedit/accountedit?id=${accId.value}` })
}
function goBack() { uni.navigateBack() }
function goRecord() {
  uni.navigateTo({ url: `/pages/record/record?accountId=${accId.value}` })
}
function goTransfer() {
  uni.navigateTo({ url: `/pages/record/record?type=transfer&accountId=${accId.value}` })
}
</script>

<template>
  <view class="page">
    <!-- 头部：返回 / 标题 / 编辑 -->
    <view class="hero" :style="{ paddingTop: statusBarHeight }">
      <view class="hero-nav">
        <text class="hn-back" @click="goBack">‹</text>
        <text class="hn-title">{{ acc ? accountDisplayName(acc) : '账户明细' }}</text>
        <text class="hn-more" @click="goEdit">•••</text>
      </view>
      <view class="hero-body">
        <text class="h-label">账户余额（元）</text>
        <text class="h-bal" :class="{ neg: acc && Number(acc.currentBalance) < 0 }">{{ acc ? formatAmount(acc.currentBalance) : '0.00' }}</text>
        <view class="h-foot">
          <text>流入 {{ formatAmount(inflow) }}</text>
          <text>流出 {{ formatAmount(outflow) }}</text>
        </view>
      </view>
    </view>

    <view v-if="!txs.length && !loading" class="empty">该账户还没有流水</view>

    <!-- 按月流水 -->
    <view v-for="m in months" :key="m.key" class="month">
      <view class="m-head" @click="toggleMonth(m.key)">
        <view class="m-date">
          <text class="m-year">{{ m.year }}年</text>
          <text class="m-mon">{{ m.mon }}月</text>
        </view>
        <view class="m-sum">
          <text class="m-io">流入 {{ formatAmount(m.inflow) }}</text>
          <text class="m-io">流出 {{ formatAmount(m.outflow) }}</text>
        </view>
        <view class="m-right">
          <text class="m-net" :class="{ neg: m.net < 0 }">结余 {{ formatAmount(m.net) }}</text>
          <text class="m-caret">{{ expanded[m.key] ? '︿' : '﹀' }}</text>
        </view>
      </view>

      <view v-if="expanded[m.key]" class="m-list">
        <view v-for="t in m.list" :key="t.id" class="tx">
          <view class="tx-ic"><AppIcon :name="iconOf(t)" :size="40" /></view>
          <view class="tx-main">
            <text class="tx-name">{{ titleOf(t) }}</text>
            <text class="tx-sub">{{ subOf(t) }}</text>
          </view>
          <text class="tx-amt" :class="signedAmount(t) >= 0 ? 'inc' : 'exp'">{{ signedAmount(t) >= 0 ? '+' : '-' }}{{ formatAmount(Math.abs(signedAmount(t))) }}</text>
        </view>
      </view>
    </view>

    <view style="height:160rpx;"></view>

    <!-- 底部操作栏 -->
    <view class="actionbar">
      <view class="ab-btn" @click="goRecord"><AppIcon name="list" :size="38" /><text>记一笔</text></view>
      <view class="ab-sep"></view>
      <view class="ab-btn" @click="goTransfer"><AppIcon name="transfer" :size="38" /><text>转账</text></view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; }
/* 头部卡 */
.hero {
  background: linear-gradient(150deg, #3b4a63, #2b3647 72%);
  color: #fff;
  padding-bottom: 34rpx;
}
.hero-nav {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12rpx 24rpx;
}
.hn-back { font-size: 48rpx; line-height: 1; width: 60rpx; }
.hn-title { font-size: 32rpx; font-weight: 700; }
.hn-more { font-size: 30rpx; width: 60rpx; text-align: right; letter-spacing: 1rpx; }
.hero-body { padding: 8rpx 34rpx 0; }
.h-label { font-size: 24rpx; opacity: 0.85; }
.h-bal { display: block; font-size: 66rpx; font-weight: 800; letter-spacing: -0.02em; margin: 6rpx 0 18rpx; }
.h-bal.neg { color: #fecaca; }
.h-foot { display: flex; gap: 40rpx; font-size: 24rpx; opacity: 0.9; }
.empty { text-align: center; color: #9aa2ad; font-size: 28rpx; margin-top: 80rpx; }
/* 月分组 */
.month { margin: 20rpx 24rpx 0; }
.m-head {
  display: flex; align-items: center; gap: 16rpx;
  background: #fff; border-radius: 18rpx 18rpx 0 0; padding: 22rpx 26rpx;
}
.m-date { display: flex; flex-direction: column; min-width: 96rpx; }
.m-year { font-size: 20rpx; color: #9aa2ad; }
.m-mon { font-size: 32rpx; font-weight: 800; color: #16181c; }
.m-sum { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.m-io { font-size: 20rpx; color: #9aa2ad; }
.m-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.m-net { font-size: 26rpx; font-weight: 700; color: #16181c; }
.m-net.neg { color: #e5484d; }
.m-caret { font-size: 22rpx; color: #c0c4cc; }
.m-list { background: #fff; border-radius: 0 0 18rpx 18rpx; padding: 0 26rpx; }
.tx { display: flex; align-items: center; gap: 20rpx; padding: 22rpx 0; border-top: 1rpx solid #f1f3f5; }
.tx-ic { width: 68rpx; height: 68rpx; border-radius: 20rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.tx-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.tx-name { font-size: 30rpx; font-weight: 600; color: #16181c; }
.tx-sub { font-size: 22rpx; color: #9aa2ad; }
.tx-amt { font-size: 30rpx; font-weight: 800; }
.tx-amt.inc { color: #0f8a45; }
.tx-amt.exp { color: #16181c; }
/* 底部操作栏 */
.actionbar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
  display: flex; align-items: center;
  background: #fff; border-top: 1rpx solid #eef0f2;
  padding-bottom: env(safe-area-inset-bottom);
  box-shadow: 0 -6rpx 20rpx rgba(20, 24, 28, 0.05);
}
.ab-btn {
  flex: 1; display: flex; align-items: center; justify-content: center; gap: 12rpx;
  padding: 26rpx 0; font-size: 30rpx; color: #2b2f36; font-weight: 600;
}
.ab-sep { width: 1rpx; height: 44rpx; background: #eceef1; }
</style>
