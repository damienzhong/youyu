<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useLedgerStore } from '../../stores/ledger'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth } from '../../api/transaction'
import { listTags } from '../../api/tag'
import { listAllAccounts, listAllCategories, listAllTransactionsByMonth } from '../../api/aggregate'
import { budgetOverview } from '../../api/budget'
import { createLedger, listMembers } from '../../api/ledger'
import { formatAmount, categoryEmoji, dayKeyOf, dayLabel, currentMonth } from '../../utils/format'

const auth = useAuthStore()
const ledgerStore = useLedgerStore()

const month = ref(currentMonth())
const loaded = ref(false)
const accounts = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const transactions = ref([])
const budget = ref(null)
const memberMap = ref({})
const tagNameById = ref({})

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'
const isAll = computed(() => ledgerStore.isAll)
const isCollab = computed(() => !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE')
const isHistory = computed(() => month.value !== currentMonth())

const heroDateLabel = computed(() => {
  const [y, m] = month.value.split('-')
  return `${y}年${Number(m)}月`
})

const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of transactions.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense, net: income - expense }
})
const heroValueText = computed(() => {
  const n = totals.value.net
  return (n < 0 ? '-¥' : '¥') + formatAmount(Math.abs(n))
})

// 预算视图（仅具体账本）
const budgetView = computed(() => {
  if (isAll.value) return null
  const ov = budget.value
  if (!ov || !ov.hasBudget) return { hasBudget: false }
  const pct = Number(ov.usedPercent) || 0
  const status = pct >= 100 ? 'over' : pct >= 80 ? 'warn' : 'normal'
  return {
    hasBudget: true,
    pct,
    widthPct: Math.min(pct, 100),
    status,
    spent: ov.spent,
    total: ov.totalBudget,
    remaining: ov.remaining
  }
})

// 协作账本本月成员支出
const memberExpenses = computed(() => {
  if (!isCollab.value) return []
  const by = new Map()
  for (const t of transactions.value) {
    if (t.type !== 'expense' || t.createdBy == null) continue
    by.set(t.createdBy, (by.get(t.createdBy) || 0) + Number(t.amount))
  }
  return [...by.entries()]
    .map(([uid, amt]) => ({ userId: uid, name: memberMap.value[uid] || '成员' + uid, amount: amt }))
    .sort((a, b) => b.amount - a.amount)
})

// 全部聚合：账本名映射，用于流水来源标签
const ledgerNameMap = computed(() =>
  Object.fromEntries((ledgerStore.ledgers || []).map((l) => [l.id, l.name]))
)

