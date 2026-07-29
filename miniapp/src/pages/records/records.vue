<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth, deleteTransaction } from '../../api/transaction'
import { listMembers } from '../../api/ledger'
import { listProjects } from '../../api/project'
import { listMerchants } from '../../api/merchant'
import { listTags } from '../../api/tag'
import {
  listAllAccounts,
  listAllCategories,
  listAllTransactionsByMonth
} from '../../api/aggregate'
import { useLedgerStore } from '../../stores/ledger'
import {
  formatAmount,
  categoryEmoji,
  dayKeyOf,
  dayLabel,
  timeLabelOf,
  currentMonth
} from '../../utils/format'

const ledgerStore = useLedgerStore()

const month = ref(currentMonth())
const transactions = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const memberMap = ref({})
const loading = ref(false)

// 协作账本（非「全部」模式）才显示记账人。
const isCollab = computed(
  () => !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE'
)

// ---------- 筛选（项目/商家/标签/记账人，单选，客户端过滤当月数据）----------
const filterDim = ref(null) // null | 'project' | 'merchant' | 'tag' | 'recorder'
const filterId = ref(null)
const filterSheet = ref(false)
const sheetDim = ref('project') // 筛选面板当前维度
const projects = ref([])
const merchants = ref([])
const tags = ref([])
const showFilter = computed(() => !ledgerStore.isAll)
const DIM_LABEL = { project: '项目', merchant: '商家', tag: '标签', recorder: '记账人' }

// 当前筛选面板维度下的可选项 [{id,name}]
const sheetOptions = computed(() => {
  switch (sheetDim.value) {
    case 'project':
      return projects.value.map((p) => ({ id: p.id, name: p.name }))
    case 'merchant':
      return merchants.value.map((m) => ({ id: m.id, name: m.name }))
    case 'tag':
      return tags.value.map((t) => ({ id: t.id, name: t.name }))
    case 'recorder':
      return Object.entries(memberMap.value).map(([id, name]) => ({ id: Number(id), name }))
    default:
      return []
  }
})

const filterLabel = computed(() => {
  if (!filterDim.value) return ''
  const opt = optionName(filterDim.value, filterId.value)
  return `${DIM_LABEL[filterDim.value]}：${opt}`
})
function optionName(dim, id) {
  const src =
    dim === 'project' ? projects.value :
    dim === 'merchant' ? merchants.value :
    dim === 'tag' ? tags.value : null
  if (dim === 'recorder') return memberMap.value[id] || '未知'
  const it = (src || []).find((x) => x.id === id)
  return it ? it.name : '已删除'
}

// 应用筛选后的交易列表
const visibleTx = computed(() => {
  if (!filterDim.value || filterId.value == null) return transactions.value
  return transactions.value.filter((t) => {
    switch (filterDim.value) {
      case 'project': return t.projectId === filterId.value
      case 'merchant': return t.merchantId === filterId.value
      case 'tag': return Array.isArray(t.tagIds) && t.tagIds.includes(filterId.value)
      case 'recorder': return t.createdBy === filterId.value
      default: return true
    }
  })
})

const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of visibleTx.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense }
})

function openFilter() {
  sheetDim.value = filterDim.value || 'project'
  filterSheet.value = true
}
function switchSheetDim(d) {
  sheetDim.value = d
}
function applyFilter(id) {
  filterDim.value = sheetDim.value
  filterId.value = id
  filterSheet.value = false
}
function clearFilter() {
  filterDim.value = null
  filterId.value = null
  filterSheet.value = false
}

