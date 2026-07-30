<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { categoryReport, memberReport, dimensionReport, trendReport, monthRange, shiftMonth } from '../../api/report'
import { listAllCategories, listAllTransactionsByMonth } from '../../api/aggregate'
import { buildCategoryLabelMap } from '../../api/category'
import { useLedgerStore } from '../../stores/ledger'
import { formatAmount, categoryEmoji, currentMonth, monthLabel } from '../../utils/format'

const ledgerStore = useLedgerStore()

const KINDS = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' }
]

// 统计维度：分类 / 项目 / 商家 / 标签
const DIMS = [
  { value: 'category', label: '分类' },
  { value: 'project', label: '项目' },
  { value: 'merchant', label: '商家' },
  { value: 'tag', label: '标签' }
]
const dim = ref('category')
// 「全部账本」聚合仅支持分类维度（维度报表按当前账本）。
const showDims = computed(() => !ledgerStore.isAll)
const dimLabel = computed(() => DIMS.find((d) => d.value === dim.value)?.label || '分类')

const kind = ref('expense')
const month = ref(currentMonth())
const total = ref('0.00')
const rows = ref([])
const members = ref([])
const trend = ref([])
const loading = ref(false)

// 趋势（近 6 个月，截至所选月）；仅具体账本展示
const showTrend = computed(() => !ledgerStore.isAll)
const trendMax = computed(() =>
  trend.value.reduce((m, p) => Math.max(m, Number(p.income), Number(p.expense)), 0) || 1
)
function barH(v) {
  return Math.max((Number(v) / trendMax.value) * 100, 1.5)
}
function trendMonthLabel(ym) {
  return Number(ym.split('-')[1]) + '月'
}

// 协作账本（非「全部」）展示成员占比（支出/收入随当前类别）。
const showMembers = computed(
  () => !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE'
)

const COLORS = ['#12a150', '#0ea5e9', '#f59e0b', '#f0553d', '#8b5cf6', '#1677ff', '#f7b500']
function colorAt(i) {
  return COLORS[i % COLORS.length]
}