async function load() {
  try {
    if (isAll.value) {
      const [accs, cats, txs] = await Promise.all([
        listAllAccounts(),
        listAllCategories(),
        listAllTransactionsByMonth(month.value)
      ])
      categoryMap.value = buildCategoryLabelMap(cats)
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
      transactions.value = txs
      budget.value = null
      memberMap.value = {}
      tagNameById.value = {} // 全部账本聚合下不展示标签(跨账本 id 不可靠)
    } else {
      const [accs, cats, txs] = await Promise.all([
        listAccounts(),
        listCategories(),
        listTransactionsByMonth(month.value)
      ])
      categoryMap.value = buildCategoryLabelMap(cats)
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
      transactions.value = txs
      loadBudget()
      if (isCollab.value) {
        try {
          const ms = await listMembers(ledgerStore.currentLedgerId)
          memberMap.value = Object.fromEntries(ms.map((m) => [m.userId, m.displayName || '用户' + m.userId]))
        } catch (e) {
          memberMap.value = {}
        }
      } else {
        memberMap.value = {}
      }
      try {
        const ts = await listTags()
        tagNameById.value = Object.fromEntries(ts.map((t) => [t.id, t.name]))
      } catch (e) {
        tagNameById.value = {}
      }
    }
    loaded.value = true
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

async function loadBudget() {
  try {
    budget.value = await budgetOverview(month.value)
  } catch (e) {
    budget.value = null
  }
}

function onMonthChange(e) {
  month.value = e.detail.value
  load()
}

onShow(async () => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  try {
    await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  load()
})

// ---------- 按日分组 ----------
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

const PALETTE = ['#e5793a', '#8b78e0', '#2eb8a6', '#3aa0d0', '#e0609a', '#5b8def', '#f0a13b', '#3ba55d']
function categoryColor(t) {
  if (t.type === 'income') return '#12a150'
  if (t.type === 'transfer') return '#8a94a6'
  const label = categoryMap.value[t.categoryId] || ''
  let h = 0
  for (let i = 0; i < label.length; i++) h = (h + label.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
function tagNamesOf(t) {
  if (!Array.isArray(t.tagIds) || !t.tagIds.length) return []
  return t.tagIds.map((id) => tagNameById.value[id]).filter(Boolean)
}
function titleOf(t) {
  if (t.type === 'transfer') {
    return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  }
  return categoryMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function accOf(t) {
  return t.type === 'transfer' ? (t.note || '转账') : accountMap.value[t.accountId] || ''
}
function recorderOf(t) {
  if (isCollab.value && t.createdBy != null && memberMap.value[t.createdBy]) {
    return memberMap.value[t.createdBy]
  }
  return ''
}
function subOf(t) {
  const parts = []
  const acc = accOf(t)
  if (acc) parts.push(acc)
  const r = recorderOf(t)
  if (r) parts.push('👤' + r)
  return parts.join(' · ')
}
function ledgerTag(t) {
  return isAll.value ? ledgerNameMap.value[t.ledgerId] || '' : ''
}
function signed(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}

// ---------- 账本切换 ----------
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
const nameSheet = ref(false)
function addLedger() {
  showLedgerSheet.value = false
  nameSheet.value = true
}
async function onCreateLedger(name) {
  if (!name) return
  nameSheet.value = false
  try {
    const l = await createLedger(name)
    ledgerStore.setCurrent(l.id)
    uni.reLaunch({ url: '/pages/index/index' })
  } catch (e) {
    uni.showToast({ title: e.message || '创建失败', icon: 'none' })
  }
}

// ---------- 更多弹层 ----------
const showMore = ref(false)
function nav(url) {
  showMore.value = false
  uni.navigateTo({ url })
}
function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
function goEdit(t) {
  const suffix = t.ledgerId ? `&ledgerId=${t.ledgerId}` : ''
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}${suffix}` })
}
function goAccounts() {
  // 账户已从底部 tab 移除，改为从首页「资产」入口 push 进入。
  uni.navigateTo({ url: '/pages/accounts/accounts' })
}
function goReport() {
  uni.switchTab({ url: '/pages/report/report' })
}
function goSearch() {
  uni.switchTab({ url: '/pages/records/records' })
}
</script>

<template>
  <view class="home">
    <!-- Hero -->
    <view class="top" :class="{ agg: isAll }">
      <view class="statusbar" :style="{ height: statusBarHeight }"></view>
      <view class="hnav">
        <view class="hnav-left" @click="showLedgerSheet = true">
          <text class="hl-name">{{ ledgerStore.currentName }}</text>
          <text v-if="isCollab" class="hl-tag">协作</text>
          <text class="hl-caret">▾</text>
        </view>
        <text class="hnav-search" @click="goSearch">🔍</text>
      </view>

      <view class="hero-main">
        <picker mode="date" fields="month" :value="month" @change="onMonthChange">
          <text class="bal-k">
            {{ heroDateLabel }} · {{ isAll ? '净收支' : '月结余' }}
            <text v-if="isHistory" class="hist">历史</text> ▾
          </text>
        </picker>
        <view class="balrow">
          <text class="bal-v" :class="{ neg: totals.net < 0 }">{{ heroValueText }}</text>
          <view class="io">
            <text>收入 {{ formatAmount(totals.income) }}</text>
            <text>支出 {{ formatAmount(totals.expense) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="quick-wrap">
      <view class="quick">
        <view class="qa" @click="goAccounts"><text class="qa-ic" style="background:#e6f6ec">💎</text><text class="qa-l">资产</text></view>
        <view class="qa" @click="goReport"><text class="qa-ic" style="background:#eef4ff">📊</text><text class="qa-l">统计</text></view>
        <view class="qa" @click="nav('/pages/budget/budget')"><text class="qa-ic" style="background:#fdf3e2">🧮</text><text class="qa-l">预算</text></view>
        <view v-if="isCollab" class="qa" @click="nav('/pages/ledgers/ledgers')"><text class="qa-ic" style="background:#e6f6ec">👥</text><text class="qa-l">成员</text></view>
        <view v-else class="qa" @click="nav('/pages/billimport/billimport')"><text class="qa-ic" style="background:#f3ecff">📥</text><text class="qa-l">导入</text></view>
        <view class="qa" @click="showMore = true"><text class="qa-ic" style="background:#eef0f2">⋯</text><text class="qa-l">更多</text></view>
      </view>
    </view>

    <!-- 预算进度卡（仅具体账本） -->
    <view v-if="budgetView" class="pad">
      <view class="card budget" @click="nav('/pages/budget/budget')">
        <template v-if="budgetView.hasBudget">
          <view class="brow">
            <text class="btitle">{{ Number(month.split('-')[1]) }}月预算</text>
            <text class="bsmall" :class="budgetView.status">
              {{ budgetView.status === 'over' ? '已超支 ¥' + formatAmount(Number(budgetView.spent) - Number(budgetView.total)) : '剩余 ¥' + formatAmount(budgetView.remaining) }}
            </text>
          </view>
          <view class="barbg"><view class="bar" :class="budgetView.status" :style="{ width: budgetView.widthPct + '%' }"></view></view>
          <view class="brow">
            <text class="bsmall">已用 {{ formatAmount(budgetView.spent) }} / {{ formatAmount(budgetView.total) }}</text>
            <text class="bsmall" :class="budgetView.status">
              {{ budgetView.status === 'over' ? '超支 ' : budgetView.status === 'warn' ? '预警 ' : '' }}{{ budgetView.pct }}%
            </text>
          </view>
        </template>
        <template v-else>
          <view class="brow"><text class="btitle">{{ Number(month.split('-')[1]) }}月预算</text><text class="bsmall">未设置</text></view>
          <view class="barbg"><view class="bar" style="width:0"></view></view>
          <view class="brow"><text class="bsmall">设置预算，月末不透支</text><text class="bsmall link">去设置 ›</text></view>
        </template>
      </view>
    </view>

    <!-- 协作：本月成员支出 -->
    <view v-if="isCollab && memberExpenses.length" class="pad">
      <view class="card mcard">
        <view class="brow"><text class="mc-title">本月成员支出</text><text class="bsmall">共 {{ memberExpenses.length }} 人</text></view>
        <view class="chips">
          <view v-for="m in memberExpenses" :key="m.userId" class="chip">
            <text class="av">{{ (m.name || '?').slice(0, 1) }}</text>
            <text>{{ m.name }} ¥{{ formatAmount(m.amount) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 全部聚合只读提示 -->
    <view v-if="isAll" class="agg-note">聚合视图只读 · 记账将记入「默认账本」</view>

    <!-- 内容区 -->
    <view class="content">
      <!-- 新用户：无账户 -->
      <view v-if="loaded && !accounts.length" class="guide card">
        <text class="g-em">🌱</text>
        <text class="g-t">开始记录第一笔</text>
        <text class="g-s">三步搞定：建账户 → 记一笔 → 看报表</text>
        <view class="g-btn" @click="goRecord">＋ 记一笔</view>
        <text class="g-link" @click="nav('/pages/billimport/billimport')">导入支付宝 / 微信账单 →</text>
      </view>

      <!-- 有账户但本月无流水 -->
      <view v-else-if="loaded && !transactions.length" class="month-empty">
        <text class="me-em">🧾</text>
        <text class="me-t">本月还没有流水</text>
        <text class="me-s">{{ isCollab ? '谁都可以记一笔，记账人会自动标注' : '点下方「＋」记一笔，或点顶部切换月份' }}</text>
      </view>

      <!-- 按日分组流水 -->
      <view v-for="g in grouped" :key="g.day" class="day">
        <view class="day-h">
          <text class="day-date">{{ g.label }}</text>
          <text class="day-sum">收 {{ formatAmount(g.income) }} · 支 {{ formatAmount(g.expense) }}</text>
        </view>
        <view class="tx-list">
          <view v-for="t in g.items" :key="t.id" class="tx" @click="goEdit(t)">
            <text class="tx-ic" :style="{ background: categoryColor(t) }">{{ iconOf(t) }}</text>
            <view class="tx-info">
              <view class="tx-titrow">
                <text class="tx-title">{{ titleOf(t) }}</text>
                <text v-if="ledgerTag(t)" class="tx-ltag">{{ ledgerTag(t) }}</text>
              </view>
              <text class="tx-sub">{{ subOf(t) }}</text>
              <view v-if="tagNamesOf(t).length" class="tx-tags">
                <text v-for="(tn, i) in tagNamesOf(t)" :key="i" class="tx-tag">{{ tn }}</text>
              </view>
            </view>
            <text class="tx-amt" :class="t.type">{{ signed(t) }}</text>
          </view>
        </view>
      </view>
      <view style="height:180rpx;"></view>
    </view>

    <TabBar active="home" />

    <!-- 账本切换 -->
    <view v-if="showLedgerSheet" class="mask" @click="showLedgerSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-cancel" @click="showLedgerSheet = false">取消</text>
          <text class="sheet-title">选择账本</text>
          <text class="sheet-spacer"></text>
        </view>
        <scroll-view scroll-y class="sheet-list">
          <view class="sheet-item" @click="pickLedger('all')">
            <text class="li-ic">🗂️</text><text class="li-name">全部账本</text>
            <text class="li-radio" :class="{ on: ledgerStore.currentLedgerId === 'all' }"></text>
          </view>
          <view v-for="(l, i) in ledgerStore.ledgers" :key="l.id" class="sheet-item" @click="pickLedger(l.id)">
            <text class="li-ic">{{ ledgerEmoji(i) }}</text>
            <view class="li-name">
              <text>{{ l.name }}</text>
              <text v-if="l.type === 'COLLABORATIVE'" class="li-collab">协作</text>
            </view>
            <text class="li-radio" :class="{ on: l.id === ledgerStore.currentLedgerId }"></text>
          </view>
        </scroll-view>
        <view class="sheet-add" @click="addLedger">＋ 新建账本</view>
      </view>
    </view>

    <!-- 更多 -->
    <view v-if="showMore" class="mask" @click="showMore = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-cancel" @click="showMore = false">取消</text>
          <text class="sheet-title">更多功能</text>
          <text class="sheet-spacer"></text>
        </view>
        <view class="more-grid">
          <view class="mi" @click="nav('/pages/loans/loans')"><text class="mi-ic">🤝</text><text class="mi-l">借贷往来</text></view>
          <view class="mi" @click="nav('/pages/billimport/billimport')"><text class="mi-ic">📥</text><text class="mi-l">账单导入</text></view>
          <view class="mi" @click="nav('/pages/data/data')"><text class="mi-ic">🗂️</text><text class="mi-l">数据备份</text></view>
          <view class="mi" @click="nav('/pages/categories/categories')"><text class="mi-ic">🏷️</text><text class="mi-l">分类管理</text></view>
          <view class="mi" @click="nav('/pages/budget/budget')"><text class="mi-ic">🧮</text><text class="mi-l">预算管理</text></view>
          <view class="mi" @click="nav('/pages/ledgers/ledgers')"><text class="mi-ic">📓</text><text class="mi-l">账本管理</text></view>
        </view>
        <view style="height:calc(20rpx + env(safe-area-inset-bottom));"></view>
      </view>
    </view>

    <InputSheet :visible="nameSheet" title="新建账本" placeholder="账本名称" @update:visible="nameSheet = $event" @confirm="onCreateLedger" />
  </view>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: #eef0f2;
}
.top {
  background: linear-gradient(155deg, #1fbf63, #0f8a45 78%);
  padding-bottom: 74rpx;
  position: relative;
  overflow: hidden;
}
.top.agg {
  background: linear-gradient(155deg, #2b3a34, #1f2a30 78%);
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
.hnav-left {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.hl-name {
  font-size: 32rpx;
  font-weight: 800;
}
.hl-tag {
  font-size: 18rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.hl-caret {
  font-size: 20rpx;
  opacity: 0.9;
}
.hnav-search {
  font-size: 34rpx;
  opacity: 0.95;
}
.hero-main {
  padding: 4rpx 32rpx 0;
  color: #fff;
  position: relative;
  z-index: 2;
}
.bal-k {
  font-size: 22rpx;
  opacity: 0.9;
}
.hist {
  font-size: 18rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 2rpx 10rpx;
  margin-left: 4rpx;
}
.balrow {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-top: 6rpx;
}
.bal-v {
  font-size: 68rpx;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.1;
}
.bal-v.neg {
  color: #ffd9d0;
}
.io {
  text-align: right;
  font-size: 22rpx;
  opacity: 0.92;
  line-height: 1.7;
  display: flex;
  flex-direction: column;
}

.quick-wrap {
  padding: 0 24rpx;
  margin-top: -54rpx;
  position: relative;
  z-index: 3;
}
.quick {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  padding: 26rpx 6rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06);
}
.qa {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.qa-ic {
  width: 82rpx;
  height: 82rpx;
  border-radius: 50%;
  text-align: center;
  line-height: 82rpx;
  font-size: 38rpx;
}
.qa-l {
  font-size: 22rpx;
  color: #5b6470;
}

.pad {
  padding: 20rpx 24rpx 0;
}
.card {
  background: #fff;
  border-radius: 18rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.budget {
  padding: 24rpx 26rpx;
}
.brow {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.btitle {
  font-size: 26rpx;
  font-weight: 700;
  color: #16181c;
}
.bsmall {
  font-size: 22rpx;
  color: #9aa2ad;
}
.bsmall.link {
  color: #0e8a44;
  font-weight: 700;
}
.bsmall.warn {
  color: #f4a72b;
  font-weight: 700;
}
.bsmall.over {
  color: #e5484d;
  font-weight: 700;
}
.barbg {
  height: 14rpx;
  background: #f0f2f5;
  border-radius: 8rpx;
  overflow: hidden;
  margin: 16rpx 0 12rpx;
}
.bar {
  height: 100%;
  border-radius: 8rpx;
  background: #12a150;
}
.bar.warn {
  background: #f4a72b;
}
.bar.over {
  background: #e5484d;
}

.mcard {
  padding: 22rpx 26rpx;
}
.mc-title {
  font-size: 26rpx;
  font-weight: 700;
  color: #16181c;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 16rpx;
}
.chip {
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: #f6f7f9;
  border-radius: 999rpx;
  padding: 8rpx 18rpx;
  font-size: 23rpx;
  color: #5b6470;
}
.av {
  width: 34rpx;
  height: 34rpx;
  border-radius: 50%;
  background: #12a150;
  color: #fff;
  font-size: 20rpx;
  text-align: center;
  line-height: 34rpx;
}

.agg-note {
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  padding: 18rpx 24rpx 0;
}

.content {
  padding: 8rpx 24rpx 0;
}
.guide {
  margin-top: 16rpx;
  padding: 40rpx 30rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.g-em {
  font-size: 96rpx;
}
.g-t {
  font-size: 34rpx;
  font-weight: 800;
  margin-top: 12rpx;
}
.g-s {
  font-size: 24rpx;
  color: #5b6470;
  margin-top: 10rpx;
}
.g-btn {
  margin-top: 28rpx;
  width: 100%;
  height: 88rpx;
  border-radius: 999rpx;
  background: #12a150;
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 14rpx 30rpx rgba(18, 161, 80, 0.26);
}
.g-link {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: #0e8a44;
  font-weight: 700;
}
.month-empty {
  text-align: center;
  padding: 90rpx 0 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}
.me-em {
  font-size: 84rpx;
  opacity: 0.9;
}
.me-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #16181c;
}
.me-s {
  font-size: 24rpx;
  color: #9aa2ad;
}

.day {
  margin-bottom: 18rpx;
}
.day-h {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 8rpx;
  font-size: 22rpx;
  color: #9aa2ad;
  font-weight: 600;
}
.tx-list {
  background: #fff;
  border-radius: 18rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.04);
}
.tx {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 26rpx;
  border-top: 1rpx solid #f1f3f5;
}
.tx-list .tx:first-child {
  border-top: none;
}
.tx-ic {
  width: 78rpx;
  height: 78rpx;
  border-radius: 22rpx;
  text-align: center;
  line-height: 78rpx;
  font-size: 38rpx;
  flex: 0 0 auto;
}
.tx-info {
  flex: 1;
  min-width: 0;
}
.tx-titrow {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.tx-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #16181c;
}
.tx-ltag {
  font-size: 18rpx;
  color: #9aa2ad;
  background: #f0f2f5;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.tx-sub {
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 4rpx;
}
.tx-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 6rpx;
}
.tx-tag {
  font-size: 18rpx;
  color: #0e8a44;
  background: #e6f6ec;
  border-radius: 6rpx;
  padding: 2rpx 12rpx;
}
.tx-amt {
  font-size: 32rpx;
  font-weight: 800;
}
.tx-amt.expense {
  color: #f0553d;
}
.tx-amt.income {
  color: #12a150;
}
.tx-amt.transfer {
  color: #8a94a6;
}

.fab {
  position: fixed;
  right: 40rpx;
  bottom: calc(136rpx + env(safe-area-inset-bottom));
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

.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding-bottom: calc(12rpx + env(safe-area-inset-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  padding: 26rpx 32rpx;
  border-bottom: 1rpx solid #f1f3f5;
}
.sheet-cancel {
  font-size: 28rpx;
  color: #9aa2ad;
  width: 90rpx;
}
.sheet-title {
  flex: 1;
  text-align: center;
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
}
.sheet-spacer {
  width: 90rpx;
}
.sheet-list {
  max-height: 560rpx;
}
.sheet-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 36rpx;
}
.li-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  background: #f6f7f9;
  text-align: center;
  line-height: 64rpx;
  font-size: 32rpx;
}
.li-name {
  flex: 1;
  font-size: 30rpx;
  color: #16181c;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.li-collab {
  font-size: 18rpx;
  color: #b9761a;
  background: #fdf3e2;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.li-radio {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 3rpx solid #d1d5db;
  box-sizing: border-box;
}
.li-radio.on {
  border-color: #12a150;
  background: radial-gradient(circle at center, #12a150 0, #12a150 9rpx, #fff 10rpx, #fff 100%);
}
.sheet-add {
  text-align: center;
  padding: 30rpx;
  font-size: 30rpx;
  color: #0e8a44;
  font-weight: 700;
  border-top: 1rpx solid #f1f3f5;
}
.more-grid {
  display: flex;
  flex-wrap: wrap;
  padding: 12rpx 12rpx 0;
}
.mi {
  width: 25%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 20rpx 0;
}
.mi-ic {
  width: 92rpx;
  height: 92rpx;
  border-radius: 26rpx;
  background: #f6f7f9;
  text-align: center;
  line-height: 92rpx;
  font-size: 42rpx;
}
.mi-l {
  font-size: 22rpx;
  color: #5b6470;
}
</style>