async function load() {
  loading.value = true
  try {
    const isAll = ledgerStore.isAll
    const [accs, cats, txs] = await Promise.all([
      isAll ? listAllAccounts() : listAccounts(),
      isAll ? listAllCategories() : listCategories(),
      isAll ? listAllTransactionsByMonth(month.value) : listTransactionsByMonth(month.value)
    ])
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
    categoryMap.value = buildCategoryLabelMap(cats)
    transactions.value = txs

    if (isCollab.value) {
      try {
        const ms = await listMembers(ledgerStore.currentLedgerId)
        memberMap.value = Object.fromEntries(
          ms.map((m) => [m.userId, m.displayName || '用户' + m.userId])
        )
      } catch (e) {
        memberMap.value = {}
      }
    } else {
      memberMap.value = {}
    }

    // 筛选选项（仅具体账本）。
    if (!isAll) {
      try {
        const [ps, ms, ts] = await Promise.all([listProjects(), listMerchants(), listTags()])
        projects.value = ps
        merchants.value = ms
        tags.value = ts
      } catch (e) {
        /* 选项加载失败不阻断明细 */
      }
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 消费其它页(报表/明细详情)投递的待应用筛选（tabBar 页无法带参，借本地存储传递）。
function consumePendingFilter() {
  let pending = null
  try {
    pending = uni.getStorageSync('youyu_records_filter')
  } catch (e) {
    pending = null
  }
  if (!pending || !pending.dim || pending.id == null) return
  uni.removeStorageSync('youyu_records_filter')
  // 若投递了目标月份则切换。
  if (pending.month) month.value = pending.month
  filterDim.value = pending.dim
  filterId.value = pending.id
  // 若投递了名称，兜底塞入选项，保证标签可显示（选项已在 load 中拉取，通常已存在）。
  if (pending.name) {
    const ensure = (arr) => {
      if (!arr.value.some((x) => x.id === pending.id)) arr.value.push({ id: pending.id, name: pending.name })
    }
    if (pending.dim === 'project') ensure(projects)
    else if (pending.dim === 'merchant') ensure(merchants)
    else if (pending.dim === 'tag') ensure(tags)
    else if (pending.dim === 'recorder' && !memberMap.value[pending.id]) memberMap.value[pending.id] = pending.name
  }
}

onShow(async () => {
  await load()
  consumePendingFilter()
})

const grouped = computed(() => {
  const groups = []
  let cur = null
  for (const t of visibleTx.value) {
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
  if (isCollab.value && t.createdBy != null && memberMap.value[t.createdBy]) {
    parts.push(`👤${memberMap.value[t.createdBy]}`)
  }
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
  const suffix = t.ledgerId ? `&ledgerId=${t.ledgerId}` : ''
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}${suffix}` })
}
function confirmDelete(t) {
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回滚账户余额，确定删除？',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteTransaction(t.id, t.ledgerId)
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

    <!-- 筛选栏 -->
    <view v-if="showFilter" class="filterbar">
      <view v-if="filterDim" class="active-filter">
        <text class="af-text">{{ filterLabel }}</text>
        <text class="af-x" @click="clearFilter">✕</text>
      </view>
      <view class="filter-btn" @click="openFilter">
        <text>🔎 {{ filterDim ? '换筛选' : '筛选' }}</text>
      </view>
    </view>

    <view v-if="!visibleTx.length && !loading" class="empty">
      {{ filterDim ? '该筛选下本月暂无记录' : '本月暂无记录' }}
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

    <text v-if="visibleTx.length" class="hint">点击编辑 · 长按删除</text>

    <!-- 筛选面板 -->
    <view v-if="filterSheet" class="mask" @click="filterSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">筛选明细</text>
        <view class="fdims">
          <text class="fdim" :class="{ on: sheetDim === 'project' }" @click="switchSheetDim('project')">项目</text>
          <text class="fdim" :class="{ on: sheetDim === 'merchant' }" @click="switchSheetDim('merchant')">商家</text>
          <text class="fdim" :class="{ on: sheetDim === 'tag' }" @click="switchSheetDim('tag')">标签</text>
          <text v-if="isCollab" class="fdim" :class="{ on: sheetDim === 'recorder' }" @click="switchSheetDim('recorder')">记账人</text>
        </view>
        <scroll-view scroll-y class="opts">
          <view v-if="!sheetOptions.length" class="opt-empty">暂无{{ DIM_LABEL[sheetDim] }}可选</view>
          <view
            v-for="o in sheetOptions"
            :key="o.id"
            class="opt"
            :class="{ on: filterDim === sheetDim && filterId === o.id }"
            @click="applyFilter(o.id)"
          >
            <text>{{ o.name }}</text>
            <text v-if="filterDim === sheetDim && filterId === o.id" class="opt-tick">✓</text>
          </view>
        </scroll-view>
        <view class="fclear" @click="clearFilter">清除筛选</view>
      </view>
    </view>
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
.s-inc { color: #12a150; }
.s-exp { color: #f0553d; }

.filterbar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.active-filter {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #e6f6ec;
  color: #0e8a44;
  border-radius: 999rpx;
  padding: 14rpx 24rpx;
  font-size: 26rpx;
  font-weight: 700;
}
.af-x {
  color: #0e8a44;
  font-size: 26rpx;
  padding-left: 16rpx;
}
.filter-btn {
  background: #fff;
  border-radius: 999rpx;
  padding: 14rpx 30rpx;
  font-size: 26rpx;
  color: #5b6470;
  box-shadow: 0 4rpx 12rpx rgba(20, 24, 28, 0.04);
  white-space: nowrap;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
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
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.sheet-title {
  display: block;
  text-align: center;
  font-size: 30rpx;
  font-weight: 800;
  margin-bottom: 20rpx;
}
.fdims {
  display: flex;
  gap: 14rpx;
  margin-bottom: 16rpx;
}
.fdim {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  border-radius: 12rpx;
  background: #f2f4f6;
  font-size: 26rpx;
  color: #5b6470;
  font-weight: 700;
}
.fdim.on {
  background: #e6f6ec;
  color: #0e8a44;
}
.opts {
  max-height: 44vh;
}
.opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 26rpx 8rpx;
  border-top: 1rpx solid #f1f3f5;
  font-size: 30rpx;
  color: #16181c;
}
.opt.on {
  color: #0e8a44;
  font-weight: 700;
}
.opt-tick {
  color: #0e8a44;
}
.opt-empty {
  text-align: center;
  color: #9aa2ad;
  font-size: 26rpx;
  padding: 40rpx 0;
}
.fclear {
  margin-top: 16rpx;
  text-align: center;
  padding: 24rpx;
  border-radius: 16rpx;
  background: #f2f4f6;
  color: #5b6470;
  font-weight: 700;
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
.day-sum .inc { color: #12a150; }
.day-sum .exp { color: #f0553d; }

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
.tx-amount.expense { color: #f0553d; }
.tx-amount.income { color: #12a150; }
.tx-amount.transfer { color: #6b7280; }
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 24rpx 0;
}
</style>