async function load() {
  loading.value = true
  try {
    if (ledgerStore.isAll) {
      await loadAllAggregate()
      members.value = []
      trend.value = []
    } else {
      const { from, to } = monthRange(month.value)
      if (dim.value === 'category') {
        const res = await categoryReport(from, to, kind.value)
        total.value = res.totalExpense
        rows.value = res.categories || []
      } else {
        const res = await dimensionReport(from, to, dim.value, kind.value)
        total.value = res.total
        // 归一化为与分类行相同的结构，复用列表渲染。
        rows.value = (res.items || []).map((it) => ({
          categoryId: it.id,
          categoryName: it.name,
          amount: it.amount,
          percentage: it.percentage,
          count: it.count
        }))
      }
      members.value = showMembers.value
        ? (await memberReport(from, to, kind.value)).members || []
        : []
      try {
        const tr = await trendReport(shiftMonth(month.value, -5), month.value)
        trend.value = tr.months || []
      } catch (e) {
        trend.value = []
      }
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 全部账本：跨账本客户端聚合分类占比
async function loadAllAggregate() {
  const [cats, txs] = await Promise.all([
    listAllCategories(),
    listAllTransactionsByMonth(month.value)
  ])
  const nameMap = buildCategoryLabelMap(cats)
  const wanted = kind.value === 'income' ? 'income' : 'expense'
  const byCat = new Map()
  let totalCents = 0
  for (const t of txs) {
    if (t.type !== wanted) continue
    const cents = Math.round(Number(t.amount) * 100)
    totalCents += cents
    const key = t.categoryId ?? 0
    const cur = byCat.get(key) || { categoryId: t.categoryId, amount: 0, count: 0 }
    cur.amount += cents
    cur.count += 1
    byCat.set(key, cur)
  }
  total.value = (totalCents / 100).toFixed(2)
  const list = [...byCat.values()].map((c) => ({
    categoryId: c.categoryId,
    categoryName: nameMap[c.categoryId] || '未分类',
    amount: (c.amount / 100).toFixed(2),
    percentage: totalCents > 0 ? Number(((c.amount / totalCents) * 100).toFixed(2)) : 0,
    count: c.count
  }))
  list.sort((a, b) => Number(b.amount) - Number(a.amount))
  rows.value = list
}

onShow(load)

function selectKind(k) {
  kind.value = k
  load()
}
function selectDim(d) {
  if (dim.value === d) return
  dim.value = d
  rows.value = []
  load()
}
// 维度报表行 → 跳到「明细」tab 并按该维度项筛选（分类维度暂无对应筛选，不跳转）。
function onRowTap(r) {
  if (ledgerStore.isAll || dim.value === 'category' || r.categoryId == null) return
  try {
    uni.setStorageSync('youyu_records_filter', {
      dim: dim.value,
      id: r.categoryId,
      name: r.categoryName,
      month: month.value
    })
  } catch (e) {
    /* ignore */
  }
  uni.switchTab({ url: '/pages/records/records' })
}
function prevMonth() {
  month.value = shiftMonth(month.value, -1)
  load()
}
function nextMonth() {
  month.value = shiftMonth(month.value, 1)
  load()
}
</script>

<template>
  <view class="page">
    <view class="kinds">
      <view
        v-for="k in KINDS"
        :key="k.value"
        class="kind"
        :class="{ active: kind === k.value }"
        @click="selectKind(k.value)"
      >
        {{ k.label }}
      </view>
    </view>

    <!-- 概览卡 -->
    <view class="total-card">
      <view class="month-bar">
        <text class="nav" @click="prevMonth">‹</text>
        <text class="month">{{ monthLabel(month) }}</text>
        <text class="nav" @click="nextMonth">›</text>
      </view>
      <text class="total-label">{{ kind === 'expense' ? '总支出' : '总收入' }}</text>
      <text class="total-value">¥{{ formatAmount(total) }}</text>
    </view>

    <!-- 统计维度：分类 / 项目 / 商家 / 标签 -->
    <scroll-view v-if="showDims" scroll-x class="dims" :show-scrollbar="false">
      <text
        v-for="d in DIMS"
        :key="d.value"
        class="dim"
        :class="{ on: dim === d.value }"
        @click="selectDim(d.value)"
      >{{ d.label }}</text>
    </scroll-view>

    <!-- 收支趋势（近半年） -->
    <view v-if="showTrend && trend.length" class="trend-card">
      <view class="tc-head">
        <text class="tc-title">近半年收支趋势</text>
        <view class="tc-legend">
          <text class="lg"><text class="dot inc"></text>收入</text>
          <text class="lg"><text class="dot exp"></text>支出</text>
        </view>
      </view>
      <view class="bars">
        <view v-for="p in trend" :key="p.month" class="bcol">
          <view class="bpair">
            <view class="bar inc" :style="{ height: barH(p.income) + '%' }"></view>
            <view class="bar exp" :style="{ height: barH(p.expense) + '%' }"></view>
          </view>
          <text class="blabel">{{ trendMonthLabel(p.month) }}</text>
        </view>
      </view>
    </view>

    <view v-if="!rows.length && !loading" class="empty">
      当月暂无{{ dim === 'category' ? '' : dimLabel + '归属的' }}{{ kind === 'expense' ? '支出' : '收入' }}
    </view>

    <view class="list" v-if="rows.length">
      <view v-for="(r, i) in rows" :key="r.categoryId ?? i" class="row" @click="onRowTap(r)">
        <text class="row-ic" :style="{ background: colorAt(i) + '22' }">
          {{ categoryEmoji(r.categoryName, kind) }}
        </text>
        <view class="row-body">
          <view class="row-head">
            <text class="row-name">{{ r.categoryName || '未分类' }}</text>
            <text class="row-amount">¥{{ formatAmount(r.amount) }}</text>
          </view>
          <view class="bar-bg">
            <view class="bar" :style="{ width: r.percentage + '%', background: colorAt(i) }"></view>
          </view>
          <view class="row-foot">
            <text class="row-pct">{{ r.percentage }}%</text>
            <text class="row-count">{{ r.count }} 笔</text>
          </view>
        </view>
        <text v-if="showDims && dim !== 'category'" class="row-caret">›</text>
      </view>
    </view>

    <!-- 协作账本：成员支出占比 -->
    <template v-if="showMembers && members.length">
      <text class="section-title">{{ kind === 'expense' ? '成员支出' : '成员收入' }}</text>
      <view class="list">
        <view v-for="(m, i) in members" :key="m.userId ?? i" class="row">
          <text class="row-ic member-ic" :style="{ background: colorAt(i) }">
            {{ (m.displayName || '?').slice(0, 1).toUpperCase() }}
          </text>
          <view class="row-body">
            <view class="row-head">
              <text class="row-name">{{ m.displayName || '未知' }}</text>
              <text class="row-amount">¥{{ formatAmount(m.amount) }}</text>
            </view>
            <view class="bar-bg">
              <view class="bar" :style="{ width: m.percentage + '%', background: colorAt(i) }"></view>
            </view>
            <view class="row-foot">
              <text class="row-pct">{{ m.percentage }}%</text>
              <text class="row-count">{{ m.count }} 笔</text>
            </view>
          </view>
        </view>
      </view>
    </template>

    <view style="height:180rpx;"></view>
    <TabBar active="report" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.kinds {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  margin-bottom: 24rpx;
}
.kind {
  flex: 1;
  text-align: center;
  padding: 28rpx 0;
  font-size: 30rpx;
  color: #6b7280;
}
.kind.active {
  background: #12a150;
  color: #fff;
  font-weight: 700;
}
.total-card {
  border-radius: 28rpx;
  padding: 32rpx 36rpx 40rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #12a150 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
}
.month-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 40rpx;
  margin-bottom: 20rpx;
}
.nav {
  font-size: 44rpx;
  padding: 0 20rpx;
  opacity: 0.9;
}
.month {
  font-size: 30rpx;
  font-weight: 700;
}
.total-label {
  font-size: 24rpx;
  opacity: 0.9;
}
.total-value {
  display: block;
  margin-top: 8rpx;
  font-size: 64rpx;
  font-weight: 800;
}
.dims {
  white-space: nowrap;
  margin-bottom: 20rpx;
}
.dim {
  display: inline-block;
  padding: 12rpx 30rpx;
  margin-right: 14rpx;
  border-radius: 999rpx;
  background: #fff;
  font-size: 26rpx;
  color: #5b6470;
  box-shadow: 0 4rpx 12rpx rgba(20, 24, 28, 0.04);
}
.dim.on {
  background: #12a150;
  color: #fff;
  font-weight: 700;
}
.trend-card {
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 24rpx 20rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.tc-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20rpx;
}
.tc-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #16181c;
}
.tc-legend {
  display: flex;
  gap: 20rpx;
}
.lg {
  font-size: 22rpx;
  color: #6b7280;
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 4rpx;
}
.dot.inc { background: #12a150; }
.dot.exp { background: #f0553d; }
.bars {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  height: 200rpx;
}
.bcol {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  height: 100%;
}
.bpair {
  flex: 1;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6rpx;
  width: 100%;
}
.bar {
  width: 16rpx;
  border-radius: 6rpx 6rpx 0 0;
  min-height: 4rpx;
}
.bar.inc { background: linear-gradient(180deg, #24bd6a, #12a150); }
.bar.exp { background: linear-gradient(180deg, #f47a66, #f0553d); }
.blabel {
  font-size: 20rpx;
  color: #9aa2ad;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
}
.section-title {
  display: block;
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
  margin: 28rpx 8rpx 14rpx;
}
.member-ic {
  color: #fff;
  font-weight: 700;
}
.list {
  background: #fff;
  border-radius: 24rpx;
  padding: 12rpx 28rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.list .row:first-child {
  border-top: none;
}
.row-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  text-align: center;
  line-height: 72rpx;
  font-size: 34rpx;
  flex: 0 0 auto;
}
.row-body {
  flex: 1;
  min-width: 0;
}
.row-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.row-name {
  font-size: 28rpx;
  color: #1f2937;
  font-weight: 600;
}
.row-amount {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2937;
}
.bar-bg {
  height: 14rpx;
  background: #f0f0f0;
  border-radius: 8rpx;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 8rpx;
}
.row-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 10rpx;
}
.row-pct {
  font-size: 22rpx;
  color: #6b7280;
  font-weight: 600;
}
.row-count {
  font-size: 22rpx;
  color: #bbb;
}
.row-caret {
  flex: 0 0 auto;
  color: #c0c4cc;
  font-size: 40rpx;
  padding-left: 8rpx;
}
</style>
